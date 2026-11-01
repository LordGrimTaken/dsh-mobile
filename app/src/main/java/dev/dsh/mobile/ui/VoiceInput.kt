package dev.dsh.mobile.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import java.util.Locale

/**
 * UI snapshot of a continuous dictation session, rendered by the chat composer.
 *
 * [listening] stays true between utterances (auto-restart) until the user stops or an error ends
 * the session; [starting] is true while the recognizer is being (re)started; [status] carries the
 * short human-readable state line; [partial] is the in-flight hypothesis that is already mirrored
 * into the composer draft; [error] is a one-shot message the screen moves into its error row.
 */
data class VoiceDictationState(
    val listening: Boolean = false,
    val starting: Boolean = false,
    val status: String? = null,
    val partial: String = "",
    val error: String? = null,
)

/**
 * Continuous dictation on top of [SpeechRecognizer].
 *
 * The old one-shot `ACTION_RECOGNIZE_SPEECH` dialog ended after every silence gap, which is the
 * "it cuts out" behaviour this replaces. This controller keeps one recognizer alive, commits every
 * final utterance into the draft and immediately starts the next utterance while the user is still
 * in listening mode.
 *
 * Text merging rules (see [mergeDictation]): the draft the user had when dictation started is kept
 * as a snapshot, finalized utterances are appended once to the committed segment list, and the live
 * partial replaces only the tentative tail — so a restart can never duplicate words.
 *
 * All public entry points must be called from the main thread (Compose callbacks are); the
 * recognizer is created there as required by the speech framework. State changes are Compose state,
 * so the composer recomposes as partials arrive.
 */
class VoiceDictationController(context: Context) {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())

    var state by mutableStateOf(VoiceDictationState())
        private set

    private var recognizer: SpeechRecognizer? = null
    private var active = false
    private var disposed = false
    /** Guards against an engine delivering the same utterance's final result twice. */
    private var utteranceResultHandled = false
    /** True between a final/error and the next start; late partials of that utterance are ignored. */
    private var awaitingRestart = false

    private var baseDraft = ""
    private val committed = mutableListOf<String>()
    private var pendingPartial = ""

    private var onPreview: (String) -> Unit = {}
    private var onCommit: (String) -> Unit = {}
    /** Prefer an installed on-device recognizer so a VPN/network stall cannot cut dictation. */
    private var preferOffline = false
    /** BCP-47 language tag; blank means the device/system language. */
    private var languageTag = ""

    // Consecutive unattended restarts per error class; reset as soon as speech is recognized.
    private var silenceRestarts = 0
    private var networkRestarts = 0
    private var busyRestarts = 0
    private var otherRestarts = 0

    private val restartRunnable = Runnable {
        if (active && !disposed) startListening()
    }

    /** Starts a continuous session on top of [baseDraft]; the callbacks replace the composer draft. */
    fun start(
        baseDraft: String,
        preferOffline: Boolean = false,
        languageTag: String = "",
        onPreview: (String) -> Unit,
        onCommit: (String) -> Unit,
    ) {
        if (disposed || active) return
        this.baseDraft = baseDraft
        this.preferOffline = preferOffline
        this.languageTag = languageTag
        this.onPreview = onPreview
        this.onCommit = onCommit
        committed.clear()
        pendingPartial = ""
        utteranceResultHandled = false
        resetRestartCounters()
        if (!recognitionAvailable()) {
            state = VoiceDictationState(
                error = "No speech recognizer is installed on this device.",
            )
            return
        }
        active = true
        state = VoiceDictationState(
            listening = true,
            starting = true,
            status = STATUS_LISTENING,
        )
        startListening()
    }

    /** Stops a session; the visible partial tail is committed so no recognized words are lost. */
    fun stop() {
        if (!active) return
        finish(commitPartial = true)
    }

    /** Hands a surfaced error back after the screen has shown it. */
    fun clearError() {
        if (state.error != null) state = state.copy(error = null)
    }

    /** Releases the microphone and recognizer; called when the chat screen leaves composition. */
    fun dispose() {
        if (disposed) return
        disposed = true
        active = false
        mainHandler.removeCallbacks(restartRunnable)
        runCatching { recognizer?.cancel() }
        runCatching { recognizer?.destroy() }
        recognizer = null
        committed.clear()
        pendingPartial = ""
    }

    private fun finish(commitPartial: Boolean) {
        val wasActive = active
        active = false
        mainHandler.removeCallbacks(restartRunnable)
        runCatching { recognizer?.cancel() }
        val tail = pendingPartial.trim()
        val commitText = if (commitPartial && wasActive && tail.isNotEmpty()) composeDraft() else null
        committed.clear()
        pendingPartial = ""
        utteranceResultHandled = false
        awaitingRestart = false
        state = state.copy(listening = false, starting = false, status = null, partial = "")
        if (commitText != null) onCommit(commitText)
    }

    private fun startListening() {
        if (!active || disposed) return
        val engine = runCatching { ensureRecognizer() }.getOrNull()
        if (engine == null) {
            failed("Speech recognition isn't available on this device.")
            return
        }
        utteranceResultHandled = false
        awaitingRestart = false
        state = state.copy(starting = true, status = STATUS_LISTENING)
        val started = runCatching { engine.startListening(recognitionIntent()) }.isSuccess
        if (!started) {
            busyRestarts += 1
            if (busyRestarts > MAX_BUSY_RESTARTS) {
                failed("The speech recognizer is busy — tap the mic to try again.")
            } else {
                scheduleRestart(BUSY_RETRY_MS)
            }
        }
    }

    private fun handleError(error: Int) {
        // Preserve words already shown: commit the tentative tail before restarting or stopping.
        commitPendingPartial()
        // The utterance that errored is finished; ignore results/partials it may still deliver so a
        // late final cannot be committed twice.
        utteranceResultHandled = true
        awaitingRestart = true
        when (error) {
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                failed("Microphone permission needed to dictate.")
            }

            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
                busyRestarts += 1
                if (busyRestarts > MAX_BUSY_RESTARTS) {
                    failed("The speech recognizer is busy — tap the mic to try again.")
                } else {
                    scheduleRestart(BUSY_RETRY_MS)
                }
            }

            SpeechRecognizer.ERROR_NETWORK,
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
            SpeechRecognizer.ERROR_SERVER -> {
                networkRestarts += 1
                if (networkRestarts > MAX_NETWORK_RESTARTS) {
                    failed("Network error — speech recognition needs a connection.")
                } else {
                    state = state.copy(status = STATUS_RETRYING)
                    scheduleRestart(backoff(networkRestarts))
                }
            }

            SpeechRecognizer.ERROR_NO_MATCH,
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                silenceRestarts += 1
                if (silenceRestarts > MAX_SILENCE_RESTARTS) {
                    finish(commitPartial = false)
                    state = state.copy(
                        status = "Dictation paused after silence — tap the mic to resume.",
                    )
                } else {
                    scheduleRestart(backoff(silenceRestarts))
                }
            }

            // ERROR_LANGUAGE_UNAVAILABLE (12), _NOT_SUPPORTED (13), _CANNOT_CHECK_SUPPORT (14).
            // Literals keep this safe on minSdk 26; the constants are API 31+.
            12, 13, 14 -> {
                failed(
                    if (preferOffline) {
                        "Offline speech isn't available for your language. Turn off " +
                            "\"Prefer offline recognition\" in Settings → Voice."
                    } else {
                        "Speech recognition language isn't supported on this device."
                    },
                )
            }

            else -> {
                otherRestarts += 1
                if (otherRestarts > MAX_OTHER_RESTARTS) {
                    failed("Dictation stopped (recognizer error $error).")
                } else {
                    scheduleRestart(backoff(otherRestarts))
                }
            }
        }
    }

    private fun failed(message: String) {
        finish(commitPartial = true)
        state = state.copy(error = message)
    }

    private fun commitPendingPartial() {
        val tail = pendingPartial.trim()
        if (tail.isEmpty()) return
        pendingPartial = ""
        committed.add(tail)
        onCommit(composeDraft())
    }

    private fun scheduleRestart(delayMs: Long) {
        if (!active || disposed) return
        mainHandler.removeCallbacks(restartRunnable)
        mainHandler.postDelayed(restartRunnable, delayMs)
    }

    /** Capped exponential backoff so a dead microphone does not burn the battery. */
    private fun backoff(attempt: Int): Long {
        val shift = (attempt - 1).coerceIn(0, 3)
        return (BASE_RETRY_MS shl shift).coerceAtMost(MAX_RETRY_MS)
    }

    private fun composeDraft(): String = mergeDictation(baseDraft, committed, pendingPartial)

    private fun resetRestartCounters() {
        silenceRestarts = 0
        networkRestarts = 0
        busyRestarts = 0
        otherRestarts = 0
    }

    private fun recognitionAvailable(): Boolean =
        runCatching { SpeechRecognizer.isRecognitionAvailable(appContext) }.getOrDefault(false)

    private fun ensureRecognizer(): SpeechRecognizer {
        recognizer?.let { return it }
        val created = SpeechRecognizer.createSpeechRecognizer(appContext)
            ?: throw IllegalStateException("Speech recognizer unavailable")
        created.setRecognitionListener(listener)
        recognizer = created
        return created
    }

    /** Free-form English/device-language dictation tuned for many short utterances, not a dialog. */
    private fun recognitionIntent(): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag.ifBlank {
            Locale.getDefault().toLanguageTag()
        })
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, preferOffline)
        // Keep a single utterance alive through natural pauses; the controller restarts the rest.
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, MINIMUM_LENGTH_MS)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, COMPLETE_SILENCE_MS)
        putExtra(
            RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
            POSSIBLY_COMPLETE_SILENCE_MS,
        )
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            if (!active || disposed) return
            state = state.copy(starting = false, status = STATUS_LISTENING)
        }

        override fun onBeginningOfSpeech() {
            if (!active || disposed) return
            state = state.copy(starting = false, status = STATUS_LISTENING)
        }

        override fun onRmsChanged(rmsdB: Float) = Unit

        override fun onBufferReceived(buffer: ByteArray?) = Unit

        override fun onEndOfSpeech() {
            if (!active || disposed || awaitingRestart) return
            state = state.copy(status = "Processing…")
        }

        override fun onError(error: Int) {
            if (disposed) return
            state = state.copy(starting = false)
            if (!active) return
            handleError(error)
        }

        override fun onResults(results: Bundle?) {
            if (!active || disposed) return
            if (utteranceResultHandled) return
            utteranceResultHandled = true
            awaitingRestart = true
            resetRestartCounters()
            val spoken = firstResult(results)
            // pendingPartial is superseded by the final hypothesis; it is never appended on top.
            pendingPartial = ""
            if (spoken.isNotEmpty()) {
                committed.add(spoken)
                onCommit(composeDraft())
            }
            state = state.copy(starting = false, status = STATUS_LISTENING, partial = "")
            scheduleRestart(RESTART_AFTER_RESULT_MS)
        }

        override fun onPartialResults(partialResults: Bundle?) {
            if (!active || disposed || awaitingRestart) return
            val text = firstResult(partialResults)
            if (text.isEmpty()) return
            pendingPartial = text
            silenceRestarts = 0
            onPreview(composeDraft())
            state = state.copy(starting = false, status = STATUS_LISTENING, partial = text)
        }

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    private fun firstResult(bundle: Bundle?): String =
        bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()
            ?.trim()
            .orEmpty()
}

/** Remembers a [VoiceDictationController] and always releases it when the screen leaves. */
@Composable
fun rememberVoiceDictation(): VoiceDictationController {
    val context = LocalContext.current.applicationContext
    val controller = remember(context) { VoiceDictationController(context) }
    DisposableEffect(controller) {
        onDispose { controller.dispose() }
    }
    return controller
}

/**
 * Joins the pre-dictation draft with finalized utterances and the live partial without
 * duplicating the tentative tail. Pure function so the merge rules are easy to reason about.
 */
internal fun mergeDictation(base: String, committed: List<String>, partial: String): String {
    val spoken = (committed.asSequence() + sequenceOf(partial))
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .joinToString(" ")
    return when {
        spoken.isEmpty() -> base
        base.isBlank() -> spoken
        else -> base.trimEnd() + " " + spoken
    }
}

private const val STATUS_LISTENING = "Listening…"
private const val STATUS_RETRYING = "Network problem — retrying…"

/** Pause between a final utterance and the automatic start of the next one. */
private const val RESTART_AFTER_RESULT_MS = 200L

/** A recognizer that is still tearing down the previous session is retried after this. */
private const val BUSY_RETRY_MS = 300L

private const val BASE_RETRY_MS = 400L
private const val MAX_RETRY_MS = 3200L

private const val MAX_SILENCE_RESTARTS = 4
private const val MAX_NETWORK_RESTARTS = 5
private const val MAX_BUSY_RESTARTS = 5
private const val MAX_OTHER_RESTARTS = 4

// Silence/timeout extras: natural pauses are tolerated, but the controller still restarts quickly.
private const val MINIMUM_LENGTH_MS = 1000L
private const val COMPLETE_SILENCE_MS = 1500L
private const val POSSIBLY_COMPLETE_SILENCE_MS = 3000L
