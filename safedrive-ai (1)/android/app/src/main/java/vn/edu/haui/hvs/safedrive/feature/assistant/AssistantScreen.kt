package vn.edu.haui.hvs.safedrive.feature.assistant

import android.content.Intent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.collectLatest
import vn.edu.haui.hvs.safedrive.core.designsystem.LocalSafeDriveStatusColors
import vn.edu.haui.hvs.safedrive.core.model.BackendMode
import vn.edu.haui.hvs.safedrive.core.model.SystemConnectionStatus
import vn.edu.haui.hvs.safedrive.domain.repository.TtsController
import vn.edu.haui.hvs.safedrive.domain.repository.TtsState
import vn.edu.haui.hvs.safedrive.feature.assistant.components.ChatBubbleItem
import vn.edu.haui.hvs.safedrive.feature.assistant.components.ConfirmActionDialog

private val quickPrompts = listOf(
    "Tôi đang cảm thấy mệt",
    "Tôi đã lái xe bao lâu rồi?",
    "Xe có cảnh báo an toàn nào không?",
    "Kiểm tra nhiệt độ động cơ",
    "Gợi ý điểm dừng nghỉ gần đây",
)

/**
 * Assistant per docs/android-mvp-plan/04-screen-specs.md. Text and (Phase 5) voice both go through
 * the same [AssistantViewModel]/[vn.edu.haui.hvs.safedrive.domain.usecase.AssistantQueryUseCase].
 */
@Composable
fun AssistantScreen(
    viewModel: AssistantViewModel,
    ttsController: TtsController,
    onOpenDiagnostics: () -> Unit,
    onTriggerVoice: () -> Unit,
    onOpenSimulator: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val ttsState by ttsController.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val listState = rememberLazyListState()

    LaunchedEffect(viewModel) {
        viewModel.effects.collectLatest { effect ->
            when (effect) {
                AssistantUiEffect.OpenDiagnostics -> onOpenDiagnostics()
                is AssistantUiEffect.ShowMessage -> snackbarHostState.showSnackbar(effect.text)
            }
        }
    }

    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty()) listState.animateScrollToItem(state.messages.size - 1)
    }

    state.pendingAction?.let { action ->
        ConfirmActionDialog(
            action = action,
            isConfirming = state.isConfirmingAction,
            errorMessage = state.confirmError,
            onConfirm = { viewModel.onAction(AssistantUiAction.ConfirmPendingAction) },
            onCancel = { viewModel.onAction(AssistantUiAction.CancelPendingAction) },
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Column(modifier = Modifier
            .fillMaxSize()
            .padding(innerPadding)) {
            AssistantHeader(
                isOffline = state.connectionStatus == SystemConnectionStatus.OFFLINE,
                backendMode = state.backendMode,
                ttsEnabled = state.ttsEnabled,
                ttsState = ttsState,
                onToggleTts = { viewModel.onAction(AssistantUiAction.ToggleTts) },
            )

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f)
                    .padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp),
            ) {
                if (state.messages.isEmpty()) {
                    item(key = "demo-guide") {
                        DemoGuideCard(
                            showScenarioShortcut = state.developerMode,
                            onOpenSimulator = onOpenSimulator,
                        )
                    }
                }
                items(state.messages, key = { it.id }) { message ->
                    ChatBubbleItem(
                        message = message,
                        developerMode = state.developerMode,
                        onExecuteAction = { viewModel.onAction(AssistantUiAction.ExecuteAction(it)) },
                    )
                }
                if (state.isSending) {
                    item(key = "thinking") {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp))
                            Text(
                                "SafeDrive đang phân tích dữ liệu xe...",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { viewModel.onAction(AssistantUiAction.CancelTurn) }) {
                                Text("Hủy")
                            }
                        }
                    }
                }
                if (state.errorMessage != null) {
                    item(key = "error") {
                        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Text(
                                state.errorMessage!!,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f),
                            )
                            if (state.canRetry) {
                                TextButton(onClick = { viewModel.onAction(AssistantUiAction.Retry) }) {
                                    Text("Thử lại")
                                }
                            }
                        }
                    }
                }
            }

            QuickPromptsRow(onSelect = { viewModel.onAction(AssistantUiAction.QuickPrompt(it)) })

            Composer(
                text = state.composerText,
                isSending = state.isSending,
                onTextChanged = { viewModel.onAction(AssistantUiAction.ComposerChanged(it)) },
                onSend = { viewModel.onAction(AssistantUiAction.Send) },
                onTriggerVoice = onTriggerVoice,
            )
        }
    }
}

@Composable
private fun DemoGuideCard(showScenarioShortcut: Boolean, onOpenSimulator: () -> Unit) {
    val colors = LocalSafeDriveStatusColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surface, RoundedCornerShape(8.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Bắt đầu một kịch bản demo", style = MaterialTheme.typography.titleSmall)
        Text(
            "1. Chọn tình huống.  2. SafeDrive đọc trạng thái xe mới nhất.  3. Hỏi trợ lý để xem lý do, mức rủi ro và hành động phù hợp.",
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceMuted,
        )
        if (showScenarioShortcut) {
            Button(onClick = onOpenSimulator) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null)
                Text("Mở kịch bản demo", modifier = Modifier.padding(start = 6.dp))
            }
        } else {
            Text(
                "Bạn có thể nói hoặc nhập: “Tôi đang cảm thấy mệt” hoặc “Xe có cảnh báo an toàn nào không?”.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceMuted,
            )
        }
    }
}

/**
 * The TTS icon reflects the real [TtsState], not just the boolean setting (docs/android-mvp-plan/12
 * W6.11): off when the user disabled it, a distinct tint while actually speaking, and an error tint
 * when the engine can't speak vi-VN at all — never silently identical to "off". When the engine
 * genuinely cannot speak (UNSUPPORTED/MISSING_DATA/ERROR) while the user has TTS turned on, a visible
 * banner with an actionable CTA is shown too — a differently-tinted icon alone is easy to miss and
 * gives the user no way to actually fix it (remediation item 7).
 */
@Composable
private fun AssistantHeader(
    isOffline: Boolean,
    backendMode: BackendMode,
    ttsEnabled: Boolean,
    ttsState: TtsState,
    onToggleTts: () -> Unit,
) {
    val colors = LocalSafeDriveStatusColors.current
    val context = LocalContext.current
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            Column {
                Text("Trợ lý SafeDrive AI", style = MaterialTheme.typography.titleMedium)
                Text(
                    when {
                        isOffline -> "Chế độ ngoại tuyến"
                        backendMode == BackendMode.REMOTE -> "Backend thật · phản hồi theo dữ liệu xe hiện tại"
                        else -> "Mô phỏng cục bộ · phản hồi mẫu"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceMuted,
                )
            }
            IconButton(onClick = onToggleTts) {
                val (icon, tint, description) = when {
                    !ttsEnabled -> Triple(Icons.Filled.VolumeOff, colors.onSurfaceMuted, "Bật đọc giọng nói")
                    ttsState == TtsState.SPEAKING -> Triple(Icons.Filled.VolumeUp, colors.normal.icon, "Đang đọc — Tắt đọc giọng nói")
                    ttsState == TtsState.UNSUPPORTED || ttsState == TtsState.MISSING_DATA || ttsState == TtsState.ERROR ->
                        Triple(Icons.Filled.VolumeUp, colors.high.icon, "Giọng nói tiếng Việt không khả dụng trên máy này")
                    else -> Triple(Icons.Filled.VolumeUp, colors.onSurfaceMuted, "Tắt đọc giọng nói")
                }
                Icon(imageVector = icon, contentDescription = description, tint = tint)
            }
        }
        if (isOffline) {
            Text(
                "Đang ở chế độ ngoại tuyến. Câu trả lời dùng dữ liệu cục bộ.",
                style = MaterialTheme.typography.labelSmall,
                color = colors.high.icon,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }
        val ttsUnavailable = ttsEnabled &&
            (ttsState == TtsState.UNSUPPORTED || ttsState == TtsState.MISSING_DATA || ttsState == TtsState.ERROR)
        if (ttsUnavailable) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                Text(
                    if (ttsState == TtsState.MISSING_DATA) {
                        "Thiếu gói dữ liệu giọng đọc tiếng Việt trên máy này."
                    } else {
                        "Giọng đọc tiếng Việt không khả dụng trên máy này."
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.high.icon,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    // Literal action string (`TextToSpeech.Engine.ACTION_TTS_SETTINGS`'s value) — used
                    // directly rather than the constant so this compiles regardless of which API level
                    // still declares it. Some OEMs may have no activity that resolves it, so this is
                    // best-effort: caught rather than left to crash the app.
                    onClick = {
                        try {
                            context.startActivity(Intent("com.android.settings.TTS_SETTINGS"))
                        } catch (_: android.content.ActivityNotFoundException) {
                            // No system TTS settings screen on this device/OEM — nothing more we can do.
                        }
                    },
                ) {
                    Text("Cài đặt giọng đọc")
                }
            }
        }
    }
}

/** Horizontally scrollable so 4 Vietnamese quick-prompt labels never clip/overflow on a 360dp-wide
 * screen (docs/android-mvp-plan/12 W6.9) — a plain [Row] has no such safety net. */
@Composable
private fun QuickPromptsRow(onSelect: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        quickPrompts.forEach { prompt ->
            TextButton(onClick = { onSelect(prompt) }) {
                Text(prompt, style = MaterialTheme.typography.labelSmall, maxLines = 1)
            }
        }
    }
}

@Composable
private fun Composer(
    text: String,
    isSending: Boolean,
    onTextChanged: (String) -> Unit,
    onSend: () -> Unit,
    onTriggerVoice: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .imePadding()
            .padding(12.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        IconButton(onClick = onTriggerVoice) {
            Icon(Icons.Filled.Mic, contentDescription = "Kích hoạt \"Mai ơi\" (giọng nói)")
        }
        OutlinedTextField(
            value = text,
            onValueChange = onTextChanged,
            modifier = Modifier.weight(1f),
            placeholder = { Text("Nhập câu hỏi hoặc nói \"Mai ơi\"...") },
            enabled = !isSending,
            singleLine = true,
        )
        IconButton(onClick = onSend, enabled = text.isNotBlank() && !isSending) {
            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Gửi câu hỏi")
        }
    }
}
