package vn.edu.haui.hvs.safedrive.feature.cockpit

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import vn.edu.haui.hvs.safedrive.feature.cockpit.components.DriverSupportDetailsDialog

/**
 * Cockpit screen per docs/android-mvp-plan/04-screen-specs.md: one non-scrolling viewport in
 * portrait, adaptive two-column in landscape (see [CockpitContent]). [onOpenDiagnostics] navigates to
 * the Diagnostics tab; [onTriggerVoice] is a no-op placeholder until Phase 5 wires a real
 * [vn.edu.haui.hvs.safedrive.voice.VoiceController]. The driver-support details dialog is local UI
 * state owned by this screen — it is not safety state and does not need a ViewModel.
 */
@Composable
fun CockpitScreen(
    viewModel: CockpitViewModel,
    onOpenDiagnostics: () -> Unit,
    onTriggerVoice: () -> Unit,
    onOpenSimulator: () -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var showDetails by rememberSaveable { mutableStateOf(false) }

    when (val state = uiState) {
        is CockpitUiState.Loading -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(
                modifier = Modifier.semantics { contentDescription = "Đang tải dữ liệu xe" },
            )
        }

        is CockpitUiState.Content -> {
            CockpitContent(
                state = state,
                onOpenDetails = { showDetails = true },
                onOpenDiagnostics = onOpenDiagnostics,
                onTriggerVoice = onTriggerVoice,
                onOpenSimulator = onOpenSimulator,
            )
            if (showDetails) {
                DriverSupportDetailsDialog(
                    signals = state.driverSupportSignals,
                    restRecommendation = state.restRecommendation,
                    onDismiss = { showDetails = false },
                )
            }
        }
    }
}
