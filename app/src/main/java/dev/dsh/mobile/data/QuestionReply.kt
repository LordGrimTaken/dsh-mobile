package dev.dsh.mobile.data

/**
 * Free-text question replies delivered from a notification (Android RemoteInput).
 *
 * Pure helpers only: the notification builder uses [RESULT_KEY]/[EXTRA_*] and
 * [canOfferInlineReply], the broadcast receiver uses [normalizeAnswer]/[copyFor], and both use the
 * stable id/request-code helpers so duplicate taps hit the same notification and PendingIntent.
 *
 * Security: an inline reply bypasses opening the UI, so it is only offered when app lock is OFF.
 * The receiver additionally re-checks the app-lock pref before sending. Answers are never logged or
 * stored; the host token is only decrypted inside the receiver process.
 */
object QuestionReply {

    /** RemoteInput result key for the typed answer. */
    const val RESULT_KEY = "dev.dsh.mobile.QUESTION_REPLY_TEXT"

    /** Private broadcast action — defense-in-depth on top of the receiver being exported=false. */
    const val ACTION_REPLY = "dev.dsh.mobile.action.QUESTION_REPLY"

    /** Intent extras: IDs only, never the answer or token. */
    const val EXTRA_QUESTION_ID = "question_id"
    const val EXTRA_SESSION_ID = "session_id"
    const val EXTRA_NOTIFICATION_ID = "notification_id"

    /** Must match the app's existing free-text answer cap (MAX_QUESTION_ANSWER_CHARS). */
    const val MIN_ANSWER_CHARS = 1
    const val MAX_ANSWER_CHARS = MAX_QUESTION_ANSWER_CHARS

    /** goAsync() budget is ~10s; leave headroom so finish() and the notification outcome run. */
    const val SEND_TIMEOUT_MS = 8_500L

    const val REPLY_ACTION_LABEL = "Reply"
    const val REPLY_INPUT_LABEL = "Type your answer"

    const val SUCCESS_TITLE = "Answer sent"
    const val SUCCESS_TEXT = "Your answer was sent to the host."
    const val ALREADY_TITLE = "Already answered"
    const val ALREADY_TEXT = "This question was answered elsewhere."
    const val FAILURE_TITLE = "Couldn't send answer"
    const val FAILURE_TEXT = "Couldn't send answer - open the app to try again."

    enum class Outcome { SENT, ALREADY_ANSWERED, FAILED }

    /** Trims and bounds a RemoteInput answer; null for blank or overlong input. */
    fun normalizeAnswer(raw: CharSequence?): String? {
        val trimmed = raw?.toString()?.trim().orEmpty()
        if (trimmed.length < MIN_ANSWER_CHARS || trimmed.length > MAX_ANSWER_CHARS) return null
        return trimmed
    }

    /**
     * Whether the notification may carry a free-text Reply action. App lock bypasses the UI, so it
     * is never offered when app lock is enabled; read-only hosts answer on the desktop instead.
     */
    fun canOfferInlineReply(appLockEnabled: Boolean, questionsReadOnly: Boolean): Boolean =
        !appLockEnabled && !questionsReadOnly

    /** R7: a failed-send notification may still offer Reply to retry (same app-lock guard). */
    fun retryActionAvailable(appLockEnabled: Boolean): Boolean = !appLockEnabled

    /** R1: only the private reply action is trusted (exported=false + explicit action check). */
    fun acceptsIntent(action: String?): Boolean = action == ACTION_REPLY

    /** Bad/partial broadcasts fail before any network/profile work. */
    fun hasUsableReply(
        questionId: String?,
        notificationId: Int,
        answer: CharSequence?,
    ): Boolean = !questionId.isNullOrBlank() &&
        notificationId != 0 &&
        normalizeAnswer(answer) != null

    /** Stable notification id per pending question (matches the input-needed notification). */
    fun notificationIdFor(sessionId: String, questionId: String): Int =
        ("input:question:$sessionId:$questionId").hashCode()

    /** Stable PendingIntent request code for the reply broadcast. */
    fun requestCodeFor(notificationId: Int, questionId: String): Int =
        notificationId xor questionId.hashCode()

    /** Title/text shown after the receiver finishes, or while it fails. */
    fun copyFor(outcome: Outcome): Pair<String, String> = when (outcome) {
        Outcome.SENT -> SUCCESS_TITLE to SUCCESS_TEXT
        Outcome.ALREADY_ANSWERED -> ALREADY_TITLE to ALREADY_TEXT
        Outcome.FAILED -> FAILURE_TITLE to FAILURE_TEXT
    }
}
