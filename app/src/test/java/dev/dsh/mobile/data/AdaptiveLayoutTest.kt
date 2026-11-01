package dev.dsh.mobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Adaptive layout decision + rail copy/a11y helpers (no Android dependencies). */
class AdaptiveLayoutTest {

    @Test
    fun `two-pane only at 960dp and above with a session and no overlay`() {
        assertFalse(usesTwoPaneLayout(widthDp = 600f, hasSession = true, hasOverlay = false))
        // R12: landscape phones below 960dp stay single-pane (840dp would leave ~532dp for chat).
        assertFalse(usesTwoPaneLayout(widthDp = 840f, hasSession = true, hasOverlay = false))
        assertFalse(usesTwoPaneLayout(widthDp = 959.9f, hasSession = true, hasOverlay = false))
        assertTrue(usesTwoPaneLayout(widthDp = 960f, hasSession = true, hasOverlay = false))
        assertTrue(usesTwoPaneLayout(widthDp = 1280f, hasSession = true, hasOverlay = false))
        // No session open -> compact list even on a wide window.
        assertFalse(usesTwoPaneLayout(widthDp = 1280f, hasSession = false, hasOverlay = false))
        // Any full-screen overlay stays single-pane (settings/pairing/inbox/... all pass true).
        assertFalse(usesTwoPaneLayout(widthDp = 1280f, hasSession = true, hasOverlay = true))
        assertFalse(usesTwoPaneLayout(widthDp = 600f, hasSession = true, hasOverlay = true))
        // Compact phones are untouched at every combination.
        listOf(false, true).forEach { hasSession ->
            listOf(false, true).forEach { hasOverlay ->
                assertFalse(usesTwoPaneLayout(320f, hasSession, hasOverlay))
            }
        }
        assertEquals(960f, TWO_PANE_MIN_WIDTH_DP)
        assertEquals(308f, SESSION_RAIL_WIDTH_DP)
    }

    @Test
    fun `rail connection summary is compact and honest`() {
        assertEquals(
            "live · Studio Mac",
            sessionRailConnectionLabel(ConnectionStatus.Live, "Studio Mac"),
        )
        assertEquals(
            "live · desktop",
            sessionRailConnectionLabel(ConnectionStatus.Live, null),
        )
        assertEquals(
            "live · desktop",
            sessionRailConnectionLabel(ConnectionStatus.Live, "   "),
        )
        assertEquals("offline", sessionRailConnectionLabel(ConnectionStatus.Offline, "host"))
        assertEquals("connecting…", sessionRailConnectionLabel(ConnectionStatus.Connecting, "host"))
        assertEquals("demo · not paired", sessionRailConnectionLabel(ConnectionStatus.Demo, "host"))
    }

    @Test
    fun `rail status labels are explicit`() {
        assertEquals("working", sessionRailStatusLabel(SessionStatus.Working))
        assertEquals("needs input", sessionRailStatusLabel(SessionStatus.NeedsInput))
        assertEquals("idle", sessionRailStatusLabel(SessionStatus.Idle))
    }

    @Test
    fun `rail row content description covers title workspace status unread and selection`() {
        assertEquals(
            "Fix parser, dsh-mobile, working, unread, current",
            sessionRailItemContentDescription(
                title = "Fix parser",
                workspaceName = "dsh-mobile",
                status = SessionStatus.Working,
                unread = true,
                selected = true,
            ),
        )
        assertEquals(
            "Fix parser, dsh-mobile, idle",
            sessionRailItemContentDescription(
                title = "Fix parser",
                workspaceName = "dsh-mobile",
                status = SessionStatus.Idle,
                unread = false,
                selected = false,
            ),
        )
        // Blank workspace/title are handled without leaks or empty commas.
        assertEquals(
            "Untitled session, needs input",
            sessionRailItemContentDescription(
                title = "   ",
                workspaceName = null,
                status = SessionStatus.NeedsInput,
                unread = false,
                selected = false,
            ),
        )
        assertEquals(
            "Title, idle, unread",
            sessionRailItemContentDescription(
                title = "Title",
                workspaceName = "  ",
                status = SessionStatus.Idle,
                unread = true,
                selected = false,
            ),
        )
    }
}
