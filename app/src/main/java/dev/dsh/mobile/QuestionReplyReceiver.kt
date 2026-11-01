package dev.dsh.mobile

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import dev.dsh.mobile.data.AndroidKeystoreTokenCipher
import dev.dsh.mobile.data.HostProfile
import dev.dsh.mobile.data.HostProfileStore
import dev.dsh.mobile.data.QuestionReply
import dev.dsh.mobile.data.SharedPreferencesHostKeyValueStore
import dev.dsh.mobile.net.DshApiClient
import dev.dsh.mobile.net.DshApiException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * Background handler for the notification's free-text question reply.
 *
 * Runs WITHOUT starting the app shell: extracts the RemoteInput text, re-checks the app-lock guard,
 * loads the active host's encrypted token, POSTs the answer with the existing API client, then
 * replaces the notification with success/failure copy.
 *
 * Security: the answer and token are never logged or persisted; the receiver is exported=false and
 * only the notification's mutable PendingIntent can reach it. Missing/blank answers, a missing
 * profile, read-only hosts (501/403), unauthorized, rate-limited and network failures all report
 * "Couldn't send answer" rather than claiming success; stale questions (404/409/already-answered)
 * are treated idempotently as "Already answered".
 */
class QuestionReplyReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // R1: defense-in-depth on top of exported=false: only our private reply action is trusted.
        if (!QuestionReply.acceptsIntent(intent.action)) return

        val questionId = intent.getStringExtra(QuestionReply.EXTRA_QUESTION_ID)
            ?.trim()
            .orEmpty()
        val sessionId = intent.getStringExtra(QuestionReply.EXTRA_SESSION_ID).orEmpty()
        val notificationId = intent.getIntExtra(QuestionReply.EXTRA_NOTIFICATION_ID, 0)
        val rawAnswer = RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(QuestionReply.RESULT_KEY)

        if (!QuestionReply.hasUsableReply(questionId, notificationId, rawAnswer)) {
            // Bad/partial broadcast: keep the notification actionable instead of claiming success.
            DshNotifications.questionReplyOutcome(
                context,
                notificationId,
                QuestionReply.Outcome.FAILED,
                questionId = questionId.takeIf { it.isNotBlank() },
                sessionId = sessionId.takeIf { it.isNotBlank() },
            )
            return
        }
        val answer = requireNotNull(QuestionReply.normalizeAnswer(rawAnswer))

        val pendingResult = goAsync()
        val appContext = context.applicationContext
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            var outcome = QuestionReply.Outcome.FAILED
            try {
                // App lock may have been enabled after the notification was posted.
                val appLockEnabled = appContext
                    .getSharedPreferences("dsh-presentation", Context.MODE_PRIVATE)
                    .getBoolean("app_lock", false)
                if (appLockEnabled) {
                    outcome = QuestionReply.Outcome.FAILED
                } else {
                    val profile = activeHostProfile(appContext)
                    if (profile == null) {
                        outcome = QuestionReply.Outcome.FAILED
                    } else {
                        outcome = try {
                            // R4: stay inside the goAsync budget so finish() always runs.
                            withTimeout(QuestionReply.SEND_TIMEOUT_MS) {
                                DshApiClient(profile.baseUrl, profile.token)
                                    .answerQuestion(questionId, answer)
                            }
                            QuestionReply.Outcome.SENT
                        } catch (timeout: TimeoutCancellationException) {
                            QuestionReply.Outcome.FAILED
                        } catch (error: Exception) {
                            if (isAlreadyAnswered(error)) {
                                QuestionReply.Outcome.ALREADY_ANSWERED
                            } else {
                                QuestionReply.Outcome.FAILED
                            }
                        }
                    }
                }
            } finally {
                runCatching {
                    DshNotifications.questionReplyOutcome(
                        appContext,
                        notificationId,
                        outcome,
                        questionId = questionId,
                        sessionId = sessionId,
                    )
                }
                pendingResult.finish()
            }
        }
    }

    /** Active host + decrypted token; null when nothing is paired or the Keystore key is gone. */
    private fun activeHostProfile(context: Context): HostProfile? = runCatching {
        val store = HostProfileStore(
            store = SharedPreferencesHostKeyValueStore(context),
            cipher = AndroidKeystoreTokenCipher(),
        )
        val snapshot = store.load()
        val activeId = snapshot.activeId ?: return@runCatching null
        snapshot.profiles.firstOrNull { profile -> profile.id == activeId }
    }.getOrNull()

    /** Stale/duplicate replies are idempotent: the question is already resolved elsewhere. */
    private fun isAlreadyAnswered(error: Exception): Boolean = when (error) {
        is DshApiException.NotFound -> true
        is DshApiException.Server -> error.httpStatus == 404 || error.httpStatus == 409
        is DshApiException.InvalidRequest -> {
            val code = error.code.orEmpty().lowercase()
            code == "already_answered" ||
                code == "already_resolved" ||
                code == "question_resolved" ||
                code == "conflict"
        }

        else -> false
    }
}
