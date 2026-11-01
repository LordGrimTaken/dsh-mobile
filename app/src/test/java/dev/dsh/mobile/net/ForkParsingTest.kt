package dev.dsh.mobile.net

import dev.dsh.mobile.data.ForkResult
import dev.dsh.mobile.data.forkAtSeqOrNull
import dev.dsh.mobile.data.forkResultStatus
import dev.dsh.mobile.data.forkSeqValid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the fork contract: optional message seq decode, fork response decode, request shape
 * (latest boundary vs atSeq) and the seq/status helpers used by the confirmations.
 */
class ForkParsingTest {

    @Test
    fun `message seq is optional on decode`() {
        val withSeq = DshJson.decodeFromString(
            MessageDto.serializer(),
            """{"id":"m1","role":"assistant","text":"hi","seq":12}""",
        )
        assertEquals(12, withSeq.seq)

        val withoutSeq = DshJson.decodeFromString(
            MessageDto.serializer(),
            """{"id":"m1","role":"assistant","text":"hi"}""",
        )
        assertNull(withoutSeq.seq)
    }

    @Test
    fun `fork response decodes latest and at seq forks`() {
        val latest = DshJson.decodeFromString(
            ForkResponse.serializer(),
            """{"sessionId":"new_1","forkedFrom":"old_1"}""",
        )
        assertEquals("new_1", latest.sessionId)
        assertEquals("old_1", latest.forkedFrom)
        assertNull(latest.atSeq)

        val atSeq = DshJson.decodeFromString(
            ForkResponse.serializer(),
            """{"sessionId":"new_2","forkedFrom":"old_1","atSeq":7}""",
        )
        assertEquals(7, atSeq.atSeq)
    }

    @Test
    fun `fork request omits a latest-boundary atSeq`() {
        val latest = DshJson.encodeToString(
            ForkRequest.serializer(),
            ForkRequest(atSeq = null),
        )
        assertEquals("{}", latest)

        val atSeq = DshJson.encodeToString(
            ForkRequest.serializer(),
            ForkRequest(atSeq = 7),
        )
        assertEquals("""{"atSeq":7}""", atSeq)
    }

    @Test
    fun `fork seq validation requires a positive boundary`() {
        assertTrue(forkSeqValid(1))
        assertTrue(forkSeqValid(42))
        assertFalse(forkSeqValid(null))
        assertFalse(forkSeqValid(0))
        assertFalse(forkSeqValid(-3))
    }

    @Test
    fun `invalid seq normalizes to the latest boundary`() {
        assertNull(forkAtSeqOrNull(null))
        assertNull(forkAtSeqOrNull(0))
        assertNull(forkAtSeqOrNull(-1))
        assertEquals(5, forkAtSeqOrNull(5))
    }

    @Test
    fun `fork status distinguishes listed and unlisted sessions`() {
        val result = ForkResult(sessionId = "new_1", forkedFrom = "old_1", atSeq = 3)
        assertEquals("Forked into a new session.", forkResultStatus(result, listed = true))
        val unlisted = forkResultStatus(result, listed = false)
        assertTrue(unlisted.contains("new_1"))
        assertTrue(unlisted.contains("not in the list yet"))
    }
}
