package dev.dsh.mobile

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import dev.dsh.mobile.data.StartupGuard
import java.io.File

/**
 * Minimal recovery screen for a crash-looping install.
 *
 * Deliberately does NOT create AppState, RemoteRepository or the Compose shell: it only touches the
 * crash counter, cache directories, the explicit presentation/notification pref allowlist and the
 * existing crash report. Host profiles and tokens are never read or written from here, so pairing
 * survives even when a bad update stops the main app from starting.
 */
class SafeModeActivity : ComponentActivity() {

    /** Explicit dark backdrop so the light recovery text has contrast under any theme. */
    private val darkBackground: Int = 0xFF101614.toInt()

    private lateinit var statusView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runCatching { StartupGuard.markSafeModeEntered(this) }
        // Defensive: if the normal app theme/resources are the crash cause, still render something
        // usable instead of inheriting the failure. The platform theme is applied in the manifest.
        val content = runCatching { buildContent() }.getOrElse {
            runCatching { buildFallbackContent() }.getOrElse { buildLastResortView() }
        }
        runCatching { setContentView(content) }.onFailure {
            setContentView(buildLastResortView())
        }
        showStatus(
            buildString {
                append("Detected ")
                append(StartupGuard.recentCrashCount(this@SafeModeActivity))
                append(" recent crash")
                if (StartupGuard.recentCrashCount(this@SafeModeActivity) != 1) append("es")
                append(" in the last 10 minutes. Nothing has been deleted yet.")
            },
        )
    }

    private fun buildContent(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(darkBackground)
            setPadding(dp(20), dp(28), dp(20), dp(28))
        }

        root.addView(
            TextView(this).apply {
                text = "DSH Mobile - Safe mode"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
                setTextColor(0xFFE8F5EE.toInt())
            },
        )
        root.addView(
            textView(
                "The app crashed repeatedly, so it started in safe mode to protect your data.",
                topDp = 8,
            ),
        )
        root.addView(
            textView(
                "Your host pairing, drafts, queued prompts and app lock are preserved. " +
                    "Nothing below deletes them.",
                topDp = 10,
                color = 0xFF7FE0B0.toInt(),
            ),
        )

        root.addView(actionButton(
            "Start app normally",
            "Leaves safe mode and clears the crash counter, then relaunches the app.",
        ) {
            // Clear the counter first so the normal launch does not immediately bounce back here.
            StartupGuard.clearCrashes(this)
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }.padTop(22))
        root.addView(
            textView(
                "Leaves safe mode and clears the crash counter. A normal run clears it again " +
                    "after 60 seconds of use; if the app still crashes, safe mode comes back.",
                topDp = 2,
            ),
        )

        root.addView(actionButton(
            "Clear caches only",
            "Deletes cached transcripts, attachments and thumbnails. Keeps pairing, drafts, " +
                "queued prompts and settings.",
        ) { clearCaches() }.padTop(16))
        root.addView(
            textView(
                "Deletes cached transcripts, attachments and thumbnails. Pairing, drafts, " +
                    "queued prompts and settings are kept.",
                topDp = 2,
            ),
        )

        root.addView(
            actionButton(
                "Reset appearance & notification settings",
                "Resets theme, text size, notification toggles, quiet hours and voice language. " +
                    "Keeps pairing, app lock, drafts and queued prompts.",
            ) { confirmResetSettings() }.padTop(16),
        )
        root.addView(
            textView(
                "Resets only theme, text size, code wrapping, notification toggles, quiet hours, " +
                    "balance alert, voice language, filters and sort. Host pairing, app lock, " +
                    "drafts, queued prompts and recovery data are kept.",
                topDp = 2,
            ),
        )

        root.addView(actionButton(
            "Share crash log",
            "Shares the last crash report so support can diagnose the crash loop.",
        ) { shareCrashLog() }.padTop(16))
        root.addView(
            textView("Send the last crash report to support for diagnosis.", topDp = 2),
        )

        root.addView(actionButton(
            "Open Android App info (last resort)",
            "Opens Android App info for Clear storage or uninstall. Clearing storage removes " +
                "the host pairing, so try the options above first.",
        ) { openAppInfo() }.padTop(16))
        root.addView(
            textView(
                "Only for a final Clear storage / uninstall attempt. Clearing storage removes " +
                    "the host pairing, so try the options above first.",
                topDp = 2,
                color = 0xFFFFB4A6.toInt(),
            ),
        )

        statusView = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(0xFFB9C7C0.toInt())
            // Announce status changes to TalkBack without stealing focus.
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            setPadding(0, dp(20), 0, 0)
        }
        root.addView(statusView)

        return ScrollView(this).apply {
            setBackgroundColor(darkBackground)
            addView(root)
        }
    }

    private fun actionButton(
        label: String,
        description: String = "",
        onClick: () -> Unit,
    ): Button =
        Button(this).apply {
            text = label
            isAllCaps = false
            contentDescription = if (description.isBlank()) label else "$label. $description"
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }

    /** Minimal fallback used if the themed content fails to build (e.g. bad density/resources). */
    private fun buildFallbackContent(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(darkBackground)
            setPadding(dp(20), dp(28), dp(20), dp(28))
        }
        root.addView(
            TextView(this).apply {
                text = "DSH Mobile - Safe mode"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
                setTextColor(0xFFE8F5EE.toInt())
            },
        )
        root.addView(
            actionButton(
                "Start app normally",
                "Leaves safe mode and relaunches the app.",
            ) {
                StartupGuard.clearCrashes(this@SafeModeActivity)
                startActivity(Intent(this@SafeModeActivity, MainActivity::class.java))
                finish()
            }.padTop(20),
        )
        root.addView(
            actionButton(
                "Open Android App info (last resort)",
                "Opens Android App info for Clear storage. Clearing storage removes pairing.",
            ) { openAppInfo() }.padTop(12),
        )
        statusView = TextView(this)
        statusView.text = "Host pairing, drafts and queued prompts are preserved."
        statusView.setTextColor(0xFFB9C7C0.toInt())
        statusView.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        statusView.setPadding(0, dp(16), 0, 0)
        root.addView(statusView)
        return ScrollView(this).apply {
            setBackgroundColor(darkBackground)
            addView(root)
        }
    }

    /** Last resort if even the fallback layout fails: a plain platform TextView. */
    private fun buildLastResortView(): View = TextView(this).apply {
        text = "DSH Mobile is in safe mode. Host pairing is preserved. " +
            "Open Android App info from system settings if this screen is not usable."
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        setTextColor(0xFFE8F5EE.toInt())
        setBackgroundColor(darkBackground)
        setPadding(40, 80, 40, 40)
    }

    private fun textView(text: String, topDp: Int = 0, color: Int = 0xFFB9C7C0.toInt()): TextView =
        TextView(this).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(color)
            setPadding(0, dp(topDp), 0, 0)
        }

    private fun View.padTop(topDp: Int): View = apply {
        setPadding(0, dp(topDp), 0, 0)
    }

    private fun showStatus(message: String) {
        if (::statusView.isInitialized) statusView.text = message
    }

    private fun clearCaches() {
        val cacheDir = cacheDir
        val reclaimed = runCatching { cacheSize(cacheDir) }.getOrDefault(0L)
        val removed = runCatching {
            cacheDir.listFiles()?.forEach { child -> child.deleteRecursively() } ?: Unit
        }
        showStatus(
            if (removed.isSuccess) {
                "Cleared ${formatBytes(reclaimed)} of cached files. Host pairing, drafts, " +
                    "queued prompts and settings were kept."
            } else {
                "Couldn't clear every cached file; you can retry or use Android App info."
            },
        )
    }

    private fun confirmResetSettings() {
        AlertDialog.Builder(this)
            .setTitle("Reset appearance & notification settings?")
            .setMessage(
                "Only theme, text size, code wrapping, notification toggles, quiet hours, balance " +
                    "alert, voice language, filters and sort are reset. Host pairing, app lock, " +
                    "drafts, queued prompts and recovery data are kept.",
            )
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Reset settings") { _, _ -> resetPresentationSettings() }
            .show()
    }

    private fun resetPresentationSettings() {
        val prefs = getSharedPreferences(StartupGuard.PRESENTATION_PREFS_NAME, MODE_PRIVATE)
        var removed = 0
        prefs.edit().let { editor ->
            StartupGuard.RESET_PRESENTATION_KEYS
                .filter { StartupGuard.resetScopeKeyAllowed(it) }
                .forEach { key ->
                    if (prefs.contains(key)) {
                        editor.remove(key)
                        removed += 1
                    }
                }
            editor.apply()
        }
        showStatus(
            "Reset $removed appearance/notification setting" +
                (if (removed == 1) "" else "s") +
                ". Host pairing, app lock, drafts, queued prompts and recovery data were kept.",
        )
    }

    private fun shareCrashLog() {
        val report = CrashReporter.read(this)
        if (report.isNullOrBlank()) {
            showStatus("No crash log was found to share.")
            return
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "DSH Mobile safe-mode crash report")
            putExtra(Intent.EXTRA_TEXT, report)
        }
        runCatching {
            startActivity(Intent.createChooser(intent, "Share crash log"))
        }.onFailure { showStatus("No app can share the crash log.") }
    }

    private fun openAppInfo() {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:$packageName"),
        )
        runCatching { startActivity(intent) }.onFailure {
            showStatus("Couldn't open Android App info.")
        }
    }

    private fun cacheSize(file: File?): Long {
        if (file == null || !file.exists()) return 0L
        if (file.isFile) return file.length()
        return file.listFiles()?.sumOf { child -> cacheSize(child) } ?: 0L
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes <= 0L -> "0 B"
        bytes < 1024L -> "$bytes B"
        bytes < 1024L * 1024L -> "${bytes / 1024L} KB"
        else -> "${bytes / (1024L * 1024L)} MB"
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            resources.displayMetrics,
        ).toInt()
}
