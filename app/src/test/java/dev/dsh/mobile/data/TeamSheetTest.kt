package dev.dsh.mobile.data

import dev.dsh.mobile.net.DshJson
import dev.dsh.mobile.net.TeamResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** GET /sessions/{id}/team contract + the pure team-sheet helpers. */
class TeamSheetTest {

    private val tasks = listOf(
        TeamTask("t1", "write docs", TeamTaskStatus.Pending),
        TeamTask("t2", "review", TeamTaskStatus.InProgress, blockedBy = listOf("t1")),
        TeamTask("t3", "ship", TeamTaskStatus.Blocked, blockedBy = listOf("t2", " t1 ", "")),
    )

    @Test
    fun `team response decodes with supported, counts and blockedBy`() {
        val response = DshJson.decodeFromString(
            TeamResponse.serializer(),
            """{"supported":true,
               "members":[{"name":"lead","phase":"working"},{"name":"critic","phase":"review"}],
               "tasks":[{"id":"t1","subject":"write docs","status":"pending"},
                        {"id":"t2","subject":"review","status":"in_progress","blockedBy":["t1"]}],
               "counts":{"pending":1,"inProgress":1,"blocked":0,"completed":4}}""",
        )
        assertTrue(response.supported)
        assertEquals(2, response.members.size)
        assertEquals("lead", response.members[0].name)
        assertEquals("working", response.members[0].phase)
        assertEquals(2, response.tasks.size)
        assertEquals(listOf("t1"), response.tasks[1].blockedBy)
        assertEquals(4, response.counts?.completed)
    }

    @Test
    fun `unsupported and old hosts default safely`() {
        val unsupported = DshJson.decodeFromString(
            TeamResponse.serializer(),
            """{"supported":false}""",
        )
        assertFalse(unsupported.supported)
        assertTrue(unsupported.members.isEmpty())
        assertTrue(unsupported.tasks.isEmpty())
        assertNull(unsupported.counts)

        val defaults = DshJson.decodeFromString(TeamResponse.serializer(), """{}""")
        assertFalse(defaults.supported)
        assertNull(defaults.counts)

        val missingCounts = DshJson.decodeFromString(
            TeamResponse.serializer(),
            """{"supported":true,"tasks":[{"id":"t1","subject":"x","status":"pending"}]}""",
        )
        assertNull(missingCounts.counts)
    }

    @Test
    fun `task statuses normalize`() {
        assertEquals(TeamTaskStatus.InProgress, normalizedTeamTaskStatus("in_progress"))
        assertEquals(TeamTaskStatus.InProgress, normalizedTeamTaskStatus("in-progress"))
        assertEquals(TeamTaskStatus.InProgress, normalizedTeamTaskStatus("working"))
        assertEquals(TeamTaskStatus.Blocked, normalizedTeamTaskStatus("blocked"))
        assertEquals(TeamTaskStatus.Blocked, normalizedTeamTaskStatus("waiting"))
        assertEquals(TeamTaskStatus.Completed, normalizedTeamTaskStatus("done"))
        assertEquals(TeamTaskStatus.Pending, normalizedTeamTaskStatus("pending"))
        assertEquals(TeamTaskStatus.Pending, normalizedTeamTaskStatus("mystery"))
        assertEquals(TeamTaskStatus.Pending, normalizedTeamTaskStatus(null))
        assertEquals("In progress", TeamTaskStatus.InProgress.label)
        assertEquals("Done", TeamTaskStatus.Completed.label)
    }

    @Test
    fun `names phases and subjects are sanitized and bounded`() {
        assertEquals("lead", sanitizedTeamName(" lead "))
        assertEquals("Agent", sanitizedTeamName(""))
        assertEquals("Agent", sanitizedTeamName(null))
        assertEquals("a b", sanitizedTeamName("a\u0000 b"))
        assertEquals(TEAM_MAX_NAME_CHARS, sanitizedTeamName("n".repeat(300)).length)
        assertEquals("working", sanitizedTeamPhase(" working "))
        assertNull(sanitizedTeamPhase("   "))
        assertEquals("phase unknown", teamMemberPhaseLabel(null))
        assertEquals("review", teamMemberPhaseLabel("review"))
        assertEquals("write docs", sanitizedTeamSubject("write\ndocs"))
        assertEquals("(untitled task)", sanitizedTeamSubject(null))
        assertEquals(TEAM_MAX_SUBJECT_CHARS, sanitizedTeamSubject("s".repeat(900)).length)
    }

    @Test
    fun `counts clamp and derive active rows`() {
        assertEquals(
            TeamCounts(pending = 1, inProgress = 1, blocked = 1, completed = 0),
            clampedTeamCounts(null, tasks),
        )
        val clamped = clampedTeamCounts(
            TeamCounts(pending = -2, inProgress = 5, blocked = -1, completed = -3),
            tasks,
        )
        assertEquals(0, clamped.pending)
        assertEquals(5, clamped.inProgress)
        assertEquals(0, clamped.blocked)
        assertEquals(0, clamped.completed)
        assertEquals(5, clamped.active)
    }

    @Test
    fun `active hint, action description and counts summary`() {
        val counts = TeamCounts(pending = 2, inProgress = 1, blocked = 1, completed = 4)
        assertEquals(4, counts.active)
        assertEquals("4 active", teamActiveHint(counts))
        assertEquals("Open team, 4 active", teamActionContentDescription(counts))
        assertNull(teamActiveHint(TeamCounts(0, 0, 0, 4)))
        assertEquals("Open team", teamActionContentDescription(TeamCounts(0, 0, 0, 4)))
        assertEquals("Open team", teamActionContentDescription(null))
        assertEquals(
            "Pending 2 · In progress 1 · Blocked 1 · Completed 4",
            teamCountsSummary(counts),
        )
    }

    @Test
    fun `tasks are ordered in progress blocked pending then completed`() {
        val ordered = orderedTeamTasks(tasks)
        assertEquals(listOf("t2", "t3", "t1"), ordered.map { it.id })
        assertEquals(TeamTaskStatus.InProgress, ordered[0].status)
        assertEquals(TeamTaskStatus.Blocked, ordered[1].status)
        assertEquals(TeamTaskStatus.Pending, ordered[2].status)
    }

    @Test
    fun `blocked-by label uses dependency ids only`() {
        assertEquals("blocked by t2, t1", teamTaskBlockedByLabel(tasks[2]))
        assertEquals("blocked by t1", teamTaskBlockedByLabel(tasks[1]))
        assertNull(teamTaskBlockedByLabel(tasks[0]))
        assertNull(teamTaskBlockedByLabel(TeamTask("t", "x", TeamTaskStatus.Blocked)))
    }

    @Test
    fun `A1 team cache respects the thirty second TTL`() {
        val cached = HostTeam.Empty
        val now = 1_800_000_000_000L
        assertFalse(shouldLoadSessionTeam(cached, false, false, fetchedAtMs = now - 1_000, nowMs = now))
        assertTrue(shouldLoadSessionTeam(cached, false, true, fetchedAtMs = now - 1_000, nowMs = now))
        assertTrue(
            shouldLoadSessionTeam(
                cached,
                false,
                false,
                fetchedAtMs = now - TEAM_CACHE_TTL_MS,
                nowMs = now,
            ),
        )
        assertFalse(shouldLoadSessionTeam(cached, true, true, fetchedAtMs = now, nowMs = now))
        assertTrue(shouldLoadSessionTeam(null, false, false, nowMs = now))
        assertEquals(30_000L, TEAM_CACHE_TTL_MS)
    }

    @Test
    fun `copy and models are distinct`() {
        assertEquals("Team", TEAM_ACTION_LABEL)
        assertEquals("Team", TEAM_SHEET_TITLE)
        assertEquals("Agents", TEAM_AGENTS_HEADING)
        assertEquals("Active tasks", TEAM_TASKS_HEADING)
        assertEquals("No active team tasks.", TEAM_EMPTY_COPY)
        assertEquals("This host does not expose team activity.", TEAM_UNSUPPORTED_COPY)
        assertEquals("Couldn't load team activity.", TEAM_LOAD_FAILED_COPY)
        assertEquals("Loading team activity…", TEAM_LOADING_COPY)
        assertFalse(HostTeam.Unsupported.supported)
        assertTrue(HostTeam.Empty.supported)
    }
}
