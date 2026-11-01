package dev.dsh.mobile.update

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.dsh.mobile.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File

/** Everything the update UI needs to render. */
sealed interface UpdateUiState {
    data object Idle : UpdateUiState
    data object Checking : UpdateUiState
    data class UpToDate(val currentVersionName: String) : UpdateUiState
    data class Available(val update: AvailableUpdate) : UpdateUiState
    data class Downloading(val update: AvailableUpdate, val percent: Int?) : UpdateUiState
    data class Verifying(val update: AvailableUpdate) : UpdateUiState
    data class NeedsInstallPermission(val update: AvailableUpdate, val apkFile: File) : UpdateUiState
    data class InstallPrompted(val update: AvailableUpdate) : UpdateUiState
    data class Failed(val message: String) : UpdateUiState
}

/**
 * Owns the sideload update flow: throttled launch check, manual check, download (DownloadManager),
 * SHA-256 verification, and the package-installer handoff. Compose observes [state]/[showDialog].
 */
class UpdateManager(
    context: Context,
    private val scope: CoroutineScope,
    private val checker: UpdateChecker = UpdateChecker(),
) {

    private val appContext = context.applicationContext
    private val installer = UpdateInstaller(appContext)
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var state: UpdateUiState by mutableStateOf(UpdateUiState.Idle)
        private set

    var showDialog: Boolean by mutableStateOf(false)
        private set

    var lastCheckedAt: Long by mutableStateOf(prefs.getLong(KEY_LAST_CHECKED_AT, 0L))
        private set

    val currentVersionCode: Int get() = BuildConfig.VERSION_CODE
    val currentVersionName: String get() = BuildConfig.VERSION_NAME

    private var checkJob: Job? = null
    private var downloadJob: Job? = null

    // region checking

    /** Launch-time check, throttled to once per [UpdateConfig.CHECK_INTERVAL_MS] (24h). */
    fun checkOnLaunch() {
        if (System.currentTimeMillis() - lastCheckedAt < UpdateConfig.CHECK_INTERVAL_MS) return
        check(manual = false)
    }

    /**
     * [manual] checks surface every outcome (dialog + row text). Launch checks are silent on
     * failure and only report a found update, per docs/updater.md.
     */
    fun check(manual: Boolean) {
        if (state is UpdateUiState.Checking ||
            state is UpdateUiState.Downloading ||
            state is UpdateUiState.Verifying
        ) {
            return
        }
        state = UpdateUiState.Checking
        if (!manual) {
            // Throttle the next launch check even if this attempt fails (offline/feed down).
            recordCheckTime()
        }
        checkJob?.cancel()
        checkJob = scope.launch {
            val result = runCatching {
                checker.check(currentVersionCode.toLong(), currentVersionName)
            }.getOrElse { error ->
                UpdateCheckResult.Failed(error.message ?: "The update check failed.")
            }
            when (result) {
                is UpdateCheckResult.UpToDate -> {
                    if (manual) recordCheckTime()
                    state = UpdateUiState.UpToDate(result.versionName)
                    if (manual) showDialog = true
                }

                is UpdateCheckResult.Available -> {
                    if (manual) recordCheckTime()
                    state = UpdateUiState.Available(result.update)
                    // A launch-time prompt can appear while the user is opening a session and
                    // hand them to the package installer by accident. Keep the update state
                    // visible in Settings and only open the dialog for an explicit check.
                    if (manual) showDialog = true
                }

                is UpdateCheckResult.Failed -> {
                    state = if (manual) UpdateUiState.Failed(result.message) else UpdateUiState.Idle
                    if (manual) showDialog = true
                }
            }
        }
    }

    // endregion

    // region download / install

    fun downloadAndInstall() {
        val update = (state as? UpdateUiState.Available)?.update ?: return
        showDialog = true
        state = UpdateUiState.Downloading(update, null)
        checkJob?.cancel()
        downloadJob?.cancel()
        downloadJob = scope.launch {
            val downloadId = runCatching { installer.enqueue(update) }.getOrElse { error ->
                state = UpdateUiState.Failed(
                    "Couldn't start the download: ${error.message ?: "unknown error"}",
                )
                return@launch
            }
            val outcome = installer.awaitAndVerify(downloadId, update) { percent ->
                state = UpdateUiState.Downloading(update, percent)
            }
            when (outcome) {
                is DownloadOutcome.Failed -> state = UpdateUiState.Failed(outcome.message)

                is DownloadOutcome.Ready -> {
                    state = UpdateUiState.Verifying(update)
                    if (!installer.canInstallPackages()) {
                        state = UpdateUiState.NeedsInstallPermission(update, outcome.file)
                        return@launch
                    }
                    launchInstaller(update, outcome.file)
                }
            }
        }
    }

    /**
     * Called from the "allow installs" step. Opens the system setting when permission is still
     * missing; otherwise hands the already-verified APK to the package installer.
     */
    fun installDownloaded() {
        val pending = state as? UpdateUiState.NeedsInstallPermission ?: return
        if (!installer.canInstallPackages()) {
            installer.openUnknownSourcesSettings()
            return
        }
        launchInstaller(pending.update, pending.apkFile)
    }

    fun openInstallSettings(): Boolean = installer.openUnknownSourcesSettings()

    fun canInstallPackages(): Boolean = installer.canInstallPackages()

    private fun launchInstaller(update: AvailableUpdate, file: File) {
        state = if (installer.install(file)) {
            UpdateUiState.InstallPrompted(update)
        } else {
            UpdateUiState.Failed("Android couldn't open the package installer.")
        }
    }

    // endregion

    fun dismissDialog() {
        showDialog = false
    }

    /** Re-opens the dialog for the current state (e.g. from the Settings row). */
    fun showUpdateDialog() {
        showDialog = true
    }

    fun dispose() {
        checkJob?.cancel()
        downloadJob?.cancel()
    }

    private fun recordCheckTime() {
        lastCheckedAt = System.currentTimeMillis()
        prefs.edit().putLong(KEY_LAST_CHECKED_AT, lastCheckedAt).apply()
    }

    private companion object {
        const val PREFS = "dsh_updates"
        const val KEY_LAST_CHECKED_AT = "last_checked_at"
    }
}
