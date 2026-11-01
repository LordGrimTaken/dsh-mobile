package dev.dsh.mobile

import android.app.ActivityManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.util.Log
import dev.dsh.mobile.BuildConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Persistent uncaught-exception recorder for remote diagnosis.
 *
 * The stack is written to app-private files and offered for sharing on the next launch and in
 * Settings. Metadata (build, device, Android, screen geometry/font scale, current screen, memory)
 * is included because the phone that crashes may be the only place the bug reproduces.
 */
object CrashReporter {
    private const val FILE_NAME = "last-crash.txt"
    private const val TAG = "DshCrash"

    @Volatile
    var lastScreen: String = "unknown"

    fun enterScreen(name: String) {
        lastScreen = name
    }

    fun install(context: Context) {
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { write(appContext, thread.name, throwable) }
                .onFailure { Log.w(TAG, "failed to persist crash", it) }
            previous?.uncaughtException(thread, throwable)
        }
    }

    private fun write(context: Context, threadName: String, throwable: Throwable) {
        val file = File(context.filesDir, FILE_NAME)
        val temp = File(context.filesDir, "$FILE_NAME.tmp")
        temp.writeText(buildReport(context, threadName, throwable))
        if (!temp.renameTo(file)) {
            file.writeText(temp.readText())
            temp.delete()
        }
    }

    private fun buildReport(context: Context, threadName: String, throwable: Throwable): String {
        val configuration: Configuration = context.resources.configuration
        val runtime = Runtime.getRuntime()
        val memory = runCatching {
            val manager = context.getSystemService(ActivityManager::class.java)
            val info = ActivityManager.MemoryInfo()
            manager?.getMemoryInfo(info)
            info
        }.getOrNull()
        return buildString {
            appendLine("DSH Mobile crash report")
            appendLine("time: ${isoNow()}")
            appendLine("version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("screen: $lastScreen")
            appendLine(
                "device: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE}); " +
                    "Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})",
            )
            appendLine(
                "display: ${configuration.screenWidthDp}x${configuration.screenHeightDp}dp " +
                    "density=${configuration.densityDpi} fontScale=${configuration.fontScale} " +
                    "orientation=${configuration.orientation}",
            )
            appendLine("package: ${context.packageName}")
            appendLine(
                "memory: max=${runtime.maxMemory() / 1024 / 1024}MB " +
                    "total=${runtime.totalMemory() / 1024 / 1024}MB " +
                    "free=${runtime.freeMemory() / 1024 / 1024}MB" +
                    (memory?.let { " systemAvail=${it.availMem / 1024 / 1024}MB lowMemory=${it.lowMemory}" } ?: ""),
            )
            appendLine("thread: $threadName")
            appendLine("----------------------------------------")
            appendLine(Log.getStackTraceString(throwable))
        }
    }

    fun hasReport(context: Context): Boolean = runCatching {
        File(context.filesDir, FILE_NAME).isFile
    }.getOrDefault(false)

    fun read(context: Context): String? = runCatching {
        val file = File(context.filesDir, FILE_NAME)
        if (file.isFile) file.readText() else null
    }.getOrNull()

    fun clear(context: Context) {
        runCatching { File(context.filesDir, FILE_NAME).delete() }
    }

    /**
     * Record a caught startup failure so Safe mode / "Share error report" can see it. The global
     * uncaught handler only fires for crashes that escape composition; the bootstrap guard catches
     * those before they kill the process.
     */
    fun record(context: Context, throwable: Throwable) {
        runCatching {
            write(
                context.applicationContext,
                Thread.currentThread().name,
                throwable,
            )
        }.onFailure { Log.w(TAG, "failed to persist startup failure", it) }
    }

    private fun isoNow(): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US).format(Date())
}
