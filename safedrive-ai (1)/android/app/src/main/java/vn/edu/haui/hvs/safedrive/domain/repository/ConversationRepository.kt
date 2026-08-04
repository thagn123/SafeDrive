package vn.edu.haui.hvs.safedrive.domain.repository

import kotlinx.coroutines.flow.StateFlow
import vn.edu.haui.hvs.safedrive.core.common.GatewayError
import vn.edu.haui.hvs.safedrive.core.model.AssistantTurnSource
import vn.edu.haui.hvs.safedrive.core.model.AssistantTurnState
import vn.edu.haui.hvs.safedrive.core.model.ChatMessage
import vn.edu.haui.hvs.safedrive.core.model.InFlightAssistantTurn
import vn.edu.haui.hvs.safedrive.core.model.RetryableAssistantTurn

/** See [ConversationRepository]. [turnState] is the authoritative W1.2 state machine
 * ([AssistantTurnState]) — [inFlightTurn]/[retryableTurn]/[errorMessage] are kept alongside it for
 * existing readers (e.g. [vn.edu.haui.hvs.safedrive.feature.assistant.AssistantViewModel]) but are
 * always published in the same atomic update as [turnState], never as separate, order-dependent
 * writes (independent re-audit follow-up item 3). */
data class ConversationState(
    val messages: List<ChatMessage> = emptyList(),
    val inFlightTurn: InFlightAssistantTurn? = null,
    val retryableTurn: RetryableAssistantTurn? = null,
    val errorMessage: String? = null,
    val turnState: AssistantTurnState = AssistantTurnState.Idle,
)

/**
 * Application-scoped conversation state: messages, the single in-flight turn and retry lineage
 * (docs/android-mvp-plan/12 §4.2). One instance is shared by text, quick-prompt and voice input so
 * Assistant history survives navigation and ViewModel recreation within the same process — chat
 * persistence across process death is explicitly out of scope for this stabilization
 * (see KNOWN_LIMITATIONS.md). Turn lifecycle *decisions* (when to call these, request id / generation
 * bookkeeping) live in [vn.edu.haui.hvs.safedrive.domain.usecase.AssistantTurnCoordinator], not here —
 * this interface only guarantees that each of its calls publishes one atomic, internally-consistent
 * [ConversationState].
 *
 * [beginTurn]/[completeSuccess]/[completeFailure]/[completeCancelled]/[rejectBeforeInFlight] each
 * perform exactly one [kotlinx.coroutines.flow.MutableStateFlow.update] — a caller can never observe a
 * state where [ConversationState.inFlightTurn] has already been cleared but [ConversationState.turnState]/
 * [ConversationState.messages]/[ConversationState.retryableTurn]/[ConversationState.errorMessage] still
 * reflect the *previous* turn (independent re-audit follow-up item 3; the old interface exposed
 * `setInFlightTurn`/`setRetryableTurn`/`setErrorMessage`/`addAssistantMessage` as independent setters,
 * so a terminal transition was 2-3 separate publishes a collector could interleave with).
 *
 * The [beginTurn] overload taking a [ChatMessage] and [rejectBeforeInFlight] exist for the same reason,
 * one level up the pipeline (independent re-audit follow-up, blocker 2): appending the turn's own user
 * bubble and starting/rejecting the turn used to be two separate publishes
 * (`addUserMessage()` then `beginTurn()`/`completeFailure()`) — `AssistantTurnCoordinator.submit()`'s
 * `synchronized(lock)` only ever serialized its *own* callers against each other, never against a
 * `StateFlow` collector running on a different thread/dispatcher, so that collector really could
 * observe the new user bubble already appended while [ConversationState.inFlightTurn] was still `null`
 * and [ConversationState.turnState]/[ConversationState.retryableTurn]/[ConversationState.errorMessage]
 * still reflected the *previous* turn. Both new methods fold the message append into the same update as
 * the turn transition, closing that window the same way item 3 closed it for terminal transitions.
 */
interface ConversationRepository {
    val state: StateFlow<ConversationState>

    /** Atomically appends [message] and starts [turn] in the same [state] update — used by a fresh
     * [AssistantTurnCoordinator.submit] call, which always appends exactly one new user bubble together
     * with starting the turn it belongs to. */
    fun beginTurn(message: ChatMessage, turn: InFlightAssistantTurn)

    /** Atomically starts [turn] with **no** new user message: publishes [InFlightAssistantTurn] and
     * [AssistantTurnState.InFlight] together, clearing any previous turn's
     * [ConversationState.retryableTurn]/[ConversationState.errorMessage] in the same update. Used only
     * by [AssistantTurnCoordinator.retry], which must never append a second bubble for the same text
     * (W1.12). */
    fun beginTurn(turn: InFlightAssistantTurn)

    /** Atomically completes the turn identified by [requestId]/[generation]/[source] with [reply]:
     * clears in-flight, appends [reply] to [ConversationState.messages], clears retry/error state and
     * publishes [AssistantTurnState.Success] — all in one [state] update. */
    fun completeSuccess(requestId: String, generation: Long, source: AssistantTurnSource, reply: ChatMessage)

    /** Atomically completes the turn identified by [requestId]/[generation]/[source] with [error]:
     * clears in-flight, publishes the user-facing [userMessage]/[retryableTurn] and
     * [AssistantTurnState.Failure] — all in one [state] update. Only ever called for a turn that was
     * already begun via [beginTurn] (its user message, if any, was already appended atomically then) —
     * never appends a message itself. */
    fun completeFailure(
        requestId: String,
        generation: Long,
        source: AssistantTurnSource,
        error: GatewayError,
        userMessage: String,
        retryableTurn: RetryableAssistantTurn,
    )

    /** Atomically appends [message] and completes as [AssistantTurnState.Failure] in the same [state]
     * update, without the turn ever going in-flight at all — used only by
     * [AssistantTurnCoordinator.submit]'s health-blocked path (a confirmed-unsupported backend rejects
     * the turn before any network call is even attempted). Exists as its own method, distinct from
     * [beginTurn] + [completeFailure], because this turn never begins — there is no in-flight window to
     * publish and then immediately end. */
    fun rejectBeforeInFlight(
        message: ChatMessage,
        requestId: String,
        generation: Long,
        source: AssistantTurnSource,
        error: GatewayError,
        userMessage: String,
        retryableTurn: RetryableAssistantTurn,
    )

    /** Atomically completes the turn identified by [requestId]/[generation]/[source] as user-cancelled:
     * clears in-flight, publishes [retryableTurn] and [AssistantTurnState.Cancelled] — all in one
     * [state] update. Never touches [ConversationState.errorMessage] (cancelling is not a failure). */
    fun completeCancelled(requestId: String, generation: Long, source: AssistantTurnSource, retryableTurn: RetryableAssistantTurn)

    /** Appends [message] with no turn implications at all — never touches [ConversationState.inFlightTurn]/
     * [ConversationState.retryableTurn]/[ConversationState.errorMessage]/[ConversationState.turnState].
     * Used only for system-originated notices (e.g. the proactive Safety Guardian risk warning in
     * [vn.edu.haui.hvs.safedrive.SafeDriveContainer]) that were never requested by a user turn and so
     * must never be mistaken for one by a collector watching [ConversationState.turnState]. */
    fun appendSystemNotice(message: ChatMessage)
}
