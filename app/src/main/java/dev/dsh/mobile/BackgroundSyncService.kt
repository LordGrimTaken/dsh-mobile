package dev.dsh.mobile

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import dev.dsh.mobile.data.AndroidKeystoreTokenCipher
import dev.dsh.mobile.data.HostProfileStore
import dev.dsh.mobile.data.SharedPreferencesHostKeyValueStore
import dev.dsh.mobile.net.DshApiClient
import dev.dsh.mobile.net.DshApiException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Optional foreground sync used only when the user enables background notifications.
 *
 * Polls the active host for session status / approvals / questions and posts local notifications.
 * The persistent service notification keeps Android from killing the watcher while the app is
 * closed. This is deliberately a simple poll, not a second SSE owner.
 */
class BackgroundSyncService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        startForeground(
            SERVICE_NOTIFICATION_ID,
            NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("DSH background sync")
                .setContentText("Watching for turns, approvals, and questions")
                .setOngoing(true)
                .setContentIntent(openAppIntent())
                .build(),
        )
        scope.launch { pollLoop() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private suspend fun pollLoop() {
        val hostStore = HostProfileStore(
            SharedPreferencesHostKeyValueStore(applicationContext),
            AndroidKeystoreTokenCipher(),
        )
        var previousStatuses: Map<String, String> = emptyMap()
        var previousApprovals: Set<String> = emptySet()
        var previousQuestions: Set<String> = emptySet()
        var ready = false

        while (currentCoroutineContext().isActive) {
            val snapshot = runCatching { hostStore.load() }.getOrNull()
            val profile = snapshot?.activeId?.let { id -> snapshot.profiles.firstOrNull { it.id == id } }
            if (profile == null) {
                stopSelf()
                return
            }
            val api = DshApiClient(profile.baseUrl, profile.token)
            try {
                val sessions = api.sessions()
                val statuses = sessions.associate { it.id to it.status }
                if (ready) {
                    statuses.forEach { (sessionId, status) ->
                        if (status != "working" && previousStatuses[sessionId] == "working") {
                            val title = sessions.firstOrNull { it.id == sessionId }?.title ?: "session"
                            DshNotifications.turnFinished(applicationContext, sessionId, title)
                        }
                    }
                }
                previousStatuses = statuses

                val approvalsResponse = runCatching { api.approvalsResponse() }.getOrNull()
                val approvals = approvalsResponse?.approvals.orEmpty()
                val approvalsReadOnly = approvalsResponse?.readOnly == true
                val questions = runCatching { api.questions() }.getOrDefault(emptyList())
                if (ready) {
                    (approvals.map { it.id }.toSet() - previousApprovals).forEach { id ->
                        val approval = approvals.firstOrNull { it.id == id } ?: return@forEach
                        val title = sessions.firstOrNull { it.id == approval.sessionId }?.title ?: "session"
                        DshNotifications.inputNeeded(
                            applicationContext,
                            approval.sessionId,
                            title,
                            "Approval needed",
                            approvalId = approval.id,
                            readOnlyApproval = approvalsReadOnly,
                        )
                    }
                    (questions.map { it.id }.toSet() - previousQuestions).forEach { id ->
                        val question = questions.firstOrNull { it.id == id } ?: return@forEach
                        val title = sessions.firstOrNull { it.id == question.sessionId }?.title ?: "session"
                        DshNotifications.inputNeeded(
                            applicationContext,
                            question.sessionId,
                            title,
                            "Question waiting",
                        )
                    }
                }
                previousApprovals = approvals.map { it.id }.toSet()
                previousQuestions = questions.map { it.id }.toSet()
                ready = true
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                    .putLong("background_sync_last_at", System.currentTimeMillis())
                    .remove("background_sync_last_error")
                    .apply()
            } catch (error: DshApiException) {
                // Host offline; keep the service alive and retry on the next interval.
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                    .putString("background_sync_last_error", error.message ?: "host offline")
                    .apply()
            } catch (error: Exception) {
                // Same: background watching must not crash the app.
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                    .putString("background_sync_last_error", error.message ?: "sync failed")
                    .apply()
            }
            delay(pollIntervalMs())
        }
    }

    private fun ensureChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL,
                "Background sync",
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = "Keeps watching your host for turns and approvals" },
        )
    }

    private fun openAppIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun pollIntervalMs(): Long {
        val seconds = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getInt(KEY_INTERVAL_SECONDS, DEFAULT_INTERVAL_SECONDS)
            .coerceIn(20, 3600)
        return seconds * 1000L
    }

    companion object {
        private const val CHANNEL = "background-sync"
        private const val SERVICE_NOTIFICATION_ID = 1042
        private const val PREFS_NAME = "dsh-presentation"
        private const val KEY_INTERVAL_SECONDS = "background_sync_interval"
        private const val DEFAULT_INTERVAL_SECONDS = 20

        fun start(context: Context) {
            runCatching {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, BackgroundSyncService::class.java),
                )
            }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, BackgroundSyncService::class.java)) }
        }
    }
}
