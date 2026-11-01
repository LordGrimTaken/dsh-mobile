package dev.dsh.mobile.update

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

sealed interface DownloadOutcome {
    data class Ready(val file: File) : DownloadOutcome
    data class Failed(val message: String) : DownloadOutcome
}

/**
 * Downloads the update APK with Android's [DownloadManager] into app-private external storage,
 * verifies its SHA-256, and hands it to the system package installer through a FileProvider URI.
 */
class UpdateInstaller(context: Context) {

    private val appContext = context.applicationContext
    private val downloadManager: DownloadManager? =
        appContext.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager

    /** REQUEST_INSTALL_PACKAGES declared, but the user must still allow "install unknown apps". */
    fun canInstallPackages(): Boolean =
        runCatching { appContext.packageManager.canRequestPackageInstalls() }.getOrDefault(false)

    /** Opens this app's "install unknown apps" settings page; returns false if no Settings app. */
    fun openUnknownSourcesSettings(): Boolean = runCatching {
        appContext.startActivity(
            Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${appContext.packageName}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }.isSuccess

    /** Queues the APK download into `Download/updates/` under the app's external files dir. */
    fun enqueue(update: AvailableUpdate): Long {
        val manager = downloadManager
            ?: throw IllegalStateException("Download manager is unavailable")
        val target = destinationFile(update)
        if (target.exists()) target.delete()
        val request = DownloadManager.Request(Uri.parse(update.apkUrl)).apply {
            setTitle("DSH Mobile ${update.versionName}")
            setDescription("Downloading update")
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setDestinationInExternalFilesDir(
                appContext,
                Environment.DIRECTORY_DOWNLOADS,
                "updates/${target.name}",
            )
            setMimeType(APK_MIME)
            setAllowedOverMetered(true)
            setAllowedOverRoaming(false)
        }
        return manager.enqueue(request)
    }

    /**
     * Polls [DownloadManager] until the download finishes, then verifies the SHA-256 from the
     * manifest. A mismatch deletes the file and returns [DownloadOutcome.Failed] - never install.
     */
    suspend fun awaitAndVerify(
        downloadId: Long,
        update: AvailableUpdate,
        onProgress: (Int?) -> Unit,
    ): DownloadOutcome = withContext(Dispatchers.IO) {
        val manager = downloadManager
            ?: return@withContext DownloadOutcome.Failed("Download manager is unavailable.")
        val deadline = System.currentTimeMillis() + DOWNLOAD_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            val snapshot = query(manager, downloadId)
            when (snapshot?.status) {
                DownloadManager.STATUS_SUCCESSFUL -> {
                    onProgress(100)
                    val file = resolveDownloadedFile(snapshot)
                        ?: destinationFile(update).takeIf { it.isFile }
                    if (file == null || !file.isFile) {
                        return@withContext DownloadOutcome.Failed(
                            "The update downloaded but the APK file is missing.",
                        )
                    }
                    if (!Sha256.verify(file, update.sha256)) {
                        file.delete()
                        return@withContext DownloadOutcome.Failed(
                            "The downloaded update failed the SHA-256 check and was deleted.",
                        )
                    }
                    return@withContext DownloadOutcome.Ready(file)
                }

                DownloadManager.STATUS_FAILED -> return@withContext DownloadOutcome.Failed(
                    "The download failed. Check your connection and try again.",
                )

                else -> {
                    val percent = snapshot
                        ?.takeIf { it.total > 0 }
                        ?.let { ((it.bytes * 100L) / it.total).toInt().coerceIn(0, 100) }
                    onProgress(percent)
                }
            }
            delay(POLL_INTERVAL_MS)
        }
        return@withContext DownloadOutcome.Failed("The download timed out.")
    }

    /** Launches the package installer for a verified APK. Returns false if no installer exists. */
    fun install(file: File): Boolean = runCatching {
        val uri = FileProvider.getUriForFile(
            appContext,
            "${appContext.packageName}.update_provider",
            file,
        )
        appContext.startActivity(
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, APK_MIME)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        )
    }.isSuccess

    private fun destinationFile(update: AvailableUpdate): File =
        File(updatesDir(), "dsh-mobile-${update.versionCode}.apk")

    private fun updatesDir(): File {
        val base = appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: appContext.cacheDir
        return File(base, "updates").apply { mkdirs() }
    }

    private fun query(manager: DownloadManager, downloadId: Long): DownloadSnapshot? {
        val cursor: Cursor = manager.query(DownloadManager.Query().setFilterById(downloadId))
        return cursor.use { row ->
            if (!row.moveToFirst()) return null
            DownloadSnapshot(
                status = row.getInt(row.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)),
                bytes = row.getLong(
                    row.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR),
                ),
                total = row.getLong(row.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)),
                localUri = row.getString(row.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI)),
            )
        }
    }

    private fun resolveDownloadedFile(snapshot: DownloadSnapshot): File? {
        val uri = snapshot.localUri ?: return null
        if (!uri.startsWith("file:")) return null
        val path = runCatching { Uri.parse(uri).path }.getOrNull() ?: return null
        return File(path).takeIf { it.isFile }
    }

    private class DownloadSnapshot(
        val status: Int,
        val bytes: Long,
        val total: Long,
        val localUri: String?,
    )

    companion object {
        const val APK_MIME = "application/vnd.android.package-archive"
        private const val POLL_INTERVAL_MS = 500L
        private const val DOWNLOAD_TIMEOUT_MS = 15L * 60L * 1000L
    }
}
