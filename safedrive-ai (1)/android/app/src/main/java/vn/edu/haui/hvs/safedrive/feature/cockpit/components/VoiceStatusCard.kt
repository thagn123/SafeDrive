package vn.edu.haui.hvs.safedrive.feature.cockpit.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import vn.edu.haui.hvs.safedrive.core.designsystem.Dimensions
import vn.edu.haui.hvs.safedrive.core.designsystem.LocalSafeDriveStatusColors
import vn.edu.haui.hvs.safedrive.core.model.VoiceState

/**
 * Visual-only in Phase 2 — [VoiceState] is real (from [vn.edu.haui.hvs.safedrive.voice.VoiceController])
 * starting Phase 5. Never claims "listening" unless the recognizer state actually says so.
 */
@Composable
fun VoiceStatusCard(
    voiceState: VoiceState,
    waitingForWakePhrase: Boolean,
    partialTranscript: String,
    finalTranscript: String,
    errorMessage: String?,
    onTriggerVoice: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalSafeDriveStatusColors.current
    val (title, subtitle, active) = when (voiceState) {
        VoiceState.DISABLED -> Triple("Micro đang tắt", "Mở trong Cài đặt", false)
        VoiceState.IDLE -> Triple("Sẵn sàng", "Nói \"Mai ơi\"", true)
        VoiceState.WAKE_WORD_DETECTED -> Triple("Đã nghe \"Hey\"", "Mời nói lệnh", true)
        VoiceState.LISTENING -> when {
            waitingForWakePhrase -> Triple(
                "Sẵn sàng lắng nghe",
                partialTranscript.ifBlank { "Nói \"Mai ơi\" để ra lệnh" },
                true,
            )
            partialTranscript.isNotBlank() -> Triple("Đang nghe", "Đã nghe: $partialTranscript", true)
            else -> Triple("Đang nghe", "Tự gửi sau 0,8 giây", true)
        }
        VoiceState.PROCESSING -> Triple("Đang xử lý", finalTranscript.takeIf { it.isNotBlank() }?.let { "Đã nhận: $it" } ?: "Đang đọc dữ liệu xe", true)
        VoiceState.SPEAKING -> Triple("Đang trả lời", "Xong sẽ nghe lại", true)
        VoiceState.ERROR -> Triple("Không nghe rõ", errorMessage ?: "Chạm để thử lại", false)
    }
    val palette = if (voiceState == VoiceState.ERROR) colors.high else colors.normal

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(Dimensions.compactRowHeight)
            .background(colors.surface, RoundedCornerShape(Dimensions.cardCornerRadius))
            .clickable(onClickLabel = "Kích hoạt trợ lý thoại", onClick = onTriggerVoice)
            .padding(horizontal = 12.dp)
            .semantics { contentDescription = "$title. $subtitle" },
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = if (active) Icons.Filled.Mic else Icons.Filled.MicOff,
                contentDescription = null,
                tint = palette.icon,
                modifier = Modifier
                    .background(palette.iconContainer, RoundedCornerShape(10.dp))
                    .padding(6.dp),
            )
            Column(modifier = Modifier.padding(start = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(if (active) colors.normal.icon else colors.onSurfaceMuted, CircleShape),
                    )
                    Text(
                        title,
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
                Text(subtitle, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceMuted, maxLines = 1)
            }
        }
    }
}
