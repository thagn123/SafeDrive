package vn.edu.haui.hvs.safedrive.feature.diagnostics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import vn.edu.haui.hvs.safedrive.core.model.CockpitSnapshot
import vn.edu.haui.hvs.safedrive.core.model.Dtc
import vn.edu.haui.hvs.safedrive.domain.repository.PreferencesRepository
import vn.edu.haui.hvs.safedrive.domain.usecase.PendingPromptCoordinator

class DiagnosticsViewModel(
    cockpitSnapshot: StateFlow<CockpitSnapshot?>,
    preferencesRepository: PreferencesRepository,
    private val pendingPromptCoordinator: PendingPromptCoordinator,
) : ViewModel() {

    private val _effects = Channel<DiagnosticsUiEffect>(Channel.BUFFERED)
    val effects: Flow<DiagnosticsUiEffect> = _effects.receiveAsFlow()

    val uiState: StateFlow<DiagnosticsUiState> = combine(
        cockpitSnapshot,
        preferencesRepository.preferences,
    ) { snapshot, prefs ->
        if (snapshot == null) {
            DiagnosticsUiState.Loading
        } else {
            DiagnosticsUiState.Content(
                dtcs = snapshot.vehicleState.activeDtcs,
                connectionStatus = snapshot.connectionStatus,
                updatedAtMs = snapshot.updatedAtMs,
                developerMode = prefs.developerMode,
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DiagnosticsUiState.Loading)

    fun askAssistant(dtc: Dtc) {
        pendingPromptCoordinator.prefill(
            "Hãy giải thích mã lỗi ${dtc.code} (${dtc.title}), mức độ nguy hiểm và tôi nên làm gì?",
        )
        viewModelScope.launch { _effects.send(DiagnosticsUiEffect.NavigateToAssistant) }
    }

    fun openSimulator() {
        viewModelScope.launch { _effects.send(DiagnosticsUiEffect.NavigateToSimulator) }
    }
}
