package dev.dsh.mobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM tests for the goals-dashboard filtering/ordering helper used by AppState/GoalsScreen. */
class GoalDashboardTest {

    private fun session(
        id: String,
        title: String,
        goalPhase: String?,
        roundsStarted: Int = 1,
        maxRounds: Int = 120,
    ) = Session(
        id = id,
        title = title,
        workspaceId = "ws_1",
        preview = "preview",
        updated = "now",
        day = "Today",
        goalPhase = goalPhase,
        goalRoundsStarted = roundsStarted,
        goalMaxRounds = maxRounds,
    )

    @Test
    fun `filters out sessions without a goal`() {
        val sessions = listOf(
            session("s1", "has goal", "active"),
            session("s2", "no goal", null),
            session("s3", "blank goal", "   "),
            session("s4", "also has goal", "complete"),
        )
        val filtered = GoalDashboard.sessionsWithGoals(sessions)
        assertEquals(listOf("s1", "s4"), filtered.map { it.id })
    }

    @Test
    fun `orders paused and blocked before active before complete`() {
        val sessions = listOf(
            session("s1", "complete one", "complete"),
            session("s2", "active one", "active"),
            session("s3", "blocked one", "blocked"),
            session("s4", "paused one", "paused"),
        )
        val ordered = GoalDashboard.sessionsWithGoals(sessions)
        // paused/blocked share the first tier and are stable by title: "blocked one" < "paused one".
        assertEquals(listOf("s3", "s4", "s2", "s1"), ordered.map { it.id })
    }

    @Test
    fun `ties are stable by title and unknown phases sort last`() {
        val sessions = listOf(
            session("s1", "Zeta", "active"),
            session("s2", "Alpha", "active"),
            session("s3", "Unknown phase", "waiting"),
            session("s4", "Beta", "paused"),
        )
        val ordered = GoalDashboard.sessionsWithGoals(sessions)
        assertEquals(listOf("s4", "s2", "s1", "s3"), ordered.map { it.id })
    }

    @Test
    fun `phase normalization and ranking`() {
        assertEquals(0, GoalDashboard.phaseRank("paused"))
        assertEquals(0, GoalDashboard.phaseRank(" BLOCKED "))
        assertEquals(1, GoalDashboard.phaseRank("Active"))
        assertEquals(2, GoalDashboard.phaseRank("complete"))
        assertEquals(3, GoalDashboard.phaseRank(null))
        assertEquals(3, GoalDashboard.phaseRank("unknown"))
    }

    @Test
    fun `count matches the filtered list size`() {
        val sessions = listOf(
            session("s1", "a", "active"),
            session("s2", "b", null),
            session("s3", "c", "paused"),
        )
        assertEquals(2, GoalDashboard.count(sessions))
        assertTrue(GoalDashboard.sessionsWithGoals(emptyList()).isEmpty())
    }
}
