package vn.edu.haui.hvs.safedrive.feature.cockpit.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import vn.edu.haui.hvs.safedrive.core.model.ConfidenceLevel
import vn.edu.haui.hvs.safedrive.core.model.DriverSupportSignals
import vn.edu.haui.hvs.safedrive.core.model.RestRecommendation

/** Driver-support-signal detail view. Reason codes are intentionally omitted (Developer Mode only). */
@Composable
fun DriverSupportDetailsDialog(
    signals: DriverSupportSignals,
    restRecommendation: RestRecommendation,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Đóng") } },
        title = { Text("Chi tiết tín hiệu hỗ trợ") },
        text = {
            Column(modifier = Modifier.padding(top = 4.dp)) {
                Text(restRecommendation.title, style = MaterialTheme.typography.titleSmall)
                Text(restRecommendation.message, style = MaterialTheme.typography.bodySmall)
                Text(
                    "Độ tin cậy: ${
                        when (restRecommendation.confidence) {
                            ConfidenceLevel.HIGH -> "Cao"
                            ConfidenceLevel.MEDIUM -> "Trung bình"
                            ConfidenceLevel.LOW -> "Thấp"
                        }
                    }",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Text(
                    "Nguồn tín hiệu khả dụng: ${signals.availableSourceCount}/${signals.totalSourceCount}",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "Vô lăng: ${if (signals.steeringSignalAvailable) "Có tín hiệu" else "Không có"}",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "Cảm biến ghế: ${if (signals.seatSensorAvailable) "Có tín hiệu" else "Không có"}",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "Thiết bị đeo: ${
                        if (signals.wearableHeartRateBpm != null) "${signals.wearableHeartRateBpm} bpm" else "Chưa kết nối"
                    }",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
    )
}
