package dev.dsh.mobile.ui

import dev.dsh.mobile.data.SessionSort
import org.junit.Assert.assertEquals
import org.junit.Test

/** Pure helpers behind the terminal-style compact home/chat simplification pass. */
class ShellSimplificationTest {

    @Test
    fun `compact status stays one joinable line and skips blanks`() {
        val status = ShellStatus(
            left = "❯ dsh",
            items = listOf("live", "  ", "3 sessions"),
            right = listOf("v1.2.3"),
        )
        assertEquals("❯ dsh · live · 3 sessions · v1.2.3", shellCompactStatusLine(status))
        assertEquals("", shellCompactStatusLine(ShellStatus(left = " ", items = emptyList(), right = emptyList())))
        assertEquals("❯ dsh", shellCompactStatusLine(ShellStatus("❯ dsh", emptyList(), emptyList())))
    }

    @Test
    fun `terminal home prompt uses host or local fallback`() {
        assertEquals("dsh@Studio Mac", homeTerminalPrompt("Studio Mac"))
        assertEquals("dsh@local", homeTerminalPrompt(null))
        assertEquals("dsh@local", homeTerminalPrompt("   "))
    }

    @Test
    fun `active filter count excludes defaults`() {
        assertEquals(0, homeActiveFilterCount(null, null, SessionSort.ACTIVITY))
        assertEquals(1, homeActiveFilterCount("w1", null, SessionSort.ACTIVITY))
        assertEquals(1, homeActiveFilterCount(null, "working", SessionSort.ACTIVITY))
        assertEquals(1, homeActiveFilterCount(null, null, SessionSort.TITLE))
        assertEquals(
            3,
            homeActiveFilterCount("w1", "unread", SessionSort.STATUS),
        )
        assertEquals(2, homeActiveFilterCount("w1", "idle", SessionSort.ACTIVITY))
    }

    @Test
    fun `filter action label shows count only when active`() {
        assertEquals("Filters", homeFilterActionLabel(0))
        assertEquals("Filters", homeFilterActionLabel(-1))
        assertEquals("Filters · 1", homeFilterActionLabel(1))
        assertEquals("Filters · 3", homeFilterActionLabel(3))
    }

    @Test
    fun `filter summary is a compact one-liner`() {
        assertEquals(
            "All workspaces · All statuses · Activity",
            homeFilterSummary(null, null, SessionSort.ACTIVITY),
        )
        assertEquals(
            "dsh-mobile · Working · Title",
            homeFilterSummary("dsh-mobile", "working", SessionSort.TITLE),
        )
        assertEquals(
            "All workspaces · Unread · Status",
            homeFilterSummary("  ", "unread", SessionSort.STATUS),
        )
        assertEquals(
            "All workspaces · All statuses · Activity",
            homeFilterSummary(null, "mystery", SessionSort.ACTIVITY),
        )
    }

    @Test
    fun `HOME more menu restores every secondary destination`() {
        assertEquals("More", HOME_MORE_CONTENT_DESCRIPTION)
        assertEquals(
            listOf(
                "Goals",
                "Schedules",
                "Archived sessions",
                "Search all sessions",
                "Inbox",
                "Usage",
            ),
            HOME_MORE_MENU_ITEMS,
        )
        assertEquals(6, HOME_MORE_MENU_ITEMS.size)
    }

    @Test
    fun `chat overflow restores session info and tool activity`() {
        assertEquals(
            listOf(
                "Skills",
                "Plan",
                "Prompt templates",
                "Session info",
                "Tool activity",
            ),
            CHAT_MORE_MENU_ITEMS,
        )
        assertEquals("Session info", CHAT_OVERFLOW_SESSION_INFO_LABEL)
        assertEquals("Tool activity", CHAT_OVERFLOW_TOOL_ACTIVITY_LABEL)
        assertEquals("More actions", CHAT_MORE_CONTENT_DESCRIPTION)
    }

    @Test
    fun `simplification copy is stable`() {
        assertEquals("Filters", HOME_FILTERS_LABEL)
        assertEquals("Reset", HOME_FILTERS_RESET_LABEL)
        assertEquals("Done", HOME_FILTERS_DONE_LABEL)
        assertEquals("New session", HOME_NEW_SESSION_LABEL)
        assertEquals("More actions", CHAT_MORE_CONTENT_DESCRIPTION)
        assertEquals("Skills", CHAT_OVERFLOW_SKILLS_LABEL)
        assertEquals("Plan", CHAT_OVERFLOW_PLAN_LABEL)
        assertEquals("Prompt templates", CHAT_OVERFLOW_TEMPLATES_LABEL)
    }
}
