package vn.edu.haui.hvs.safedrive.feature.cockpit.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RemoveCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import vn.edu.haui.hvs.safedrive.core.designsystem.Dimensions
import vn.edu.haui.hvs.safedrive.core.designsystem.LocalSafeDriveStatusColors
import vn.edu.haui.hvs.safedrive.core.model.DriverSupportSignals
import vn.edu.haui.hvs.safedrive.core.model.VehicleState

private data class SignalItem(val label: String, val active: Boolean, val value: String)

/**
 * Indirect driver-support signal availability summary. Never renders attention/drowsiness
 * conclusions — only whether a source is available and its raw last-known value
 * (see docs/android-mvp-plan/00-executive-plan.md).
 */
@Composable
fun DriverSignalSummaryCard(
    vehicleState: VehicleState,
    driverSupportSignals: DriverSupportSignals,
    onOpenDetails: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalSafeDriveStatusColors.current
    val items = listOf(
        SignalItem(
            label = "Vô lăng",
            active = driverSupportSignals.steeringSignalAvailable,
            value = vehicleState.steeringLastInteractionSeconds?.let { "${it}s trước" } ?: "Không có",
        ),
        SignalItem(
            label = "Ghế lái",
            active = driverSupportSignals.seatSensorAvailable && (vehicleState.driverSeatOccupied ?: false),
            value = if (!driverSupportSignals.seatSensorAvailable) {
                "Tắt"
            } else if (vehicleState.driverSeatOccupied == true) {
                "Có người"
            } else {
                "Trống"
            },
        ),
        SignalItem(
            label = "Wearable",
            active = vehicleState.wearableConnected,
            value = if (vehicleState.wearableConnected) {
                "${driverSupportSignals.wearableHeartRateBpm ?: "--"} bpm"
            } else {
                "Chưa kết nối"
            },
        ),
        SignalItem(
            label = "Thời gian lái",
            active = vehicleState.continuousDrivingMinutes != null,
            value = vehicleState.continuousDrivingMinutes?.let { "${it}p" } ?: "Không có",
        ),
    )
    val activeCount = items.count { it.active }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.surface, RoundedCornerShape(Dimensions.cardCornerRadius))
            .clickable(onClickLabel = "Xem chi tiết tín hiệu hỗ trợ", onClick = onOpenDetails)
            .padding(Dimensions.cardPadding),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("TÍN HIỆU HỖ TRỢ", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceMuted)
            Text(
                "$activeCount/${items.size} nguồn hoạt động",
                style = MaterialTheme.typography.labelSmall,
                color = colors.normal.icon,
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = Dimensions.cardSpacing),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items.forEach { item ->
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .background(colors.surfaceVariant, RoundedCornerShape(10.dp))
                        .padding(6.dp)
                        .semantics { contentDescription = "${item.label}: ${item.value}" },
                ) {
                    Icon(
                        imageVector = if (item.active) Icons.Filled.CheckCircle else Icons.Filled.RemoveCircle,
                        contentDescription = null,
                        tint = if (item.active) colors.normal.icon else colors.onSurfaceMuted,
                        modifier = Modifier.padding(end = 4.dp),
                    )
                    Column {
                        Text(item.label, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                        Text(item.value, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceMuted, maxLines = 1)
                    }
                }
            }
        }
    }
}
