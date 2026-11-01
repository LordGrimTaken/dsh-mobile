package dev.dsh.mobile

import android.app.Application
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import dev.dsh.mobile.data.StartupGuard
import dev.dsh.mobile.ui.DshMobileApp

/**
 * Crash-loop guard for the whole process: uncaught exceptions are counted in [StartupGuard] before
 * the system handler runs, so a bad update can route the next launch into SafeModeActivity instead
 * of forcing the user to clear app data (and lose the host pairing).
 */
class DshApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Trivial and defensive: a failure here must never prevent the shell from starting.
        runCatching { StartupGuard.installCrashRecorder(this) }
    }
}

class MainActivity : ComponentActivity() {

    private var sharedText by mutableStateOf<String?>(null)
    private var shareNonce by mutableStateOf(0)
    private var sharedImages by mutableStateOf<List<Uri>>(emptyList())
    private var shareImageNonce by mutableStateOf(0)
    private var deepLink by mutableStateOf<String?>(null)
    private var deepLinkNonce by mutableStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Idempotent: an Activity recreate must not chain a second CrashReporter handler (which
        // would record every crash twice and trip safe mode early).
        if (StartupGuard.claimCrashReporterInstall()) {
            runCatching { CrashReporter.install(applicationContext) }
        }
        // dshmobile://safe-mode (and the launcher shortcut) must not create AppState/Compose.
        if (routeSafeModeIfRequested(intent)) return
        // Crash-loop check happens BEFORE setContent so the normal shell is never constructed.
        if (StartupGuard.isCrashLoop(this)) {
            StartupGuard.markSafeModeEntered(this)
            startActivity(
                Intent(this, SafeModeActivity::class.java).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK,
                ),
            )
            finish()
            return
        }
        // With app lock enabled, keep the session UI out of screenshots and the recents thumbnail.
        val appLock = getSharedPreferences("dsh-presentation", MODE_PRIVATE)
            .getBoolean("app_lock", false)
        if (appLock) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        // Hadal Mint: transparent system bars with light icons over the ink ground.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        handleSharedIntent(intent)
        handleDeepLink(intent)
        setContent {
            DshMobileApp(
                sharedText = sharedText,
                shareNonce = shareNonce,
                sharedImages = sharedImages,
                shareImageNonce = shareImageNonce,
                deepLink = deepLink,
                deepLinkNonce = deepLinkNonce,
            )
        }
        // A normal run that stays resumed for a full minute clears the crash counter. The window
        // is deliberately long (not "first frame") so crashes that happen a few seconds later still
        // count toward the loop, and the counter is retained if the app is not actually in use.
        window.decorView.postDelayed(
            {
                runCatching {
                    if (!isFinishing && !isDestroyed &&
                        lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
                    ) {
                        StartupGuard.clearCrashes(applicationContext)
                    }
                }
            },
            StartupGuard.HEALTHY_CLEAR_DELAY_MS,
        )
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (routeSafeModeIfRequested(intent)) return
        handleSharedIntent(intent)
        handleDeepLink(intent)
    }

    /** Routes dshmobile://safe-mode to the recovery screen without touching the app shell. */
    private fun routeSafeModeIfRequested(intent: Intent?): Boolean {
        if (intent?.action != Intent.ACTION_VIEW) return false
        if (!StartupGuard.isSafeModeDeepLink(intent.data?.toString())) return false
        StartupGuard.markSafeModeEntered(this)
        startActivity(Intent(this, SafeModeActivity::class.java))
        finish()
        return true
    }

    /** ACTION_SEND text becomes a draft; ACTION_SEND/SEND_MULTIPLE image becomes an attachment. */
    @Suppress("DEPRECATION")
    private fun handleSharedIntent(intent: Intent?) {
        if (intent == null) return
        if (intent.action != Intent.ACTION_SEND && intent.action != Intent.ACTION_SEND_MULTIPLE) return
        val type = intent.type.orEmpty()
        if (type.startsWith("text/")) {
            val text = intent.getStringExtra(Intent.EXTRA_TEXT)?.trim().orEmpty()
            if (text.isEmpty()) return
            sharedText = text
            shareNonce += 1
            return
        }
        if (type.startsWith("image/")) {
            val single = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            val many = intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
            val images = when {
                !many.isNullOrEmpty() -> many
                single != null -> listOf(single)
                else -> emptyList()
            }
            if (images.isEmpty()) return
            sharedImages = images
            shareImageNonce += 1
        }
    }

    /** Launcher shortcuts: dshmobile://new and dshmobile://usage. */
    private fun handleDeepLink(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        val target = intent.data?.toString() ?: return
        if (!target.startsWith("dshmobile://")) return
        deepLink = target
        deepLinkNonce += 1
    }
}
