package dev.dsh.mobile.net

import dev.dsh.mobile.data.AgentPreset
import dev.dsh.mobile.data.PresetRoster
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the host presets contract: GET /presets decode, preset rosters (default selection,
 * broken filtering, fallback for old hosts) and the create-session request carrying the preset id.
 */
class PresetRosterTest {

    @Test
    fun `presets response decodes roster fields`() {
        val json = """
            {"supported":true,"defaultId":"code-review","presets":[
              {"id":"standard","name":"Standard","description":"General purpose",
               "isDefault":false,"broken":false},
              {"id":"code-review","name":"Code review","description":null,
               "isDefault":true,"broken":false},
              {"id":"legacy","description":"Old preset","isDefault":false,"broken":true}
            ]}
        """.trimIndent()
        val response = DshJson.decodeFromString(PresetsResponse.serializer(), json)
        assertTrue(response.supported)
        assertEquals("code-review", response.defaultId)
        assertEquals(3, response.presets.size)
        assertEquals("Standard", response.presets[0].name)
        assertEquals("General purpose", response.presets[0].description)
        assertTrue(response.presets[1].isDefault)
        assertTrue(response.presets[2].broken)
        assertNull(response.presets[2].name)
    }

    @Test
    fun `unsupported hosts decode an empty roster`() {
        val response = DshJson.decodeFromString(
            PresetsResponse.serializer(),
            """{"presets":[],"supported":false}""",
        )
        assertTrue(!response.supported)
        assertTrue(response.presets.isEmpty())
        assertNull(response.defaultId)
    }

    @Test
    fun `preset label falls back to the id`() {
        assertEquals("Code review", AgentPreset(id = "code", name = "Code review").label)
        assertEquals("code", AgentPreset(id = "code", name = "  ").label)
    }

    @Test
    fun `initial selection prefers defaultId then isDefault and skips broken`() {
        val roster = PresetRoster(
            supported = true,
            defaultId = "b",
            presets = listOf(
                AgentPreset(id = "a", name = "A"),
                AgentPreset(id = "b", name = "B"),
                AgentPreset(id = "c", name = "C", isDefault = true),
            ),
        )
        assertEquals("b", roster.initialSelection()?.id)

        val byDefaultFlag = PresetRoster(
            supported = true,
            presets = listOf(
                AgentPreset(id = "a"),
                AgentPreset(id = "c", isDefault = true),
            ),
        )
        assertEquals("c", byDefaultFlag.initialSelection()?.id)

        val defaultBroken = PresetRoster(
            supported = true,
            defaultId = "a",
            presets = listOf(
                AgentPreset(id = "a", broken = true),
                AgentPreset(id = "b", name = "B"),
            ),
        )
        assertEquals("b", defaultBroken.initialSelection()?.id)

        val allBroken = PresetRoster(
            supported = true,
            presets = listOf(
                AgentPreset(id = "a", broken = true),
                AgentPreset(id = "b", broken = true),
            ),
        )
        assertNull(allBroken.initialSelection())
    }

    @Test
    fun `fallback roster keeps the three legacy chips and selects Standard`() {
        val fallback = PresetRoster.Fallback
        assertTrue(fallback.isFallback)
        assertTrue(!fallback.supported)
        assertEquals(listOf("standard", "minimal", "ptc"), fallback.presets.map { it.id })
        assertEquals(listOf("Standard", "Minimal", "PTC"), fallback.presets.map { it.label })
        assertEquals("standard", fallback.initialSelection()?.id)

        val emptyHostRoster = PresetRoster(supported = false, presets = emptyList())
        assertTrue(emptyHostRoster.isFallback)
    }

    @Test
    fun `create session request sends the preset id verbatim`() {
        val json = DshJson.encodeToString(
            CreateSessionRequest.serializer(),
            CreateSessionRequest(workspaceId = "ws_1", preset = "code-review"),
        )
        assertTrue(json.contains("\"preset\":\"code-review\""))
        assertTrue(json.contains("\"workspaceId\":\"ws_1\""))

        val withoutPreset = DshJson.encodeToString(
            CreateSessionRequest.serializer(),
            CreateSessionRequest(workspaceId = "ws_1"),
        )
        assertTrue("null preset is omitted", !withoutPreset.contains("preset"))
    }
}
