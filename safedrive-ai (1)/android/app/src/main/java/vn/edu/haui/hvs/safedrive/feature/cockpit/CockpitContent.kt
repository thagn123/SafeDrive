package vn.edu.haui.hvs.safedrive.feature.cockpit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import vn.edu.haui.hvs.safedrive.core.designsystem.Dimensions
import vn.edu.haui.hvs.safedrive.feature.cockpit.components.CockpitHeader
import vn.edu.haui.hvs.safedrive.feature.cockpit.components.DriverSignalSummaryCard
import vn.edu.haui.hvs.safedrive.feature.cockpit.components.DtcSummaryCard
import vn.edu.haui.hvs.safedrive.feature.cockpit.components.StatusHeroCard
import vn.edu.haui.hvs.safedrive.feature.cockpit.components.VehicleMetricsPanel
import vn.edu.haui.hvs.safedrive.feature.cockpit.components.VoiceStatusCard

/**
 * Adaptive Cockpit body per docs/android-mvp-plan/04-screen-specs.md ("Quy tắc adaptive Cockpit"):
 * portrait is a single non-scrolling column; landscape splits into two columns. No fixed pixel
 * viewport height is used — Compose constraints and weights drive both layouts.
 */
@Composable
fun CockpitContent(
    state: CockpitUiState.Content,
    onOpenDetails: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onTriggerVoice: () -> Unit,
    onOpenSimulator: () -> Unit = {},
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val isLandscape = maxWidth > maxHeight
        if (isLandscape) {
            LandscapeCockpitContent(state, onOpenDetails, onOpenDiagnostics, onTriggerVoice, onOpenSimulator)
        } else {
            PortraitCockpitContent(state, onOpenDetails, onOpenDiagnostics, onTriggerVoice, onOpenSimulator)
        }
    }
}

@Composable
private fun PortraitCockpitContent(
    state: CockpitUiState.Content,
    onOpenDetails: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onTriggerVoice: () -> Unit,
    onOpenSimulator: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(Dimensions.screenPadding),
        verticalArrangement = Arrangement.spacedBy(Dimensions.cardSpacing),
    ) {
        CockpitHeader(
            connectionStatus = state.connectionStatus,
            backendMode = state.backendMode,
            onOpenSimulator = onOpenSimulator,
        )
        StaleBanner(state)
        StatusHeroCard(
            riskAssessment = state.riskAssessment,
            restRecommendation = state.restRecommendation,
            driverSupportSignals = state.driverSupportSignals,
            onOpenDetails = onOpenDetails,
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.30f),
        )
        VehicleMetricsPanel(vehicleState = state.vehicleState, modifier = Modifier.weight(0.32f))
        DriverSignalSummaryCard(
            vehicleState = state.vehicleState,
            driverSupportSignals = state.driverSupportSignals,
            onOpenDetails = onOpenDetails,
            modifier = Modifier.weight(0.20f),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.18f),
            horizontalArrangement = Arrangement.spacedBy(Dimensions.cardSpacing),
        ) {
            DtcSummaryCard(
                activeDtcs = state.vehicleState.activeDtcs,
                onOpenDiagnostics = onOpenDiagnostics,
                modifier = Modifier.weight(1f),
            )
            VoiceStatusCard(
                voiceState = state.voiceState,
                waitingForWakePhrase = state.waitingForWakePhrase,
                partialTranscript = state.voicePartialTranscript,
                finalTranscript = state.voiceFinalTranscript,
                errorMessage = state.voiceErrorMessage,
                onTriggerVoice = onTriggerVoice,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun LandscapeCockpitContent(
    state: CockpitUiState.Content,
    onOpenDetails: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onTriggerVoice: () -> Unit,
    onOpenSimulator: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(Dimensions.screenPadding),
        verticalArrangement = Arrangement.spacedBy(Dimensions.cardSpacing),
    ) {
        CockpitHeader(
            connectionStatus = state.connectionStatus,
            backendMode = state.backendMode,
            onOpenSimulator = onOpenSimulator,
        )
        StaleBanner(state)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            horizontalArrangement = Arrangement.spacedBy(Dimensions.cardSpacing),
        ) {
            StatusHeroCard(
                riskAssessment = state.riskAssessment,
                restRecommendation = state.restRecommendation,
                driverSupportSignals = state.driverSupportSignals,
                onOpenDetails = onOpenDetails,
                modifier = Modifier.weight(0.5f),
            )
            Column(
                modifier = Modifier.weight(0.5f),
                verticalArrangement = Arrangement.spacedBy(Dimensions.cardSpacing),
            ) {
                VehicleMetricsPanel(vehicleState = state.vehicleState, modifier = Modifier.weight(1f))
                DriverSignalSummaryCard(
                    vehicleState = state.vehicleState,
                    driverSupportSignals = state.driverSupportSignals,
                    onOpenDetails = onOpenDetails,
                    modifier = Modifier.weight(1f),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(Dimensions.cardSpacing)) {
                    DtcSummaryCard(
                        activeDtcs = state.vehicleState.activeDtcs,
                        onOpenDiagnostics = onOpenDiagnostics,
                        modifier = Modifier.weight(1f),
                    )
                    VoiceStatusCard(
                        voiceState = state.voiceState,
                        waitingForWakePhrase = state.waitingForWakePhrase,
                        partialTranscript = state.voicePartialTranscript,
                        finalTranscript = state.voiceFinalTranscript,
                        errorMessage = state.voiceErrorMessage,
                        onTriggerVoice = onTriggerVoice,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun StaleBanner(state: CockpitUiState.Content) {
    if (!state.isStale) return
    Text(
        text = "Dữ liệu cũ — đang hiển thị giá trị gần nhất",
        style = MaterialTheme.typography.labelSmall,
        color = Color(0xFFFBBF24),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
    )
}
