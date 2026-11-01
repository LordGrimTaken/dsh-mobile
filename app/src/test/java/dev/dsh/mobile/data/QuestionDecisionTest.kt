package dev.dsh.mobile.data

import dev.dsh.mobile.net.DshApiException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the question-answer failure mapping and the lost-race local filter.
 * `POST /questions/{id}` can fail because the row was already answered on the desktop (404/409)
 * or because the host exposes questions read-only (501).
 */
class QuestionDecisionTest {

    private fun question(id: String) = Question(
        id = id,
        sessionId = "session-1",
        prompt = "Which approach?",
        options = listOf("A", "B"),
        createdAt = "2026-10-31T06:00:00Z",
    )

    @Test
    fun `not found means the question was already answered`() {
        val error = DshApiException.NotFound("question gone")
        assertTrue(isAlreadyAnsweredApprovalError(error))
        assertEquals(
            ALREADY_ANSWERED_QUESTION_NOTICE,
            questionAnswerFailureNotice(error, readOnly = false),
        )
        assertEquals("Already answered on the desktop.", ALREADY_ANSWERED_QUESTION_NOTICE)
    }

    @Test
    fun `conflict status and expired codes map to already answered`() {
        assertEquals(
            ALREADY_ANSWERED_QUESTION_NOTICE,
            questionAnswerFailureNotice(DshApiException.Server(409, null, "boom"), readOnly = false),
        )
        assertEquals(
            ALREADY_ANSWERED_QUESTION_NOTICE,
            questionAnswerFailureNotice(
                DshApiException.InvalidRequest("expired", "already_answered"),
                readOnly = false,
            ),
        )
        assertEquals(
            ALREADY_ANSWERED_QUESTION_NOTICE,
            questionAnswerFailureNotice(
                DshApiException.Server(400, "already_resolved", "boom"),
                readOnly = false,
            ),
        )
    }

    @Test
    fun `unsupported host shows the read-only question copy`() {
        assertTrue(READ_ONLY_QUESTION_NOTICE.contains("Questions"))
        assertEquals(
            READ_ONLY_QUESTION_NOTICE,
            questionAnswerFailureNotice(DshApiException.Unsupported("501"), readOnly = false),
        )
        assertEquals(
            READ_ONLY_QUESTION_NOTICE,
            questionAnswerFailureNotice(DshApiException.Network("offline"), readOnly = true),
        )
    }

    @Test
    fun `unrelated failures keep the generic message`() {
        assertNull(questionAnswerFailureNotice(DshApiException.Network("offline"), readOnly = false))
        assertNull(
            questionAnswerFailureNotice(DshApiException.Server(500, null, "boom"), readOnly = false),
        )
    }

    @Test
    fun `filtering only hides the raced row and keeps the rest of the batch`() {
        val questions = listOf(question("q_1"), question("q_2"), question("q_3"))
        val remaining = questionsAfterLocalDismissals(questions, setOf("q_2"))
        assertEquals(listOf("q_1", "q_3"), remaining.map { it.id })
        assertEquals(3, questionsAfterLocalDismissals(questions, emptySet()).size)
        assertTrue(questionsAfterLocalDismissals(questions, setOf("q_1", "q_2", "q_3")).isEmpty())
    }
}
