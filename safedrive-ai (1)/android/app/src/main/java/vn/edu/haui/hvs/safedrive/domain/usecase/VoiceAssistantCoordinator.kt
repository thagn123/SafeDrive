package vn.edu.haui.hvs.safedrive.domain.usecase

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import vn.edu.haui.hvs.safedrive.core.model.AssistantTurnSource
import vn.edu.haui.hvs.safedrive.core.model.AssistantTurnState
import vn.edu.haui.hvs.safedrive.core.model.EmergencyResponseType
import vn.edu.haui.hvs.safedrive.core.model.EmergencyState
import vn.edu.haui.hvs.safedrive.domain.repository.EmergencyRepository
import vn.edu.haui.hvs.safedrive.domain.repository.TtsController
import vn.edu.haui.hvs.safedrive.domain.repository.TtsState
import vn.edu.haui.hvs.safedrive.core.model.VoiceState
import vn.edu.haui.hvs.safedrive.voice.VoiceController
import vn.edu.haui.hvs.safedrive.voice.VoiceInputEvent
import android.util.Log

private val ACTIVE_EMERGENCY_STATES = setOf(
    EmergencyState.CANDIDATE_DETECTED,
    EmergencyState.VERIFYING_EVIDENCE,
    EmergencyState.AWAITING_USER_RESPONSE,
    EmergencyState.FINAL_COUNTDOWN,
)

/** The terminal outcome of the most recent VOICE-sourced turn, for
 * [vn.edu.haui.hvs.safedrive.feature.voice.VoiceOverlay] to display (docs/android-mvp-plan/12 W6.10,
 * remediation item 6) — never set by a text/quick-prompt turn, so a text reply can never leak into the
 * voice overlay. `null` again once [VoiceAssistantCoordinator.dismissTurnOutcome] is called, or a user
 * cancel produced nothing worth showing. */
sealed interface VoiceTurnOutcome {
    data class Success(val replyText: String) : VoiceTurnOutcome
    data class Failure(val errorMessage: String) : VoiceTurnOutcome
}

/**
 * Routes a final voice transcript to either the Emergency exact-match handler or the shared
 * [AssistantTurnCoordinator] pipeline (docs/android-mvp-plan/12 §4, W2.4-W2.8). Application-scoped:
 * [start] is called once from `SafeDriveContainer` and observes [VoiceController.events] for the
 * process lifetime, so voice triggered from Cockpit, Assistant or Emergency all route identically —
 * emergency-active always takes priority and never reaches chat, matching the pre-W2 behavior that
 * lived directly in `AndroidSpeechRecognizerController`. [start] is idempotent — a second call is a
 * no-op — so an accidental double-call can never register two competing collectors on the same
 * [VoiceController.events] (remediation item 7).
 */
class VoiceAssistantCoordinator(
    private val voiceController: VoiceController,
    private val assistantTurnCoordinator: AssistantTurnCoordinator,
    private val emergencyRepository: EmergencyRepository,
    private val ttsController: TtsController,
    private val externalScope: CoroutineScope,
    private val completionScope: CoroutineScope = externalScope,
) {
    /** Guards [start] against registering more than one collector on [VoiceController.events]
     * (remediation item 7, hardened further below). A plain `Boolean` read-then-write
     * (`if (collectorStarted) return; collectorStarted = true`) is not atomic: two OS threads calling
     * [start] at the same instant could both observe `false` before either writes `true`, both then
     * pass the guard and both register a collector — this does not rely on [VoiceController.events]
     * being `Channel`-backed (which only guarantees each *value* goes to one collector, not that only
     * one collector ever exists) to paper over the bug. `compareAndSet(false, true)` is a single atomic
     * hardware operation: of any number of concurrent callers, exactly one observes `true` (its own
     * CAS succeeded) and proceeds; every other caller's CAS fails and returns immediately. */
    private val collectorStarted = AtomicBoolean(false)

    /** Guards every read/write of [activeOwner] and every check-then-act transition built on top of it,
     * as one atomic critical section (independent re-audit follow-up, sixth pass). Replaces the earlier
     * `AtomicBoolean voiceTurnInFlight`, which tracked only *whether* some voice turn was in flight, not
     * *which* one — an ABA race: turn A is accepted (flag -> true); A completes at the
     * [AssistantTurnCoordinator] level, freeing global single-flight, but *this* coordinator's own
     * completion consumer for A has not run yet; turn B is then accepted (flag stays/becomes true,
     * indistinguishable from A) and legitimately claims the Processing indicator/outcome slot; A's now-
     * late consumer finally runs, observes the flag as `true` (which it cannot tell apart from "A is
     * still what's in flight"), and incorrectly clears B's Processing state and/or publishes A's stale
     * outcome over B's. A per-turn identity token ([VoiceTurnOwner]) fixes this structurally: a late
     * consumer compares its own token against [activeOwner] by reference and becomes a safe no-op the
     * instant it is no longer the current owner — never a silent double-publish or a stolen clear.
     *
     * Never held across a suspension point — every block below is a synchronous state read/mutation
     * only (comparing/reassigning [activeOwner], setting [_turnOutcome].value,
     * [VoiceController.clearProcessingState]) — and never acquired while already holding
     * [AssistantTurnCoordinator]'s own internal lock (acquisition order is always: call into
     * [AssistantTurnCoordinator] fully, *then* acquire this lock once it has returned), so this cannot
     * lock-order-invert against it. */
    private val voiceOwnershipLock = Any()

    /** The accepted voice turn that currently owns the Processing indicator and [turnOutcome], or
     * `null` if none. Only ever read or written inside [voiceOwnershipLock] — see that field's KDoc for
     * the race this closes. */
    private var activeOwner: VoiceTurnOwner? = null

    /** Identity token minted for one accepted voice turn (independent re-audit follow-up, sixth pass).
     * `requestId`/`turnGeneration` are carried for diagnostic value only — every ownership comparison
     * uses reference identity (`===`), never [equals]/[hashCode], so two distinct accepted turns can
     * never be mistaken for the same owner even in a hypothetical id collision.
     *
     * [voiceGeneration] (independent re-audit follow-up, eighth pass) is the *recognizer session's own*
     * [vn.edu.haui.hvs.safedrive.voice.VoiceInputEvent.generation] this turn was accepted from — distinct
     * from [turnGeneration], which is [AssistantTurnCoordinator]'s own per-turn generation counter and has
     * no relationship to the recognizer at all. Retained so this turn's own eventual
     * [VoiceController.clearProcessingState] call always names the *exact* generation whose `PROCESSING`
     * it is entitled to resolve — see the pre-claim race this closes in [route]'s KDoc. */
    private class VoiceTurnOwner(val requestId: String, val turnGeneration: Long, val voiceGeneration: Long)

    private val _turnOutcome = MutableStateFlow<VoiceTurnOutcome?>(null)

    /** Read-only presentation source for the voice overlay (remediation item 6) — combined for
     * *display* only with [VoiceController.state]/`TtsController.state`, never merging ownership: this
     * class alone decides when a voice turn succeeded/failed, [VoiceController] never sees a reply or
     * error. */
    val turnOutcome: StateFlow<VoiceTurnOutcome?> = _turnOutcome.asStateFlow()

    // ─── Continuous conversation loop state ───
    /** Whether continuous voice mode is active (auto-restart listening after each reply). */
    @Volatile private var continuousMode = false
    /** The screen the current continuous session started from. */
    @Volatile private var lastScreen = "assistant"

    private val EXIT_PHRASES = listOf(
        "hết rồi", "kết thúc", "hết", "dừng", "dừng lại", "thôi", "tạm biệt",
    )
    private val EXIT_CONTAINS = listOf(
        "hết rồi", "kết thúc", "dừng lại", "không còn", "tạm biệt",
    )

    private fun isExitPhrase(text: String): Boolean {
        val lower = text.lowercase().trim()
        return EXIT_PHRASES.any { lower == it } || EXIT_CONTAINS.any { lower.contains(it) }
    }

    fun start() {
        if (!collectorStarted.compareAndSet(false, true)) return
        Log.d("SafeDriveVoiceDebug", "[VAC] VoiceAssistantCoordinator started - collecting events & monitoring state")
        voiceController.events.onEach(::route).launchIn(externalScope)

        // Continuous mode auto-recovery observer:
        // If SpeechRecognizer hits an ERROR (e.g. Code 11 mic busy) or resets to IDLE while continuousMode is active,
        // wait a moment for the audio system to reset and automatically restart listening.
        externalScope.launch {
            voiceController.state.collect { voiceState ->
                if (continuousMode && (voiceState.state == VoiceState.ERROR || voiceState.state == VoiceState.IDLE)) {
                    delay(1500)
                    if (continuousMode && activeOwner == null) {
                        val currentState = voiceController.state.value.state
                        if (currentState == VoiceState.ERROR || currentState == VoiceState.IDLE) {
                            Log.d("SafeDriveVoiceDebug", "[VAC] Continuous mode auto-recovery: restarting listening after state=$currentState")
                            voiceController.startListening(lastScreen)
                        }
                    }
                }
            }
        }
    }

    /** Clears the currently-shown outcome — called when the user dismisses the voice overlay's
     * reply/error bubble (W6.10). Never touches chat history; the message/error stays there. */
    fun dismissTurnOutcome() {
        _turnOutcome.value = null
    }

    /** Stop continuous conversation mode explicitly (e.g. user taps close/cancel). */
    fun stopContinuousMode() {
        Log.d("SafeDriveVoiceDebug", "[VAC] Explicitly stopping continuous mode")
        continuousMode = false
    }

    /**
     * Routes one final transcript. `event.`[VoiceInputEvent.generation] is the exact recognizer-session
     * generation [AndroidSpeechRecognizerController.onResults]/[FakeVoiceController.emitFinalTranscript]
     * published `PROCESSING` under *before this function was ever called* — routing (single-flight
     * accept/reject, emergency-phrase check) always happens strictly after that publish, never before,
     * so by the time any code below runs, [VoiceController.state]'s visible generation may already have
     * moved on to a *newer* session's if more than one transcript was queued ahead of this one being
     * processed. Every resolution below therefore names [event]'s own generation explicitly — see
     * [VoiceTurnOwner.voiceGeneration]'s KDoc for the pre-claim race a bare, ungenerationed clear used to
     * allow: an older, already-superseded turn's completion consumer could otherwise wipe out a newer
     * session's legitimate `PROCESSING` before that newer session's own transcript was even routed.
     */
    private suspend fun route(event: VoiceInputEvent) {
        Log.d("SafeDriveVoiceDebug", "[VAC] route() received event: text='${event.text}', screen='${event.screen}', gen=${event.generation}")

        // Enable continuous conversation mode for every voice event
        continuousMode = true
        lastScreen = event.screen

        // Check exit phrase BEFORE submitting — stop the loop immediately
        if (isExitPhrase(event.text)) {
            Log.d("SafeDriveVoiceDebug", "[VAC] Exit phrase detected: '${event.text}' → stopping continuous mode")
            continuousMode = false
            resolveUnclaimedEvent(event.generation)
            return
        }

        val activeEmergency = emergencyRepository.activeSnapshot.value
        if (activeEmergency != null && activeEmergency.state in ACTIVE_EMERGENCY_STATES) {
            Log.d("SafeDriveVoiceDebug", "[VAC] route() -> emergency active, checking cancel phrase")
            if (EmergencyVoicePhrases.isCancelPhrase(event.text)) {
                emergencyRepository.respond(EmergencyResponseType.CANCEL_SOS)
            }
            resolveUnclaimedEvent(event.generation)
            return
        }

        var startedTurn: AssistantTurnCoordinator.StartedTurn? = null
        Log.d("SafeDriveVoiceDebug", "[VAC] route() -> calling assistantTurnCoordinator.submit()")
        val started = assistantTurnCoordinator.submit(
            event.text,
            AssistantTurnSource.VOICE,
            event.screen,
            event.captureTimings,
        ) { turn -> startedTurn = turn }
        Log.d("SafeDriveVoiceDebug", "[VAC] route() -> submit result: started=$started")
        if (!started) {
            Log.w("SafeDriveVoiceDebug", "[VAC] route() -> submit FAILED (single-flight rejected), resolving unclaimed")
            resolveUnclaimedEvent(event.generation)
            // Even if rejected, restart listening in continuous mode
            if (continuousMode) {
                externalScope.launch {
                    delay(1500)
                    if (continuousMode) {
                        Log.d("SafeDriveVoiceDebug", "[VAC] Continuous mode: restarting after rejection")
                        voiceController.startListening(lastScreen)
                    }
                }
            }
            return
        }
        val turn = requireNotNull(startedTurn)
        val owner = VoiceTurnOwner(turn.requestId, turn.generation, event.generation)
        synchronized(voiceOwnershipLock) {
            activeOwner = owner
            _turnOutcome.value = null
        }
        completionScope.launch {
            val terminal = turn.completion.await()
            Log.d("SafeDriveVoiceDebug", "[VAC] turn completed: terminal=${terminal::class.simpleName}")
            val outcome: VoiceTurnOutcome? = when (terminal) {
                is AssistantTurnState.Success -> VoiceTurnOutcome.Success(terminal.reply.text)
                is AssistantTurnState.Failure -> VoiceTurnOutcome.Failure(terminal.userMessage)
                is AssistantTurnState.Cancelled -> null
                is AssistantTurnState.Idle, is AssistantTurnState.InFlight -> null
            }
            synchronized(voiceOwnershipLock) {
                if (activeOwner === owner) {
                    _turnOutcome.value = outcome
                    activeOwner = null
                    voiceController.clearProcessingState(owner.voiceGeneration)
                }
            }

            // ─── Continuous conversation loop: restart for ANY result ───
            if (continuousMode) {
                Log.d("SafeDriveVoiceDebug", "[VAC] Continuous mode: turn done (${terminal::class.simpleName}), waiting for TTS then restarting")
                awaitTtsDone()
                delay(1200)
                if (continuousMode) {
                    Log.d("SafeDriveVoiceDebug", "[VAC] Continuous mode: restarting listening on screen='$lastScreen'")
                    voiceController.startListening(lastScreen)
                }
            }
        }
    }

    /**
     * Resolves the visible `PROCESSING` state for a transcript event that will *never* become an
     * accepted assistant turn of its own (rejected by single-flight, or an emergency-phrase command) —
     * called with [eventGeneration], the exact generation that event's recognizer session published
     * `PROCESSING` under (independent re-audit follow-up, eighth pass, closing a P1 pre-claim race).
     *
     * Two cases, both decided atomically under [voiceOwnershipLock] so no newly-accepted turn can land
     * in between the check and the act:
     * - **No turn currently owns Processing** (`activeOwner == null`, e.g. a *text* turn is what's busy,
     *   or nothing at all is in flight): nothing else will ever resolve this specific generation, so it
     *   is cleared right here, directly.
     * - **Some other, still-genuinely-in-flight voice turn owns Processing** (`activeOwner != null`):
     *   that older turn's `onResults()` already published its own `PROCESSING`/generation *before* this
     *   newer event's session started — but this newer session's own `onResults()` has since overwritten
     *   the *visible* generation to its own value (recognizer sessions always publish `PROCESSING`
     *   unconditionally, independent of whatever `route()` later decides). Left alone, the older turn's
     *   own eventual [VoiceController.clearProcessingState] call would name its own generation, which no
     *   longer matches what is visible — a silent, permanent stranding of this rejected generation's
     *   `PROCESSING` (never cleared, since nothing else has a reason to touch it) that would also survive
     *   long after the older turn genuinely finishes. Re-attributing the visible generation back to the
     *   still-owning turn (rather than clearing outright) keeps the indication accurate — real work is
     *   still happening — and restores the older turn's own later clear call to correctly match again.
     *
     * [eventGeneration] is passed as [VoiceController.reassignProcessingOwner]'s own
     * `expectedCurrentGeneration` — never [owner]'s (independent re-audit follow-up, ninth pass, closing
     * P1-1). A *third*, even-newer session (C) can publish its own `PROCESSING`/generation for this
     * event's own recognizer session (B) — enqueued, but not yet routed — since `route()` processes
     * queued events strictly one at a time, in enqueue order, and nothing gates a recognizer session's
     * own publish on that. If this call named anything other than [eventGeneration] as the
     * expected-current value, or reassigned unconditionally, it could silently stamp the older turn's
     * generation over C's — destroying C's legitimate, currently-visible `PROCESSING` before C's own
     * transcript is even routed. Naming exactly [eventGeneration] makes this call a safe no-op the
     * instant the visible generation has already moved past this event: it is only ever entitled to move
     * this event's own generation, never whatever happens to be visible by the time it actually runs.
     */
    private fun resolveUnclaimedEvent(eventGeneration: Long) {
        synchronized(voiceOwnershipLock) {
            val owner = activeOwner
            if (owner != null) {
                voiceController.reassignProcessingOwner(
                    expectedCurrentGeneration = eventGeneration,
                    newOwnerGeneration = owner.voiceGeneration,
                )
            } else {
                voiceController.clearProcessingState(eventGeneration)
            }
        }
    }

    /** Suspends until TTS is no longer SPEAKING (max ~30s safety timeout). */
    private suspend fun awaitTtsDone() {
        // If TTS isn't speaking, return immediately
        if (ttsController.state.value != TtsState.SPEAKING) return
        Log.d("SafeDriveVoiceDebug", "[VAC] awaitTtsDone: TTS is SPEAKING, waiting...")
        try {
            kotlinx.coroutines.withTimeoutOrNull(30_000L) {
                ttsController.state.first { it != TtsState.SPEAKING }
            }
        } catch (_: Exception) { /* timeout or cancellation — proceed anyway */ }
        Log.d("SafeDriveVoiceDebug", "[VAC] awaitTtsDone: TTS done, state=${ttsController.state.value}")
    }
}
