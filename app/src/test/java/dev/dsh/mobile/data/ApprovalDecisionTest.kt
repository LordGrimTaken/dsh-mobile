package dev.dsh.mobile.data

import dev.dsh.mobile.net.DshApiException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the approval-decision failure mapping used by the first-answer-wins UX
 * (404/409 => "Already answered", 501/read-only => read-only copy, otherwise generic error).
 */
class ApprovalDecisionTest {

    @Test
    fun `not found means the approval was already answered`() {
        val error = DshApiException.NotFound("approval no longer exists")
        assertTrue(isAlreadyAnsweredApprovalError(error))
        assertEquals(
            ALREADY_ANSWERED_APPROVAL_NOTICE,
            approvalDecisionFailureNotice(error, readOnly = false),
        )
    }

    @Test
    fun `conflict status and already-answered codes mean already answered`() {
        assertTrue(isAlreadyAnsweredApprovalError(DshApiException.Server(409, "conflict", "boom")))
        assertTrue(isAlreadyAnsweredApprovalError(DshApiException.Server(404, null, "boom")))
        assertTrue(
            isAlreadyAnsweredApprovalError(
                DshApiException.Server(400, "already_answered", "boom"),
            ),
        )
        assertTrue(
            isAlreadyAnsweredApprovalError(
                DshApiException.InvalidRequest("question gone", "approval_resolved"),
            ),
        )
        assertFalse(isAlreadyAnsweredApprovalError(DshApiException.Server(500, null, "boom")))
        assertFalse(isAlreadyAnsweredApprovalError(DshApiException.Network("offline")))
    }

    @Test
    fun `unsupported host shows the read-only copy instead of a raw error`() {
        assertEquals(
            READ_ONLY_APPROVAL_NOTICE,
            approvalDecisionFailureNotice(DshApiException.Unsupported("501"), readOnly = false),
        )
    }

    @Test
    fun `read-only host shows the read-only copy for any failure`() {
        assertEquals(
            READ_ONLY_APPROVAL_NOTICE,
            approvalDecisionFailureNotice(DshApiException.Server(500, null, "boom"), readOnly = true),
        )
        assertEquals(
            READ_ONLY_APPROVAL_NOTICE,
            approvalDecisionFailureNotice(DshApiException.NotFound("gone"), readOnly = true),
        )
    }

    @Test
    fun `unrelated failures keep the generic message`() {
        assertNull(
            approvalDecisionFailureNotice(DshApiException.Network("offline"), readOnly = false),
        )
        assertNull(
            approvalDecisionFailureNotice(DshApiException.RateLimited("slow"), readOnly = false),
        )
        assertTrue(ALREADY_ANSWERED_APPROVAL_NOTICE.isNotBlank())
        assertTrue(READ_ONLY_APPROVAL_NOTICE.isNotBlank())
    }
}
