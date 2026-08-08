package vn.edu.haui.hvs.safedrive.feature.assistant

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import vn.edu.haui.hvs.safedrive.core.common.AppClock
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import vn.edu.haui.hvs.safedrive.core.common.GatewayResult
import vn.edu.haui.hvs.safedrive.core.model.ActionType
import vn.edu.haui.hvs.safedrive.core.model.AssistantTurnSource
import vn.edu.haui.hvs.safedrive.core.model.ChatMessage
import vn.edu.haui.hvs.safedrive.core.model.ChatSender
import vn.edu.haui.hvs.safedrive.core.model.CockpitSnapshot
import vn.edu.haui.hvs.safedrive.core.model.SafeDriveAction
import vn.edu.haui.hvs.safedrive.domain.repository.ConversationRepository
import vn.edu.haui.hvs.safedrive.domain.repository.PreferencesRepository
import vn.edu.haui.hvs.safedrive.domain.repository.VehicleDataSource
import vn.edu.haui.hvs.safedrive.domain.usecase.AssistantTurnCoordinator
import vn.edu.haui.hvs.safedrive.domain.usecase.ConfirmActionUseCase
import vn.edu.haui.hvs.safedrive.domain.usecase.PendingPromptCoordinator

private const val SCREEN_ASSISTANT = "assistant"

/**
 * Presentation delegate over the application-scoped [ConversationRepository]/[AssistantTurnCoordinator]
 * (docs/android-mvp-plan/12 §4.1, W1.8). Owns only composer text and action-confirmation state —
 * messages/turn lifecycle live in the shared repository, so history and in-flight status survive this
 * ViewModel being recreated (rotation, tab navigation) and are identical whether a turn was started
 * from text, quick prompt or (from W2) voice.
 */
class AssistantViewModel(
    private val conversationRepository: ConversationRepository,
    private val assistantTurnCoordinator: AssistantTurnCoordinator,
    private val confirmActionUseCase: ConfirmActionUseCase,
    private val preferencesRepository: PreferencesRepository,
    private val cockpitSnapshot: StateFlow<CockpitSnapshot?>,
    private val pendingPromptCoordinator: PendingPromptCoordinator,
    private val vehicleDataSource: VehicleDataSource? = null,
    private val clock: AppClock? = null,
) : ViewModel() {

    private val _state = MutableStateFlow(AssistantUiState())
    val state: StateFlow<AssistantUiState> = _state.asStateFlow()

    private val _effects = Channel<AssistantUiEffect>(Channel.BUFFERED)
    val effects: Flow<AssistantUiEffect> = _effects.receiveAsFlow()

    init {
        viewModelScope.launch {
            conversationRepository.state.collect { conversation ->
                _state.update {
                    it.copy(
                        messages = conversation.messages,
                        isSending = conversation.inFlightTurn != null,
                        canRetry = conversation.retryableTurn != null,
                        errorMessage = conversation.errorMessage,
                    )
                }
            }
        }
        viewModelScope.launch {
            preferencesRepository.preferences.collect { prefs ->
                _state.update {
                    it.copy(
                        ttsEnabled = prefs.ttsEnabled,
                        developerMode = prefs.developerMode,
                        backendMode = prefs.backendMode,
                    )
                }
            }
        }
        viewModelScope.launch {
            cockpitSnapshot.collect { snapshot ->
                if (snapshot != null) {
                    _state.update { it.copy(connectionStatus = snapshot.connectionStatus) }
                }
            }
        }
        pendingPromptCoordinator.consume()?.let { prompt ->
            _state.update { it.copy(composerText = prompt) }
        }
    }

    fun onAction(action: AssistantUiAction) {
        when (action) {
            is AssistantUiAction.ComposerChanged -> _state.update { it.copy(composerText = action.text) }
            is AssistantUiAction.QuickPrompt -> submit(action.text, AssistantTurnSource.QUICK_PROMPT)
            AssistantUiAction.Send -> submit(_state.value.composerText, AssistantTurnSource.TEXT)
            AssistantUiAction.Retry -> assistantTurnCoordinator.retry()
            AssistantUiAction.CancelTurn -> assistantTurnCoordinator.cancelCurrent()
            is AssistantUiAction.ExecuteAction -> executeAction(action.action)
            AssistantUiAction.ConfirmPendingAction -> confirmPendingAction()
            AssistantUiAction.CancelPendingAction ->
                _state.update { it.copy(pendingAction = null, confirmError = null) }
            AssistantUiAction.ToggleTts ->
                viewModelScope.launch { preferencesRepository.setTtsEnabled(!_state.value.ttsEnabled) }
        }
    }

    /** Delegates to the shared coordinator; clears the composer only if a turn actually started. */
    private fun submit(text: String, source: AssistantTurnSource) {
        if (assistantTurnCoordinator.submit(text, source, SCREEN_ASSISTANT)) {
            _state.update { it.copy(composerText = "") }
        }
    }

    private fun executeAction(action: SafeDriveAction) {
        if (action.requiresConfirmation) {
            _state.update { it.copy(pendingAction = action, confirmError = null) }
        } else {
            performEffect(action)
        }
    }

    private fun confirmPendingAction() {
        val action = _state.value.pendingAction ?: return
        _state.update { it.copy(isConfirmingAction = true) }
        viewModelScope.launch {
            val contextVersion = cockpitSnapshot.value?.stateVersion ?: 0L
            when (val result = confirmActionUseCase(action, true, contextVersion)) {
                is GatewayResult.Success -> {
                    _state.update { it.copy(pendingAction = null, isConfirmingAction = false, confirmError = null) }
                    // The backend can return HTTP 200 with accepted=false (stale context, an engine
                    // temperature that crossed a safety threshold, a tampered/replayed confirmation --
                    // see MobileSessionStore.confirm_action() and RemoteSafeDriveGateway.confirmAction(),
                    // which only inspects HTTP status, never this field). A GatewayResult.Success
                    // wrapper alone does not mean the executor applied anything.
                    //
                    // This gate is deliberately centralized ahead of the per-type dispatch rather than
                    // living inside the HVAC branch: HVAC is the only type that mutates simulated
                    // vehicle state today, but acknowledging a backend *rejection* with a success
                    // effect -- a confirmation toast, a diagnostics screen, a "recorded" message --
                    // is an authority violation for every type, not just the one that writes state.
                    if (!result.data.accepted) {
                        _state.update {
                            it.copy(confirmError = "Không thể xác nhận hành động. Vui lòng thử lại.")
                        }
                        return@launch
                    }
                    if (action.type == ActionType.SET_HVAC_TEMPERATURE) {
                        appendHvacConfirmationMessage(action)
                    }
                    performEffect(action)
                }

                is GatewayResult.Failure -> {
                    _state.update {
                        it.copy(isConfirmingAction = false, confirmError = "Không thể xác nhận hành động. Vui lòng thử lại.")
                    }
                }
            }
        }
    }

    /** Persists the verified HVAC confirmation as a real conversation turn (SAFEDRIVE_AGENT_ARMOR_PLAN.md
     * Slice 5A) via [ConversationRepository.appendSystemNotice] -- the same safe, no-turn-implications
     * append already used for backend-confirmed facts that were never a user-turn reply (see
     * [vn.edu.haui.hvs.safedrive.SafeDriveContainer]'s proactive Safety Guardian notice). Called only
     * from the `result.data.accepted == true` branch above -- [action.hvacTargetTemperatureC] is trusted
     * here specifically because the backend's confirm_action() only accepts a confirmation whose target
     * exactly matches what it issued (a mismatched target is rejected before execution), so this is the
     * backend-verified value, not the optimistic proposal. */
    private fun appendHvacConfirmationMessage(action: SafeDriveAction) {
        val target = action.hvacTargetTemperatureC ?: return
        conversationRepository.appendSystemNotice(
            ChatMessage(
                id = "${action.id}_confirmed",
                sender = ChatSender.SAFEDRIVE,
                text = "Tôi đã đặt điều hòa ở ${target.toInt()}°C.",
                timestampMs = clock?.nowMs() ?: System.currentTimeMillis(),
                route = "action.confirm.hvac",
            ),
        )
    }

    private fun performEffect(action: SafeDriveAction) {
        viewModelScope.launch {
            when (action.type) {
                ActionType.OPEN_DIAGNOSTICS -> _effects.send(AssistantUiEffect.OpenDiagnostics)
                ActionType.SHOW_WARNING -> _effects.send(AssistantUiEffect.ShowMessage("Cảnh báo an toàn đã được ghi nhận."))
                ActionType.SUGGEST_REST_STOP -> _effects.send(AssistantUiEffect.ShowMessage("Đã ghi nhận đề xuất dừng nghỉ."))
                ActionType.START_SOS_COUNTDOWN ->
                    // The Emergency countdown (docs/android-mvp-plan/12) is only ever triggered by the
                    // crash-detection evidence rule (SafeDriveContainer) or the Simulator's crash
                    // preset, not directly from an assistant reply — confirming this action does not
                    // itself start a countdown. The old message claimed this would arrive "ở Phase 6",
                    // which was already stale by the time Phase 6/Emergency shipped; this no longer
                    // promises a future phase or a capability the app doesn't have.
                    _effects.send(
                        AssistantUiEffect.ShowMessage(
                            "Đã ghi nhận yêu cầu hỗ trợ khẩn cấp. Để xem luồng SOS mô phỏng đầy đủ, dùng kịch bản va chạm trong Simulator.",
                        ),
                    )
                ActionType.SET_HVAC_TEMPERATURE -> applySimulatedHvacTarget(action)
                ActionType.NONE -> Unit // unknown/no-op action — never crashes, never silently "succeeds"
            }
        }
    }

    private suspend fun applySimulatedHvacTarget(action: SafeDriveAction) {
        val target = action.hvacTargetTemperatureC
        val snapshot = cockpitSnapshot.value
        val dataSource = vehicleDataSource
        val currentClock = clock
        if (target == null || snapshot == null || dataSource == null || currentClock == null) {
            _effects.send(AssistantUiEffect.ShowMessage("Không có đủ trạng thái xe để cập nhật HVAC mô phỏng."))
            return
        }
        dataSource.updateManual(
            vehicleState = snapshot.vehicleState.copy(
                hvacTargetTemperatureC = target,
                updatedAtMs = currentClock.nowMs(),
            ),
            driverSupportSignals = snapshot.driverSupportSignals,
        )
        _effects.send(
            AssistantUiEffect.ShowMessage("Đã đặt điều hòa mô phỏng ở ${target.toInt()}°C."),
        )
    }
}
