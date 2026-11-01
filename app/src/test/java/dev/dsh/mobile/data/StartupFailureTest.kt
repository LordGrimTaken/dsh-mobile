package dev.dsh.mobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StartupFailureTest {

    @Test
    fun `summary uses exception type and one sanitized message line`() {
        val summary = startupFailureSummary(
            NullPointerException("Attempt to invoke\nMutableState.setValue\t on a null object reference"),
        )
        assertEquals(
            "NullPointerException: Attempt to invoke MutableState.setValue on a null object reference",
            summary,
        )
    }

    @Test
    fun `summary omits blank and overly long messages`() {
        assertEquals("IllegalStateException", startupFailureSummary(IllegalStateException("   ")))
        val long = startupFailureSummary(IllegalStateException("x".repeat(400)))
        assertTrue(long.startsWith("IllegalStateException: "))
        assertEquals("IllegalStateException: ".length + 180, long.length)
    }

    @Test
    fun `summary never includes a stack trace or newline`() {
        val summary = startupFailureSummary(RuntimeException("boom\nat A.b(C.kt:1)"))
        assertEquals("RuntimeException: boom at A.b(C.kt:1)", summary)
        assertEquals("Unknown error", startupFailureSummary(null))
    }
}
