package vn.edu.haui.hvs.safedrive.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import vn.edu.haui.hvs.safedrive.core.common.GatewayError
import vn.edu.haui.hvs.safedrive.core.common.GatewayResult
import vn.edu.haui.hvs.safedrive.core.model.BackendMode
import vn.edu.haui.hvs.safedrive.core.model.ChatSender
import vn.edu.haui.hvs.safedrive.core.model.CockpitSnapshot
import vn.edu.haui.hvs.safedrive.core.model.HealthStatus
import vn.edu.haui.hvs.safedrive.core.model.SimulatedLatencyProfile
import vn.edu.haui.hvs.safedrive.core.observability.AssistantTurnMetricsRecorder
import vn.edu.haui.hvs.safedrive.domain.repository.ConversationState
import vn.edu.haui.hvs.safedrive.domain.repository.GatewayProvider
import vn.edu.haui.hvs.safedrive.domain.repository.PreferencesRepository

private const val HEALTH_CHECK_TIMEOUT_MS = 5_000L

/** Intermediate holder so a 6th flow (conversation state) can be combined without exceeding
 * kotlinx.coroutines.flow.combine's typed-arity overloads. */
private data class BasicsSnapshot(
    val prefs: vn.edu.haui.hvs.safedrive.domain.repository.AppPreferences,
    val health: HealthCheckState,
    val draft: String?,
    val error: String?,
    val lastTurn: vn.edu.haui.hvs.safedrive.core.observability.AssistantTurnMetrics?,
)

/**
 * Settings ViewModel. Health check always calls the real gateway — never a fake `setTimeout`-style
 * ping (see docs/android-mvp-plan/01-source-audit.md, "Những gì phải viết lại"), and is capped at the
 * 5s budget from docs/android-mvp-plan/12 W5 (never hangs on the network timeout's own 8s).
 * [onHealthChecked] feeds the last known capability set to `AssistantTurnCoordinator`
 * (docs/android-mvp-plan/12 W5.10) — this is the only place in the app that calls `checkHealth()`.
 */
class SettingsViewModel(
    private val preferencesRepository: PreferencesRepository,
    private val gatewayProvider: GatewayProvider,
    private val metricsRecorder: AssistantTurnMetricsRecorder,
    cockpitSnapshot: StateFlow<CockpitSnapshot?>,
    conversationState: StateFlow<ConversationState>,
    appVersionLabel: String,
    private val onHealthChecked: (HealthStatus) -> Unit = {},
) : ViewModel() {

    private val _healthStatus = MutableStateFlow<HealthCheckState>(HealthCheckState.Idle)
    private val _baseUrlDraft = MutableStateFlow<String?>(null)
    private val _baseUrlError = MutableStateFlow<String?>(null)
    private val _baseUrlSaveState = MutableStateFlow<BaseUrlSaveState>(BaseUrlSaveState.Idle)

    private val basics = combine(
        preferencesRepository.preferences,
        _healthStatus,
        _baseUrlDraft,
        _baseUrlError,
        metricsRecorder.lastTurn,
    ) { prefs, health, draft, error, lastTurn -> BasicsSnapshot(prefs, health, draft, error, lastTurn) }

    val uiState: StateFlow<SettingsUiState> = combine(
        basics,
        conversationState,
    ) { snapshot, conversation ->
        val (prefs, health, draft, error, lastTurn) = snapshot
        // Explicit fields only -- never inferred from the `model` string.
        val lastSafeDriveMessage = conversation.messages.lastOrNull { it.sender == ChatSender.SAFEDRIVE }
        SettingsUiState(
            ttsEnabled = prefs.ttsEnabled,
            wakeWordEnabled = prefs.wakeWordEnabled,
            developerMode = prefs.developerMode,
            backendMode = prefs.backendMode,
            baseUrl = prefs.baseUrl,
            baseUrlDraft = draft ?: prefs.baseUrl,
            baseUrlError = error,
            baseUrlSaveState = _baseUrlSaveState.value,
            healthStatus = health,
            appVersion = appVersionLabel,
            wearableConnected = cockpitSnapshot.value?.vehicleState?.wearableConnected ?: false,
            developerLatencyProfile = prefs.developerLatencyProfile,
            lastTurnLatencySummary = lastTurn?.let {
                "Tổng: ${it.totalTurnMs ?: "?"}ms · Mạng: ${it.networkMs ?: "?"}ms · " +
                    "Phiên: ${it.sessionMs ?: "?"}ms · TTS: ${it.responseToTtsStartMs ?: "?"}ms" +
                    (it.micStartToReadyMs?.let { mic -> " · Mic→sẵn sàng: ${mic}ms" } ?: "")
            },
            lastLlmUsed = lastSafeDriveMessage?.llmUsed,
            lastFallback = lastSafeDriveMessage?.fallback ?: false,
            lastFallbackReason = lastSafeDriveMessage?.fallbackReason,
            lastModel = lastSafeDriveMessage?.model,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = SettingsUiState(appVersion = appVersionLabel),
    )

    fun setDeveloperLatencyProfile(profile: SimulatedLatencyProfile) {
        viewModelScope.launch { preferencesRepository.setDeveloperLatencyProfile(profile) }
    }

    fun setTtsEnabled(enabled: Boolean) {
        viewModelScope.launch { preferencesRepository.setTtsEnabled(enabled) }
    }

    fun setWakeWordEnabled(enabled: Boolean) {
        viewModelScope.launch { preferencesRepository.setWakeWordEnabled(enabled) }
    }

    fun setDeveloperMode(enabled: Boolean) {
        viewModelScope.launch { preferencesRepository.setDeveloperMode(enabled) }
    }

    fun setBackendMode(mode: BackendMode) {
        viewModelScope.launch { preferencesRepository.setBackendMode(mode) }
    }

    fun onBaseUrlDraftChanged(text: String) {
        _baseUrlDraft.value = text
        _baseUrlError.value = null
        _baseUrlSaveState.value = BaseUrlSaveState.Idle
    }

    fun applyBaseUrl(url: String) {
        viewModelScope.launch {
            _baseUrlSaveState.value = BaseUrlSaveState.Saving
            when (val result = preferencesRepository.setBaseUrl(url)) {
                is GatewayResult.Success -> {
                    _baseUrlError.value = null
                    val normalizedUrl = url.trim().let { if (it.endsWith('/')) it else "$it/" }
                    _baseUrlSaveState.value = BaseUrlSaveState.Saved(normalizedUrl)
                    _baseUrlDraft.value = null
                }

                is GatewayResult.Failure -> {
                    _baseUrlError.value = errorMessageFor(result.error)
                    _baseUrlSaveState.value = BaseUrlSaveState.Idle
                }
            }
        }
    }

    fun checkHealth() {
        viewModelScope.launch {
            _healthStatus.value = HealthCheckState.Checking
            val prefs = uiState.value
            val result = withTimeoutOrNull(HEALTH_CHECK_TIMEOUT_MS) { gatewayProvider.current().checkHealth() }
            when (result) {
                null -> _healthStatus.value = HealthCheckState.Failure("Hết thời gian chờ phản hồi (>${HEALTH_CHECK_TIMEOUT_MS / 1000}s).")

                is GatewayResult.Success -> {
                    onHealthChecked(result.data)
                    val label = if (prefs.backendMode == BackendMode.DEMO) {
                        "Local Mock"
                    } else {
                        runCatching { java.net.URI(prefs.baseUrl).host }.getOrNull() ?: prefs.baseUrl
                    }
                    _healthStatus.value = HealthCheckState.Success(
                        "$label · ${result.data.serviceName} · API ${result.data.apiVersion} · " +
                            "assistant=${result.data.capabilities.assistant}",
                    )
                }

                is GatewayResult.Failure -> _healthStatus.value = HealthCheckState.Failure(errorMessageFor(result.error))
            }
        }
    }

    private fun errorMessageFor(error: GatewayError): String = when (error) {
        GatewayError.Timeout -> "Hết thời gian chờ phản hồi."
        GatewayError.Offline -> "Không thể kết nối tới máy chủ."
        GatewayError.Unauthorized -> "Phiên làm việc không hợp lệ."
        GatewayError.Unsupported -> "Máy chủ không hỗ trợ tính năng này."
        is GatewayError.Conflict -> "Xung đột dữ liệu, vui lòng thử lại."
        is GatewayError.Validation -> error.message ?: "URL không hợp lệ."
        is GatewayError.Server -> "Máy chủ gặp sự cố."
        is GatewayError.Protocol -> "Phản hồi không hợp lệ từ máy chủ."
        is GatewayError.Configuration -> "Chưa cấu hình BASE_URL cho Remote Mode."
        is GatewayError.Unexpected -> "Đã xảy ra lỗi không mong muốn."
    }
}
