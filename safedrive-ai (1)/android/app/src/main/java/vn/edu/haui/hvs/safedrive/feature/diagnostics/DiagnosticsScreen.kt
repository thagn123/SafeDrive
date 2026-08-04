package vn.edu.haui.hvs.safedrive.feature.diagnostics

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.collectLatest
import vn.edu.haui.hvs.safedrive.core.designsystem.Dimensions
import vn.edu.haui.hvs.safedrive.core.designsystem.LocalSafeDriveStatusColors
import vn.edu.haui.hvs.safedrive.core.designsystem.StatusBadge
import vn.edu.haui.hvs.safedrive.core.designsystem.paletteForSeverity
import vn.edu.haui.hvs.safedrive.core.model.Dtc

/** Diagnostics per docs/android-mvp-plan/04-screen-specs.md. */
@Composable
fun DiagnosticsScreen(
    viewModel: DiagnosticsViewModel,
    onNavigateToAssistant: () -> Unit,
    onNavigateToSimulator: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(viewModel) {
        viewModel.effects.collectLatest { effect ->
            when (effect) {
                DiagnosticsUiEffect.NavigateToAssistant -> onNavigateToAssistant()
                DiagnosticsUiEffect.NavigateToSimulator -> onNavigateToSimulator()
            }
        }
    }

    when (val current = state) {
        is DiagnosticsUiState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }

        is DiagnosticsUiState.Content -> DiagnosticsContent(
            state = current,
            onAskAssistant = viewModel::askAssistant,
            onOpenSimulator = viewModel::openSimulator,
        )
    }
}

@Composable
private fun DiagnosticsContent(
    state: DiagnosticsUiState.Content,
    onAskAssistant: (Dtc) -> Unit,
    onOpenSimulator: () -> Unit,
) {
    val colors = LocalSafeDriveStatusColors.current
    Column(modifier = Modifier.fillMaxSize().padding(Dimensions.screenPadding)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Chẩn đoán kỹ thuật DTC", style = MaterialTheme.typography.titleLarge)
            Text(
                "Lỗi đang hoạt động: ${state.dtcs.size}",
                style = MaterialTheme.typography.labelMedium,
                color = colors.onSurfaceMuted,
            )
        }

        if (state.dtcs.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = colors.normal.icon)
                Text(
                    "Không có lỗi kỹ thuật đang hoạt động",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 12.dp),
                )
                Text(
                    "Tất cả các hệ thống cảm biến, truyền động và động cơ đang hoạt động đúng tiêu chuẩn kỹ thuật an toàn.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceMuted,
                    modifier = Modifier.padding(top = 4.dp),
                )
                if (state.developerMode) {
                    Button(onClick = onOpenSimulator, modifier = Modifier.padding(top = 16.dp)) {
                        Text("Chuyển tới Simulator (Dev Mode)")
                    }
                }
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(Dimensions.cardSpacing)) {
                items(state.dtcs, key = { it.code }) { dtc ->
                    DtcCard(dtc = dtc, onAskAssistant = { onAskAssistant(dtc) })
                }
            }
        }
    }
}

@Composable
private fun DtcCard(dtc: Dtc, onAskAssistant: () -> Unit) {
    val colors = LocalSafeDriveStatusColors.current
    val palette = paletteForSeverity(dtc.severity)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surface, RoundedCornerShape(Dimensions.cardCornerRadius))
            .padding(Dimensions.cardPadding),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Warning, contentDescription = null, tint = palette.icon)
            Text(dtc.code, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 8.dp))
            StatusBadge(text = dtc.severity.name, palette = palette, modifier = Modifier.padding(start = 8.dp))
        }
        Text(dtc.title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 4.dp))
        Text(
            dtc.description,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(
            "Khuyến nghị: ${dtc.recommendation}",
            style = MaterialTheme.typography.bodySmall,
            color = colors.normal.icon,
            modifier = Modifier.padding(top = 8.dp),
        )
        Button(onClick = onAskAssistant, modifier = Modifier.padding(top = 12.dp)) {
            Text("Hỏi SafeDrive AI")
        }
    }
}
