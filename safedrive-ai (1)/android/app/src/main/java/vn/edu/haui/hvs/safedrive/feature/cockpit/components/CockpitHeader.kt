package vn.edu.haui.hvs.safedrive.feature.cockpit.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import vn.edu.haui.hvs.safedrive.core.designsystem.Dimensions
import vn.edu.haui.hvs.safedrive.core.designsystem.LocalSafeDriveStatusColors
import vn.edu.haui.hvs.safedrive.core.designsystem.StatusBadge
import vn.edu.haui.hvs.safedrive.core.designsystem.paletteForConnectionStatus
import vn.edu.haui.hvs.safedrive.core.model.SystemConnectionStatus
import vn.edu.haui.hvs.safedrive.core.model.BackendMode

/**
 * App title + connection chip, per docs/android-mvp-plan/04-screen-specs.md ("header + connection
 * chip"). Also renders a compact demo-scenario shortcut chip when [onOpenSimulator] is supplied.
 * (docs/android-mvp-plan/12 W6.3) — fixed-height row, so this never grows Cockpit's tightly-weighted
 * non-scrolling portrait layout. Advanced signal editing remains protected inside Developer Mode.
 */
@Composable
fun CockpitHeader(
    connectionStatus: SystemConnectionStatus,
    backendMode: BackendMode,
    modifier: Modifier = Modifier,
    onOpenSimulator: (() -> Unit)? = null,
) {
    val colors = LocalSafeDriveStatusColors.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(Dimensions.compactRowHeight)
            .background(colors.surface, RoundedCornerShape(Dimensions.cardCornerRadius))
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("SafeDrive AI", style = MaterialTheme.typography.titleMedium)
            Text(
                if (backendMode == BackendMode.REMOTE) "Backend thật · dữ liệu xe đồng bộ" else "Mô phỏng cục bộ · dữ liệu giả lập",
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (onOpenSimulator != null) {
                DeveloperSimulatorChip(onClick = onOpenSimulator)
            }
            ConnectionChip(connectionStatus)
        }
    }
}

@Composable
private fun DeveloperSimulatorChip(onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .background(Color(0xFF7C3AED).copy(alpha = 0.18f), RoundedCornerShape(999.dp))
            .clickable(onClickLabel = "Mở kịch bản demo", onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .semantics { contentDescription = "Mở kịch bản demo" },
    ) {
        Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = Color(0xFF7C3AED))
        Text("Demo", style = MaterialTheme.typography.labelSmall, color = Color(0xFF7C3AED))
    }
}

@Composable
private fun ConnectionChip(status: SystemConnectionStatus) {
    val palette = paletteForConnectionStatus(status)
    val label = when (status) {
        SystemConnectionStatus.NORMAL -> "Đã kết nối"
        SystemConnectionStatus.OFFLINE -> "Ngoại tuyến"
        SystemConnectionStatus.NO_AI_SERVICE -> "Không có AI"
        SystemConnectionStatus.NO_VEHICLE_DATA -> "Thiếu dữ liệu xe"
        SystemConnectionStatus.STALE_DATA -> "Dữ liệu cũ"
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.semantics { contentDescription = "Trạng thái kết nối: $label" },
    ) {
        Icon(
            imageVector = if (status == SystemConnectionStatus.OFFLINE) Icons.Filled.CloudOff else Icons.Filled.CloudQueue,
            contentDescription = null,
            tint = palette.icon,
            modifier = Modifier.padding(end = 2.dp),
        )
        StatusBadge(text = label, palette = palette)
    }
}
