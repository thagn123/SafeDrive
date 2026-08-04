package vn.edu.haui.hvs.safedrive.domain.usecase

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import vn.edu.haui.hvs.safedrive.core.model.AssistantTurnSource
import vn.edu.haui.hvs.safedrive.core.model.AssistantTurnState
import vn.edu.haui.hvs.safedrive.core.model.EmergencyResponseType
import vn.edu.haui.hvs.safedrive.core.model.EmergencyState
import vn.edu.haui.hvs.safedrive.domain.repository.EmergencyRepository
import vn.edu.haui.hvs.safedrive.voice.VoiceController
import vn.edu.haui.hvs.safedrive.voice.VoiceInputEvent

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
    private val externalScope: CoroutineScope,
    /** Where each accepted turn's completion-awaiting consumer coroutine ([route]'s
     * `completionScope.launch { turn.completion.await() ... }` below) actually runs. Defaults to
     * [externalScope] — the same scope the voice-event collector itself runs on — so production
     * behavior is completely unchanged (independent re-audit follow-up, sixth pass). Exposed as its own
     * parameter purely so a test can pump "the collector accepting a turn" and "that turn's own
     * completion consumer" on two independently-controlled dispatchers: this is required to
     * deterministically force "turn B has already claimed ownership before turn A's completion consumer
     * gets a chance to run" for a *synchronously*-resolved turn (the health-blocked path), where —
     * unlike an in-flight network call — there is no `await`/`delay` suspension point anywhere to hang
     * that gap on; the only remaining gap is the scheduling of the consumer coroutine's own launch.
     */
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

    fun start() {
        if (!collectorStarted.compareAndSet(false, true)) return
        voiceController.events.onEach(::route).launchIn(externalScope)
    }

    /** Clears the currently-shown outcome — called when the user dismisses the voice overlay's
     * reply/error bubble (W6.10). Never touches chat history; the message/error stays there. */
    fun dismissTurnOutcome() {
        _turnOutcome.value = null
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
        val activeEmergency = emergencyRepository.activeSnapshot.value
        if (activeEmergency != null && activeEmergency.state in ACTIVE_EMERGENCY_STATES) {
            if (EmergencyVoicePhrases.isCancelPhrase(event.text)) {
                emergencyRepository.respond(EmergencyResponseType.CANCEL_SOS)
            }
            // Same ownership rule as the single-flight rejection below: an emergency-phrase event
            // arriving while a genuinely-accepted voice turn of our own is still in flight must not
            // steal that turn's own Processing indication — only its own eventual completion may clear
            // it. Resolves this specific event's own generation (see resolveUnclaimedEvent's KDoc).
            resolveUnclaimedEvent(event.generation)
            return
        }

        // Captured synchronously from submit()'s onStarted callback — a per-turn
        // AssistantTurnCoordinator.StartedTurn carrying its own Deferred completion, not a re-read of
        // ConversationRepository's StateFlow (blocker 1). That StateFlow only ever holds the *latest*
        // turnState: if this voice turn's terminal value were published and then overwritten by a
        // later, different turn before this coroutine got scheduled to look, a `.first { matches }`
        // collector would search the current (already-different) value forever, since StateFlow does
        // not replay superseded history — a genuine indefinite hang, not just a slow response.
        // `Deferred.await()` has no such hazard: it always resolves to the exact value `completion` was
        // completed with, independent of subscription timing and independent of whatever
        // ConversationRepository shows by the time `await()` actually runs.
        var startedTurn: AssistantTurnCoordinator.StartedTurn? = null
        val started = assistantTurnCoordinator.submit(
            event.text,
            AssistantTurnSource.VOICE,
            event.screen,
            event.captureTimings,
        ) { turn -> startedTurn = turn }
        if (!started) {
            // Single-flight rejected this voice turn — resolves this specific event's own generation
            // (see resolveUnclaimedEvent's KDoc: either clears it directly, or transfers visible
            // ownership back to whichever turn is genuinely still in flight).
            resolveUnclaimedEvent(event.generation)
            return
        }
        val turn = requireNotNull(startedTurn) // onStarted always fires synchronously when started == true
        val owner = VoiceTurnOwner(turn.requestId, turn.generation, event.generation)
        synchronized(voiceOwnershipLock) {
            // Unconditionally takes over ownership — this is exactly the ABA race's resolution point:
            // if a previous turn's completion consumer has not run yet, its own later ownership check
            // (below) will find `activeOwner` no longer references it and become a stale no-op, instead
            // of racing this turn for the Processing indicator/outcome slot.
            activeOwner = owner
            _turnOutcome.value = null // a new voice turn begins — never show a previous one's stale outcome
        }
        completionScope.launch {
            // Resolves correctly whether this turn already completed (synchronously, e.g. the
            // health-blocked path) or is still genuinely in flight — Deferred.await() suspends only if
            // truly necessary, and wakes the instant `completion` is completed, with zero added delay.
            val terminal = turn.completion.await()
            val outcome: VoiceTurnOutcome? = when (terminal) {
                is AssistantTurnState.Success -> VoiceTurnOutcome.Success(terminal.reply.text)
                is AssistantTurnState.Failure -> VoiceTurnOutcome.Failure(terminal.userMessage)
                is AssistantTurnState.Cancelled -> null // user cancelled — nothing to show
                is AssistantTurnState.Idle, is AssistantTurnState.InFlight -> null // unreachable: never completed with these
            }
            synchronized(voiceOwnershipLock) {
                // A later voice turn may already have claimed ownership while this turn's own terminal
                // value was resolved but this consumer coroutine had not yet been scheduled to run (the
                // ABA race this pass closes) — in that case, this is a stale no-op: never publish this
                // turn's outcome, never clear the current owner's Processing state, never touch
                // `activeOwner`. The check, the outcome publish, the owner clear and the
                // clearProcessingState() call all happen inside this one critical section so no new
                // turn can be accepted in the gap between "check" and "act".
                if (activeOwner === owner) {
                    _turnOutcome.value = outcome
                    activeOwner = null
                    // Runs exactly once per accepted turn, on every path (success/failure/cancelled) —
                    // never skipped, since `completion` is guaranteed to eventually resolve on all three
                    // paths, and never doubled, since only the genuine current owner ever reaches here.
                    // Names this turn's *own* voice generation explicitly (independent re-audit
                    // follow-up, eighth pass) — if a later recognizer session has since overwritten the
                    // visible generation (e.g. this same turn's own Processing was already transferred
                    // away by a rejected newer event, or a newer turn's onResults ran ahead of this
                    // turn's own resolution being processed), this call correctly becomes a no-op instead
                    // of clearing a generation that no longer belongs to it.
                    voiceController.clearProcessingState(owner.voiceGeneration)
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
}
