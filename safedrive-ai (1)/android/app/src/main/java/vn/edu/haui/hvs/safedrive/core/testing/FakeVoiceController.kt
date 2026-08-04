package vn.edu.haui.hvs.safedrive.core.testing

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import vn.edu.haui.hvs.safedrive.core.model.VoiceState
import vn.edu.haui.hvs.safedrive.core.observability.VoiceCaptureTimings
import vn.edu.haui.hvs.safedrive.voice.VoiceController
import vn.edu.haui.hvs.safedrive.voice.VoiceInputEvent
import vn.edu.haui.hvs.safedrive.voice.VoiceUiState

/**
 * In-memory [VoiceController] test double — no `SpeechRecognizer` Android framework dependency, so
 * it is usable from both `src/test` and `src/androidTest`
 * (docs/android-mvp-plan/08-claude-prompts.md, Prompt 5: "Viết test bằng fake controller"). Owns only
 * mic/STT state, matching the real controller post-W2 (docs/android-mvp-plan/12) — TTS is
 * [FakeTtsController]'s job.
 *
 * [events] is `Channel`-backed, matching [vn.edu.haui.hvs.safedrive.voice.AndroidSpeechRecognizerController]'s
 * delivery guarantee (remediation item 7): a transcript emitted before any collector subscribes is
 * queued, not lost, so tests can exercise "emit then start collecting" ordering realistically.
 */
class FakeVoiceController(initial: VoiceUiState = VoiceUiState()) : VoiceController {
    private val _state = MutableStateFlow(initial)
    override val state: StateFlow<VoiceUiState> = _state

    private val _events = Channel<VoiceInputEvent>(capacity = 4)
    override val events: Flow<VoiceInputEvent> = _events.receiveAsFlow()

    /** Auto-increments a fresh generation per [emitFinalTranscript] call when the caller does not name
     * one explicitly (independent re-audit follow-up, eighth pass) — starts at 1 so it never collides
     * with [VoiceUiState]'s own default `generation = 0`. Tests that need to force a specific ordering
     * (e.g. two sessions racing) pass `generation` explicitly instead of relying on this counter. */
    private val generationCounter = AtomicLong(0)

    var startListeningCallCount = 0
        private set
    var cancelCallCount = 0
        private set
    var finishListeningCallCount = 0
        private set
    var clearProcessingStateCallCount = 0
        private set
    var reassignProcessingOwnerCallCount = 0
        private set
    var lastScreen: String? = null
        private set

    private val _reassignProcessingOwnerCalls = mutableListOf<Pair<Long, Long>>()

    /** Every `(expectedCurrentGeneration, newOwnerGeneration)` pair ever passed to
     * [reassignProcessingOwner], in call order (independent re-audit follow-up, ninth pass) — lets a test
     * verify a *caller* (e.g. [vn.edu.haui.hvs.safedrive.domain.usecase.VoiceAssistantCoordinator]) wires
     * the right generation into the right argument position, independent of whether this fake's own
     * compare-and-set logic happens to accept or reject the call (that half is covered separately by
     * tests that exercise this fake directly). */
    val reassignProcessingOwnerCalls: List<Pair<Long, Long>> get() = _reassignProcessingOwnerCalls

    fun setState(newState: VoiceUiState) {
        _state.value = newState
    }

    /** Simulates the recognizer producing a final transcript, as a test would drive it. Mirrors
     * [vn.edu.haui.hvs.safedrive.voice.AndroidSpeechRecognizerController]'s real
     * `RecognitionListener.onResults()` (independent re-audit follow-up, second pass): the real
     * controller transitions [state] to [VoiceState.PROCESSING] *before* the event is even routed —
     * without this, a test driving this fake could never observe (or accidentally violate) the real
     * "which turn owns the Processing indicator" contract a rejected, single-flight-busy voice event
     * must respect (see [vn.edu.haui.hvs.safedrive.domain.usecase.VoiceAssistantCoordinator]). Also
     * mirrors the real controller's `trySend` failure fallback (independent re-audit follow-up, sixth
     * pass): if the bounded buffer is genuinely full, the real controller never leaves the UI stuck
     * showing `PROCESSING` for a transcript that will never actually be routed/cleared — it falls back
     * to [VoiceState.ERROR] instead.
     *
     * [generation] (independent re-audit follow-up, eighth pass) is stamped onto both [state] and the
     * enqueued [VoiceInputEvent] together, exactly like the real controller's `onResults()` — defaults
     * to a fresh, auto-incrementing value per call so ordinary tests that don't care about generations
     * still get a distinct one each time, while a pre-claim-race test can pass explicit generations for
     * two "sessions" to force a specific ordering deterministically.
     *
     * Returns whether the event was actually queued (`false` only if the bounded buffer is genuinely
     * full). */
    fun emitFinalTranscript(
        text: String,
        screen: String = "assistant",
        captureTimings: VoiceCaptureTimings? = null,
        generation: Long = generationCounter.incrementAndGet(),
    ): Boolean {
        _state.value = _state.value.copy(state = VoiceState.PROCESSING, finalTranscript = text, partialTranscript = "", generation = generation)
        val emitted = _events.trySend(VoiceInputEvent(text, screen, captureTimings, generation)).isSuccess
        if (!emitted) {
            _state.value = _state.value.copy(state = VoiceState.ERROR, errorMessage = "Không thể xử lý yêu cầu, vui lòng thử lại")
        }
        return emitted
    }

    override fun startListening(screen: String) {
        startListeningCallCount++
        lastScreen = screen
        _state.value = _state.value.copy(state = VoiceState.LISTENING)
    }

    override fun cancel() {
        cancelCallCount++
        _state.value = VoiceUiState(state = VoiceState.IDLE)
    }

    override fun finishListening() {
        finishListeningCallCount++
    }

    override fun clearProcessingState(expectedGeneration: Long) {
        clearProcessingStateCallCount++
        if (_state.value.state == VoiceState.PROCESSING && _state.value.generation == expectedGeneration) {
            _state.value = _state.value.copy(state = VoiceState.IDLE)
        }
    }

    override fun reassignProcessingOwner(expectedCurrentGeneration: Long, newOwnerGeneration: Long) {
        reassignProcessingOwnerCallCount++
        _reassignProcessingOwnerCalls.add(expectedCurrentGeneration to newOwnerGeneration)
        if (_state.value.state == VoiceState.PROCESSING && _state.value.generation == expectedCurrentGeneration) {
            _state.value = _state.value.copy(generation = newOwnerGeneration)
        }
    }
}
