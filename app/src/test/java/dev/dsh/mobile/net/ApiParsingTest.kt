package dev.dsh.mobile.net

import dev.dsh.mobile.data.AssistantMessage
import dev.dsh.mobile.data.GoalPhaseBadge
import dev.dsh.mobile.data.SystemNote
import dev.dsh.mobile.data.ToolState
import dev.dsh.mobile.data.UserMessage
import dev.dsh.mobile.data.clockLabel
import dev.dsh.mobile.data.dayLabel
import dev.dsh.mobile.data.relativeLabel
import dev.dsh.mobile.data.toUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * JVM-only checks for the DSH Mobile API v1 wire format: JSON DTO decoding, SSE frame parsing
 * (full envelope in `data:`), nullable workspace handling and the wire -> UI model mapping.
 * No Android or network dependency, so this runs anywhere.
 */
class ApiParsingTest {

    // region JSON DTOs

    @Test
    fun `session summary decodes with nullable workspaceId`() {
        val json = """
            {"sessions":[
              {"id":"s_1","title":"Cut a release","workspaceId":"ws_1","status":"working",
               "preview":"All tests passed","updatedAt":"2026-10-30T09:41:12Z","unread":true,"live":true},
              {"id":"s_2","title":"Orphaned session","workspaceId":null,"status":"idle",
               "preview":"","updatedAt":"2026-10-29T09:41:12Z","unread":false}
            ]}
        """.trimIndent()

        val response = DshJson.decodeFromString(SessionsResponse.serializer(), json)
        assertEquals(2, response.sessions.size)
        assertEquals("ws_1", response.sessions[0].workspaceId)
        assertNull("workspaceId may legitimately be null", response.sessions[1].workspaceId)

        val uiSession = response.sessions[1].toUi()
        assertEquals("", uiSession.workspaceId)
        assertEquals("Orphaned session", uiSession.title)
    }

    @Test
    fun `message rows map to UI models including tool rows`() {
        val json = """
            {"id":"s_1","title":"t","workspaceId":"ws_1","status":"idle","preview":"","updatedAt":"","unread":false}
        """.trimIndent()
        val session = DshJson.decodeFromString(SessionSummaryDto.serializer(), json)
        assertEquals("s_1", session.id)

        val user = DshJson.decodeFromString(
            MessageDto.serializer(),
            """{"id":"m_1","role":"user","text":"hello","createdAt":"2026-10-30T09:41:12Z","streaming":false}""",
        ).toUi()
        assertTrue(user is UserMessage)
        assertEquals("hello", (user as UserMessage).text)

        val tool = DshJson.decodeFromString(
            MessageDto.serializer(),
            """{"id":"m_2","role":"tool","text":"","createdAt":"2026-10-30T09:41:12Z",
                "tool":{"name":"Bash","detail":"pwsh test","state":"running"}}""",
        ).toUi()
        assertTrue(tool is AssistantMessage)
        assertEquals(ToolState.Running, (tool as AssistantMessage).tool?.state)
        assertEquals("Bash", tool.tool?.name)

        val system = DshJson.decodeFromString(
            MessageDto.serializer(),
            """{"id":"m_3","role":"system","text":"You denied: cmd","createdAt":"2026-10-30T09:41:12Z"}""",
        ).toUi()
        assertTrue(system is SystemNote)

        val assistant = DshJson.decodeFromString(
            MessageDto.serializer(),
            """{"id":"m_4","role":"assistant","text":"All 128 ","createdAt":"2026-10-30T09:41:12Z","streaming":true}""",
        ).toUi()
        assertTrue(assistant is AssistantMessage)
        assertTrue((assistant as AssistantMessage).streaming)
    }

    @Test
    fun `error envelope decodes`() {
        val envelope = DshJson.decodeFromString(
            ErrorEnvelopeDto.serializer(),
            """{"error":{"code":"invalid_code","message":"Pairing code invalid or expired"}}""",
        )
        assertEquals("invalid_code", envelope.error?.code)
        assertEquals("Pairing code invalid or expired", envelope.error?.message)
    }

    // endregion

    // region SSE framing

    @Test
    fun `sse parser emits full envelope for replayable frame`() {
        val parser = SseParser()
        val frame = listOf(
            "event: message.delta",
            "id: ev_000123",
            """data: {"id":"ev_000123","type":"message.delta","at":"2026-10-30T10:00:00Z","sessionId":"s_1","data":{"sessionId":"s_1","messageId":"m_9","text":"All 128 "}}""",
            "",
        )
        var event: SseEvent? = null
        frame.forEach { line ->
            parser.feed(line)?.let { event = it }
        }
        assertNotNull(event)
        assertEquals("ev_000123", event!!.id)
        assertEquals("message.delta", event!!.type)
        assertEquals("s_1", event!!.sessionId)
        assertEquals("All 128 ", event!!.data?.get("text")?.toString()?.trim('"'))
    }

    @Test
    fun `sse parser handles per-connection frames without id`() {
        val parser = SseParser()
        val hostStatus = listOf(
            "event: host.status",
            """data: {"type":"host.status","at":"2026-10-30T10:00:00Z","data":{"connected":true}}""",
            "",
        )
        var status: SseEvent? = null
        hostStatus.forEach { line ->
            parser.feed(line)?.let { status = it }
        }
        assertNotNull(status)
        assertNull("host.status carries no replay cursor", status!!.id)
        assertEquals("host.status", status!!.type)
        assertEquals("true", status!!.data?.get("connected")?.toString())

        val ping = listOf(
            "event: ping",
            """data: {"type":"ping","at":"2026-10-30T10:00:20Z","data":{}}""",
            "",
        )
        var pingEvent: SseEvent? = null
        ping.forEach { line ->
            parser.feed(line)?.let { pingEvent = it }
        }
        assertNotNull(pingEvent)
        assertNull(pingEvent!!.id)
        assertEquals("ping", pingEvent!!.type)
    }

    @Test
    fun `sse parser ignores comments and multiple data lines are joined`() {
        val parser = SseParser()
        val lines = listOf(
            ": keep-alive",
            "event: question.requested",
            """data: {"id":"ev_9","type":"question.requested","at":"x","sessionId":"s_1","data":""",
            """data: {"id":"q_1","sessionId":"s_1","prompt":"Which approach?","options":["A","B"],"createdAt":"x"}}""",
            "",
        )
        var event: SseEvent? = null
        lines.forEach { line ->
            parser.feed(line)?.let { event = it }
        }
        assertNotNull(event)
        val payload = event!!.data
        assertNotNull(payload)
        assertEquals("q_1", payload!!["id"]?.toString()?.trim('"'))
    }

    // endregion

    // region time labels

    @Test
    fun `relative and day labels match the session list groups`() {
        val fourMinutesAgo = Instant.now().minus(4, ChronoUnit.MINUTES).toString()
        assertEquals("4m", relativeLabel(fourMinutesAgo))
        assertTrue(clockLabel(fourMinutesAgo).matches(Regex("\\d{2}:\\d{2}")))

        assertEquals("Today", dayLabel(Instant.now().toString()))
        assertEquals("Yesterday", dayLabel(Instant.now().minus(1, ChronoUnit.DAYS).toString()))
        assertEquals("Earlier", dayLabel(Instant.now().minus(5, ChronoUnit.DAYS).toString()))
    }

    // endregion

    // region API root normalisation

    @Test
    fun `api root normalisation accepts host roots and full api roots`() {
        assertEquals("https://pc.tailnet.ts.net/mobile/v1", DshApiClient.normalizeApiRoot("https://pc.tailnet.ts.net"))
        assertEquals("https://pc.tailnet.ts.net/mobile/v1", DshApiClient.normalizeApiRoot("https://pc.tailnet.ts.net/"))
        assertEquals("http://10.0.2.2:3543/mobile/v1", DshApiClient.normalizeApiRoot("http://10.0.2.2:3543"))
        assertEquals(
            "https://pc.ts.net/mobile/v1",
            DshApiClient.normalizeApiRoot("https://pc.ts.net/mobile/v1"),
        )
    }

    @Test
    fun `api root normalisation rejects non-http schemes`() {
        for (bad in listOf("pc.tailnet.ts.net", "ftp://pc", "https://")) {
            try {
                DshApiClient.normalizeApiRoot(bad)
                fail("Expected IllegalArgumentException for $bad")
            } catch (expected: IllegalArgumentException) {
                assertFalse(expected.message.isNullOrBlank())
            }
        }
    }

    @Test
    fun `goal response decodes a paused goal`() {
        val json = """
            {"supported":true,"live":true,"goal":{
              "id":"goal-1","objective":"Ship the mobile goal controls","phase":"paused",
              "maxGoalRounds":120,"roundsStarted":61,"revision":6,"activation":"disarmed",
              "createdAt":"2026-10-30T17:16:03Z","updatedAt":"2026-10-31T05:44:32Z"}}
        """.trimIndent()
        val response = DshJson.decodeFromString(SessionGoalResponse.serializer(), json)
        assertTrue(response.supported)
        assertTrue(response.live)
        val goal = response.goal
        assertNotNull(goal)
        assertEquals("goal-1", goal?.id)
        assertEquals("paused", goal?.phase)
        assertEquals(61, goal?.roundsStarted)
        assertEquals(120, goal?.maxGoalRounds)
        assertEquals("disarmed", goal?.activation)
        assertNull(goal?.blockedReason)
    }

    @Test
    fun `goal response tolerates a missing goal and unknown fields`() {
        val json = """{"supported":true,"live":false,"futureField":42}"""
        val response = DshJson.decodeFromString(SessionGoalResponse.serializer(), json)
        assertTrue(response.supported)
        assertFalse(response.live)
        assertNull(response.goal)
    }

    @Test
    fun `goal changed SSE payload decodes phase and activation`() {
        val json = """
            {"sessionId":"session-1","operation":"resume","goal":{
              "id":"goal-9","objective":"Keep going","phase":"active",
              "maxGoalRounds":120,"roundsStarted":62,"revision":7,"activation":"armed",
              "createdAt":"2026-10-30T17:16:03Z","updatedAt":"2026-10-31T06:00:00Z"}}
        """.trimIndent()
        val event = DshJson.decodeFromString(GoalChangedEventDto.serializer(), json)
        assertEquals("session-1", event.sessionId)
        assertEquals("resume", event.operation)
        assertEquals("active", event.goal?.phase)
        assertEquals(62, event.goal?.roundsStarted)
        assertEquals("armed", event.goal?.activation)
    }

    @Test
    fun `session summary decodes optional goal and maps the badge fields`() {
        val json = """
            {"sessions":[
              {"id":"s_1","title":"Goal session","workspaceId":"ws_1","status":"working",
               "preview":"","updatedAt":"2026-10-30T09:41:12Z","unread":false,
               "goal":{"phase":"paused","roundsStarted":12,"maxGoalRounds":120}},
              {"id":"s_2","title":"No goal","workspaceId":"ws_1","status":"idle",
               "preview":"","updatedAt":"2026-10-30T09:41:12Z","unread":false}
            ]}
        """.trimIndent()
        val response = DshJson.decodeFromString(SessionsResponse.serializer(), json)
        val goalDto = response.sessions[0]
        assertEquals("paused", goalDto.goal?.phase)
        assertEquals(12, goalDto.goal?.roundsStarted)
        assertEquals(120, goalDto.goal?.maxGoalRounds)
        assertNull("absent goal is null", response.sessions[1].goal)

        val uiSession = goalDto.toUi()
        assertEquals("paused", uiSession.goalPhase)
        assertEquals(12, uiSession.goalRoundsStarted)
        assertEquals(120, uiSession.goalMaxRounds)
        assertEquals("goal paused", GoalPhaseBadge.label(uiSession.goalPhase))
        assertNull(GoalPhaseBadge.label(response.sessions[1].toUi().goalPhase))
    }

    @Test
    fun `session summary tolerates explicit null goal and unknown fields`() {
        val json = """
            {"id":"s_3","title":"t","workspaceId":"ws_1","status":"idle","preview":"",
             "updatedAt":"2026-10-30T09:41:12Z","unread":false,"goal":null,"futureGoalField":42}
        """.trimIndent()
        val dto = DshJson.decodeFromString(SessionSummaryDto.serializer(), json)
        assertNull(dto.goal)
        assertNull(dto.toUi().goalPhase)
    }

    @Test
    fun `goal phase badge normalizes casing and blanks`() {
        assertEquals("goal active", GoalPhaseBadge.label("active"))
        assertEquals("goal paused", GoalPhaseBadge.label(" PAUSED "))
        assertEquals("goal complete", GoalPhaseBadge.label("Complete"))
        assertNull(GoalPhaseBadge.label(null))
        assertNull(GoalPhaseBadge.label("   "))
    }

    @Test
    fun `session updated without goal preserves an existing goal badge`() {
        val base = SessionSummaryDto(
            id = "s_1",
            title = "Goal session",
            goal = GoalSummaryDto(phase = "paused", roundsStarted = 2, maxGoalRounds = 10),
        ).toUi()
        val updated = SessionSummaryDto(id = "s_1", title = "Goal session")
            .toUi(base, preserveGoalWhenMissing = true)
        assertEquals("paused", updated.goalPhase)
        assertEquals(2, updated.goalRoundsStarted)

        // A list snapshot legitimately omits the goal: that must clear the badge.
        val cleared = SessionSummaryDto(id = "s_1", title = "Goal session")
            .toUi(base, preserveGoalWhenMissing = false)
        assertNull(cleared.goalPhase)
    }

    @Test
    fun `session summary decodes optional goal objective and maps it`() {
        val json = """
            {"sessions":[
              {"id":"s_1","title":"Goal session","workspaceId":"ws_1","status":"active",
               "preview":"preview","updatedAt":"2026-10-30T09:41:12Z","unread":false,
               "goal":{"phase":"active","roundsStarted":3,"maxGoalRounds":120,
                       "objective":"  Ship the release with tests  "}},
              {"id":"s_2","title":"Legacy goal","workspaceId":"ws_1","status":"paused",
               "preview":"fallback preview","updatedAt":"2026-10-30T09:41:12Z","unread":false,
               "goal":{"phase":"paused","roundsStarted":1,"maxGoalRounds":60}}
            ]}
        """.trimIndent()
        val response = DshJson.decodeFromString(SessionsResponse.serializer(), json)
        assertEquals("  Ship the release with tests  ", response.sessions[0].goal?.objective)
        assertEquals("absent objective defaults to empty", "", response.sessions[1].goal?.objective)

        val withObjective = response.sessions[0].toUi()
        assertEquals("Ship the release with tests", withObjective.goalObjective)

        val legacy = response.sessions[1].toUi()
        assertNull("legacy summary without objective maps to null", legacy.goalObjective)
    }

    @Test
    fun `session updated without goal preserves an existing goal objective`() {
        val base = SessionSummaryDto(
            id = "s_1",
            title = "Goal session",
            goal = GoalSummaryDto(
                phase = "active",
                roundsStarted = 2,
                maxGoalRounds = 10,
                objective = "Keep the stream alive",
            ),
        ).toUi()
        assertEquals("Keep the stream alive", base.goalObjective)

        val updated = SessionSummaryDto(id = "s_1", title = "Goal session")
            .toUi(base, preserveGoalWhenMissing = true)
        assertEquals("Keep the stream alive", updated.goalObjective)
        assertEquals("active", updated.goalPhase)
    }

    // endregion
}
