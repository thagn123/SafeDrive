package vn.edu.haui.hvs.safedrive.voice

import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.SpeechRecognizer

// Requires "mai" AND "ơi" adjacent, not bare "mai" alone: "mai" alone is an extremely common Vietnamese
// word ("ngày mai", "mai tính", "hẹn mai gặp" -- "tomorrow") and would false-trigger constantly in
// ordinary conversation. The full vocative "mai ơi" (calling out to "Mai" by name) is far more
// distinctive. "[oơ]i" tolerates the diacritic on "ơi" being transcribed as a plain "oi".
private val WAKE_PHRASE_PATTERN = Regex("\\bmai\\s+[oơ]i\\b", RegexOption.IGNORE_CASE)

/** The wake phrase is "Mai ơi" (chosen 2026-08-04, replacing first the original English "Hey SafeDrive",
 * then a "SafeDrive ơi" alternative, per explicit user direction): the physical test device has the
 * Vietnamese on-device speech-recognition language pack installed but not the English one (confirmed via
 * logcat -- vi-VN sessions initialize cleanly, en-US sessions fail with "Failed to get language pack...
 * error 13" on every single attempt), so recognizing an English phrase was never going to work reliably
 * on this hardware regardless of how well-tuned the rest of this pipeline is. Matching only requires
 * [WAKE_PHRASE_PATTERN] ("mai ơi") to appear anywhere in the transcript -- not an exact full-phrase match
 * -- so it tolerates extra words around it. `internal` (not `private`) so it's directly unit-testable
 * without a real `Bundle` — `android.os.Bundle` cannot carry real data in a plain JVM unit test
 * (`isReturnDefaultValues = true` stubs `getStringArrayList` to always return `null`), the same
 * limitation documented on `AndroidSpeechRecognizerControllerTest`. */
internal fun containsWakePhrase(text: String): Boolean = WAKE_PHRASE_PATTERN.containsMatchIn(text)

/**
 * Fallback [WakeWordDetector] built on Android's own [SpeechRecognizer], used when a dedicated
 * low-power keyword-spotting engine isn't available (no mature, no-account Android SDK currently
 * exists as a Porcupine replacement — see the "real background wake-word listening" plan). Keeps
 * listening continuously by restarting the recognizer on every silence/no-match ending, in `vi-VN` —
 * see [containsWakePhrase]'s KDoc for why this is Vietnamese, not English. Heavier on battery than a
 * purpose-built spotter (this runs full recognition sessions back-to-back, not just keyword detection),
 * but needs no third-party account or API key.
 *
 * Entirely separate from [AndroidSpeechRecognizerController]'s own recognizer instance. Two independent
 * safeguards keep them from holding the platform recognizer at the same instant: [stop] is always called
 * before a command-capture session starts ([AndroidSpeechRecognizerController.onBeforeListen],
 * [WakeWordSessionCoordinator.onWakeWordDetected]), *and* [isCommandCaptureActive] is re-checked here,
 * synchronously, immediately before every actual platform `startListening()` call — including this
 * detector's own self-triggered restarts. The second check exists because a live device was observed
 * losing the race even with the first one in place: this detector's *own* restart (queued moments earlier
 * by its previous session's `onResults`/`onError`, itself unrelated to any user action) could still reach
 * the platform recognizer a few milliseconds *after* a manual mic tap's command session had already begun
 * — `stop()` had already run by then, but the already-in-flight restart's own `listening` check had been
 * evaluated *before* that, so it proceeded anyway, producing a brief real collision
 * (`MICROPHONE_UNAVAILABLE`) on the physical test device. Re-checking the *other* controller's actual
 * current state at the last possible moment, right here, closes that gap regardless of which callback
 * queued first.
 */
class SpeechRecognizerWakeWordDetector(
    private val recognizerFactory: SpeechRecognizerFactory,
    private val mainThreadExecutor: MainThreadExecutor = AndroidMainThreadExecutor(),
    private val isCommandCaptureActive: () -> Boolean = { false },
) : WakeWordDetector {

    private var recognizer: PlatformSpeechRecognizer? = null
    private var listening = false
    private var onDetected: (() -> Unit)? = null
    private var onErrorCallback: ((String) -> Unit)? = null

    override fun start(onWakeWordDetected: () -> Unit, onError: (String) -> Unit) {
        if (listening) return
        listening = true
        onDetected = onWakeWordDetected
        onErrorCallback = onError
        mainThreadExecutor.execute { beginListeningSession() }
    }

    override fun stop() {
        listening = false
        mainThreadExecutor.execute {
            recognizer?.cancel()
            recognizer?.destroy()
            recognizer = null
        }
    }

    override fun destroy() = stop()

    private fun beginListeningSession() {
        if (!listening) return
        if (isCommandCaptureActive()) {
            // Don't error out -- this is an expected, routine collision (this restart just happened to
            // land while a command-capture session claimed the mic first), not a real failure. Simply
            // skip this attempt; WakeWordSessionCoordinator's own reactive stop will already be tearing
            // this detector down via stop(), and it resumes normally once the command session ends.
            return
        }
        if (!recognizerFactory.isRecognitionAvailable()) {
            listening = false
            onErrorCallback?.invoke("Thiết bị không hỗ trợ nhận diện giọng nói")
            return
        }
        recognizer?.destroy()
        val instance = recognizerFactory.create(listener())
        recognizer = instance
        // Deliberate middle ground, not the command-capture tuning and not "no timeout at all" — see
        // PlatformSpeechRecognizer.startListening's KDoc for the full history of why both extremes were
        // tried and rejected on the physical test device.
        instance.startListening(
            "vi-VN",
            completeSilenceMs = AMBIENT_COMPLETE_SILENCE_MS,
            possiblyCompleteSilenceMs = AMBIENT_POSSIBLY_COMPLETE_SILENCE_MS,
        )
    }

    private fun listener() = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit
        override fun onPartialResults(partialResults: Bundle?) = Unit

        override fun onError(error: Int) {
            if (!listening) return
            // Silence/no-match is the normal ambient state, not a real error — restart immediately
            // without ever surfacing it. Anything else (network, audio, permission) is real.
            if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                mainThreadExecutor.execute { beginListeningSession() }
            } else {
                listening = false
                onErrorCallback?.invoke(mapErrorCode(error))
            }
        }

        override fun onResults(results: Bundle?) {
            if (!listening) return
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
            if (containsWakePhrase(text)) {
                listening = false
                onDetected?.invoke()
            } else {
                // Recognized speech, but not the wake phrase — keep listening silently.
                mainThreadExecutor.execute { beginListeningSession() }
            }
        }
    }

    private fun mapErrorCode(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Lỗi mạng khi nhận diện giọng nói"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Thiếu quyền microphone"
        SpeechRecognizer.ERROR_AUDIO -> "Lỗi ghi âm"
        else -> "Lỗi nhận diện giọng nói"
    }
}
