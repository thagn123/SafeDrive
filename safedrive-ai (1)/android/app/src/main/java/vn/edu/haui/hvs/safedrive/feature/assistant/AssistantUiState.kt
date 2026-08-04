package vn.edu.haui.hvs.safedrive.feature.assistant

import vn.edu.haui.hvs.safedrive.core.model.ChatMessage
import vn.edu.haui.hvs.safedrive.core.model.BackendMode
import vn.edu.haui.hvs.safedrive.core.model.SafeDriveAction
import vn.edu.haui.hvs.safedrive.core.model.SystemConnectionStatus

/** Assistant screen state per docs/android-mvp-plan/04-screen-specs.md ("Assistant"). */
data class AssistantUiState(
    val messages: List<ChatMessage> = emptyList(),
    val composerText: String = "",
    val isSending: Boolean = false,
    val ttsEnabled: Boolean = true,
    val developerMode: Boolean = false,
    val backendMode: BackendMode = BackendMode.DEMO,
    val connectionStatus: SystemConnectionStatus = SystemConnectionStatus.NORMAL,
    val pendingAction: SafeDriveAction? = null,
    val isConfirmingAction: Boolean = false,
    val confirmError: String? = null,
    val errorMessage: String? = null,
    val canRetry: Boolean = false,
)

sealed interface AssistantUiAction {
    data class ComposerChanged(val text: String) : AssistantUiAction
    data object Send : AssistantUiAction
    data object Retry : AssistantUiAction
    data object CancelTurn : AssistantUiAction
    data class QuickPrompt(val text: String) : AssistantUiAction
    data class ExecuteAction(val action: SafeDriveAction) : AssistantUiAction
    data object ConfirmPendingAction : AssistantUiAction
    data object CancelPendingAction : AssistantUiAction
    data object ToggleTts : AssistantUiAction
}

/** One-shot effects the screen consumes once (navigation/snackbar) — never stored back into [AssistantUiState]. */
sealed interface AssistantUiEffect {
    data object OpenDiagnostics : AssistantUiEffect
    data class ShowMessage(val text: String) : AssistantUiEffect
}
