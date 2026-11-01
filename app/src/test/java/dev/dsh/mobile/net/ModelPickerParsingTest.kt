package dev.dsh.mobile.net

import dev.dsh.mobile.data.ModelCatalog
import dev.dsh.mobile.data.ModelEffort
import dev.dsh.mobile.data.ModelFailure
import dev.dsh.mobile.data.ModelGroup
import dev.dsh.mobile.data.ModelInfo
import dev.dsh.mobile.data.ModelReasoning
import dev.dsh.mobile.data.ModelSelection
import dev.dsh.mobile.data.SessionModel
import dev.dsh.mobile.data.modelChipLabel
import dev.dsh.mobile.data.modelDefaultEffortId
import dev.dsh.mobile.data.modelDisplayName
import dev.dsh.mobile.data.modelEffortsFor
import dev.dsh.mobile.data.modelFailureForGroup
import dev.dsh.mobile.data.modelSelectionChanged
import dev.dsh.mobile.data.modelSelectionValid
import dev.dsh.mobile.data.sanitizedModelEffort
import dev.dsh.mobile.data.sessionModelSourceLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the model/thinking contract: GET catalog + session model decode, PUT request shape,
 * selection validation and the chip/label helpers.
 */
class ModelPickerParsingTest {

    private fun catalog(): ModelCatalog = ModelCatalog(
        supported = true,
        default = ModelSelection("deepseek", "m1", "medium"),
        groups = listOf(
            ModelGroup(
                id = "deepseek",
                name = "DeepSeek",
                models = listOf(
                    ModelInfo(
                        id = "m1",
                        name = "Model One",
                        description = "Balanced",
                        reasoning = ModelReasoning(
                            efforts = listOf(
                                ModelEffort("low", "Low"),
                                ModelEffort("high", "High"),
                            ),
                            defaultEffort = "medium",
                        ),
                    ),
                    ModelInfo(id = "m2", name = "Model Two"),
                ),
            ),
        ),
        failures = listOf(ModelFailure("bad", "Broken provider", "not configured")),
        truncated = true,
    )

    @Test
    fun `models response decodes catalog`() {
        val json = """
            {"supported":true,
             "default":{"provider":"deepseek","model":"m1","reasoningEffort":"medium"},
             "groups":[{"id":"deepseek","name":"DeepSeek","models":[
               {"id":"m1","name":"Model One","description":"Balanced",
                "reasoning":{"efforts":[{"id":"low","name":"Low"},
                                        {"id":"high","name":"High","description":"More thinking"}],
                             "defaultEffort":"medium"}},
               {"id":"m2","name":"Model Two"}]}],
             "failures":[{"id":"bad","name":"Broken provider","message":"not configured"}],
             "truncated":true}
        """.trimIndent()
        val response = DshJson.decodeFromString(ModelsResponse.serializer(), json)
        assertTrue(response.supported)
        assertEquals("deepseek", response.default?.provider)
        assertEquals("m1", response.default?.model)
        assertEquals("medium", response.default?.reasoningEffort)
        assertEquals(1, response.groups.size)
        assertEquals(2, response.groups[0].models.size)
        val reasoning = response.groups[0].models[0].reasoning
        assertEquals("medium", reasoning?.defaultEffort)
        assertEquals(listOf("low", "high"), reasoning?.efforts?.map { it.id })
        assertEquals("More thinking", reasoning?.efforts?.get(1)?.description)
        assertNull("reasoning is optional", response.groups[0].models[1].reasoning)
        assertEquals("not configured", response.failures[0].message)
        assertTrue(response.truncated)
    }

    @Test
    fun `models response defaults are safe`() {
        val response = DshJson.decodeFromString(ModelsResponse.serializer(), """{}""")
        assertTrue(response.supported)
        assertNull(response.default)
        assertTrue(response.groups.isEmpty())
        assertTrue(response.failures.isEmpty())
        assertFalse(response.truncated)
    }

    @Test
    fun `session model decodes source and live`() {
        val json = """
            {"supported":true,"live":true,
             "selection":{"provider":"anthropic","model":"claude","reasoningEffort":"high"},
             "source":"session"}
        """.trimIndent()
        val response = DshJson.decodeFromString(SessionModelResponse.serializer(), json)
        assertTrue(response.supported)
        assertTrue(response.live)
        assertEquals("anthropic", response.selection?.provider)
        assertEquals("claude", response.selection?.model)
        assertEquals("high", response.selection?.reasoningEffort)
        assertEquals("session", response.source)
    }

    @Test
    fun `session model defaults and unsupported decode`() {
        val defaults = DshJson.decodeFromString(SessionModelResponse.serializer(), """{}""")
        assertTrue(defaults.supported)
        assertFalse(defaults.live)
        assertNull(defaults.selection)
        assertEquals("default", defaults.source)

        val unsupported = DshJson.decodeFromString(
            SessionModelResponse.serializer(),
            """{"supported":false}""",
        )
        assertFalse(unsupported.supported)
    }

    @Test
    fun `selection request omits blank reasoning effort`() {
        val defaultEffort = DshJson.encodeToString(
            ModelSelectionRequest.serializer(),
            ModelSelectionRequest(provider = "deepseek", model = "m1", reasoningEffort = null),
        )
        assertTrue(defaultEffort.contains("\"provider\":\"deepseek\""))
        assertTrue(defaultEffort.contains("\"model\":\"m1\""))
        assertFalse(defaultEffort.contains("reasoningEffort"))

        val withEffort = DshJson.encodeToString(
            ModelSelectionRequest.serializer(),
            ModelSelectionRequest(provider = "deepseek", model = "m1", reasoningEffort = "high"),
        )
        assertTrue(withEffort.contains("\"reasoningEffort\":\"high\""))
    }

    @Test
    fun `selection validation requires provider and model`() {
        assertTrue(modelSelectionValid(ModelSelection("deepseek", "m1")))
        assertFalse(modelSelectionValid(null))
        assertFalse(modelSelectionValid(ModelSelection("", "m1")))
        assertFalse(modelSelectionValid(ModelSelection("deepseek", " ")))
    }

    @Test
    fun `selection change detection compares provider model and effort`() {
        val base = ModelSelection("deepseek", "m1", "low")
        assertFalse(modelSelectionChanged(base, base.copy()))
        assertTrue(modelSelectionChanged(base, base.copy(reasoningEffort = "high")))
        assertTrue(modelSelectionChanged(base, base.copy(model = "m2")))
        assertTrue(modelSelectionChanged(base, base.copy(provider = "anthropic")))
        assertTrue(modelSelectionChanged(null, base))
        assertTrue(modelSelectionChanged(base, null))
        assertFalse(modelSelectionChanged(null, null))
    }

    @Test
    fun `display helpers resolve names with id fallbacks`() {
        val catalog = catalog()
        assertEquals(
            "Model One",
            modelDisplayName(catalog, ModelSelection("deepseek", "m1")),
        )
        assertEquals("missing-model", modelDisplayName(catalog, ModelSelection("deepseek", "missing-model")))
        assertEquals("", modelDisplayName(catalog, null))

        assertEquals(
            "High",
            dev.dsh.mobile.data.modelEffortDisplayName(
                catalog,
                ModelSelection("deepseek", "m1", "high"),
            ),
        )
        assertEquals(
            "custom",
            dev.dsh.mobile.data.modelEffortDisplayName(
                catalog,
                ModelSelection("deepseek", "m1", "custom"),
            ),
        )
        assertNull(
            dev.dsh.mobile.data.modelEffortDisplayName(
                catalog,
                ModelSelection("deepseek", "m1", null),
            ),
        )
    }

    @Test
    fun `chip label combines model and effort`() {
        val catalog = catalog()
        assertEquals("Model One · High", modelChipLabel(catalog, ModelSelection("deepseek", "m1", "high")))
        assertEquals("Model One", modelChipLabel(catalog, ModelSelection("deepseek", "m1")))
        assertEquals("Model", modelChipLabel(catalog, null))
    }

    @Test
    fun `efforts come from the selected model`() {
        val catalog = catalog()
        assertEquals(
            listOf("low", "high"),
            modelEffortsFor(catalog, ModelSelection("deepseek", "m1")).map { it.id },
        )
        assertTrue(modelEffortsFor(catalog, ModelSelection("deepseek", "m2")).isEmpty())
        assertTrue(modelEffortsFor(null, ModelSelection("deepseek", "m1")).isEmpty())
    }

    @Test
    fun `stale efforts are sanitized to an advertised default`() {
        val catalog = catalog()
        // Advertised and selected model: keep it.
        assertEquals(
            "high",
            sanitizedModelEffort(catalog, ModelSelection("deepseek", "m1", "high")),
        )
        // Not advertised by the model: fall back to the model's advertised default (first effort,
        // since the fixture's "medium" default is itself not in the efforts list).
        assertEquals(
            "low",
            sanitizedModelEffort(catalog, ModelSelection("deepseek", "m1", "stale")),
        )
        assertEquals(
            "low",
            sanitizedModelEffort(catalog, ModelSelection("deepseek", "m1", "medium")),
        )
        // Model without thinking controls never sends an effort.
        assertNull(sanitizedModelEffort(catalog, ModelSelection("deepseek", "m2", "high")))
        assertNull(sanitizedModelEffort(catalog, ModelSelection("deepseek", "m1", null)))
        assertNull(sanitizedModelEffort(catalog, ModelSelection("deepseek", "missing", "high")))
        assertNull(sanitizedModelEffort(null, ModelSelection("deepseek", "m1", "high")))
    }

    @Test
    fun `model default effort prefers the advertised default when valid`() {
        val catalog = catalog()
        // Fixture default "medium" is not offered; the first advertised effort wins.
        assertEquals("low", modelDefaultEffortId(catalog, "deepseek", "m1"))
        assertNull(modelDefaultEffortId(catalog, "deepseek", "m2"))
        assertNull(modelDefaultEffortId(catalog, "missing", "m1"))

        val validDefault = catalog.copy(
            groups = listOf(
                ModelGroup(
                    id = "deepseek",
                    name = "DeepSeek",
                    models = listOf(
                        ModelInfo(
                            id = "m3",
                            name = "Model Three",
                            reasoning = ModelReasoning(
                                efforts = listOf(
                                    ModelEffort("low", "Low"),
                                    ModelEffort("high", "High"),
                                ),
                                defaultEffort = "high",
                            ),
                        ),
                    ),
                ),
            ),
        )
        assertEquals("high", modelDefaultEffortId(validDefault, "deepseek", "m3"))
    }

    @Test
    fun `provider failures are matched to groups by id or name`() {
        val catalog = catalog().copy(
            failures = listOf(
                ModelFailure(id = "deepseek", name = "DeepSeek", message = "auth missing"),
                ModelFailure(id = "other", name = "Broken provider", message = "offline"),
            ),
        )
        val deepseek = catalog.groups.first()
        val failure = modelFailureForGroup(catalog, deepseek)
        assertEquals("auth missing", failure?.message)
        assertNull(
            modelFailureForGroup(
                catalog,
                ModelGroup(id = "fine", name = "Fine", models = emptyList()),
            ),
        )
        assertNull(modelFailureForGroup(null, deepseek))
    }

    @Test
    fun `source labels are friendly and unsupported is not live`() {
        assertEquals("Session override", sessionModelSourceLabel("session"))
        assertEquals("Host default", sessionModelSourceLabel("default"))
        assertEquals("Host default", sessionModelSourceLabel(""))
        assertFalse(ModelCatalog.Unsupported.supported)
        assertFalse(SessionModel.Unsupported.supported)
        assertFalse(SessionModel.Unsupported.live)
    }
}
