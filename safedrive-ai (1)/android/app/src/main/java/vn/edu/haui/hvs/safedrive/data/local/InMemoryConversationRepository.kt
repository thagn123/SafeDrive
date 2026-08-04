package vn.edu.haui.hvs.safedrive.data.local

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import vn.edu.haui.hvs.safedrive.core.common.GatewayError
import vn.edu.haui.hvs.safedrive.core.model.AssistantTurnSource
import vn.edu.haui.hvs.safedrive.core.model.AssistantTurnState
import vn.edu.haui.hvs.safedrive.core.model.ChatMessage
import vn.edu.haui.hvs.safedrive.core.model.InFlightAssistantTurn
import vn.edu.haui.hvs.safedrive.core.model.RetryableAssistantTurn
import vn.edu.haui.hvs.safedrive.domain.repository.ConversationRepository
import vn.edu.haui.hvs.safedrive.domain.repository.ConversationState

/**
 * In-memory only (docs/android-mvp-plan/12 §4.2: chat persistence across process death is not a
 * stabilization gate). One instance lives for the application's process lifetime in
 * [vn.edu.haui.hvs.safedrive.SafeDriveContainer].
 */
class InMemoryConversationRepository(initialMessages: List<ChatMessage>) : ConversationRepository {

    private val _state = MutableStateFlow(ConversationState(messages = initialMessages))
    override val state: StateFlow<ConversationState> = _state.asStateFlow()

    override fun beginTurn(message: ChatMessage, turn: InFlightAssistantTurn) {
        _state.update {
            it.copy(
                messages = it.messages + message,
                inFlightTurn = turn,
                retryableTurn = null,
                errorMessage = null,
                turnState = AssistantTurnState.InFlight(
                    requestId = turn.requestId,
                    generation = turn.generation,
                    source = turn.source,
                    text = turn.text,
                    screen = turn.screen,
                ),
            )
        }
    }

    override fun beginTurn(turn: InFlightAssistantTurn) {
        _state.update {
            it.copy(
                inFlightTurn = turn,
                retryableTurn = null,
                errorMessage = null,
                turnState = AssistantTurnState.InFlight(
                    requestId = turn.requestId,
                    generation = turn.generation,
                    source = turn.source,
                    text = turn.text,
                    screen = turn.screen,
                ),
            )
        }
    }

    override fun completeSuccess(requestId: String, generation: Long, source: AssistantTurnSource, reply: ChatMessage) {
        _state.update {
            it.copy(
                messages = it.messages + reply,
                inFlightTurn = null,
                retryableTurn = null,
                errorMessage = null,
                turnState = AssistantTurnState.Success(requestId, generation, source, reply),
            )
        }
    }

    override fun completeFailure(
        requestId: String,
        generation: Long,
        source: AssistantTurnSource,
        error: GatewayError,
        userMessage: String,
        retryableTurn: RetryableAssistantTurn,
    ) {
        _state.update {
            it.copy(
                inFlightTurn = null,
                retryableTurn = retryableTurn,
                errorMessage = userMessage,
                turnState = AssistantTurnState.Failure(requestId, generation, source, error, userMessage),
            )
        }
    }

    override fun rejectBeforeInFlight(
        message: ChatMessage,
        requestId: String,
        generation: Long,
        source: AssistantTurnSource,
        error: GatewayError,
        userMessage: String,
        retryableTurn: RetryableAssistantTurn,
    ) {
        _state.update {
            it.copy(
                messages = it.messages + message,
                inFlightTurn = null,
                retryableTurn = retryableTurn,
                errorMessage = userMessage,
                turnState = AssistantTurnState.Failure(requestId, generation, source, error, userMessage),
            )
        }
    }

    override fun completeCancelled(requestId: String, generation: Long, source: AssistantTurnSource, retryableTurn: RetryableAssistantTurn) {
        _state.update {
            it.copy(
                inFlightTurn = null,
                retryableTurn = retryableTurn,
                errorMessage = null,
                turnState = AssistantTurnState.Cancelled(requestId, generation, source),
            )
        }
    }

    override fun appendSystemNotice(message: ChatMessage) {
        _state.update { it.copy(messages = it.messages + message) }
    }
}
