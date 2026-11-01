package dev.dsh.mobile.data

import android.content.Context

/**
 * Crash-loop detection and safe-mode policy for the mobile app.
 *
 * The app must never force a user to clear app data (which loses the host pairing) just to get
 * past a bad update. [dev.dsh.mobile.DshApplication] records uncaught crashes here, [MainActivity]
 * checks the counter BEFORE creating AppState/Compose, and SafeModeActivity offers recovery actions
 * that never touch host profiles or tokens.
 *
 * The counting/normalisation helpers are pure so they can be unit tested without Android.
 */
object StartupGuard {

    const val PREFS_NAME = "dsh-startup-guard"
    const val KEY_CRASH_TIMES = "crash_times"
    const val KEY_SAFE_MODE_LAST_ENTERED = "safe_mode_last_entered"

    /** Crash-loop window: crashes inside this window count toward the threshold. */
    const val CRASH_WINDOW_MS: Long = 10 * 60 * 1000L

    /** Enter safe mode after this many crashes inside [CRASH_WINDOW_MS]. */
    const val CRASH_THRESHOLD: Int = 3

    /** Keep at most this many timestamps (bounded prefs growth). */
    const val MAX_RECORDED_CRASHES: Int = 12

    /**
     * A normal launch must stay resumed this long before the crash counter is cleared. Short
     * "first frame" windows can mask a crash loop that dies a few seconds later, so the record is
     * kept until the app has actually survived a minute of use (or the user tapped Start normally).
     */
    const val HEALTHY_CLEAR_DELAY_MS: Long = 60_000L

    /** Launcher shortcut + deep link entry points. */
    const val SAFE_MODE_DEEP_LINK = "dshmobile://safe-mode"
    const val SAFE_MODE_SHORTCUT_ID = "safe_mode"
    const val SAFE_MODE_ACTIVITY = "dev.dsh.mobile.SafeModeActivity"

    /** The only prefs file Safe mode will edit, and the only keys it may remove. */
    const val PRESENTATION_PREFS_NAME = "dsh-presentation"

    /**
     * Explicit allowlist of presentation/notification keys Safe mode may reset.
     *
     * Deliberately NOT included (must survive): host profiles/tokens (`dsh-hosts` / pairing
     * prefs), app lock (`app_lock`, `app_lock_delay_seconds`), per-session notification mutes
     * (`muted_notifications`), drafts (`session-drafts`), queued prompts (`queued-prompts`),
     * attachment drafts (`dsh-pending-attachments`), usage baselines, background-sync timestamps
     * and anything named token/secret/profile.
     */
    val RESET_PRESENTATION_KEYS: Set<String> = linkedSetOf(
        "dark_theme",
        "amoled_dark",
        "chat_font_scale",
        "app_font_scale",
        "wrap_code_blocks",
        "notify_input",
        "notify_done",
        "notify_goal",
        "low_balance_alerts",
        "low_balance_threshold",
        "quiet_hours",
        "quiet_start_hour",
        "quiet_end_hour",
        "voice_prefer_offline",
        "voice_language",
        "status_filter",
        "session_sort",
    )

    /** True only for keys explicitly allowlisted above; never used for pairing/token prefs. */
    fun resetScopeKeyAllowed(key: String): Boolean = key in RESET_PRESENTATION_KEYS

    // ------------------------------------------------------------------ pure math

    /** Drops stale records, appends [nowMs], sorts and caps the list. */
    fun recordCrash(existing: List<Long>, nowMs: Long): List<Long> =
        (existing.filter { it > nowMs - CRASH_WINDOW_MS } + nowMs)
            .sorted()
            .takeLast(MAX_RECORDED_CRASHES)

    /** Crashes that still count inside the window. */
    fun recentCrashCount(times: List<Long>, nowMs: Long): Int =
        times.count { it > nowMs - CRASH_WINDOW_MS }

    /** Crash-loop condition used before the app shell is created. */
    fun isCrashLoop(times: List<Long>, nowMs: Long): Boolean =
        recentCrashCount(times, nowMs) >= CRASH_THRESHOLD

    fun crashTimesToCsv(times: List<Long>): String = times.joinToString(",")

    fun crashTimesFromCsv(raw: String?): List<Long> = raw
        .orEmpty()
        .split(',')
        .mapNotNull { it.trim().toLongOrNull() }
        .filter { it > 0L }

    /** Accepts exact links, surrounding whitespace, a trailing slash and extra query params. */
    fun isSafeModeDeepLink(raw: String?): Boolean =
        raw?.trim()?.substringBefore('?')?.trimEnd('/') == SAFE_MODE_DEEP_LINK

    // ------------------------------------------------------------------ Android storage

    private fun prefs(context: Context) = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun readCrashTimes(context: Context): List<Long> =
        crashTimesFromCsv(prefs(context).getString(KEY_CRASH_TIMES, null))

    fun recordCrash(context: Context, nowMs: Long = System.currentTimeMillis()) {
        runCatching {
            val times = recordCrash(readCrashTimes(context), nowMs)
            // UncaughtExceptionHandler path: the process may die immediately after this call, so
            // the write must be synchronous (commit) — apply() can be lost with the process.
            prefs(context).edit()
                .putString(KEY_CRASH_TIMES, crashTimesToCsv(times))
                .commit()
        }
    }

    fun isCrashLoop(context: Context, nowMs: Long = System.currentTimeMillis()): Boolean =
        runCatching { isCrashLoop(readCrashTimes(context), nowMs) }.getOrDefault(false)

    fun recentCrashCount(context: Context, nowMs: Long = System.currentTimeMillis()): Int =
        runCatching { recentCrashCount(readCrashTimes(context), nowMs) }.getOrDefault(0)

    /** Called by Safe mode "Start app normally" and after a normal run survives briefly. */
    fun clearCrashes(context: Context) {
        runCatching { prefs(context).edit().remove(KEY_CRASH_TIMES).apply() }
    }

    fun markSafeModeEntered(context: Context, nowMs: Long = System.currentTimeMillis()) {
        runCatching {
            prefs(context).edit().putLong(KEY_SAFE_MODE_LAST_ENTERED, nowMs).apply()
        }
    }

    /**
     * Installs the uncaught-exception recorder that feeds the crash-loop counter. Chained so the
     * existing CrashReporter (installed later by MainActivity) still runs first in normal launches.
     *
     * Idempotent: repeated Activity creation must not wrap the handler again (each extra chain link
     * would record the same crash multiple times and create false crash-loop positives).
     */
    fun installCrashRecorder(context: Context) {
        if (!claimCrashRecorderInstall()) return
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { recordCrash(appContext) }
            previous?.uncaughtException(thread, throwable)
        }
    }

    @Volatile
    private var crashRecorderInstalled = false

    @Volatile
    private var crashReporterInstalled = false

    /** Testable idempotency guards for the two chained handlers. */
    internal fun claimCrashRecorderInstall(): Boolean {
        if (crashRecorderInstalled) return false
        crashRecorderInstalled = true
        return true
    }

    internal fun claimCrashReporterInstall(): Boolean {
        if (crashReporterInstalled) return false
        crashReporterInstalled = true
        return true
    }

    internal fun resetInstallGuardsForTests() {
        crashRecorderInstalled = false
        crashReporterInstalled = false
    }
}
