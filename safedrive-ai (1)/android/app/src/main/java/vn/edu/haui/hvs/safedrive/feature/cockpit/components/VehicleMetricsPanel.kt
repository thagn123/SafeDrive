package vn.edu.haui.hvs.safedrive.feature.cockpit.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import vn.edu.haui.hvs.safedrive.core.designsystem.Dimensions
import vn.edu.haui.hvs.safedrive.core.designsystem.LocalSafeDriveStatusColors
import vn.edu.haui.hvs.safedrive.core.model.VehicleState
import java.util.Locale

private const val ENGINE_HOT_THRESHOLD_C = 105f
private const val LONG_DRIVE_MINUTES = 120

/** Speed/engine-temp/energy/continuous-driving 2x2 grid; cabin temperature shown as a header badge. */
@Composable
fun VehicleMetricsPanel(vehicleState: VehicleState, modifier: Modifier = Modifier) {
    val colors = LocalSafeDriveStatusColors.current
    val isEngineHot = vehicleState.engineTemperatureC >= ENGINE_HOT_THRESHOLD_C
    val isDrivingLong = (vehicleState.continuousDrivingMinutes ?: 0) >= LONG_DRIVE_MINUTES

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.surface, RoundedCornerShape(Dimensions.cardCornerRadius))
            .padding(Dimensions.cardPadding),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("TỔNG QUAN XE", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceMuted)
            Row(horizontalArrangement = Arrangement.spacedBy(Dimensions.cardSpacing / 2)) {
                Text(
                    "Cabin ${formatTemp(vehicleState.cabinTemperatureC)}°C",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceMuted,
                )
                val hvacTarget = vehicleState.hvacTargetTemperatureC
                if (hvacTarget != null) {
                    Text(
                        "HVAC ${formatTemp(hvacTarget)}°C",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.onSurfaceMuted,
                        modifier = Modifier.semantics {
                            contentDescription = "HVAC nhắm ${formatTemp(hvacTarget)} độ C"
                        },
                    )
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(Dimensions.cardSpacing / 2)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = Dimensions.cardSpacing),
                horizontalArrangement = Arrangement.spacedBy(Dimensions.cardSpacing),
            ) {
                MetricTile(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Filled.Speed,
                    label = "Tốc độ",
                    value = "${vehicleState.speedKmh.toInt()}",
                    unit = "km/h",
                    warn = false,
                )
                MetricTile(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Filled.LocalFireDepartment,
                    label = "Động cơ",
                    value = "${vehicleState.engineTemperatureC.toInt()}",
                    unit = "°C",
                    warn = isEngineHot,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Dimensions.cardSpacing),
            ) {
                MetricTile(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Filled.BatteryChargingFull,
                    label = "Năng lượng",
                    value = "${vehicleState.energyPercent}",
                    unit = "%",
                    warn = false,
                )
                MetricTile(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Filled.Schedule,
                    label = "Thời gian lái",
                    value = formatDrivingTime(vehicleState.continuousDrivingMinutes),
                    unit = "",
                    warn = isDrivingLong,
                )
            }
        }
    }
}

@Composable
private fun MetricTile(
    icon: ImageVector,
    label: String,
    value: String,
    unit: String,
    warn: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = LocalSafeDriveStatusColors.current
    val palette = if (warn) colors.monitor else colors.normal
    Row(
        modifier = modifier
            .background(colors.surfaceVariant, RoundedCornerShape(14.dp))
            .padding(10.dp)
            .semantics { contentDescription = "$label: $value $unit" },
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = palette.icon,
            modifier = Modifier
                .background(palette.iconContainer, RoundedCornerShape(10.dp))
                .padding(6.dp),
        )
        Column(modifier = Modifier.padding(start = 8.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceMuted)
            Text(
                "$value $unit",
                style = MaterialTheme.typography.titleSmall,
            )
        }
    }
}

private fun formatDrivingTime(minutes: Int?): String {
    if (minutes == null) return "--"
    val h = minutes / 60
    val m = minutes % 60
    return when {
        h == 0 -> "${m}p"
        m == 0 -> "${h}g"
        else -> "${h}g ${m}p"
    }
}

internal fun formatTemp(temp: Float): String {
    // Locale.US pins the decimal separator to "." regardless of device
    // locale (e.g. vi-VN uses "," by default), so this always matches the
    // backend's locale-independent "23.5" formatting and the assistant/
    // action-title text instead of silently mismatching on real devices.
    return if (temp % 1f == 0f) temp.toInt().toString() else String.format(Locale.US, "%.1f", temp)
}
