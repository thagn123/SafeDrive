package vn.edu.haui.hvs.safedrive.core.model

import vn.edu.haui.hvs.safedrive.core.common.GatewayError

/**
 * How an assistant turn's input text originated (docs/android-mvp-plan/12
 * §4.2/W1.1). Text, quick prompt and voice all funnel through the same
 * [vn.edu.haui.hvs.safedrive.domain.usecase.AssistantTurnCoordinator] — this is the only thing that
 * differs between them at the coordinator level.
 */
enum class AssistantTurnSource { TEXT, VOICE, QUICK_PROMPT, RETRY }

/**
 * The single turn currently being processed, if any. Its presence is the global single-flight lock
 * (docs/android-mvp-plan/12 W1.11): while non-null, [vn.edu.haui.hvs.safedrive.domain.usecase.AssistantTurnCoordinator.submit]
 * is a no-op.
 */
data class InFlightAssistantTurn(
    val requestId: String,
    val generation: Long,
    val source: AssistantTurnSource,
    val text: String,
    val screen: String,
)

/**
 * A turn that failed or was cancelled after its user bubble was already appended, so it can be
 * retried without duplicating that bubble (docs/android-mvp-plan/12 W1.12/W1.13). [clientAttemptOf]
 * is the original turn's `requestId` — the exact id that was actually sent on the wire — kept for
 * lineage even though retry mints a new one. `null` when no attempt was ever actually sent to the
 * network for this turn: blocked by a known-unavailable capability before any request was built,
 * cancelled while session resolution was still suspended, or the session itself failed/was
 * incompatible before the assistant query was ever dispatched — a retry must never claim a
 * `clientAttemptOf` the backend never received (remediation item 1).
 *
 * [wasCancelled] distinguishes a user-initiated [vn.edu.haui.hvs.safedrive.domain.usecase.AssistantTurnCoordinator.cancelCurrent]
 * from a genuine gateway/session failure (remediation item 5) — both populate this same retryable
 * turn, but only a real failure also sets [ConversationState.errorMessage][vn.edu.haui.hvs.safedrive.domain.repository.ConversationState.errorMessage].
 * Readers that need to tell these apart (e.g. the voice overlay deciding whether to show an error
 * bubble) must use this field rather than inferring it from `errorMessage == null`.
 */
data class RetryableAssistantTurn(
    val clientAttemptOf: String?,
    val text: String,
    val screen: String,
    val source: AssistantTurnSource,
    val wasCancelled: Boolean = false,
)

/**
 * Full W1.2 turn state machine — idle/in-flight/success/failure/cancelled — carried as
 * [vn.edu.haui.hvs.safedrive.domain.repository.ConversationState.turnState]. Every terminal value
 * ([Success]/[Failure]/[Cancelled]) is published in the exact same [kotlinx.coroutines.flow.MutableStateFlow.update]
 * call that also updates [vn.edu.haui.hvs.safedrive.domain.repository.ConversationState.messages]/`retryableTurn`/`errorMessage`
 * (see [vn.edu.haui.hvs.safedrive.domain.repository.ConversationRepository.completeSuccess]/`completeFailure`/`completeCancelled`)
 * — independent re-audit follow-up item 3. Before this fix, a success/failure transition was published
 * as two or three *separate* `update` calls (`setInFlightTurn(null)` first, then `addAssistantMessage`/
 * `setErrorMessage`/`setRetryableTurn` afterward), so a collector could observe `inFlightTurn == null`
 * with no terminal outcome yet visible — e.g. [vn.edu.haui.hvs.safedrive.domain.usecase.VoiceAssistantCoordinator]
 * waking up between those two updates and reading a stale/previous-turn reply. [requestId]/[generation]
 * on every case let a reader correlate a terminal state to the exact turn it started, instead of
 * inferring "whichever turn just finished" from ambient state.
 */
sealed interface AssistantTurnState {
    data object Idle : AssistantTurnState

    data class InFlight(
        val requestId: String,
        val generation: Long,
        val source: AssistantTurnSource,
        val text: String,
        val screen: String,
    ) : AssistantTurnState

    data class Success(
        val requestId: String,
        val generation: Long,
        val source: AssistantTurnSource,
        val reply: ChatMessage,
    ) : AssistantTurnState

    data class Failure(
        val requestId: String,
        val generation: Long,
        val source: AssistantTurnSource,
        val error: GatewayError,
        val userMessage: String,
    ) : AssistantTurnState

    data class Cancelled(
        val requestId: String,
        val generation: Long,
        val source: AssistantTurnSource,
    ) : AssistantTurnState
}
