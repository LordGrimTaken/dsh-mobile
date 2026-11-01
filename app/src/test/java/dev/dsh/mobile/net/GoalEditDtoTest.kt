package dev.dsh.mobile.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM DTO tests for the goal edit/clear contract added alongside the host task:
 * `POST /sessions/{id}/goal/edit` and `POST /sessions/{id}/goal/clear`.
 */
class GoalEditDtoTest {

    @Test
    fun `edit request encodes objective and max rounds`() {
        val json = DshJson.encodeToString(
            GoalEditRequest.serializer(),
            GoalEditRequest(objective = "Ship the mobile goal controls", maxGoalRounds = 42),
        )
        assertTrue(json.contains("\"objective\":\"Ship the mobile goal controls\""))
        assertTrue(json.contains("\"maxGoalRounds\":42"))
    }

    @Test
    fun `edit request omits null fields so the host keeps them`() {
        val json = DshJson.encodeToString(GoalEditRequest.serializer(), GoalEditRequest())
        assertFalse("null objective must be omitted", json.contains("objective"))
        assertFalse("null rounds must be omitted", json.contains("maxGoalRounds"))
    }

    @Test
    fun `edit request supports changing only the objective`() {
        val json = DshJson.encodeToString(
            GoalEditRequest.serializer(),
            GoalEditRequest(objective = "New objective"),
        )
        assertTrue(json.contains("\"objective\":\"New objective\""))
        assertFalse(json.contains("maxGoalRounds"))
    }

    @Test
    fun `edit response decodes the updated goal`() {
        val json = """
            {"supported":true,"live":true,"goal":{
              "id":"goal-1","objective":"Ship the edit dialog","phase":"paused",
              "maxGoalRounds":64,"roundsStarted":12,"revision":9,
              "activation":"disarmed","blockedReason":null
            }}
        """.trimIndent()
        val response = DshJson.decodeFromString(SessionGoalResponse.serializer(), json)
        assertTrue(response.supported)
        assertTrue(response.live)
        val goal = response.goal
        assertNotNull(goal)
        assertEquals("goal-1", goal?.id)
        assertEquals("Ship the edit dialog", goal?.objective)
        assertEquals(64, goal?.maxGoalRounds)
        assertEquals(9, goal?.revision)
        assertEquals("paused", goal?.phase)
    }

    @Test
    fun `clear response decodes a null goal`() {
        val json = """{"supported":true,"live":true,"goal":null}"""
        val response = DshJson.decodeFromString(SessionGoalResponse.serializer(), json)
        assertTrue(response.supported)
        assertTrue(response.live)
        assertNull(response.goal)
    }

    @Test
    fun `clear response tolerates omitted goal and unknown fields`() {
        val json = """{"supported":true,"live":false,"futureField":1}"""
        val response = DshJson.decodeFromString(SessionGoalResponse.serializer(), json)
        assertTrue(response.supported)
        assertFalse(response.live)
        assertNull(response.goal)
    }

    @Test
    fun `edit request tolerates unknown fields and missing values on decode`() {
        val request = DshJson.decodeFromString(
            GoalEditRequest.serializer(),
            """{"objective":"Keep going","extra":"ignored"}""",
        )
        assertEquals("Keep going", request.objective)
        assertNull(request.maxGoalRounds)
    }
}
