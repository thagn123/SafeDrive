package vn.edu.haui.hvs.safedrive.feature.diagnostics

import vn.edu.haui.hvs.safedrive.core.model.Dtc
import vn.edu.haui.hvs.safedrive.core.model.SystemConnectionStatus

/** Diagnostics state per docs/android-mvp-plan/04-screen-specs.md ("Diagnostics"). Severity/recommendation
 * always come straight from the gateway-provided [Dtc] list — this screen never reclassifies them. */
sealed interface DiagnosticsUiState {
    data object Loading : DiagnosticsUiState

    data class Content(
        val dtcs: List<Dtc>,
        val connectionStatus: SystemConnectionStatus,
        val updatedAtMs: Long,
        val developerMode: Boolean,
    ) : DiagnosticsUiState
}

sealed interface DiagnosticsUiEffect {
    data object NavigateToAssistant : DiagnosticsUiEffect
    data object NavigateToSimulator : DiagnosticsUiEffect
}
