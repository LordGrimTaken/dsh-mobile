package dev.dsh.mobile.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM DTO tests for the read-only questions envelope added by the host task:
 * `GET /questions -> {questions:[...], supported:true, readOnly:true}`.
 */
class QuestionsReadOnlyDtoTest {

    @Test
    fun `decodes a read-only questions response`() {
        val json = """
            {
              "questions": [{
                "id": "q_1", "sessionId": "session-1",
                "prompt": "Which approach should I take?",
                "options": ["Keep the stream alive", "Restart cleanly"],
                "createdAt": "2026-10-31T06:00:00Z"
              }],
              "supported": true,
              "readOnly": true
            }
        """.trimIndent()
        val response = DshJson.decodeFromString(QuestionsResponse.serializer(), json)
        assertEquals(1, response.questions.size)
        assertEquals("q_1", response.questions[0].id)
        assertEquals("session-1", response.questions[0].sessionId)
        assertEquals(listOf("Keep the stream alive", "Restart cleanly"), response.questions[0].options)
        assertTrue(response.supported)
        assertTrue(response.readOnly)
    }

    @Test
    fun `absent flags default to supported and writable`() {
        val response = DshJson.decodeFromString(
            QuestionsResponse.serializer(),
            """{"questions":[]}""",
        )
        assertTrue("older hosts default to supported", response.supported)
        assertFalse("older hosts default to writable", response.readOnly)
        assertTrue(response.questions.isEmpty())
    }

    @Test
    fun `explicit false flags decode`() {
        val response = DshJson.decodeFromString(
            QuestionsResponse.serializer(),
            """{"questions":[],"supported":true,"readOnly":false}""",
        )
        assertTrue(response.supported)
        assertFalse(response.readOnly)
    }

    @Test
    fun `read-only response tolerates unknown fields and missing questions`() {
        val response = DshJson.decodeFromString(
            QuestionsResponse.serializer(),
            """{"supported":true,"readOnly":true,"futureField":42}""",
        )
        assertTrue(response.readOnly)
        assertTrue(response.questions.isEmpty())
    }
}
