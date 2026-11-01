package dev.dsh.mobile.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Safety net for the v0.2.3 filtered session API: an older host may still return subagent/internal
 * sessions whose ids are bare UUIDs; the client must never show them. Normal ids (including
 * `session-*`) must stay visible.
 */
class SessionVisibilityTest {

    @Test
    fun `hides bare uuid internal sessions`() {
        assertFalse(SessionVisibility.isUserSession("0f8fad5b-d9cb-469f-a165-70867728950e"))
        assertFalse(SessionVisibility.isUserSession("0F8FAD5B-D9CB-469F-A165-70867728950E"))
        assertFalse(SessionVisibility.isUserSession("  0f8fad5b-d9cb-469f-a165-70867728950e  "))
    }

    @Test
    fun `keeps user sessions with the session prefix`() {
        assertTrue(SessionVisibility.isUserSession("session-0f8fad5b-d9cb-469f-a165-70867728950e"))
        assertTrue(SessionVisibility.isUserSession("session-abc"))
        assertTrue(SessionVisibility.isUserSession("session-"))
    }

    @Test
    fun `keeps ordinary non-uuid ids`() {
        listOf("s_release", "s-1", "abc", "sessionless", "s_01JABC", "ws_1", "6988a3d2").forEach { id ->
            assertTrue("id '$id' should stay visible", SessionVisibility.isUserSession(id))
        }
    }

    @Test
    fun `hides blank ids`() {
        assertFalse(SessionVisibility.isUserSession(""))
        assertFalse(SessionVisibility.isUserSession("   "))
    }

    @Test
    fun `only the exact uuid shape is hidden`() {
        // Near misses are ordinary ids, not internal session UUIDs.
        assertTrue(SessionVisibility.isUserSession("0f8fad5bd9cb469fa16570867728950e"))
        assertTrue(SessionVisibility.isUserSession("0f8fad5b-d9cb-469f-a165-70867728950"))
        assertTrue(SessionVisibility.isUserSession("0f8fad5b-d9cb-469f-a165-70867728950e-extra"))
        assertTrue(SessionVisibility.isUserSession("uuid-0f8fad5b-d9cb-469f-a165-70867728950e"))
    }
}
