package vn.edu.haui.hvs.safedrive.feature.assistant.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import vn.edu.haui.hvs.safedrive.core.designsystem.LocalSafeDriveStatusColors
import vn.edu.haui.hvs.safedrive.core.model.SafeDriveAction

/** Renders a suggested action inline in a chat bubble; `NONE`/unknown types simply render no button. */
@Composable
fun AssistantActionCard(action: SafeDriveAction, onExecute: (SafeDriveAction) -> Unit, modifier: Modifier = Modifier) {
    val colors = LocalSafeDriveStatusColors.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 4.dp)
            .background(colors.surfaceVariant, RoundedCornerShape(12.dp))
            .padding(10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(action.title, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
        Button(onClick = { onExecute(action) }) {
            Text(if (action.requiresConfirmation) "Xác nhận" else "Thực thi")
        }
    }
}
