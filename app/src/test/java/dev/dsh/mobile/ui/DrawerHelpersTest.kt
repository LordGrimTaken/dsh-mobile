package dev.dsh.mobile.ui

import dev.dsh.mobile.data.SessionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure helpers behind the terminal cockpit drawer (copy, status chip, session meta, badges). */
class DrawerHelpersTest {

    private fun session(
        id: String = "s1",
        title: String = "Fix parser",
        workspace: String = "dsh-mobile",
        status: SessionStatus = SessionStatus.Working,
        unread: Boolean = false,
        updated: String = "2m",
        current: Boolean = false,
    ) = ShellDrawerSession(id, title, workspace, status, unread, updated, current)

    @Test
    fun `status chip normalizes the shell connection line`() {
        fun status(vararg items: String) = ShellStatus(left = "❯ dsh", items = items.toList())
        assertEquals("[ ● live ]", drawerStatusChipText(status("● Live", "3 sessions")))
        assertEquals("[ ● offline ]", drawerStatusChipText(status("offline · retrying")))
        assertEquals("[ ● connecting ]", drawerStatusChipText(status("connecting…")))
        assertEquals("[ ● demo ]", drawerStatusChipText(status("demo · not paired")))
        assertEquals("[ ● idle ]", drawerStatusChipText(status()))
        assertEquals("[ ● idle ]", drawerStatusChipText(ShellStatus("❯ dsh", emptyList())))
    }

    @Test
    fun `header meta prefers subtitle and falls back to status items`() {
        val status = ShellStatus("❯ dsh", listOf("live", "3 sessions"), listOf("v1"))
        assertEquals("workspace · preset", drawerHeaderMeta("workspace · preset", status))
        assertEquals("live · 3 sessions", drawerHeaderMeta(null, status))
        assertEquals("live · 3 sessions", drawerHeaderMeta("   ", status))
        assertEquals("❯ dsh", drawerHeaderMeta(null, ShellStatus("❯ dsh", emptyList())))
    }

    @Test
    fun `footer text prefers version items and falls back`() {
        val status = ShellStatus("❯ dsh", listOf("live"), listOf("v0.2.142", "host v0.3.12"))
        assertEquals("v0.2.142 · host v0.3.12", drawerFooterText(status, "sub"))
        assertEquals("sub", drawerFooterText(ShellStatus("❯ dsh", emptyList()), "sub"))
        assertEquals("live", drawerFooterText(ShellStatus("❯ dsh", listOf("live")), null))
    }

    @Test
    fun `status labels are explicit`() {
        assertEquals("working", drawerStatusLabel(SessionStatus.Working))
        assertEquals("needs input", drawerStatusLabel(SessionStatus.NeedsInput))
        assertEquals("idle", drawerStatusLabel(SessionStatus.Idle))
    }

    @Test
    fun `session meta is one line and skips blanks`() {
        assertEquals(
            "dsh-mobile · working · 2m",
            drawerSessionMeta("dsh-mobile", SessionStatus.Working, "2m"),
        )
        assertEquals(
            "dsh-mobile · idle",
            drawerSessionMeta("dsh-mobile", SessionStatus.Idle, "  "),
        )
        assertEquals("needs input · now", drawerSessionMeta("  ", SessionStatus.NeedsInput, "now"))
    }

    @Test
    fun `badge formatting is hidden at zero and capped at 99 plus`() {
        assertNull(drawerBadgeLabel(0))
        assertNull(drawerBadgeLabel(-3))
        assertEquals("1", drawerBadgeLabel(1))
        assertEquals("99", drawerBadgeLabel(99))
        assertEquals("99+", drawerBadgeLabel(100))
        assertEquals("99+", drawerBadgeLabel(1000))
    }

    @Test
    fun `session headings and overflow counts`() {
        assertEquals("[ navigate ]", DRAWER_NAV_HEADING)
        assertEquals("[ sessions ]", DRAWER_SESSIONS_HEADING)
        assertEquals("[ sessions ]", drawerSessionsHeading(0))
        assertEquals("[ sessions ] · 12", drawerSessionsHeading(12))
        assertNull(drawerSessionsOverflow(DRAWER_MAX_SESSION_ROWS))
        assertEquals("+2", drawerSessionsOverflow(DRAWER_MAX_SESSION_ROWS + 2))
        assertEquals(8, DRAWER_MAX_SESSION_ROWS)
    }

    @Test
    fun `nav order and footer labels are stable`() {
        assertEquals(
            listOf(
                "Goals",
                "Schedules",
                "Archived sessions",
                "Search all sessions",
                "Inbox",
                "Usage",
            ),
            DRAWER_NAV_LABELS,
        )
        assertEquals("New session", DRAWER_NEW_SESSION_LABEL)
        assertEquals("Search", DRAWER_SEARCH_LABEL)
        assertEquals("Refresh", DRAWER_REFRESH_LABEL)
        assertEquals("Settings", DRAWER_SETTINGS_LABEL)
        assertEquals("About", DRAWER_ABOUT_LABEL)
        assertEquals("view all", DRAWER_VIEW_ALL_LABEL)
        assertTrue(DRAWER_NO_SESSIONS_LABEL.isNotBlank())
    }

    @Test
    fun `session a11y label covers title workspace status unread and current`() {
        assertEquals(
            "Fix parser, dsh-mobile, working, unread, current",
            drawerSessionA11y(session(unread = true, current = true)),
        )
        assertEquals(
            "Fix parser, dsh-mobile, idle",
            drawerSessionA11y(session(status = SessionStatus.Idle)),
        )
        assertEquals(
            "Untitled session, no workspace, needs input",
            drawerSessionA11y(session(title = "  ", workspace = " ", status = SessionStatus.NeedsInput)),
        )
    }
}
