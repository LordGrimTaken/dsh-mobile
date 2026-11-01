package dev.dsh.mobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Client-side mirror of the host's 8-attachments-per-prompt cap. */
class AttachmentCapTest {

    @Test
    fun `cap allows up to eight pending attachments`() {
        assertEquals(8, AttachmentLimits.MAX_PENDING_ATTACHMENTS)
        for (count in 0 until AttachmentLimits.MAX_PENDING_ATTACHMENTS) {
            assertTrue(
                "pending count $count should still allow another attachment",
                AttachmentLimits.canAddPendingAttachment(count),
            )
        }
    }

    @Test
    fun `cap blocks the ninth attachment`() {
        assertFalse(AttachmentLimits.canAddPendingAttachment(8))
        assertFalse(AttachmentLimits.canAddPendingAttachment(9))
        assertFalse(AttachmentLimits.canAddPendingAttachment(64))
    }

    @Test
    fun `cap rejects nonsensical counts`() {
        assertFalse(AttachmentLimits.canAddPendingAttachment(-1))
    }
}
