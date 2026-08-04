package vn.edu.haui.hvs.safedrive.feature.voice

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import vn.edu.haui.hvs.safedrive.core.designsystem.Dimensions
import vn.edu.haui.hvs.safedrive.core.designsystem.LocalSafeDriveStatusColors
import vn.edu.haui.hvs.safedrive.core.model.VoiceState
import vn.edu.haui.hvs.safedrive.domain.repository.TtsController
import vn.edu.haui.hvs.safedrive.domain.repository.TtsState
import vn.edu.haui.hvs.safedrive.domain.usecase.VoiceTurnOutcome
import vn.edu.haui.hvs.safedrive.voice.VoiceController

/**
 * Overlay on top of the current screen per docs/android-mvp-plan/04-screen-specs.md ("Voice
 * overlay"). Combines independently-owned, read-only states for *display* only
 * (docs/android-mvp-plan/12 W3.14) — [voiceController] for mic/STT, [ttsController] for speech
 * output, [turnOutcome] for the voice-sourced turn's reply/error
 * ([vn.edu.haui.hvs.safedrive.domain.usecase.VoiceAssistantCoordinator], remediation item 6) —
 * without merging their ownership. Visible whenever any of these is actively showing something.
 * [onCancelProcessing] cancels the in-flight assistant turn ("Hủy xử lý"), distinct from
 * [VoiceController.cancel] ("Hủy nghe") and [TtsController.stop] ("Dừng đọc") — see W2.9.
 */
@Composable
fun VoiceOverlay(
    voiceController: VoiceController,
    ttsController: TtsController,
    onCancelProcessing: () -> Unit,
    turnOutcome: StateFlow<VoiceTurnOutcome?> = MutableStateFlow(null),
    onDismissTurnOutcome: () -> Unit = {},
) {
    val voiceState by voiceController.state.collectAsStateWithLifecycle()
    val ttsState by ttsController.state.collectAsStateWithLifecycle()
    val outcome by turnOutcome.collectAsStateWithLifecycle()
    val speaking = ttsState == TtsState.SPEAKING
    val showRecognitionBubble =
        (voiceState.state == VoiceState.WAKE_WORD_DETECTED && !voiceState.waitingForWakePhrase) ||
            (voiceState.state == VoiceState.LISTENING && !voiceState.waitingForWakePhrase)
    val showConversationSheet =
        speaking ||
            outcome != null ||
            voiceState.state == VoiceState.PROCESSING ||
            voiceState.state == VoiceState.ERROR
    if (!showRecognitionBubble && !showConversationSheet) return

    val colors = LocalSafeDriveStatusColors.current
    Box(modifier = Modifier.fillMaxSize()) {
        if (showConversationSheet) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.45f)),
                contentAlignment = Alignment.BottomCenter,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(Dimensions.screenPadding)
                        .background(colors.surface, RoundedCornerShape(Dimensions.cardCornerRadius))
                        .padding(Dimensions.cardPadding),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
            when {
                // A completed voice turn's reply/error takes priority over both the recognizer's own
                // state (which has usually already gone back to IDLE by the time this shows — see
                // VoiceAssistantCoordinator.clearProcessingState) *and* the generic "speaking" indicator
                // below — shown until the user dismisses it, never auto-hidden (W6.10). Before this fix,
                // the `speaking` branch was checked first, so the actual reply text was hidden for the
                // entire duration TTS was reading it aloud; the only way to ever see it was to catch the
                // brief window after TTS finished and before the user dismissed the bubble.
                outcome is VoiceTurnOutcome.Success -> {
                    Text(
                        if (speaking) "SafeDrive đang trả lời" else "SafeDrive đã trả lời",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text((outcome as VoiceTurnOutcome.Success).replyText, style = MaterialTheme.typography.bodySmall)
                    if (speaking) {
                        // "Dừng đọc" only stops TTS (W2.9) — it must never dismiss the reply bubble
                        // itself; the reply stays visible (now in the non-speaking copy above) until the
                        // user explicitly taps "Đóng".
                        IconButton(onClick = ttsController::stop) {
                            Icon(Icons.Filled.Stop, contentDescription = "Dừng đọc")
                        }
                    } else {
                        TextButton(onClick = onDismissTurnOutcome) { Text("Đóng") }
                    }
                }

                outcome is VoiceTurnOutcome.Failure -> {
                    Text(
                        (outcome as VoiceTurnOutcome.Failure).errorMessage,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    TextButton(onClick = onDismissTurnOutcome) { Text("Đóng") }
                }

                // No turnOutcome recorded for whatever is currently speaking (e.g. it was already
                // dismissed, or nothing routed through VoiceAssistantCoordinator) — fall back to the
                // generic indicator.
                speaking -> {
                    Text("SafeDrive đang trả lời", style = MaterialTheme.typography.titleSmall)
                    IconButton(onClick = ttsController::stop) {
                        Icon(Icons.Filled.Stop, contentDescription = "Dừng đọc")
                    }
                }

                voiceState.state == VoiceState.WAKE_WORD_DETECTED -> {
                    Text("Đã nhận diện \"Mai ơi\"", style = MaterialTheme.typography.titleSmall)
                    Text("Đang mở trợ lý thoại...", style = MaterialTheme.typography.bodySmall)
                }

                voiceState.state == VoiceState.LISTENING -> {
                    Icon(Icons.Filled.Mic, contentDescription = "Đang nghe", tint = colors.normal.icon)
                    Text(
                        if (voiceState.waitingForWakePhrase) "Đang chờ \"Mai ơi\"" else "SafeDrive đang nghe...",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        voiceState.partialTranscript.ifBlank {
                            if (voiceState.waitingForWakePhrase) "Nói \"Mai ơi\" để mở trợ lý"
                            else "Hãy nói câu lệnh của bạn"
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                    // User-initiated "Kết thúc câu nói" (docs/android-mvp-plan/12 W4.7): finalizes
                    // whatever was captured so far instead of relying only on the recognizer's
                    // best-effort (not guaranteed) silence detection. Distinct from the close button's
                    // "Hủy nghe" below, which discards the session instead of finalizing it.
                    TextButton(onClick = voiceController::finishListening) {
                        Text("Kết thúc câu nói")
                    }
                }

                voiceState.state == VoiceState.PROCESSING -> {
                    CircularProgressIndicator()
                    Text("Đang xử lý yêu cầu...", style = MaterialTheme.typography.titleSmall)
                    if (voiceState.finalTranscript.isNotBlank()) {
                        Text(voiceState.finalTranscript, style = MaterialTheme.typography.bodySmall)
                    }
                    IconButton(onClick = onCancelProcessing) {
                        Icon(Icons.Filled.Close, contentDescription = "Hủy xử lý")
                    }
                }

                voiceState.state == VoiceState.ERROR -> {
                    Text(
                        voiceState.errorMessage ?: "Không nghe rõ, vui lòng thử lại",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                else -> Unit
            }

            IconButton(
                onClick = {
                    if (speaking) ttsController.stop()
                    onDismissTurnOutcome()
                    onCancelProcessing()
                    voiceController.cancel()
                },
            ) {
                Icon(Icons.Filled.Close, contentDescription = "Đóng trợ lý thoại")
            }
                }
            }
        }

        if (showRecognitionBubble && !showConversationSheet) {
            VoiceRecognitionBubble(
                state = voiceState.state,
                partialTranscript = voiceState.partialTranscript,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = Dimensions.screenPadding)
                    .padding(bottom = 112.dp),
            )
        }
    }
}

/** Small non-blocking acknowledgement while wake-word and command speech are recognized. */
@Composable
private fun VoiceRecognitionBubble(
    state: VoiceState,
    partialTranscript: String,
    modifier: Modifier = Modifier,
) {
    val colors = LocalSafeDriveStatusColors.current
    val wakeWordRecognized = state == VoiceState.WAKE_WORD_DETECTED
    val title = if (wakeWordRecognized) "Đã nhận diện \"Mai ơi\"" else "SafeDrive đang nghe"
    val detail =
        if (wakeWordRecognized) {
            "Mời bạn nói câu lệnh"
        } else {
            partialTranscript.ifBlank { "Dừng nói khoảng 0,8 giây để tự gửi" }
        }

    Row(
        modifier = modifier
            .background(colors.surface, RoundedCornerShape(28.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .semantics { contentDescription = "$title. $detail" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier.size(40.dp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.Mic,
                contentDescription = null,
                tint = colors.normal.icon,
            )
            if (!wakeWordRecognized) {
                CircularProgressIndicator(
                    modifier = Modifier.size(38.dp),
                    color = colors.normal.icon,
                    strokeWidth = 2.dp,
                )
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(detail, style = MaterialTheme.typography.bodySmall)
        }
    }
}
