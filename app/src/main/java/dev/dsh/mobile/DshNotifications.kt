package dev.dsh.mobile

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import dev.dsh.mobile.data.QuestionReply
import java.time.LocalTime

/** Local notifications for turn completion and pending agent input. */
object DshNotifications {
    const val CHANNEL_TURN = "turn-finished"
    const val CHANNEL_INPUT = "input-needed"
    const val CHANNEL_USAGE = "usage-alerts"
    const val CHANNEL_GOAL = "goal-alerts"
    private const val NOTIFICATION_GROUP = "dsh.mobile.events"
    private const val GROUP_SUMMARY_ID = 1000

    fun ensureChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_TURN,
                "Turn finished",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { description = "An agent finished working" },
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_INPUT,
                "Input needed",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply { description = "An approval or question is waiting" },
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_USAGE,
                "DeepSeek balance",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { description = "Remaining DeepSeek credit is low" },
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_GOAL,
                "Goal updates",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { description = "An agent goal paused, blocked or completed" },
        )
    }

    fun hasPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    }

    /** True while the user's quiet-hours window is active (no turn/input notifications posted). */
    fun isQuietHours(context: Context): Boolean {
        val prefs = context.getSharedPreferences("dsh-presentation", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("quiet_hours", false)) return false
        val start = prefs.getInt("quiet_start_hour", 22).coerceIn(0, 23)
        val end = prefs.getInt("quiet_end_hour", 8).coerceIn(0, 23)
        if (start == end) return false
        val hour = LocalTime.now().hour
        return if (start < end) hour in start until end else hour >= start || hour < end
    }

    private fun isSessionMuted(context: Context, sessionId: String): Boolean {
        if (sessionId.isBlank()) return false
        return context.getSharedPreferences("dsh-presentation", Context.MODE_PRIVATE)
            .getStringSet("muted_notifications", emptySet())
            ?.contains(sessionId) == true
    }

    /** Diagnostic helper from Settings; returns false when notifications are blocked. */
    @SuppressLint("MissingPermission")
    fun test(context: Context): Boolean {
        ensureChannels(context)
        if (!hasPermission(context)) return false
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(
            context,
            999,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_TURN)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("DSH Mobile notifications")
            .setContentText("Test notification — this is how turn and approval alerts appear.")
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        return runCatching {
            NotificationManagerCompat.from(context).notify(999, notification)
            true
        }.getOrDefault(false)
    }

    fun clearAll(context: Context) {
        runCatching { NotificationManagerCompat.from(context).cancelAll() }
    }

    /** One low-credit alert per day; deep-links to the Usage dashboard. */
    @SuppressLint("MissingPermission")
    fun lowBalance(context: Context, text: String) {
        ensureChannels(context)
        if (!hasPermission(context) || isQuietHours(context)) return
        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = Uri.parse("dshmobile://usage")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(
            context,
            1001,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_USAGE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Low DeepSeek balance")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(1001, notification) }
    }

    fun turnFinished(context: Context, sessionId: String, sessionTitle: String) {
        post(
            context = context,
            channel = CHANNEL_TURN,
            notificationId = ("turn:$sessionId").hashCode(),
            title = "Turn finished",
            text = sessionTitle,
            sessionId = sessionId,
        )
    }

    fun inputNeeded(
        context: Context,
        sessionId: String,
        sessionTitle: String,
        kind: String,
        approvalId: String? = null,
        /** Read-only approvals cannot be decided from a notification: no Allow/Deny actions. */
        readOnlyApproval: Boolean = false,
        /** Pending question id (adds the free-text RemoteInput reply action when writable). */
        questionId: String? = null,
        /** Question prompt shown as the notification big text (trimmed by the caller or here). */
        questionPrompt: String? = null,
        /** Read-only hosts answer questions on the desktop: no inline reply action. */
        questionsReadOnly: Boolean = false,
    ) {
        post(
            context = context,
            channel = CHANNEL_INPUT,
            // Per-question id so multiple questions in one session don't overwrite each other.
            notificationId = if (questionId != null) {
                QuestionReply.notificationIdFor(sessionId, questionId)
            } else {
                ("input:$kind:$sessionId").hashCode()
            },
            title = kind,
            text = sessionTitle,
            sessionId = sessionId,
            approvalId = approvalId,
            readOnlyApproval = readOnlyApproval,
            questionId = questionId,
            questionPrompt = questionPrompt,
            questionsReadOnly = questionsReadOnly,
        )
    }

    /**
     * Goal alert for a paused/blocked/complete transition. Shares [post]'s permission,
     * quiet-hours, mute and foreground guards, deep-links to the session and auto-cancels.
     */
    fun goalChanged(
        context: Context,
        title: String,
        text: String,
        sessionId: String,
    ) {
        post(
            context = context,
            channel = CHANNEL_GOAL,
            notificationId = ("goal:$sessionId").hashCode(),
            title = title,
            text = text,
            sessionId = sessionId,
        )
    }

    @SuppressLint("MissingPermission")
    private fun post(
        context: Context,
        channel: String,
        notificationId: Int,
        title: String,
        text: String,
        sessionId: String,
        approvalId: String? = null,
        readOnlyApproval: Boolean = false,
        questionId: String? = null,
        questionPrompt: String? = null,
        questionsReadOnly: Boolean = false,
    ) {
        if (!hasPermission(context)) return
        if (isQuietHours(context)) return
        if (isSessionMuted(context, sessionId)) return
        // The foreground Compose monitors own the alert when the Activity is visible; background
        // sync must not post a duplicate on top of them.
        if (context.getSharedPreferences("dsh-presentation", Context.MODE_PRIVATE)
                .getBoolean("app_foreground", false)
        ) {
            return
        }
        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = Uri.parse("dshmobile://session/$sessionId")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(
            context,
            notificationId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        // Questions show the actual prompt; approvals keep the session title.
        val prompt = questionPrompt?.trim().orEmpty()
        val displayText = if (prompt.isNotEmpty()) prompt else text
        val notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(displayText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(displayText))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setGroup(NOTIFICATION_GROUP)
            .apply {
                // R6: a question prompt is user-private; hide it on the lock screen and show only
                // a generic public version there.
                if (channel == CHANNEL_INPUT && questionId != null && prompt.isNotEmpty()) {
                    setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                    setPublicVersion(buildQuestionPublicVersion(context, notificationId))
                }
                if (channel == CHANNEL_INPUT) {
                    addAction(
                        R.drawable.ic_notification,
                        "Open inbox",
                        deepLinkPending(context, "dshmobile://inbox", notificationId xor 0x5A5A),
                    )
                    if (approvalId != null && !readOnlyApproval) {
                        addAction(
                            R.drawable.ic_notification,
                            "Allow",
                            deepLinkPending(
                                context,
                                "dshmobile://approval/$approvalId/allow",
                                notificationId xor 0x11,
                            ),
                        )
                        addAction(
                            R.drawable.ic_notification,
                            "Deny",
                            deepLinkPending(
                                context,
                                "dshmobile://approval/$approvalId/deny",
                                notificationId xor 0x22,
                            ),
                        )
                    }
                    if (questionId != null && questionId.isNotBlank()) {
                        // App lock bypasses the UI, so the inline reply is only offered with app
                        // lock OFF; the receiver re-checks the pref before answering. Read-only
                        // hosts answer on the desktop, and quiet/mute/foreground are already
                        // filtered above, so no extra notification action is added there.
                        val appLockEnabled = context
                            .getSharedPreferences("dsh-presentation", Context.MODE_PRIVATE)
                            .getBoolean("app_lock", false)
                        if (QuestionReply.canOfferInlineReply(appLockEnabled, questionsReadOnly)) {
                            addAction(buildQuestionReplyAction(context, notificationId, questionId, sessionId))
                        }
                    }
                }
            }
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(notificationId, notification) }
        updateGroupSummary(context)
    }

    /** Android groups 2+ DSH alerts under a single summary that opens the Inbox. */
    @SuppressLint("MissingPermission")
    private fun updateGroupSummary(context: Context) {
        if (!hasPermission(context)) return
        val manager = context.getSystemService(android.app.NotificationManager::class.java) ?: return
        val children = runCatching {
            manager.activeNotifications.filter { record ->
                record.notification.group == NOTIFICATION_GROUP &&
                    (record.notification.flags and Notification.FLAG_GROUP_SUMMARY) == 0
            }
        }.getOrDefault(emptyList())
        if (children.size <= 1) {
            runCatching { manager.cancel(GROUP_SUMMARY_ID) }
            return
        }
        val summary = NotificationCompat.Builder(context, CHANNEL_TURN)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("DSH Mobile")
            .setContentText("${children.size} updates waiting")
            .setGroup(NOTIFICATION_GROUP)
            .setGroupSummary(true)
            .setAutoCancel(true)
            .setContentIntent(deepLinkPending(context, "dshmobile://inbox", GROUP_SUMMARY_ID))
            .build()
        runCatching { manager.notify(GROUP_SUMMARY_ID, summary) }
    }

    /**
     * Free-text answer action: RemoteInput collects the reply and a broadcast PendingIntent (mutable
     * so the system can attach the results) hands it to [QuestionReplyReceiver].
     */
    private fun buildQuestionReplyAction(
        context: Context,
        notificationId: Int,
        questionId: String,
        sessionId: String,
    ): NotificationCompat.Action {
        val replyIntent = Intent(context, QuestionReplyReceiver::class.java).apply {
            action = QuestionReply.ACTION_REPLY
            putExtra(QuestionReply.EXTRA_QUESTION_ID, questionId)
            putExtra(QuestionReply.EXTRA_SESSION_ID, sessionId)
            putExtra(QuestionReply.EXTRA_NOTIFICATION_ID, notificationId)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        val replyPending = PendingIntent.getBroadcast(
            context,
            QuestionReply.requestCodeFor(notificationId, questionId),
            replyIntent,
            flags,
        )
        val remoteInput = RemoteInput.Builder(QuestionReply.RESULT_KEY)
            .setLabel(QuestionReply.REPLY_INPUT_LABEL)
            .setAllowFreeFormInput(true)
            .build()
        return NotificationCompat.Action.Builder(
            R.drawable.ic_notification,
            QuestionReply.REPLY_ACTION_LABEL,
            replyPending,
        ).addRemoteInput(remoteInput).build()
    }

    /** Generic lock-screen version: never repeats the question prompt. */
    private fun buildQuestionPublicVersion(context: Context, notificationId: Int): Notification =
        NotificationCompat.Builder(context, CHANNEL_INPUT)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Question waiting")
            .setContentText("Open DSH Mobile to answer.")
            .setContentIntent(
                deepLinkPending(context, "dshmobile://inbox", notificationId xor 0x7E),
            )
            .setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()

    /**
     * Replaces the pending-question notification after the receiver tries to answer. Never claims
     * success for a failed send; the fallback opens the inbox and keeps a Reply action for a retry
     * when app lock is still off.
     */
    @SuppressLint("MissingPermission")
    fun questionReplyOutcome(
        context: Context,
        notificationId: Int,
        outcome: QuestionReply.Outcome,
        questionId: String? = null,
        sessionId: String? = null,
    ) {
        if (notificationId == 0) return
        ensureChannels(context)
        if (!hasPermission(context)) return
        val (title, text) = QuestionReply.copyFor(outcome)
        val openInbox = deepLinkPending(context, "dshmobile://inbox", notificationId xor 0x3C)
        val appLockEnabled = context
            .getSharedPreferences("dsh-presentation", Context.MODE_PRIVATE)
            .getBoolean("app_lock", false)
        val notification = NotificationCompat.Builder(context, CHANNEL_INPUT)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openInbox)
            .setAutoCancel(true)
            .setGroup(NOTIFICATION_GROUP)
            .apply {
                if (outcome != QuestionReply.Outcome.SENT) {
                    addAction(R.drawable.ic_notification, "Open inbox", openInbox)
                }
                if (outcome == QuestionReply.Outcome.FAILED &&
                    questionId != null && sessionId != null &&
                    QuestionReply.retryActionAvailable(appLockEnabled)
                ) {
                    addAction(
                        buildQuestionReplyAction(context, notificationId, questionId, sessionId),
                    )
                }
            }
            .build()
        runCatching {
            NotificationManagerCompat.from(context).notify(notificationId, notification)
        }
        updateGroupSummary(context)
    }

    private fun deepLinkPending(context: Context, uri: String, requestCode: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = Uri.parse(uri)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
