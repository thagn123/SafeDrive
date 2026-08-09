package vn.edu.haui.hvs.safedrive.domain.usecase

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import android.util.Log
import vn.edu.haui.hvs.safedrive.core.common.AppClock
import vn.edu.haui.hvs.safedrive.core.common.GatewayError
import vn.edu.haui.hvs.safedrive.core.common.GatewayResult
import vn.edu.haui.hvs.safedrive.core.common.IdGenerator
import vn.edu.haui.hvs.safedrive.core.model.AssistantTurnSource
import vn.edu.haui.hvs.safedrive.core.model.AssistantTurnState
import vn.edu.haui.hvs.safedrive.core.model.ChatMessage
import vn.edu.haui.hvs.safedrive.core.model.ChatSender
import vn.edu.haui.hvs.safedrive.core.model.CockpitSnapshot
import vn.edu.haui.hvs.safedrive.core.model.HealthStatus
import vn.edu.haui.hvs.safedrive.core.model.InFlightAssistantTurn
import vn.edu.haui.hvs.safedrive.core.model.RetryableAssistantTurn
import vn.edu.haui.hvs.safedrive.core.observability.AssistantTurnMetrics
import vn.edu.haui.hvs.safedrive.core.observability.AssistantTurnMetricsRecorder
import vn.edu.haui.hvs.safedrive.core.observability.VoiceCaptureTimings
import vn.edu.haui.hvs.safedrive.domain.repository.AppPreferences
import vn.edu.haui.hvs.safedrive.domain.repository.ConversationRepository
import vn.edu.haui.hvs.safedrive.domain.repository.TtsController

/** How long to wait for the TTS engine's real "audio started" callback before giving up on recording
 * [AssistantTurnMetrics.ttsStartedAtMs] for a turn — bounded so this never blocks/leaks a coroutine
 * indefinitely; a timeout leaves the field `null` (never fabricated) rather than guessing. */
private const val TTS_START_WAIT_MS = 5_000L

/**
 * Single entry point for text, quick-prompt and voice assistant turns (docs/android-mvp-plan/12 §4/W1).
 * Application-scoped so it — and the [ConversationRepository] it writes to — outlive any one screen's
 * ViewModel (docs/android-mvp-plan/12 §4.2).
 *
 * Global single-flight (W1.11) is enforced by [lock]: [submit]/[retry]/[cancelCurrent] each perform
 * their busy-check and state transition as one atomic, synchronized critical section, not a
 * check-then-act read of [ConversationRepository]'s `StateFlow` — two callers on different dispatchers
 * (Compose Main for typed/quick-prompt input, an application coroutine scope for voice) can never both
 * pass the busy-check and start a turn (remediation item 2). [currentGeneration]/[currentJob] are only
 * ever read or written inside a `synchronized(lock)` block, so a turn's completion callback checking
 * "am I still the current turn" can never race a concurrent [cancelCurrent] or a newer [submit].
 *
 * Mints exactly one network `requestId` per attempt (in [beginTurnLocked]) and threads that same id
 * through [InFlightAssistantTurn], [AssistantQueryUseCase], [AssistantTurnMetrics] and the TTS
 * `utteranceId` — never a second, independently-minted id (remediation item 1, fixing the old
 * `turn_*`/`req_*` mismatch where a retry's `clientAttemptOf` referenced an id the backend never
 * actually received).
 *
 * Also the *only* caller of [TtsController.speak] for assistant replies (docs/android-mvp-plan/12
 * W3.13): only a successful reply, only when [AppPreferences.ttsEnabled] is true — never for typed
 * errors or system messages, since those never reach the `GatewayResult.Success` branch below. And the
 * single place a completed turn's [AssistantTurnMetrics] are assembled and recorded (W4.1).
 */
class AssistantTurnCoordinator(
    private val conversationRepository: ConversationRepository,
    private val assistantQueryUseCase: AssistantQueryUseCase,
    private val cockpitSnapshot: StateFlow<CockpitSnapshot?>,
    private val appPreferences: StateFlow<AppPreferences>,
    private val ttsController: TtsController,
    private val metricsRecorder: AssistantTurnMetricsRecorder,
    private val lastHealthStatus: StateFlow<HealthStatus?>,
    private val idGenerator: IdGenerator,
    private val clock: AppClock,
    private val externalScope: CoroutineScope,
    /** Redacted, injectable diagnostic sink for exceptions this class deliberately does not let
     * propagate (independent re-audit follow-up, second pass) — never the transcript, reply text or
     * any raw backend content, and never [Throwable.message] directly (which could itself echo request/
     * response content back out); only the exception's class name plus requestId/generation/source.
     * Defaults to a no-op so every existing call site and test is unaffected. */
    private val logger: (String) -> Unit = {},
) {
    /** Guards every read/write of [currentGeneration]/[currentJob] and every busy-check-then-mutate
     * transition on [conversationRepository]'s turn state (remediation item 2). Plain `synchronized` —
     * not a `Mutex` — because [submit]/[retry]/[cancelCurrent] are ordinary (non-suspend) functions
     * callable directly from a ViewModel action handler; the only suspending work (the actual gateway
     * call) always happens outside this lock. */
    private val lock = Any()
    private var currentGeneration = 0L
    private var currentJob: Job? = null

    /** Tracks whether the in-flight attempt's assistant query has actually been dispatched to the
     * network yet (remediation item 1). Written from within [beginTurnLocked]'s coroutine — outside
     * [lock] — the instant [AssistantQueryUseCase]'s `onTiming` callback fires (which only happens
     * once session resolution succeeded, immediately before the real network call), so [cancelCurrent]
     * (which runs under [lock] on a different thread/coroutine) can always tell whether *this specific*
     * attempt ever actually went on the wire. `@Volatile` for cross-thread visibility of this one flag;
     * [currentAttempt] itself is only ever read/written under [lock]. */
    private class InFlightAttempt {
        @Volatile var querySent: Boolean = false
    }

    private var currentAttempt: InFlightAttempt? = null

    /** The currently in-flight turn's completion, if any — resolved exactly once, either by
     * [beginTurnLocked]'s coroutine reaching a terminal `GatewayResult`, or by [cancelCurrent]. Only
     * ever read/written under [lock]. See [StartedTurn] for why this exists instead of correlating via
     * [ConversationRepository.state] (blocker 1). */
    private var currentCompletion: CompletableDeferred<AssistantTurnState>? = null

    val isBusy: Boolean get() = conversationRepository.state.value.inFlightTurn != null

    /**
     * Handle returned via [submit]/[retry]'s `onStarted` callback for an accepted turn: the exact
     * `requestId`/`generation` minted for it, plus [completion] — a per-turn [Deferred] that resolves
     * to this turn's own terminal [AssistantTurnState] (`Success`/`Failure`/`Cancelled`) exactly once,
     * regardless of when the caller starts awaiting it (independent re-audit follow-up, blocker 1).
     *
     * This replaces correlating via [ConversationRepository.state]`.map { it.turnState }.first { ... }`,
     * which has a real hazard: that `StateFlow` only ever holds the *latest* `turnState` — if a turn's
     * terminal value is published and then a *later*, different turn overwrites it before a collector
     * ever subscribes (entirely possible: nothing prevents a next turn from starting and finishing
     * before the previous turn's own completion-handling coroutine gets scheduled), a `.first { matches
     * this turn's id } }` collector would search the *current* (already-different) value, never find a
     * match, and wait forever — `StateFlow` does not replay superseded history. [completion] has no such
     * hazard: it is a genuine single-value identity handed to the caller at turn-start time;
     * [kotlinx.coroutines.Deferred.await] resolves to the exact value it was completed with no matter
     * when `await()` is called relative to that completion.
     *
     * Never retained beyond the awaiting coroutine's own lifetime — nothing in [AssistantTurnCoordinator]
     * stores a history of these, so there is no unbounded growth.
     */
    class StartedTurn(
        val requestId: String,
        val generation: Long,
        val completion: Deferred<AssistantTurnState>,
    )

    /**
     * Appends a user bubble and starts a turn. Returns `false` (no-op, no bubble appended) if the
     * text is blank or a turn is already in flight — callers should not clear their composer/input
     * in that case. [captureTimings] is voice-only mic/recognizer timing (docs/android-mvp-plan/12
     * W4), `null` for text/quick-prompt. [onStarted] is invoked synchronously, still holding [lock],
     * exactly once whenever this call returns `true` — with a [StartedTurn] for this attempt, whether
     * it goes on to a real network call or is synthesized straight to a terminal
     * [AssistantTurnState.Failure] (the health-blocked path below).
     * [vn.edu.haui.hvs.safedrive.domain.usecase.VoiceAssistantCoordinator] uses this to correlate its
     * own wait for *this exact* turn's terminal state — added as a defaulted trailing parameter so
     * every existing text/quick-prompt call site is unaffected.
     */
    fun submit(
        text: String,
        source: AssistantTurnSource,
        screen: String,
        captureTimings: VoiceCaptureTimings? = null,
        onStarted: (StartedTurn) -> Unit = {},
    ): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) {
            Log.d("SafeDriveVoiceDebug", "[ATC] submit() -> REJECTED: text is empty")
            return false
        }

        synchronized(lock) {
            val inFlight = conversationRepository.state.value.inFlightTurn
            if (inFlight != null) {
                Log.d("SafeDriveVoiceDebug", "[ATC] submit() -> REJECTED: already in-flight (reqId=${inFlight.requestId}, src=${inFlight.source})")
                return false
            }

            Log.d("SafeDriveVoiceDebug", "[ATC] submit() -> ACCEPTED: text='$trimmed', source=$source, screen=$screen")

            val userMessage = ChatMessage(
                id = idGenerator.next("msg_user"),
                sender = ChatSender.USER,
                text = trimmed,
                timestampMs = clock.nowMs(),
            )

            if (lastHealthStatus.value?.capabilities?.assistant == false) {
                Log.w("SafeDriveVoiceDebug", "[ATC] submit() -> health-blocked: assistant=false")
                val generation = ++currentGeneration
                val requestId = idGenerator.next("req")
                val errorText = "Máy chủ hiện không hỗ trợ tính năng trợ lý."
                conversationRepository.rejectBeforeInFlight(
                    message = userMessage,
                    requestId = requestId,
                    generation = generation,
                    source = source,
                    error = GatewayError.Unsupported,
                    userMessage = errorText,
                    retryableTurn = RetryableAssistantTurn(clientAttemptOf = null, text = trimmed, screen = screen, source = source),
                )
                val state = AssistantTurnState.Failure(requestId, generation, source, GatewayError.Unsupported, errorText)
                invokeOnStartedSafely(onStarted, StartedTurn(requestId, generation, CompletableDeferred(state)), source)
                return true
            }

            if (source == AssistantTurnSource.VOICE && appPreferences.value.ttsEnabled) {
                Log.d("SafeDriveVoiceDebug", "[ATC] submit() -> speaking TTS acknowledgment")
                ttsController.speak("Tôi đã nhận được lời nói của bạn. Đang thực thi câu lệnh.", idGenerator.next("tts_ack"))
            } else {
                Log.d("SafeDriveVoiceDebug", "[ATC] submit() -> TTS ack skipped: source=$source, ttsEnabled=${appPreferences.value.ttsEnabled}")
            }

            beginTurnLocked(
                text = trimmed,
                source = source,
                screen = screen,
                clientAttemptOf = null,
                captureTimings = captureTimings,
                userMessage = userMessage,
                onStarted = onStarted,
            )
        }
        return true
    }

    /** Retries the last failed/cancelled turn without appending another user bubble (W1.12). */
    fun retry(onStarted: (StartedTurn) -> Unit = {}): Boolean {
        synchronized(lock) {
            val retryable = conversationRepository.state.value.retryableTurn ?: return false
            if (conversationRepository.state.value.inFlightTurn != null) return false

            beginTurnLocked(
                text = retryable.text,
                source = AssistantTurnSource.RETRY,
                screen = retryable.screen,
                clientAttemptOf = retryable.clientAttemptOf,
                captureTimings = null,
                userMessage = null,
                onStarted = onStarted,
            )
        }
        return true
    }

    /**
     * Cancels the in-flight turn, if any. The user bubble it already appended (if any) is kept; the
     * turn becomes retryable and no assistant bubble is fabricated (W1.13). A stale reply that
     * arrives after this call is dropped by the generation check in [beginTurnLocked]'s coroutine.
     * Also resolves the cancelled turn's [StartedTurn.completion] to [AssistantTurnState.Cancelled] —
     * any waiter (e.g. [vn.edu.haui.hvs.safedrive.domain.usecase.VoiceAssistantCoordinator]) is released
     * immediately instead of hanging until a network call that will never complete for it.
     */
    fun cancelCurrent() {
        synchronized(lock) {
            val inFlight = conversationRepository.state.value.inFlightTurn ?: return
            // Only claim lineage to a request the network actually saw (remediation item 1) — if
            // cancel lands while session resolution is still suspended, no query was ever sent.
            val querySent = currentAttempt?.querySent == true
            currentGeneration += 1 // invalidates the in-flight coroutine's generation check
            currentJob?.cancel()
            currentJob = null
            currentAttempt = null
            val cancelledState = AssistantTurnState.Cancelled(inFlight.requestId, inFlight.generation, inFlight.source)
            conversationRepository.completeCancelled(
                requestId = inFlight.requestId,
                generation = inFlight.generation,
                source = inFlight.source,
                retryableTurn = RetryableAssistantTurn(
                    clientAttemptOf = if (querySent) inFlight.requestId else null,
                    text = inFlight.text,
                    screen = inFlight.screen,
                    source = inFlight.source,
                    wasCancelled = true,
                ),
            )
            currentCompletion?.complete(cancelledState)
            currentCompletion = null
        }
    }

    /** Must only be called while holding [lock]. Mints the turn's one and only network `requestId`,
     * publishes [InFlightAssistantTurn] (atomically with [userMessage], if any — blocker 2) and
     * launches the suspending gateway call outside the lock.
     *
     * The launched coroutine is guaranteed to reach exactly one of [ConversationRepository.completeSuccess]/
     * [ConversationRepository.completeFailure] (or, via [cancelCurrent] *or* the [terminalizeAsCancelledIfAbandoned]
     * safety net installed below, [ConversationRepository.completeCancelled]) and to resolve [completion]
     * exactly once, *even if [assistantQueryUseCase] throws an unanticipated exception instead of
     * returning a typed [GatewayResult.Failure]* (independent re-audit follow-up, blocker 1): the query
     * call is wrapped so any non-cancellation exception becomes an ordinary [GatewayResult.Failure] and
     * flows through the *same* single `when (result)` terminalization branch below as a real network
     * failure.
     *
     * [CancellationException] is always rethrown here, never converted — cooperative cancellation must
     * keep working exactly as before. But a `CancellationException` reaching this coroutine does **not**
     * always mean [cancelCurrent] already handled cleanup for it (second independent re-audit pass): the
     * exception could instead come from [externalScope] itself being cancelled (e.g. application
     * shutdown), from the gateway/session throwing cancellation for an unrelated internal reason while
     * this generation is still current, or from this coroutine never even starting its body at all
     * because [externalScope] was already cancelled *before* `launch` was called. None of those go
     * through [cancelCurrent], so none of them would otherwise clear [currentJob]/[currentAttempt]/
     * [currentCompletion] or resolve [completion] — leaving the turn abandoned `InFlight` forever and any
     * waiter (e.g. [VoiceAssistantCoordinator.route]'s `completion.await()`) hanging indefinitely. The
     * `job.invokeOnCompletion { ... }` registered right after `launch` below is the single, uniform
     * safety net for exactly this: it fires on *every* way this Job can reach a final state — normal
     * completion, an exception escaping, or cancellation, *including* if the Job is already cancelled
     * before its body ever runs — and terminalizes the turn as [AssistantTurnState.Cancelled] if, and
     * only if, nothing else already has ([cancelCurrent] always bumps [currentGeneration] first; this
     * coroutine's own normal-path cleanup below always clears [currentCompletion] under [lock] first) —
     * see [terminalizeAsCancelledIfAbandoned]'s KDoc for why this can never double-terminalize. */
    private fun beginTurnLocked(
        text: String,
        source: AssistantTurnSource,
        screen: String,
        clientAttemptOf: String?,
        captureTimings: VoiceCaptureTimings?,
        userMessage: ChatMessage?,
        onStarted: (StartedTurn) -> Unit = {},
    ) {
        val generation = ++currentGeneration
        val requestId = idGenerator.next("req")
        val turnStartedAtMs = clock.nowMs()
        val attempt = InFlightAttempt()
        currentAttempt = attempt
        val completion = CompletableDeferred<AssistantTurnState>()
        currentCompletion = completion
        val turn = InFlightAssistantTurn(requestId = requestId, generation = generation, source = source, text = text, screen = screen)
        if (userMessage != null) {
            conversationRepository.beginTurn(userMessage, turn)
        } else {
            conversationRepository.beginTurn(turn)
        }

        // onStarted is caller-supplied and runs synchronously, still holding [lock], *before* currentJob
        // is assigned below — so a throwing callback has no coroutine yet for terminalizeAsCancelledIfAbandoned
        // to piggyback on via invokeOnCompletion. CancellationException is terminalized right here,
        // synchronously, before being rethrown (cooperative cancellation must still propagate); any other
        // exception is logged (never silently swallowed) and the turn proceeds to launch its own
        // coroutine normally below regardless — a bug in a caller's callback must not itself abort a turn
        // that would otherwise complete correctly.
        try {
            onStarted(StartedTurn(requestId, generation, completion))
        } catch (e: CancellationException) {
            terminalizeAsCancelledIfAbandoned(requestId, generation, source, text, screen, attempt, completion)
            throw e
        } catch (e: Exception) {
            logSwallowedException("onStarted callback", e, requestId, generation, source)
        }

        val job = externalScope.launch {
            val stateVersion = cockpitSnapshot.value?.stateVersion ?: 0L
            var sessionStartedAtMs: Long? = null
            var requestSentAtMs: Long? = null
            // Any exception other than cancellation — e.g. a mapper bug tripping on a malformed
            // response, or any other unanticipated RuntimeException anywhere in session
            // resolution/the gateway call — is folded into an ordinary GatewayResult.Failure here, so
            // it terminates this turn through the exact same path as a real network failure below
            // instead of crashing this coroutine and leaving the turn stuck InFlight forever (blocker 1).
            val result = try {
                assistantQueryUseCase(
                    text = text,
                    screen = screen,
                    stateVersion = stateVersion,
                    source = source,
                    requestId = requestId,
                    clientAttemptOf = clientAttemptOf,
                ) { session, sent ->
                    sessionStartedAtMs = session
                    requestSentAtMs = sent
                    // Only ever invoked once session resolution succeeded, immediately before the real
                    // network call (see AssistantQueryUseCase) — the one authoritative "this attempt is
                    // now actually on the wire" signal cancelCurrent() needs (remediation item 1).
                    attempt.querySent = true
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                GatewayResult.Failure(GatewayError.Unexpected(e.message))
            }
            val responseReceivedAtMs = clock.nowMs()

            synchronized(lock) {
                // Superseded by cancel or a newer turn — cancelCurrent() (or whatever superseded this
                // generation) already resolved `completion` itself; nothing left to do here.
                if (generation != currentGeneration) return@launch
                currentJob = null
                if (currentAttempt === attempt) currentAttempt = null
                if (currentCompletion === completion) currentCompletion = null

                when (result) {
                    is GatewayResult.Success -> {
                        val queryResult = result.data
                        // Model/llmUsed/fallback metadata is observability only; safety policy and actions
                        // stay deterministic regardless of what these say.
                        val assistantMessage = queryResult.message.copy(
                            model = queryResult.model,
                            llmUsed = queryResult.llmUsed,
                            fallback = queryResult.fallback,
                            fallbackReason = queryResult.fallbackReason,
                        )
                        val state = AssistantTurnState.Success(requestId, generation, source, assistantMessage)
                        conversationRepository.completeSuccess(requestId, generation, source, assistantMessage)
                        completion.complete(state)
                        // Everything below is a side effect of an *already-committed* terminal state —
                        // the turn is done and `completion` is resolved the instant the line above runs.
                        // A failure in metrics recording or the TTS pipeline past this point must never
                        // crash this coroutine (which would surface as an uncaught exception on
                        // [externalScope]) nor be mistaken for a reason to revisit the turn's outcome
                        // (independent re-audit follow-up, blocker 1).
                        try {
                            val ttsEnabled = appPreferences.value.ttsEnabled
                            val ttsRequestedAtMs = if (ttsEnabled) clock.nowMs() else null
                            // Base metrics are recorded *before* speak()/awaitTtsStarted() are even called
                            // (independent re-audit follow-up item 5): recordTtsStarted() below correlates
                            // purely by requestId, so if this base record instead happened only *after*
                            // awaitTtsStarted() returned, a TTS engine whose onStart callback reaches another
                            // thread fast enough could race the patch coroutine ahead of this base record —
                            // which would find no matching lastTurn yet (still the previous turn, or null)
                            // and silently drop the real timestamp forever (recordTtsStarted() is a no-op on
                            // a non-match, by design, so a late/stale event never overwrites a different
                            // turn). Recording first closes that window structurally: there is no instant
                            // after speak() is called where a matching lastTurn doesn't already exist.
                            metricsRecorder.record(
                                AssistantTurnMetrics(
                                    requestId = requestId,
                                    turnStartedAtMs = turnStartedAtMs,
                                    voiceCapture = captureTimings,
                                    sessionStartedAtMs = sessionStartedAtMs,
                                    requestSentAtMs = requestSentAtMs,
                                    responseReceivedAtMs = responseReceivedAtMs,
                                    serverProcessingMs = queryResult.serverProcessingMs,
                                    ttsRequestedAtMs = ttsRequestedAtMs,
                                    turnCompletedAtMs = clock.nowMs(),
                                ),
                            )
                            if (ttsEnabled) {
                                awaitTtsStarted(
                                    requestId,
                                    generation,
                                    source,
                                    before = { ttsController.speak(queryResult.message.text, utteranceId = requestId) },
                                )
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            // Logged, not silently swallowed (independent re-audit follow-up, second
                            // pass) — see the comment above: the turn's terminal state and completion
                            // are already committed, so this is purely a metrics/TTS side effect and must
                            // never be treated as a reason to revisit the outcome.
                            logSwallowedException("post-Success metrics/TTS", e, requestId, generation, source)
                        }
                    }

                    is GatewayResult.Failure -> {
                        val userFacingMessage = errorMessageFor(result.error)
                        val state = AssistantTurnState.Failure(requestId, generation, source, result.error, userFacingMessage)
                        conversationRepository.completeFailure(
                            requestId = requestId,
                            generation = generation,
                            source = source,
                            error = result.error,
                            userMessage = userFacingMessage,
                            retryableTurn = RetryableAssistantTurn(
                                // Only claim lineage to a request the network actually saw — a session
                                // resolution/contractVersion failure (or a caught unexpected exception,
                                // which by construction never reaches attempt.querySent = true unless the
                                // exception happened after the query was already dispatched) never
                                // dispatched a query at all (remediation item 1).
                                clientAttemptOf = if (attempt.querySent) requestId else null,
                                text = text,
                                screen = screen,
                                source = source,
                            ),
                        )
                        completion.complete(state)
                        try {
                            val ttsEnabled = appPreferences.value.ttsEnabled
                            metricsRecorder.record(
                                AssistantTurnMetrics(
                                    requestId = requestId,
                                    turnStartedAtMs = turnStartedAtMs,
                                    voiceCapture = captureTimings,
                                    sessionStartedAtMs = sessionStartedAtMs,
                                    requestSentAtMs = requestSentAtMs,
                                    responseReceivedAtMs = responseReceivedAtMs,
                                    turnCompletedAtMs = clock.nowMs(),
                                ),
                            )
                            if (source == AssistantTurnSource.VOICE && ttsEnabled) {
                                awaitTtsStarted(
                                    requestId,
                                    generation,
                                    source,
                                    before = { ttsController.speak(userFacingMessage, utteranceId = requestId) },
                                )
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            // Logged, not silently swallowed — see the Success branch's comment above.
                            logSwallowedException("post-Failure metrics/TTS", e, requestId, generation, source)
                        }
                    }
                }
            }
        }
        // Guarded, not a bare `currentJob = job` (independent re-audit follow-up, sixth pass): if
        // `externalScope` ever dispatches eagerly (e.g. `Dispatchers.Unconfined`, or any dispatcher that
        // happens to run inline until first suspension) and this specific attempt's gateway call never
        // actually suspends, the coroutine above can run to completion — including its own
        // `synchronized(lock) { ...; currentCompletion = null }` normal-path cleanup — *before* control
        // ever returns to this line. Unconditionally assigning `currentJob = job` afterwards would then
        // clobber that already-correct `null` with a reference to an already-dead `Job`, leaving
        // `currentJob` stale until the next accepted turn happens to overwrite it. Guarding on
        // `currentCompletion === completion` makes the assignment a no-op exactly when the coroutine has
        // already run its own cleanup (having cleared `currentCompletion` itself), and behaves exactly as
        // before (unconditional assignment) on every other path, where the coroutine is still genuinely
        // suspended and `currentCompletion` is still this attempt's own.
        synchronized(lock) {
            if (currentCompletion === completion) {
                currentJob = job
            }
        }
        // Safety net (independent re-audit follow-up, second pass): guarantees this turn is *always*
        // terminalized exactly once, even on paths the try/catch above cannot reach — this Job being
        // cancelled *before* its body ever runs (externalScope already cancelled at the moment `launch`
        // was called above), or a CancellationException originating from somewhere other than
        // cancelCurrent() (externalScope's parent being cancelled, or the gateway/session throwing
        // cancellation for an unrelated internal reason) while this generation is still current. Fires
        // on *every* way this Job reaches a final state, including normal completion — see
        // terminalizeAsCancelledIfAbandoned's KDoc for why it is always a safe no-op whenever
        // cancelCurrent() or this coroutine's own normal-path cleanup already ran.
        job.invokeOnCompletion {
            terminalizeAsCancelledIfAbandoned(requestId, generation, source, text, screen, attempt, completion)
        }
    }

    /** Terminalizes an accepted turn as [AssistantTurnState.Cancelled] if, and only if, nobody has
     * already terminalized it — called both from [beginTurnLocked]'s `onStarted` exception handler (for
     * a `CancellationException` thrown before the turn's own coroutine ever exists) and from that
     * coroutine's `Job.invokeOnCompletion` safety net (for a `CancellationException`/abandonment that
     * happens during or around the coroutine's own execution).
     *
     * Safe to call unconditionally, any number of times, from any thread: [cancelCurrent] always bumps
     * [currentGeneration] *before* touching anything else, and this coroutine's own normal-path
     * terminalization (in [beginTurnLocked]) always clears [currentCompletion] under [lock] *before*
     * returning — so by the time either of those has genuinely run, `generation != currentGeneration` or
     * `currentCompletion !== completion` is already true here, and this becomes a no-op. It only ever
     * does real work when *neither* of those ran — i.e. exactly the abandonment cases this safety net
     * exists for. Uses `synchronized(lock)` directly (not assuming the caller already holds it): safe
     * whether invoked reentrantly from a thread already holding [lock] (Java monitors are reentrant) or
     * from a completely different thread (e.g. whatever thread drives `invokeOnCompletion`). */
    private fun terminalizeAsCancelledIfAbandoned(
        requestId: String,
        generation: Long,
        source: AssistantTurnSource,
        text: String,
        screen: String,
        attempt: InFlightAttempt,
        completion: CompletableDeferred<AssistantTurnState>,
    ) {
        synchronized(lock) {
            if (generation != currentGeneration || currentCompletion !== completion) return
            currentJob = null
            currentAttempt = null
            currentCompletion = null
            try {
                val cancelledState = AssistantTurnState.Cancelled(requestId, generation, source)
                conversationRepository.completeCancelled(
                    requestId = requestId,
                    generation = generation,
                    source = source,
                    retryableTurn = RetryableAssistantTurn(
                        // Only claim lineage to a request the network actually saw (remediation item 1) —
                        // an abandoned turn whose query was never dispatched must not fabricate one.
                        clientAttemptOf = if (attempt.querySent) requestId else null,
                        text = text,
                        screen = screen,
                        source = source,
                        wasCancelled = true,
                    ),
                )
                completion.complete(cancelledState)
            } catch (e: Exception) {
                // This runs from coroutines-internal completion machinery (Job.invokeOnCompletion) — an
                // uncaught exception here must never propagate into that machinery. Logged, not silent.
                logSwallowedException("abandoned-turn cancellation terminalization", e, requestId, generation, source)
            }
        }
    }

    /** Redacts and forwards a deliberately-swallowed (non-`CancellationException`) exception to
     * [logger] — never [Throwable.message] directly (which could itself echo transcript/response
     * content back out via the exception chain), only the exception's class name, and only
     * requestId/generation/source, never message bodies (independent re-audit follow-up, second pass:
     * these sites used to swallow silently with no diagnostic trail at all). */
    private fun logSwallowedException(context: String, e: Exception, requestId: String, generation: Long, source: AssistantTurnSource) {
        logger(
            "AssistantTurnCoordinator: swallowed exception in $context " +
                "requestId=$requestId generation=$generation source=$source exceptionType=${e::class.simpleName}",
        )
    }

    /** For the health-blocked path in [submit] only, where [turn]`.completion` is already resolved at
     * construction time (the turn never goes in-flight at all, so there is nothing left to terminalize
     * regardless of what [onStarted] does). [onStarted] is caller-supplied and invoked synchronously
     * while still holding [lock] — a bug in a caller's callback (e.g. [VoiceAssistantCoordinator.route]'s
     * `{ turn -> startedTurn = turn }`) must never crash [submit] itself. `CancellationException` is
     * still always rethrown (cooperative cancellation), and any other exception is logged — never
     * silently swallowed (independent re-audit follow-up, second pass) — via [logSwallowedException]. */
    private fun invokeOnStartedSafely(onStarted: (StartedTurn) -> Unit, turn: StartedTurn, source: AssistantTurnSource) {
        try {
            onStarted(turn)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logSwallowedException("onStarted callback (health-blocked path)", e, turn.requestId, turn.generation, source)
        }
    }

    /** Subscribes to [TtsController.events] for [requestId]'s utterance *before* calling [before]
     * (which must trigger [TtsController.speak]), then waits (briefly, bounded) for the real `onStart`
     * callback and patches [AssistantTurnMetrics.ttsStartedAtMs] in after the fact.
     *
     * Subscription order matters: [TtsController.events] is a hot, non-replaying stream — if the
     * collector were only registered *after* [TtsController.speak] was called, an engine whose
     * `onStart` callback fires synchronously (or on another thread faster than this coroutine gets
     * scheduled) would emit into a flow nobody is listening to yet, and that event would be lost
     * forever, not merely delayed (remediation item 4 — the old bug). Starting the collector with
     * [CoroutineStart.UNDISPATCHED] guarantees it has already suspended waiting inside
     * `events.first { ... }` (i.e. is registered as a subscriber) before this function returns control
     * to the caller and [before] runs — so no emission for this utterance, however fast, can race
     * ahead of it. */
    private fun awaitTtsStarted(requestId: String, generation: Long, source: AssistantTurnSource, before: () -> Unit) {
        val ttsStarted: Deferred<Long?> = externalScope.async(start = CoroutineStart.UNDISPATCHED) {
            withTimeoutOrNull(TTS_START_WAIT_MS) {
                ttsController.events.first { it.utteranceId == requestId }.startedAtMs
            }
        }
        before()
        externalScope.launch {
            val startedAtMs = ttsStarted.await()
            if (startedAtMs != null) {
                // This turn's terminal state was already committed and its completion already resolved
                // before awaitTtsStarted() was ever called (it only runs inside the Success branch,
                // after both) — a failure patching in this timestamp is a metrics side effect only and
                // must never crash this independently-launched coroutine (independent re-audit
                // follow-up, blocker 1).
                try {
                    metricsRecorder.recordTtsStarted(requestId, startedAtMs)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Logged, not silently swallowed — see comment above.
                    logSwallowedException("recordTtsStarted", e, requestId, generation, source)
                }
            }
        }
    }

    private fun errorMessageFor(error: GatewayError): String = when (error) {
        GatewayError.Timeout -> "Yêu cầu quá thời gian chờ. Vui lòng thử lại."
        GatewayError.Offline -> "Mất kết nối mạng. Vui lòng kiểm tra lại."
        GatewayError.Unauthorized -> "Phiên làm việc đã hết hạn."
        GatewayError.Unsupported -> "Tính năng trợ lý chưa khả dụng."
        is GatewayError.Conflict -> "Dữ liệu đã thay đổi, vui lòng thử lại."
        is GatewayError.Validation -> error.message ?: "Câu hỏi không hợp lệ."
        is GatewayError.Server -> "Máy chủ gặp sự cố. Vui lòng thử lại."
        is GatewayError.Protocol -> "Phản hồi không hợp lệ từ máy chủ."
        is GatewayError.Configuration -> when (error.reasonCode) {
            "CONTRACT_VERSION_INCOMPATIBLE" -> "Phiên bản API của máy chủ không tương thích với ứng dụng này. Vui lòng cập nhật ứng dụng."
            else -> "Chưa cấu hình BASE_URL cho Remote Mode. Vào Cài đặt để thiết lập."
        }
        is GatewayError.Unexpected -> "Đã xảy ra lỗi không mong muốn. Vui lòng thử lại."
    }
}
