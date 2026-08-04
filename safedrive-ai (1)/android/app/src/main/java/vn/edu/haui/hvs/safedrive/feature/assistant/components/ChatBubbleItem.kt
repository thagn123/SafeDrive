package vn.edu.haui.hvs.safedrive.feature.assistant.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import vn.edu.haui.hvs.safedrive.core.designsystem.Dimensions
import vn.edu.haui.hvs.safedrive.core.designsystem.LocalSafeDriveStatusColors
import vn.edu.haui.hvs.safedrive.core.designsystem.StatusBadge
import vn.edu.haui.hvs.safedrive.core.designsystem.paletteForSeverity
import vn.edu.haui.hvs.safedrive.core.model.ChatMessage
import vn.edu.haui.hvs.safedrive.core.model.ChatSender
import vn.edu.haui.hvs.safedrive.core.model.SafeDriveAction
import vn.edu.haui.hvs.safedrive.core.model.Severity

/**
 * One chat bubble. Reason codes/latency/route are only rendered in Developer Mode
 * (docs/android-mvp-plan/04-screen-specs.md, "Assistant acceptance": "latency/route chỉ hiện trong
 * Developer Mode").
 */
@Composable
fun ChatBubbleItem(
    message: ChatMessage,
    developerMode: Boolean,
    onExecuteAction: (SafeDriveAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalSafeDriveStatusColors.current
    val isUser = message.sender == ChatSender.USER

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Column(
            modifier = Modifier
                .background(
                    if (isUser) MaterialTheme.colorScheme.primary else colors.surface,
                    RoundedCornerShape(16.dp),
                )
                .padding(12.dp),
        ) {
            Text(
                message.text,
                color = if (isUser) Color.White else MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyMedium,
            )

            if (!isUser && message.risk != null && message.risk.level != Severity.LOW) {
                val palette = paletteForSeverity(message.risk.level)
                Column(
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .background(colors.surfaceVariant, RoundedCornerShape(10.dp))
                        .padding(8.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusBadge(text = message.risk.level.name, palette = palette)
                        Text(
                            message.risk.title,
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(start = 6.dp),
                        )
                    }
                    Text(message.risk.message, style = MaterialTheme.typography.bodySmall)
                    if (developerMode && message.risk.reasonCodes.isNotEmpty()) {
                        Text(
                            message.risk.reasonCodes.joinToString(", "),
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.onSurfaceMuted,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }

            if (!isUser && message.actions.isNotEmpty()) {
                Column(modifier = Modifier.padding(top = 8.dp)) {
                    message.actions.forEach { action ->
                        AssistantActionCard(action = action, onExecute = onExecuteAction)
                    }
                }
            }

            if (developerMode && !isUser) {
                Text(
                    "route=${message.route ?: "-"} · latency=${message.latencyMs ?: "-"}ms",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceMuted,
                    modifier = Modifier.padding(top = 6.dp),
                )
                message.model?.let { model ->
                    Text(
                        "model=$model",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.onSurfaceMuted,
                    )
                }
            }
        }
    }
}
