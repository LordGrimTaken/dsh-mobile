package dev.dsh.mobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure tests for the notification question-reply path: RemoteInput answer validation, app-lock /
 * read-only gating, stable notification/request ids and the success/failure copy shown after send.
 */
class QuestionReplyTest {

    @Test
    fun `blank answers are rejected`() {
        assertNull(QuestionReply.normalizeAnswer(null))
        assertNull(QuestionReply.normalizeAnswer(""))
        assertNull(QuestionReply.normalizeAnswer("   "))
        assertNull(QuestionReply.normalizeAnswer("\n\t "))
    }

    @Test
    fun `answers are trimmed and bounded by the shared question-answer cap`() {
        assertEquals("deploy it", QuestionReply.normalizeAnswer("  deploy it  "))
        assertEquals("line one\nline two", QuestionReply.normalizeAnswer("line one\nline two"))
        val max = "x".repeat(QuestionReply.MAX_ANSWER_CHARS)
        assertEquals(max, QuestionReply.normalizeAnswer(max))
        assertNull(
            QuestionReply.normalizeAnswer("x".repeat(QuestionReply.MAX_ANSWER_CHARS + 1)),
        )
        assertEquals(2_000, QuestionReply.MAX_ANSWER_CHARS)
        assertEquals(MAX_QUESTION_ANSWER_CHARS, QuestionReply.MAX_ANSWER_CHARS)
    }

    @Test
    fun `inline reply is gated by app lock and read-only hosts`() {
        assertTrue(QuestionReply.canOfferInlineReply(appLockEnabled = false, questionsReadOnly = false))
        assertFalse("app lock must keep notification replies out", QuestionReply.canOfferInlineReply(appLockEnabled = true, questionsReadOnly = false))
        assertFalse("read-only hosts answer on the desktop", QuestionReply.canOfferInlineReply(appLockEnabled = false, questionsReadOnly = true))
        assertFalse(QuestionReply.canOfferInlineReply(appLockEnabled = true, questionsReadOnly = true))
    }

    @Test
    fun `reply broadcast requires the private action and usable extras`() {
        assertTrue(QuestionReply.acceptsIntent(QuestionReply.ACTION_REPLY))
        assertFalse(QuestionReply.acceptsIntent(null))
        assertFalse(QuestionReply.acceptsIntent("android.intent.action.VIEW"))
        assertFalse(QuestionReply.acceptsIntent("dev.dsh.mobile.action.OTHER"))
        assertTrue(QuestionReply.ACTION_REPLY.startsWith("dev.dsh.mobile.action."))

        assertTrue(QuestionReply.hasUsableReply("q-1", 42, " answer "))
        assertFalse("blank question id", QuestionReply.hasUsableReply(" ", 42, "answer"))
        assertFalse("missing notification id", QuestionReply.hasUsableReply("q-1", 0, "answer"))
        assertFalse("blank answer", QuestionReply.hasUsableReply("q-1", 42, "   "))
        assertFalse("overlong answer", QuestionReply.hasUsableReply("q-1", 42, "x".repeat(2_001)))
    }

    @Test
    fun `send timeout fits inside the goAsync budget and retry respects app lock`() {
        assertTrue(QuestionReply.SEND_TIMEOUT_MS in 1L until 10_000L)
        assertTrue(QuestionReply.SEND_TIMEOUT_MS <= 9_000L)
        assertTrue("retry offered when app lock is off", QuestionReply.retryActionAvailable(appLockEnabled = false))
        assertFalse("no retry once app lock is on", QuestionReply.retryActionAvailable(appLockEnabled = true))
    }

    @Test
    fun `notification ids and request codes are stable and question specific`() {
        val id = QuestionReply.notificationIdFor("session-1", "question-1")
        assertEquals(id, QuestionReply.notificationIdFor("session-1", "question-1"))
        assertNotEquals(id, QuestionReply.notificationIdFor("session-1", "question-2"))
        assertNotEquals(id, QuestionReply.notificationIdFor("session-2", "question-1"))

        val code = QuestionReply.requestCodeFor(id, "question-1")
        assertEquals(code, QuestionReply.requestCodeFor(id, "question-1"))
        assertNotEquals(code, QuestionReply.requestCodeFor(id, "question-2"))
    }

    @Test
    fun `result key and extras are non blank and stable`() {
        assertEquals(QuestionReply.RESULT_KEY, QuestionReply.RESULT_KEY)
        assertTrue(QuestionReply.RESULT_KEY.contains("QUESTION_REPLY"))
        assertEquals("question_id", QuestionReply.EXTRA_QUESTION_ID)
        assertEquals("session_id", QuestionReply.EXTRA_SESSION_ID)
        assertEquals("notification_id", QuestionReply.EXTRA_NOTIFICATION_ID)
        assertEquals("Reply", QuestionReply.REPLY_ACTION_LABEL)
    }

    @Test
    fun `outcome copy never claims success for failures`() {
        val sent = QuestionReply.copyFor(QuestionReply.Outcome.SENT)
        assertEquals("Answer sent", sent.first)
        assertTrue(sent.second.contains("sent"))

        val already = QuestionReply.copyFor(QuestionReply.Outcome.ALREADY_ANSWERED)
        assertEquals("Already answered", already.first)

        val failed = QuestionReply.copyFor(QuestionReply.Outcome.FAILED)
        assertEquals("Couldn't send answer", failed.first)
        assertTrue(failed.second.contains("open the app"))
        assertFalse(failed.second.contains("sent.", ignoreCase = false) && failed.first == "Answer sent")
    }
}
