package vn.edu.haui.hvs.safedrive.feature.simulator

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.collectLatest
import vn.edu.haui.hvs.safedrive.core.designsystem.Dimensions
import vn.edu.haui.hvs.safedrive.core.designsystem.LocalSafeDriveStatusColors
import vn.edu.haui.hvs.safedrive.core.model.BackendMode
import vn.edu.haui.hvs.safedrive.feature.simulator.components.CrashSignalTestPanel
import vn.edu.haui.hvs.safedrive.feature.simulator.components.JsonPreviewDialog
import vn.edu.haui.hvs.safedrive.feature.simulator.components.ScenarioPresetCard
import vn.edu.haui.hvs.safedrive.feature.simulator.components.SignalDashboard

/**
 * Guided scenario runner for the SafeDrive demo. Everyone can select a prepared scenario;
 * Developer Mode additionally exposes manual signal editing and the JSON inspection tool.
 * The top app bar always shows the current backend mode and a Back action.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SimulatorScreen(viewModel: SimulatorViewModel, onBack: () -> Unit) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var showAdvancedControls by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(viewModel) {
        viewModel.effects.collectLatest { effect ->
            when (effect) {
                is SimulatorUiEffect.ShowMessage -> snackbarHostState.showSnackbar(effect.text)
            }
        }
    }

    if (state.developerMode && showAdvancedControls) {
        state.jsonPreview?.let { json ->
            JsonPreviewDialog(json = json, onClose = viewModel::hideJsonPreview)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Kịch bản demo SafeDrive")
                        Text(
                            if (state.backendMode == BackendMode.DEMO) "Đang chạy Demo Mode" else "Đang gửi tới Remote Mode",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Quay lại")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(innerPadding)
            .padding(Dimensions.screenPadding),
        verticalArrangement = Arrangement.spacedBy(Dimensions.cardSpacing),
    ) {
        item {
            androidx.compose.material3.Card(
                colors = androidx.compose.material3.CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Nhận dữ liệu từ PC (ADB)", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Cho phép script gửi thông số qua ADB Broadcast.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                    Switch(
                        checked = state.adbTelemetryEnabled,
                        onCheckedChange = { viewModel.toggleAdbTelemetry(it) }
                    )
                }
            }
        }
        item {
            DemoFlowCard()
        }
        item {
            SynchronizationCard(
                backendMode = state.backendMode,
                message = state.syncMessage,
                isSynchronizing = state.isSynchronizing,
            )
        }
        item {
            if (state.backendMode == BackendMode.DEMO) {
                SignalDashboard(uiState = state)
            }
        }
        item {
            if (state.developerMode) {
                CrashSignalTestPanel(onInject = viewModel::injectCrashSignal)
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Chọn một tình huống", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Chạm vào thẻ để áp dụng tín hiệu ngay. Quay lại Cockpit hoặc Trợ lý để xem SafeDrive đánh giá và phản hồi.",
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalSafeDriveStatusColors.current.onSurfaceMuted,
                )
            }
        }
        items(state.presets, key = { it.id }) { preset ->
            ScenarioPresetCard(
                preset = preset,
                isSelected = state.selectedPresetId == preset.id,
                onSelect = { viewModel.selectPreset(preset) },
            )
        }

        if (state.developerMode && showAdvancedControls) {
            item {
                ManualTelemetrySection(
                    form = state.manual,
                    onFormChanged = viewModel::updateManual,
                )
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Dimensions.cardSpacing),
                ) {
                    Button(onClick = viewModel::applyManual, modifier = Modifier.weight(1f)) {
                        Text("Áp dụng trạng thái")
                    }
                    OutlinedButton(onClick = viewModel::resetToDefault, modifier = Modifier.weight(1f)) {
                        Text("Khôi phục bình thường")
                    }
                }
            }

            item {
                OutlinedButton(onClick = viewModel::showJsonPreview, modifier = Modifier.fillMaxWidth()) {
                    Text("Xem JSON demo")
                }
            }
        } else {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Dimensions.cardSpacing),
                ) {
                    OutlinedButton(onClick = viewModel::resetToDefault, modifier = Modifier.weight(1f)) {
                        Text("Đặt lại trạng thái bình thường")
                    }
                    if (state.developerMode) {
                        OutlinedButton(
                            onClick = { showAdvancedControls = true },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("Tùy chỉnh tín hiệu")
                        }
                    }
                }
            }
        }
    }
    }
}

@Composable
private fun SynchronizationCard(backendMode: BackendMode, message: String, isSynchronizing: Boolean) {
    val colors = LocalSafeDriveStatusColors.current
    val title = if (backendMode == BackendMode.REMOTE) "Đồng bộ Backend thật" else "Trạng thái mô phỏng cục bộ"
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surface, RoundedCornerShape(Dimensions.cardCornerRadius))
            .padding(Dimensions.cardPadding),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Text(message, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceMuted)
        if (backendMode == BackendMode.REMOTE && isSynchronizing) {
            Text("Chờ xác nhận trước khi hỏi Trợ lý.", style = MaterialTheme.typography.labelSmall, color = colors.normal.icon)
        }
    }
}

@Composable
private fun DemoFlowCard() {
    val colors = LocalSafeDriveStatusColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surface, RoundedCornerShape(Dimensions.cardCornerRadius))
            .padding(Dimensions.cardPadding),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("Luồng trình diễn 3 bước", style = MaterialTheme.typography.titleSmall)
        Text("1. Chọn kịch bản: mệt mỏi, lỗi xe hoặc va chạm.", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceMuted)
        Text("2. SafeDrive nhận tín hiệu, kiểm tra độ mới dữ liệu và đánh giá mức rủi ro.", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceMuted)
        Text("3. Mở Cockpit hoặc Trợ lý để xem giải thích, khuyến nghị và SOS mô phỏng khi cần.", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceMuted)
    }
}

@Composable
private fun ManualTelemetrySection(form: ManualTelemetryForm, onFormChanged: ((ManualTelemetryForm) -> ManualTelemetryForm) -> Unit) {
    val colors = LocalSafeDriveStatusColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surface, RoundedCornerShape(Dimensions.cardCornerRadius))
            .padding(Dimensions.cardPadding),
    ) {
        Text("Tùy chỉnh thông số thủ công", style = MaterialTheme.typography.titleSmall)

        LabeledSlider(
            label = "Tốc độ",
            value = form.speedKmh,
            valueRange = 0f..160f,
            unit = "km/h",
            onValueChange = { v -> onFormChanged { it.copy(speedKmh = v) } },
        )
        LabeledSlider(
            label = "Nhiệt độ cabin",
            value = form.cabinTemperatureC,
            valueRange = 16f..35f,
            unit = "°C",
            onValueChange = { v -> onFormChanged { it.copy(cabinTemperatureC = v) } },
        )
        LabeledSlider(
            label = "Nhiệt độ động cơ",
            value = form.engineTemperatureC,
            valueRange = 70f..125f,
            unit = "°C",
            onValueChange = { v -> onFormChanged { it.copy(engineTemperatureC = v) } },
        )
        LabeledSlider(
            label = "Năng lượng",
            value = form.energyPercent,
            valueRange = 0f..100f,
            unit = "%",
            onValueChange = { v -> onFormChanged { it.copy(energyPercent = v) } },
        )

        ToggleRow(
            "Có dữ liệu thời gian lái",
            form.hasDrivingMinutes,
            { onFormChanged { form -> form.copy(hasDrivingMinutes = it) } },
        )
        if (form.hasDrivingMinutes) {
            LabeledSlider(
                label = "Thời gian lái liên tục",
                value = form.drivingMinutes.toFloat(),
                valueRange = 0f..300f,
                unit = "phút",
                onValueChange = { v -> onFormChanged { it.copy(drivingMinutes = v.toInt()) } },
            )
        }

        ToggleRow("Cảm biến vô lăng khả dụng", form.steeringAvailable) { onFormChanged { f -> f.copy(steeringAvailable = it) } }
        ToggleRow("Cảm biến ghế lái khả dụng", form.seatAvailable) { onFormChanged { f -> f.copy(seatAvailable = it) } }
        ToggleRow("Phát hiện người ngồi ghế lái", form.seatOccupied) { onFormChanged { f -> f.copy(seatOccupied = it) } }
        ToggleRow("Kết nối thiết bị wearable", form.wearableConnected) { onFormChanged { f -> f.copy(wearableConnected = it) } }
        if (form.wearableConnected) {
            LabeledSlider(
                label = "Nhịp tim wearable",
                value = form.wearableHeartRateBpm.toFloat(),
                valueRange = 40f..200f,
                unit = "bpm",
                onValueChange = { v -> onFormChanged { it.copy(wearableHeartRateBpm = v.toInt()) } },
            )
        }
        ToggleRow("Người dùng báo đang mệt", form.userReportedFatigue) { onFormChanged { f -> f.copy(userReportedFatigue = it) } }

        Text("Mã lỗi chẩn đoán DTC", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp))
        DtcSelectionRow(form.dtcSelection) { selection -> onFormChanged { it.copy(dtcSelection = selection) } }

        ToggleRow("Phát hiện va chạm xe", form.crashDetected) { onFormChanged { f -> f.copy(crashDetected = it) } }
        ToggleRow("Hành khách KHÔNG phản hồi", form.passengerUnresponsive) { onFormChanged { f -> f.copy(passengerUnresponsive = it) } }
    }
}

@Composable
private fun LabeledSlider(label: String, value: Float, valueRange: ClosedFloatingPointRange<Float>, unit: String, onValueChange: (Float) -> Unit) {
    Column(modifier = Modifier.padding(vertical = 6.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.labelMedium)
            Text("${value.toInt()} $unit", style = MaterialTheme.typography.labelMedium)
        }
        Slider(value = value, onValueChange = onValueChange, valueRange = valueRange)
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun DtcSelectionRow(selected: DtcSelection, onSelect: (DtcSelection) -> Unit) {
    Column {
        DtcOption("Không có lỗi", DtcSelection.NONE, selected, onSelect)
        DtcOption("P0301 - Misfire xi-lanh số 1 (MEDIUM)", DtcSelection.P0301, selected, onSelect)
        DtcOption("ENGINE_OVERHEAT - Quá nhiệt (HIGH)", DtcSelection.OVERHEAT, selected, onSelect)
    }
}

@Composable
private fun DtcOption(label: String, value: DtcSelection, selected: DtcSelection, onSelect: (DtcSelection) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        RadioButton(selected = selected == value, onClick = { onSelect(value) })
        Text(label, style = MaterialTheme.typography.bodySmall)
    }
}
