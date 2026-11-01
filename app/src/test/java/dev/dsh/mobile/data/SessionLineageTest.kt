package dev.dsh.mobile.data

import dev.dsh.mobile.net.DshJson
import dev.dsh.mobile.net.SessionSummaryDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Optional lineage/preset context (host 0.3.0+) and its safe fallbacks. */
class SessionLineageTest {

    private fun session(
        id: String = "sess_child",
        title: String = "Child session",
        parentSessionId: String? = null,
        agentPreset: String? = null,
    ) = Session(
        id = id,
        title = title,
        workspaceId = "ws",
        preview = "",
        updated = "now",
        day = "Today",
        parentSessionId = parentSessionId,
        agentPreset = agentPreset,
    )

    @Test
    fun `session summary decodes optional lineage fields and old hosts default blank`() {
        val withLineage = DshJson.decodeFromString(
            SessionSummaryDto.serializer(),
            """{"id":"s2","title":"Fork","parentSessionId":"s1","agentPreset":"cordis"}""",
        )
        assertEquals("s1", withLineage.parentSessionId)
        assertEquals("cordis", withLineage.agentPreset)

        val oldHost = DshJson.decodeFromString(
            SessionSummaryDto.serializer(),
            """{"id":"s1","title":"Old"}""",
        )
        assertNull(oldHost.parentSessionId)
        assertNull(oldHost.agentPreset)
    }

    @Test
    fun `mapping carries lineage and preserves it when a payload omits it`() {
        val dto = SessionSummaryDto(
            id = "s2",
            title = "Fork",
            parentSessionId = " s1 ",
            agentPreset = " cordis ",
        )
        val mapped = dto.toUi()
        assertEquals("s1", mapped.parentSessionId)
        assertEquals("cordis", mapped.agentPreset)

        val existing = session(parentSessionId = "s1", agentPreset = "cordis")
        val partial = SessionSummaryDto(id = "s2", title = "Fork")
        // L1: an incremental/partial update preserves known lineage when the payload omits it.
        val merged = partial.toUi(existing = existing, preserveLineageWhenMissing = true)
        assertEquals("s1", merged.parentSessionId)
        assertEquals("cordis", merged.agentPreset)
        // L1: a full refresh that omits the fields clears stale lineage instead.
        val cleared = partial.toUi(existing = existing)
        assertNull(cleared.parentSessionId)
        assertNull(cleared.agentPreset)
        // Explicit values always win in either mode.
        val updated = SessionSummaryDto(
            id = "s2",
            title = "Fork",
            parentSessionId = "s9",
            agentPreset = "other",
        ).toUi(existing = existing)
        assertEquals("s9", updated.parentSessionId)
        assertEquals("other", updated.agentPreset)

        val oldHost = SessionSummaryDto(id = "s3", title = "Old").toUi()
        assertNull(oldHost.parentSessionId)
        assertNull(oldHost.agentPreset)
    }

    @Test
    fun `preset labels are sanitized and bounded`() {
        assertEquals("cordis", sanitizedAgentPreset("  cordis  "))
        assertEquals("cordis", sanitizedAgentPreset("cordis\u0000\u0007"))
        assertNull(sanitizedAgentPreset(null))
        assertNull(sanitizedAgentPreset("   "))
        assertNull(sanitizedAgentPreset("\u0000"))
        val long = sanitizedAgentPreset("p".repeat(200)).orEmpty()
        assertEquals(80, long.length)

        assertEquals("Preset: cordis", sessionPresetLabel(session(agentPreset = "cordis")))
        assertNull(sessionPresetLabel(session()))
    }

    @Test
    fun `parent display uses loaded title and short id fallback`() {
        val parent = session(id = "sess_parent_1234567890", title = "Parent session")
        val child = session(parentSessionId = parent.id)
        val available = sessionParentUi(child, listOf(parent, child))
        assertEquals("Parent session", available?.title)
        assertEquals("sess_par…", available?.shortId)
        assertTrue(available?.available == true)
        assertEquals("Forked from Parent session", available?.let { sessionForkedFromLabel(it) })

        val unavailable = sessionParentUi(child, listOf(child))
        assertFalse(unavailable?.available == true)
        assertEquals("sess_par…", unavailable?.shortId)
        assertEquals("Forked from sess_par…", unavailable?.let { sessionForkedFromLabel(it) })
        assertEquals(
            "Open parent session: sess_par…",
            unavailable?.let { sessionOpenParentContentDescription(it) },
        )
    }

    @Test
    fun `open parent label is accessible and unavailable path is safe`() {
        val parent = session(id = "sess_parent_1234567890", title = "Parent session")
        val child = session(parentSessionId = parent.id)
        val available = sessionParentUi(child, listOf(parent, child))
        assertEquals(
            "Open parent session: Parent session",
            available?.let { sessionOpenParentContentDescription(it) },
        )
        assertEquals("Open parent", SESSION_OPEN_PARENT_LABEL)

        // Not-visible parent: non-null stable short id, explicit unavailable status, no crash.
        val missing = sessionParentUi(child, emptyList())
        assertEquals("sess_par…", missing?.shortId)
        assertEquals("Parent session not available", SESSION_PARENT_UNAVAILABLE)
        assertEquals("Open parent session: sess_par…", missing?.let {
            sessionOpenParentContentDescription(it)
        })
        assertEquals("sess_parent_1234567890", missing?.id)
        assertEquals("Forked from sess_par…", missing?.let { sessionForkedFromLabel(it) })
    }

    @Test
    fun `blank self and old-host parents never render lineage`() {
        assertNull(sessionParentUi(session(), listOf(session())))
        assertNull(sessionParentUi(session(parentSessionId = "  "), emptyList()))
        assertNull(sessionParentUi(session(parentSessionId = "sess_child"), listOf(session())))
        assertNull(sessionParentUi(session(parentSessionId = null), listOf(session())))
    }

    @Test
    fun `long parent ids and titles are shortened and sanitized`() {
        val parent = session(
            id = "x".repeat(60),
            title = "T".repeat(200) + "\n",
        )
        val child = session(parentSessionId = parent.id)
        val ui = sessionParentUi(child, listOf(parent, child))
        assertEquals(9, ui?.shortId?.length)
        assertTrue(ui?.shortId.orEmpty().endsWith("…"))
        assertEquals(120, ui?.title?.length)
        assertEquals("ab", shortSessionId("ab"))
        assertEquals("abcdefgh…", shortSessionId("abcdefghij"))
    }

    @Test
    fun `lineage copy constants are stable`() {
        assertEquals("Preset: ", SESSION_PRESET_LABEL_PREFIX)
        assertEquals("Forked from ", SESSION_FORKED_FROM_PREFIX)
        assertEquals("Parent session not available", SESSION_PARENT_UNAVAILABLE)
        assertEquals("Open parent", SESSION_OPEN_PARENT_LABEL)
    }
}
