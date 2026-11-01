package dev.dsh.mobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure tests for crash-loop counting, the Safe mode reset allowlist, and the shortcut/deep-link
 * constants. Android storage wiring lives behind simple wrappers and is not exercised here.
 */
class StartupGuardTest {

    private val now = 1_800_000_000_000L

    @Test
    fun `recordCrash drops stale entries and appends the new crash`() {
        val stale = now - StartupGuard.CRASH_WINDOW_MS - 1_000L
        val fresh = now - 60_000L
        val recorded = StartupGuard.recordCrash(listOf(stale, fresh), now)
        assertEquals(listOf(fresh, now), recorded)
    }

    @Test
    fun `recordCrash keeps timestamps sorted and bounded`() {
        val existing = (1..20).map { now - it * 1_000L }
        val recorded = StartupGuard.recordCrash(existing, now)
        assertEquals(StartupGuard.MAX_RECORDED_CRASHES, recorded.size)
        assertEquals(recorded.sorted(), recorded)
    }

    @Test
    fun `recentCrashCount only counts crashes inside the window`() {
        val outside = now - StartupGuard.CRASH_WINDOW_MS - 1L
        val boundary = now - StartupGuard.CRASH_WINDOW_MS
        val inside = now - 1_000L
        assertEquals(
            1,
            StartupGuard.recentCrashCount(listOf(outside, boundary, inside), now),
        )
        assertEquals(0, StartupGuard.recentCrashCount(emptyList(), now))
    }

    @Test
    fun `three crashes inside the window enter safe mode`() {
        val times = listOf(now - 5_000L, now - 120_000L, now - 400_000L)
        assertTrue(StartupGuard.isCrashLoop(times, now))
        assertEquals(3, StartupGuard.recentCrashCount(times, now))
        assertFalse(StartupGuard.isCrashLoop(times.take(2), now))
        assertFalse(
            StartupGuard.isCrashLoop(
                listOf(now - 9 * 60_000L, now - 5 * 60_000L, now - 11 * 60_000L),
                now,
            ),
        )
    }

    @Test
    fun `crash csv round-trips and ignores garbage entries`() {
        val times = listOf(3L, 1L, 2L)
        assertEquals("3,1,2", StartupGuard.crashTimesToCsv(times))
        assertEquals(times, StartupGuard.crashTimesFromCsv(StartupGuard.crashTimesToCsv(times)))
        assertEquals(listOf(3L, 4L), StartupGuard.crashTimesFromCsv("3, garbage, -5, 0, 4"))
        assertEquals(emptyList<Long>(), StartupGuard.crashTimesFromCsv(null))
        assertEquals(emptyList<Long>(), StartupGuard.crashTimesFromCsv(""))
    }

    @Test
    fun `reset allowlist contains only presentation and notification keys`() {
        val allowlist = StartupGuard.RESET_PRESENTATION_KEYS
        assertTrue(allowlist.contains("dark_theme"))
        assertTrue(allowlist.contains("chat_font_scale"))
        assertTrue(allowlist.contains("notify_goal"))
        assertTrue(allowlist.contains("quiet_hours"))
        assertTrue(allowlist.contains("voice_language"))
        assertTrue(allowlist.contains("session_sort"))

        // Pairing/token/app-lock/draft/queue state must never be resettable from Safe mode.
        val forbidden = listOf(
            "app_lock",
            "app_lock_delay_seconds",
            "muted_notifications",
            "token",
            "host_profile",
            "draft",
            "queue",
            "background_sync",
            "usage_day_baseline",
            "last_low_balance_alert",
        )
        forbidden.forEach { key ->
            assertFalse("safe mode must not reset $key", StartupGuard.resetScopeKeyAllowed(key))
            assertFalse("allowlist must not include $key", key in allowlist)
        }
        allowlist.forEach { key ->
            assertFalse("suspicious allowlist key $key", key.contains("token"))
            assertFalse("suspicious allowlist key $key", key.contains("secret"))
            assertFalse("suspicious allowlist key $key", key.contains("profile"))
            assertFalse("suspicious allowlist key $key", key.contains("draft"))
            assertFalse("suspicious allowlist key $key", key.contains("queue"))
        }
        assertTrue(StartupGuard.resetScopeKeyAllowed("dark_theme"))
        assertFalse(StartupGuard.resetScopeKeyAllowed("app_lock"))
    }

    @Test
    fun `safe mode deep link accepts canonical forms only`() {
        assertTrue(StartupGuard.isSafeModeDeepLink("dshmobile://safe-mode"))
        assertTrue(StartupGuard.isSafeModeDeepLink("  dshmobile://safe-mode/  "))
        assertTrue(StartupGuard.isSafeModeDeepLink("dshmobile://safe-mode?source=shortcut"))
        assertFalse(StartupGuard.isSafeModeDeepLink("dshmobile://safe-mode-other"))
        assertFalse(StartupGuard.isSafeModeDeepLink("dshmobile://usage"))
        assertFalse(StartupGuard.isSafeModeDeepLink("https://example.com"))
        assertFalse(StartupGuard.isSafeModeDeepLink(null))
    }

    @Test
    fun `repeated crashes are retained inside the window`() {
        val once = StartupGuard.recordCrash(emptyList(), now)
        val twice = StartupGuard.recordCrash(once, now + 1_000L)
        assertEquals(listOf(now, now + 1_000L), twice)
        assertEquals(2, StartupGuard.recentCrashCount(twice, now + 1_000L))
    }

    @Test
    fun `install guards prevent duplicate handler chaining`() {
        StartupGuard.resetInstallGuardsForTests()
        assertTrue(StartupGuard.claimCrashRecorderInstall())
        assertFalse("recorder must install once", StartupGuard.claimCrashRecorderInstall())
        assertTrue(StartupGuard.claimCrashReporterInstall())
        assertFalse("reporter must install once", StartupGuard.claimCrashReporterInstall())
        StartupGuard.resetInstallGuardsForTests()
        assertTrue(StartupGuard.claimCrashRecorderInstall())
        assertTrue(StartupGuard.claimCrashReporterInstall())
        StartupGuard.resetInstallGuardsForTests()
    }

    @Test
    fun `shortcut and activity constants match the manifest resources`() {
        assertEquals("dshmobile://safe-mode", StartupGuard.SAFE_MODE_DEEP_LINK)
        assertEquals("safe_mode", StartupGuard.SAFE_MODE_SHORTCUT_ID)
        assertEquals("dev.dsh.mobile.SafeModeActivity", StartupGuard.SAFE_MODE_ACTIVITY)
        assertEquals(10 * 60 * 1000L, StartupGuard.CRASH_WINDOW_MS)
        assertEquals(3, StartupGuard.CRASH_THRESHOLD)
        // A healthy run must survive at least 30 s (not a first-frame clear) before resetting.
        assertEquals(60_000L, StartupGuard.HEALTHY_CLEAR_DELAY_MS)
        assertTrue(StartupGuard.HEALTHY_CLEAR_DELAY_MS in 30_000L..120_000L)
        assertEquals("dsh-presentation", StartupGuard.PRESENTATION_PREFS_NAME)
    }
}
