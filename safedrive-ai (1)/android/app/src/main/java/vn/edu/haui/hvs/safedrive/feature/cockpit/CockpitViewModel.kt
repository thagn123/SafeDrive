package vn.edu.haui.hvs.safedrive.feature.cockpit

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import vn.edu.haui.hvs.safedrive.core.model.CockpitSnapshot
import vn.edu.haui.hvs.safedrive.core.model.SystemConnectionStatus
import vn.edu.haui.hvs.safedrive.domain.repository.PreferencesRepository
import vn.edu.haui.hvs.safedrive.voice.VoiceController

class CockpitViewModel(
    cockpitSnapshot: StateFlow<CockpitSnapshot?>,
    voiceController: VoiceController,
    preferencesRepository: PreferencesRepository,
) : ViewModel() {

    val uiState: StateFlow<CockpitUiState> = combine(
        cockpitSnapshot.filterNotNull(),
        voiceController.state,
        preferencesRepository.preferences,
    ) { snapshot, voice, prefs ->
        CockpitUiState.Content(
            vehicleState = snapshot.vehicleState,
            driverSupportSignals = snapshot.driverSupportSignals,
            riskAssessment = snapshot.riskAssessment,
            restRecommendation = snapshot.restRecommendation,
            connectionStatus = snapshot.connectionStatus,
            voiceState = voice.state,
            waitingForWakePhrase = voice.waitingForWakePhrase,
            voicePartialTranscript = voice.partialTranscript,
            voiceFinalTranscript = voice.finalTranscript,
            voiceErrorMessage = voice.errorMessage,
            isStale = snapshot.connectionStatus != SystemConnectionStatus.NORMAL,
            developerMode = prefs.developerMode,
            backendMode = prefs.backendMode,
        )
    }
        .map<CockpitUiState.Content, CockpitUiState> { it }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000),
            initialValue = CockpitUiState.Loading,
        )
}
