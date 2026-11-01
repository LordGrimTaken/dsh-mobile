package dev.dsh.mobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** URL normalisation used by onboarding + add-host so pasted URLs behave predictably. */
class HostUrlTest {

    private fun root(raw: String): String {
        val result = HostUrl.parse(raw)
        assertTrue("expected valid: '$raw' -> $result", result is HostUrlResult.Valid)
        return (result as HostUrlResult.Valid).root
    }

    private fun error(raw: String): String {
        val result = HostUrl.parse(raw)
        assertTrue("expected invalid: '$raw' -> $result", result is HostUrlResult.Invalid)
        return (result as HostUrlResult.Invalid).message
    }

    @Test
    fun `accepts https and http host roots`() {
        assertEquals("https://dsh.example.com", root("https://dsh.example.com"))
        assertEquals("http://10.0.2.2:3543", root("http://10.0.2.2:3543"))
        assertEquals("http://localhost:3543", root("http://localhost:3543"))
        assertEquals("http://[::1]:3543", root("http://[::1]:3543"))
    }

    @Test
    fun `trims whitespace and trailing slashes`() {
        assertEquals("https://dsh.example.com", root("  https://dsh.example.com/  "))
        assertEquals("https://dsh.example.com", root("https://dsh.example.com///"))
    }

    @Test
    fun `tolerates a pasted mobile api suffix`() {
        assertEquals("https://dsh.example.com", root("https://dsh.example.com/mobile/v1"))
        assertEquals("https://dsh.example.com", root("https://dsh.example.com/mobile/v1/"))
    }

    @Test
    fun `normalises the scheme case`() {
        assertEquals("https://dsh.example.com", root("HTTPS://dsh.example.com"))
    }

    @Test
    fun `rejects missing or unsupported schemes`() {
        assertTrue(error("dsh.example.com").contains("https://"))
        assertTrue(error("ftp://dsh.example.com").contains("https://"))
        assertTrue(error("//dsh.example.com").contains("https://"))
    }

    @Test
    fun `rejects blank and malformed hosts`() {
        assertTrue(error("").isNotBlank())
        assertTrue(error("   ").isNotBlank())
        assertTrue(error("https://").contains("host name"))
        assertTrue(error("https://host name").contains("spaces"))
        assertTrue(error("https://user@host.example.com").contains("username"))
    }

    @Test
    fun `rejects deeper paths gracefully`() {
        val message = error("https://dsh.example.com/some/path")
        assertTrue(message.contains("root URL"))
        assertTrue(error("https://dsh.example.com?token=x").contains("root URL"))
        assertTrue(error("https://dsh.example.com/mobile/v2").contains("root URL"))
    }

    @Test
    fun `rootOrNull is null for invalid input`() {
        assertNull(HostUrl.rootOrNull("nope"))
        assertEquals("https://host.example.com", HostUrl.rootOrNull("https://host.example.com/"))
    }

    @Test
    fun `displayHost strips scheme and path`() {
        assertEquals("dsh.example.com", HostUrl.displayHost("https://dsh.example.com"))
        assertEquals("host:3543", HostUrl.displayHost("http://host:3543/mobile/v1"))
    }
}
