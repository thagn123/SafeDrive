package vn.edu.haui.hvs.safedrive.core.testing

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import vn.edu.haui.hvs.safedrive.domain.repository.TtsController
import vn.edu.haui.hvs.safedrive.domain.repository.TtsState
import vn.edu.haui.hvs.safedrive.domain.repository.TtsUtteranceEvent

/** In-memory [TtsController] test double — no Android `TextToSpeech` dependency.
 *
 * @param autoEmitStartAtMs when non-null, simulates an engine whose `onStart` callback fires
 * *synchronously, inside [speak] itself* — the worst-case timing for the race
 * [vn.edu.haui.hvs.safedrive.domain.usecase.AssistantTurnCoordinator.awaitTtsStarted] guards against
 * (remediation item 4): if the coordinator only started collecting [events] *after* calling [speak],
 * this emission would already have happened and be lost forever (replay-0 stream, no late delivery). */
class FakeTtsController(
    initial: TtsState = TtsState.READY,
    private val autoEmitStartAtMs: (() -> Long)? = null,
) : TtsController {
    private val _state = MutableStateFlow(initial)
    override val state: StateFlow<TtsState> = _state

    private val _events = MutableSharedFlow<TtsUtteranceEvent>(extraBufferCapacity = 4)
    override val events: SharedFlow<TtsUtteranceEvent> = _events

    var lastSpokenText: String? = null
        private set
    var lastUtteranceId: String? = null
        private set
    var speakCallCount = 0
        private set
    var stopCallCount = 0
        private set

    fun setState(newState: TtsState) {
        _state.value = newState
    }

    /** Simulates the platform's real "audio actually started" callback for a previously-spoken
     * utterance, so tests can assert on [vn.edu.haui.hvs.safedrive.core.observability.AssistantTurnMetrics.ttsStartedAtMs]
     * without an Android `TextToSpeech` engine. */
    fun emitStarted(utteranceId: String, startedAtMs: Long) {
        _events.tryEmit(TtsUtteranceEvent(utteranceId, startedAtMs))
    }

    override fun speak(text: String, utteranceId: String) {
        speakCallCount++
        lastSpokenText = text
        lastUtteranceId = utteranceId
        _state.value = TtsState.SPEAKING
        autoEmitStartAtMs?.let { computeStartedAtMs -> _events.tryEmit(TtsUtteranceEvent(utteranceId, computeStartedAtMs())) }
    }

    override fun stop() {
        stopCallCount++
        if (_state.value == TtsState.SPEAKING) _state.value = TtsState.READY
    }
}
