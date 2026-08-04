package vn.edu.haui.hvs.safedrive.voice

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import vn.edu.haui.hvs.safedrive.core.model.VoiceState
import vn.edu.haui.hvs.safedrive.domain.repository.TtsController
import vn.edu.haui.hvs.safedrive.domain.repository.TtsState

/** `internal`, not `private`: also read by `SpeechRecognizerWakeWordDetector`'s own
 * `isCommandCaptureActive` check (wired from `WakeWordListeningService`) so both the reactive stop here
 * and the synchronous last-moment check there agree on exactly what "a command session is active" means. */
internal val LISTENABLE_VOICE_STATES = setOf(VoiceState.IDLE, VoiceState.ERROR)

/** How long to wait before retrying the ambient detector after it reports a real (non-silent) error —
 * see [WakeWordSessionCoordinator.onDetectorFailure]'s KDoc for why a retry is needed at all. Long enough
 * not to spam a persistently-failing engine, short enough that "Mai ơi" recovers within one
 * short drive rather than staying deaf for the rest of the trip. */
private const val ERROR_RETRY_DELAY_MS = 10_000L

/**
 * Plain-Kotlin orchestration for [WakeWordDetector] — no `Service`/`Context` dependency, so it is
 * unit-testable like everything else in this voice pipeline (mirrors why
 * [AndroidSpeechRecognizerController]'s own logic is separated from the platform `SpeechRecognizer`).
 * [vn.edu.haui.hvs.safedrive.service.WakeWordListeningService] merely constructs this and delegates
 * [start]/[stop] to it.
 *
 * Reactively watches [voiceController]'s and [ttsController]'s hot [kotlinx.coroutines.flow.StateFlow]s:
 * the wake-word detector runs whenever [VoiceController.state] is [VoiceState.IDLE] or [VoiceState.ERROR]
 * (i.e. no command capture is in flight) *and* [TtsController.state] is not [TtsState.SPEAKING] (so the
 * mic never transcribes SafeDrive's own reply as a new command) — and stops the instant either condition
 * no longer holds. This single reactive loop replaces the old
 * `VoiceAssistantCoordinator.resumeWakeListeningAfterResponse()`, which only rearmed after a
 * *successfully routed* transcript: a blank/timeout/error result during command capture left the old
 * design silently deaf, since nothing else ever called `startWakeWord()` again. Reacting to the state
 * itself — not to a specific turn's outcome — closes that gap: every path back to `IDLE`/`ERROR`, from
 * any cause, resumes ambient listening.
 *
 * Also self-heals after the detector itself reports a real error (network blip, transient audio
 * conflict) — see [onDetectorFailure]. Without this, a live device was observed going silently and
 * permanently deaf: the detector's own error handling stops it internally, and nothing in the `combine`
 * loop above ever re-emits on its own, since neither [VoiceController.state] nor
 * [vn.edu.haui.hvs.safedrive.domain.repository.TtsController.state] necessarily changes just because the
 * *detector* failed — so "resume on the next state change" alone left ambient listening dead until the
 * user happened to trigger some unrelated state transition (a manual mic tap, a TTS reply).
 */
class WakeWordSessionCoordinator(
    private val voiceController: VoiceController,
    private val ttsController: TtsController,
    private val wakeWordDetector: WakeWordDetector,
    private val externalScope: CoroutineScope,
    private val onDetectorError: (String) -> Unit,
) {
    private var job: Job? = null
    private var retryJob: Job? = null

    fun start() {
        if (job != null) return
        job = combine(voiceController.state, ttsController.state) { voice, tts -> voice.state to tts }
            .onEach { (voiceState, ttsState) ->
                retryJob?.cancel()
                val shouldListen = voiceState in LISTENABLE_VOICE_STATES && ttsState != TtsState.SPEAKING
                if (shouldListen) {
                    wakeWordDetector.start(onWakeWordDetected = ::onWakeWordDetected, onError = ::onDetectorFailure)
                } else {
                    wakeWordDetector.stop()
                }
            }
            .launchIn(externalScope)
    }

    fun stop() {
        job?.cancel()
        job = null
        retryJob?.cancel()
        retryJob = null
        wakeWordDetector.stop()
    }

    private fun onWakeWordDetected() {
        retryJob?.cancel()
        // Released before claiming the command-capture mic session, not after — the ambient detector
        // and the command recognizer must never hold the microphone at the same instant.
        wakeWordDetector.stop()
        voiceController.startListening(screen = "background")
    }

    /**
     * A real (non-silent) detector error -- e.g. `SpeechRecognizerWakeWordDetector` treats anything other
     * than no-match/timeout this way -- stops the detector internally and would otherwise leave it dead
     * forever (see this class's own KDoc for why the `combine` loop alone can't be relied on to notice).
     * Reports the error outward via [onDetectorError] (diagnostics only -- never fatal to the service
     * itself), then schedules exactly one retry [ERROR_RETRY_DELAY_MS] later, re-checking that ambient
     * listening is still actually appropriate (state didn't move to something non-listenable, wake word
     * wasn't disabled, TTS didn't start speaking) before restarting -- never a blind, unconditional retry.
     * A later, real state change (handled in [start]'s own `onEach`) always supersedes this by cancelling
     * [retryJob] first, so a stale retry can never fight a fresher, legitimate start/stop decision.
     */
    private fun onDetectorFailure(message: String) {
        wakeWordDetector.stop()
        onDetectorError(message)
        retryJob?.cancel()
        retryJob = externalScope.launch {
            delay(ERROR_RETRY_DELAY_MS)
            val voiceState = voiceController.state.value.state
            val ttsState = ttsController.state.value
            if (voiceState in LISTENABLE_VOICE_STATES && ttsState != TtsState.SPEAKING) {
                wakeWordDetector.start(onWakeWordDetected = ::onWakeWordDetected, onError = ::onDetectorFailure)
            }
        }
    }
}
