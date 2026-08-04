package vn.edu.haui.hvs.safedrive.feature.cockpit

import vn.edu.haui.hvs.safedrive.core.model.DriverSupportSignals
import vn.edu.haui.hvs.safedrive.core.model.BackendMode
import vn.edu.haui.hvs.safedrive.core.model.RestRecommendation
import vn.edu.haui.hvs.safedrive.core.model.RiskAssessment
import vn.edu.haui.hvs.safedrive.core.model.SystemConnectionStatus
import vn.edu.haui.hvs.safedrive.core.model.VehicleState
import vn.edu.haui.hvs.safedrive.core.model.VoiceState

/**
 * Cockpit screen state per docs/android-mvp-plan/04-screen-specs.md. Risk/rest/DTC severity are
 * always gateway output copied verbatim — this state never derives or overrides them.
 */
sealed interface CockpitUiState {
    data object Loading : CockpitUiState

    data class Content(
        val vehicleState: VehicleState,
        val driverSupportSignals: DriverSupportSignals,
        val riskAssessment: RiskAssessment,
        val restRecommendation: RestRecommendation,
        val connectionStatus: SystemConnectionStatus,
        val voiceState: VoiceState,
        val waitingForWakePhrase: Boolean = false,
        val voicePartialTranscript: String = "",
        val voiceFinalTranscript: String = "",
        val voiceErrorMessage: String? = null,
        val isStale: Boolean,
        val developerMode: Boolean = false,
        val backendMode: BackendMode = BackendMode.DEMO,
    ) : CockpitUiState
}
