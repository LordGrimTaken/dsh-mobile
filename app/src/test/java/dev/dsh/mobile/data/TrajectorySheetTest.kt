package dev.dsh.mobile.data

import dev.dsh.mobile.net.DshJson
import dev.dsh.mobile.net.TrajectoryResponse
import dev.dsh.mobile.net.trajectoryQueryParams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** GET /sessions/{id}/trajectory contract + pure journal helpers (pagination, dedupe, TTL). */
class TrajectorySheetTest {

    private fun event(seq: Int, type: String = "message", status: String? = null) =
        TrajectoryEvent(
            seq = seq,
            time = "2026-11-01T10:0$seq:00Z",
            typeLabel = trajectoryTypeLabel(type),
            summary = "event $seq",
            status = normalizedTrajectoryStatus(status),
        )

    @Test
    fun `trajectory response decodes supported and pagination fields`() {
        val response = DshJson.decodeFromString(
            TrajectoryResponse.serializer(),
            """{"supported":true,
               "events":[{"seq":42,"time":"2026-11-01T10:00:00Z","type":"tool_call",
                          "summary":"ran ls","status":"ok"}],
               "nextBeforeSeq":42,"hasMore":true}""",
        )
        assertTrue(response.supported)
        assertEquals(1, response.events.size)
        assertEquals(42, response.events[0].seq)
        assertEquals("tool_call", response.events[0].type)
        assertEquals("ok", response.events[0].status)
        assertEquals(42, response.nextBeforeSeq)
        assertTrue(response.hasMore)
    }

    @Test
    fun `unsupported and old hosts default safely`() {
        val unsupported = DshJson.decodeFromString(
            TrajectoryResponse.serializer(),
            """{"supported":false}""",
        )
        assertFalse(unsupported.supported)
        assertTrue(unsupported.events.isEmpty())
        assertNull(unsupported.nextBeforeSeq)
        assertFalse(unsupported.hasMore)

        val defaults = DshJson.decodeFromString(TrajectoryResponse.serializer(), """{}""")
        assertFalse(defaults.supported)
        assertNull(defaults.nextBeforeSeq)
    }

    @Test
    fun `query params request the newest page or a validated older cursor`() {
        assertEquals(mapOf("limit" to "100"), trajectoryQueryParams())
        assertEquals(
            mapOf("limit" to "100", "beforeSeq" to "42"),
            trajectoryQueryParams(beforeSeq = 42),
        )
        // non-positive cursors are never sent
        assertEquals(mapOf("limit" to "100"), trajectoryQueryParams(beforeSeq = 0))
        assertEquals(mapOf("limit" to "100"), trajectoryQueryParams(beforeSeq = -5))
        // limit is clamped to a sane window
        assertEquals(mapOf("limit" to "1"), trajectoryQueryParams(limit = 0))
        assertEquals(mapOf("limit" to "200"), trajectoryQueryParams(limit = 9999))
    }

    @Test
    fun `type labels are friendly and bounded`() {
        assertEquals("Tool call", trajectoryTypeLabel("tool_call"))
        assertEquals("User message", trajectoryTypeLabel("user_message"))
        assertEquals("Message", trajectoryTypeLabel("assistant"))
        assertEquals("Delegation", trajectoryTypeLabel("subagent"))
        assertEquals("Plan", trajectoryTypeLabel("todo"))
        assertEquals("Event", trajectoryTypeLabel(null))
        assertEquals("Event", trajectoryTypeLabel("   "))
        assertEquals("Custom event", trajectoryTypeLabel("custom_event"))
        assertEquals("Weird type", trajectoryTypeLabel("weird-type"))
        assertEquals(40, trajectoryTypeLabel("x".repeat(200)).length)
    }

    @Test
    fun `summary and time are sanitized and bounded`() {
        assertEquals("ran ls", sanitizedTrajectorySummary(" ran\nls "))
        assertEquals("(no summary)", sanitizedTrajectorySummary("   "))
        assertEquals("(no summary)", sanitizedTrajectorySummary(null))
        assertEquals(
            TRAJECTORY_MAX_SUMMARY_CHARS,
            sanitizedTrajectorySummary("s".repeat(900)).length,
        )
        assertEquals("a b", sanitizedTrajectorySummary("a\u0000 b"))
        assertEquals("2026-11-01T10:00:00Z", sanitizedTrajectoryTime(" 2026-11-01T10:00:00Z "))
        assertEquals("", sanitizedTrajectoryTime(null))
    }

    @Test
    fun `status normalization and error tint cue`() {
        assertEquals("failed", normalizedTrajectoryStatus(" Failed "))
        assertNull(normalizedTrajectoryStatus("  "))
        assertNull(normalizedTrajectoryStatus(null))
        assertTrue(isTrajectoryErrorStatus("error"))
        assertTrue(isTrajectoryErrorStatus("FAILED"))
        assertTrue(isTrajectoryErrorStatus("timeout"))
        assertFalse(isTrajectoryErrorStatus("ok"))
        assertFalse(isTrajectoryErrorStatus(null))
    }

    @Test
    fun `older pages append in order and dedupe by seq`() {
        val current = HostTrajectoryPage(
            supported = true,
            events = listOf(event(100), event(90)),
            nextBeforeSeq = 90,
            hasMore = true,
        )
        val older = HostTrajectoryPage(
            supported = true,
            events = listOf(event(90), event(80), event(70), event(70)),
            nextBeforeSeq = 70,
            hasMore = true,
        )
        val merged = appendTrajectoryPage(current, older, requestedBeforeSeq = 90)
        assertEquals(listOf(100, 90, 80, 70), merged.events.map { it.seq })
        assertEquals(70, merged.nextBeforeSeq)
        assertTrue(merged.hasMore)
    }

    @Test
    fun `stale page is rejected and empty page stops pagination`() {
        val current = HostTrajectoryPage(
            supported = true,
            events = listOf(event(100), event(90)),
            nextBeforeSeq = 90,
            hasMore = true,
        )
        val stale = HostTrajectoryPage(
            supported = true,
            events = listOf(event(10)),
            nextBeforeSeq = 10,
            hasMore = false,
        )
        val unchanged = appendTrajectoryPage(current, stale, requestedBeforeSeq = 50)
        assertEquals(current, unchanged)

        val empty = HostTrajectoryPage(supported = true, nextBeforeSeq = null, hasMore = true)
        val stopped = appendTrajectoryPage(current, empty, requestedBeforeSeq = 90)
        assertEquals(listOf(100, 90), stopped.events.map { it.seq })
        assertFalse(stopped.hasMore)
        assertNull(stopped.nextBeforeSeq)

        // A failed/unsupported older page never destroys the visible journal or its cursor.
        assertEquals(current, appendTrajectoryPage(current, HostTrajectoryPage.Unsupported, 90))
        assertTrue(appendTrajectoryPage(current, HostTrajectoryPage.Unsupported, 90).hasMore)
    }

    @Test
    fun `load older is only offered when the cursor is usable and nothing is in flight`() {
        val page = HostTrajectoryPage(
            supported = true,
            events = listOf(event(5)),
            nextBeforeSeq = 5,
            hasMore = true,
        )
        assertTrue(canLoadOlderTrajectory(page, false, false))
        assertFalse(canLoadOlderTrajectory(page, true, false))
        assertFalse(canLoadOlderTrajectory(page, false, true))
        assertFalse(canLoadOlderTrajectory(page.copy(hasMore = false), false, false))
        assertFalse(canLoadOlderTrajectory(page.copy(nextBeforeSeq = null), false, false))
        assertFalse(canLoadOlderTrajectory(page.copy(nextBeforeSeq = 0), false, false))
        assertFalse(canLoadOlderTrajectory(null, false, false))
        assertFalse(canLoadOlderTrajectory(HostTrajectoryPage.Unsupported, false, false))
    }

    @Test
    fun `A1 trajectory cache respects the thirty second TTL`() {
        val cached = HostTrajectoryPage.Empty
        val now = 1_800_000_000_000L
        assertFalse(
            shouldLoadSessionTrajectory(cached, false, false, fetchedAtMs = now - 1_000, nowMs = now),
        )
        assertTrue(
            shouldLoadSessionTrajectory(cached, false, true, fetchedAtMs = now - 1_000, nowMs = now),
        )
        assertTrue(
            shouldLoadSessionTrajectory(
                cached,
                false,
                false,
                fetchedAtMs = now - TRAJECTORY_CACHE_TTL_MS,
                nowMs = now,
            ),
        )
        assertFalse(shouldLoadSessionTrajectory(cached, true, true, fetchedAtMs = now, nowMs = now))
        assertTrue(shouldLoadSessionTrajectory(null, false, false, nowMs = now))
        assertEquals(30_000L, TRAJECTORY_CACHE_TTL_MS)
    }

    @Test
    fun `copy and models are distinct`() {
        assertEquals("Trajectory", TRAJECTORY_ACTION_LABEL)
        assertEquals("Trajectory", TRAJECTORY_SHEET_TITLE)
        assertEquals("Read-only journal of this session.", TRAJECTORY_READ_ONLY_NOTE)
        assertEquals("No trajectory events yet.", TRAJECTORY_EMPTY_COPY)
        assertEquals("This host does not expose a trajectory.", TRAJECTORY_UNSUPPORTED_COPY)
        assertEquals("Couldn't load the trajectory.", TRAJECTORY_LOAD_FAILED_COPY)
        assertEquals("Loading trajectory…", TRAJECTORY_LOADING_COPY)
        assertEquals("Loading older events…", TRAJECTORY_LOADING_OLDER_COPY)
        assertEquals("Load older", TRAJECTORY_LOAD_OLDER_LABEL)
        assertFalse(HostTrajectoryPage.Unsupported.supported)
        assertTrue(HostTrajectoryPage.Empty.supported)
    }
}
