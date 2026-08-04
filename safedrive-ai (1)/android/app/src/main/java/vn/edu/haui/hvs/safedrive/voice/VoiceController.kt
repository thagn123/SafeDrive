package vn.edu.haui.hvs.safedrive.voice

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import vn.edu.haui.hvs.safedrive.core.model.VoiceState
import vn.edu.haui.hvs.safedrive.core.observability.VoiceCaptureTimings

/**
 * Voice UI state per docs/android-mvp-plan/05-voice-emergency.md. [generation] increments on every
 * new listening session so a stale recognizer callback can be ignored by the collector.
 */
data class VoiceUiState(
    val state: VoiceState = VoiceState.IDLE,
    /** True only while the foreground recognizer is waiting for the wake phrase, not a command. */
    val waitingForWakePhrase: Boolean = false,
    val partialTranscript: String = "",
    val finalTranscript: String = "",
    val errorMessage: String? = null,
    val generation: Long = 0,
)

/**
 * A completed, non-blank voice transcript ready to be routed by
 * [vn.edu.haui.hvs.safedrive.domain.usecase.VoiceAssistantCoordinator] (docs/android-mvp-plan/12 §4,
 * W2.1). [screen] is the UI surface the listening session was started from ("cockpit", "assistant",
 * "emergency", ...) — used for observability/context, not for routing (routing depends only on
 * whether an emergency is currently active).
 *
 * [generation] is the exact [VoiceUiState.generation] this event's recognizer session published
 * `PROCESSING` under (independent re-audit follow-up, eighth pass) — carried all the way through
 * [vn.edu.haui.hvs.safedrive.domain.usecase.VoiceAssistantCoordinator]'s routing/ownership decisions so
 * that whichever code path ultimately resolves *this* event's own visible Processing indication
 * ([VoiceController.clearProcessingState]/[VoiceController.reassignProcessingOwner]) can never
 * accidentally act on a *different*, later recognizer session's Processing state instead. Before this
 * field existed, [VoiceController.clearProcessingState] had no way to tell whether the currently-shown
 * `PROCESSING` still belonged to the turn resolving it, or had already been silently overwritten by a
 * newer, unrelated recognizer session's own `onResults()` call — a real pre-claim race (see that
 * function's KDoc).
 */
data class VoiceInputEvent(
    val text: String,
    val screen: String,
    val captureTimings: VoiceCaptureTimings? = null,
    val generation: Long,
)

/**
 * Command-transcript capture lifecycle owner (docs/android-mvp-plan/12 §4.1/W2) — started either by a
 * manual mic tap or by `vn.edu.haui.hvs.safedrive.service.WakeWordListeningService` once it detects
 * "Mai ơi". Owns ONLY mic/STT lifecycle and UI state — it must never call the assistant pipeline
 * or a TTS engine directly; final transcripts are published on [events] for
 * [vn.edu.haui.hvs.safedrive.domain.usecase.VoiceAssistantCoordinator] to route, and speech output is
 * [vn.edu.haui.hvs.safedrive.domain.repository.TtsController]'s job entirely (W2.11). Permission and
 * lifecycle are managed by the controller implementation, never by the composable.
 */
interface VoiceController {
    val state: StateFlow<VoiceUiState>
    val events: Flow<VoiceInputEvent>

    fun startListening(screen: String)

    /** Stops listening before a final transcript arrives ("Hủy nghe"); safe to call anytime,
     * including when nothing is listening. Never touches TTS. */
    fun cancel()

    /** User-initiated "Kết thúc câu nói" (docs/android-mvp-plan/12 W4.7): tells the recognizer to stop
     * accepting more audio and produce a final result from whatever was captured so far, instead of
     * waiting for the recognizer's own (best-effort, not guaranteed) end-of-speech detection. */
    fun finishListening()

    /** Clears the transient `PROCESSING` UI state once the turn started for [expectedGeneration]'s
     * transcript has finished (success, failure or cancellation) — called by
     * [vn.edu.haui.hvs.safedrive.domain.usecase.VoiceAssistantCoordinator], not by UI code.
     *
     * Only transitions `PROCESSING` → `IDLE` when [VoiceUiState.generation] is still exactly
     * [expectedGeneration] (independent re-audit follow-up, eighth pass) — a pre-claim race is otherwise
     * possible: a *newer* recognizer session can publish its own `PROCESSING`/generation (via
     * [VoiceInputEvent.generation]) before the voice-event collector has even routed it, i.e. before any
     * assistant turn exists to "own" that new generation yet. If an *older* turn's completion consumer
     * happens to run in that exact window and called an unconditional clear, it would wipe out the
     * newer session's legitimate `PROCESSING` indication — even though nothing about the older turn was
     * actually wrong, and the newer turn is genuinely still in flight. Requiring the caller to name the
     * exact generation it is resolving makes that a safe, silent no-op instead: the currently-visible
     * generation no longer matches, so nothing is touched. */
    fun clearProcessingState(expectedGeneration: Long)

    /** Re-attributes the currently-shown `PROCESSING` state (if any) from [expectedCurrentGeneration] to
     * [newOwnerGeneration] — never changes `IDLE`/`ERROR`/other states, and is a no-op if not currently
     * `PROCESSING` (independent re-audit follow-up, eighth pass).
     *
     * Compare-and-set, not an unconditional overwrite (independent re-audit follow-up, ninth pass,
     * closing P1-1): only transitions when [VoiceUiState.generation] is still exactly
     * [expectedCurrentGeneration]. Without this check, a *third*, even-newer recognizer session (C) can
     * publish its own `PROCESSING`/generation *after* a rejected event (B) was enqueued but *before* B's
     * own rejection is actually resolved — [resolveUnclaimedEvent][vn.edu.haui.hvs.safedrive.domain.usecase.VoiceAssistantCoordinator]
     * would then call this naming B's own generation as the one to re-attribute *from*; an unconditional
     * overwrite would blindly stamp the (older, still-genuinely-in-flight) turn's generation over C's —
     * even though C's session is the one actually visible right now — silently destroying C's legitimate
     * `PROCESSING` indication before C's own transcript is ever routed. Naming the exact generation this
     * call is entitled to move away from makes that a safe no-op instead: if the visible generation has
     * already moved on to C, this call touches nothing, leaving C's indication intact for C's own
     * eventual resolution to handle.
     *
     * Used when a *newer* recognizer session's transcript is rejected (single-flight busy with an
     * *older*, still-genuinely-in-flight accepted voice turn, or an emergency-phrase event arriving
     * mid-turn) while that older turn's own eventual [clearProcessingState] call is the one that must
     * still correctly resolve the visible indication: since the newer session's `onResults()` already
     * overwrote [VoiceUiState.generation] to its own value before rejection was even decided, the older
     * turn's own generation would otherwise never match again, silently stranding the visible
     * `PROCESSING` forever (never cleared, since nothing else has a legitimate reason to touch this
     * newer, rejected generation). Re-stamping the generation back to the turn that is actually still
     * running keeps `PROCESSING` visible (correctly — real work is still happening) and lets that turn's
     * own later [clearProcessingState] call resolve it exactly once, as intended. */
    fun reassignProcessingOwner(expectedCurrentGeneration: Long, newOwnerGeneration: Long)
}
