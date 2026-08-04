package vn.edu.haui.hvs.safedrive.feature.settings

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import vn.edu.haui.hvs.safedrive.core.designsystem.Dimensions
import vn.edu.haui.hvs.safedrive.core.designsystem.LocalSafeDriveStatusColors
import vn.edu.haui.hvs.safedrive.core.model.BackendMode

/** Settings per docs/android-mvp-plan/04-screen-specs.md. Developer section only renders when
 * [SettingsUiState.developerMode] is true — raw endpoint/health data is never shown to a normal user. */
@Composable
fun SettingsScreen(viewModel: SettingsViewModel, onOpenSimulator: () -> Unit) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = LocalSafeDriveStatusColors.current
    val context = LocalContext.current
    var micPermissionGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted -> micPermissionGranted = granted }
    var notificationPermissionGranted by remember {
        mutableStateOf(
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED,
        )
    }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted -> notificationPermissionGranted = granted }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(Dimensions.screenPadding),
        verticalArrangement = Arrangement.spacedBy(Dimensions.cardSpacing),
    ) {
        item {
            Text("Cài đặt ứng dụng SafeDrive AI", style = MaterialTheme.typography.titleLarge)
        }

        item {
            SectionCard(title = "Âm thanh & Tương tác giọng nói") {
                SettingsToggleRow(
                    title = "Phản hồi giọng nói (TTS)",
                    subtitle = "Phát âm thanh đọc phản hồi từ Trợ lý SafeDrive AI",
                    checked = state.ttsEnabled,
                    onCheckedChange = viewModel::setTtsEnabled,
                )
                SettingsToggleRow(
                    title = "Nhận diện \"Mai ơi\"",
                    subtitle = "Lắng nghe nền, kể cả khi ứng dụng không mở",
                    checked = state.wakeWordEnabled,
                    onCheckedChange = viewModel::setWakeWordEnabled,
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Quyền microphone: ${if (micPermissionGranted) "Đã cấp phép" else "Chưa cấp phép"}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (!micPermissionGranted) {
                        Button(onClick = { micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO) }) {
                            Text("Cấp quyền")
                        }
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Quyền thông báo: ${if (notificationPermissionGranted) "Đã cấp phép" else "Chưa cấp phép"}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (!notificationPermissionGranted) {
                        Button(onClick = { notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }) {
                            Text("Cấp quyền")
                        }
                    }
                }
            }
        }

        item {
            SectionCard(title = "Nguồn tín hiệu") {
                Text(
                    "Kết nối wearable: ${if (state.wearableConnected) "Đã kết nối" else "Chưa kết nối"}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        item {
            SectionCard(title = "Cam kết bảo mật") {
                Text(
                    "SafeDrive AI chỉ phân tích gián tiếp các thông số vận hành xe (thời gian lái, " +
                        "tương tác vô lăng, hiện diện ghế lái và wearable). Không thu thập hoặc xử lý " +
                        "hình ảnh từ camera/DMS.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceMuted,
                )
            }
        }

        item {
            SectionCard(title = "Chế độ demo & kỹ thuật") {
                SettingsToggleRow(
                    title = "Bật kịch bản demo",
                    subtitle = "Mô phỏng tín hiệu xe, cấu hình BASE_URL và kiểm tra kết nối backend",
                    checked = state.developerMode,
                    onCheckedChange = viewModel::setDeveloperMode,
                )
            }
        }

        if (state.developerMode) {
            item {
                SectionCard(title = "Mô phỏng trạng thái xe") {
                    Text(
                        "Chọn tình huống để trình diễn cách SafeDrive hiểu bối cảnh và phản hồi an toàn.",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceMuted,
                    )
                    Button(onClick = onOpenSimulator, modifier = Modifier.padding(top = 8.dp)) {
                        Text("Mở kịch bản demo")
                    }
                }
            }

            item {
                SectionCard(title = "Backend mode") {
                    BackendMode.entries.forEach { mode ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            RadioButton(
                                selected = state.backendMode == mode,
                                onClick = { viewModel.setBackendMode(mode) },
                            )
                            Text(if (mode == BackendMode.DEMO) "Demo (Mock Gateway)" else "Remote (backend thật)")
                        }
                    }
                }
            }

            item {
                SectionCard(title = "BASE_URL") {
                    OutlinedTextField(
                        value = state.baseUrlDraft,
                        onValueChange = viewModel::onBaseUrlDraftChanged,
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        isError = state.baseUrlError != null,
                        supportingText = state.baseUrlError?.let { { Text(it) } },
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        baseUrlPresets.forEach { preset ->
                            Button(onClick = { viewModel.applyBaseUrl(preset.url) }) {
                                Text(preset.label, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                    Button(
                        onClick = { viewModel.applyBaseUrl(state.baseUrlDraft) },
                        modifier = Modifier.padding(top = 8.dp),
                        enabled = state.baseUrlSaveState !is BaseUrlSaveState.Saving,
                    ) {
                        Text(
                            if (state.baseUrlSaveState is BaseUrlSaveState.Saving) "Đang lưu..." else "Lưu BASE_URL",
                        )
                    }
                    when (val saveState = state.baseUrlSaveState) {
                        BaseUrlSaveState.Idle, BaseUrlSaveState.Saving -> Unit
                        is BaseUrlSaveState.Saved -> Text(
                            "Đã lưu BASE_URL: ${saveState.normalizedUrl}",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.normal.icon,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            }

            item {
                SectionCard(title = "Độ trễ giả lập (Developer Mode)") {
                    Text(
                        "Chỉ áp dụng cho Demo Mode; mặc định KHÔNG có độ trễ giả (docs W4.3).",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.onSurfaceMuted,
                    )
                    latencyProfileLabels.forEach { (profile, label) ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            RadioButton(
                                selected = state.developerLatencyProfile == profile,
                                onClick = { viewModel.setDeveloperLatencyProfile(profile) },
                            )
                            Text(label)
                        }
                    }
                    state.lastTurnLatencySummary?.let { summary ->
                        Text(
                            "Lượt gần nhất — $summary",
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.onSurfaceMuted,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
            }

            item {
                SectionCard(title = "Kiểm tra kết nối") {
                    Button(onClick = viewModel::checkHealth) {
                        Text(
                            when (state.healthStatus) {
                                HealthCheckState.Checking -> "Đang kiểm tra..."
                                else -> "Kiểm tra sức khỏe backend"
                            },
                        )
                    }
                    val healthText = when (val health = state.healthStatus) {
                        HealthCheckState.Idle -> null
                        HealthCheckState.Checking -> null
                        is HealthCheckState.Success -> health.message
                        is HealthCheckState.Failure -> health.message
                    }
                    val isError = state.healthStatus is HealthCheckState.Failure
                    if (healthText != null) {
                        Text(
                            healthText,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isError) MaterialTheme.colorScheme.error else colors.normal.icon,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                    // From the last SAFEDRIVE reply's explicit llmUsed/fallback fields only -- never
                    // parsed out of the developer-only `model` string, and never shown on the normal
                    // Cockpit/Assistant screens (developer/settings surface only).
                    Text(
                        "LLM: ${llmStatusLabel(state.lastLlmUsed, state.lastFallback)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceMuted,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    state.lastFallbackReason?.let { reason ->
                        Text(
                            "Lý do fallback gần nhất: $reason",
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.onSurfaceMuted,
                        )
                    }
                }
            }
        }

        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.surface, RoundedCornerShape(Dimensions.cardCornerRadius))
                    .padding(Dimensions.cardPadding),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(state.appVersion, style = MaterialTheme.typography.titleSmall)
                Text(
                    "Hanoi University of Industry (HaUI) — Vehicle Smart Systems (HVS)",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceMuted,
                )
            }
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    val colors = LocalSafeDriveStatusColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surface, RoundedCornerShape(Dimensions.cardCornerRadius))
            .padding(Dimensions.cardPadding),
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = colors.onSurfaceMuted)
        Column(modifier = Modifier.padding(top = 8.dp)) { content() }
    }
}

@Composable
private fun SettingsToggleRow(title: String, subtitle: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(subtitle, style = MaterialTheme.typography.labelSmall)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
