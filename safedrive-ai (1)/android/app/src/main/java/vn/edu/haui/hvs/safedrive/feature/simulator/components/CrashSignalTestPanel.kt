package vn.edu.haui.hvs.safedrive.feature.simulator.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import vn.edu.haui.hvs.safedrive.domain.repository.CrashEvidenceSource

private data class TestSignal(val source: CrashEvidenceSource, val label: String, val note: String)

/** Mirrors the labels on the SOS screen's signal panel (EmergencyScreen.kt's KNOWN_CRASH_SIGNALS)
 * and the real CrashEvidenceFusion rule, so the note next to each button is never a guess. */
private val TEST_SIGNALS = listOf(
    TestSignal(CrashEvidenceSource.VHAL_IMPACT, "Va chạm VHAL", "Tự kích hoạt SOS một mình"),
    TestSignal(CrashEvidenceSource.VHAL_AIRBAG, "Túi khí", "Tự kích hoạt SOS một mình"),
    TestSignal(CrashEvidenceSource.DEVICE_IMU, "Gia tốc điện thoại", "Cần bấm thêm \"Giảm tốc đột ngột\""),
    TestSignal(CrashEvidenceSource.VHAL_SPEED_DROP, "Giảm tốc đột ngột", "Cần bấm thêm \"Gia tốc điện thoại\""),
    TestSignal(CrashEvidenceSource.HIGH_SPEED, "Tốc độ cao", "Chỉ làm bối cảnh, không tự kích hoạt SOS"),
    TestSignal(CrashEvidenceSource.CRITICAL_SENSOR_FAULT, "Cảm biến quanh xe", "Chỉ làm bối cảnh, không tự kích hoạt SOS"),
)

/**
 * Developer-Mode-only test hook: sends a single signal straight into the real
 * [vn.edu.haui.hvs.safedrive.domain.repository.CrashEvidenceAdapter]/`CrashEvidenceFusion`
 * pipeline via `injectSignal`, exactly as a genuine VHAL/IMU event would arrive. Lets the SOS
 * screen's signal panel and fusion rules be verified on any device -- including a plain phone
 * with no real Car Service -- without needing CarSky hardware.
 */
@Composable
fun CrashSignalTestPanel(onInject: (CrashEvidenceSource) -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            "Test tín hiệu va chạm (VHAL) — Developer Mode",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "Bấm để gửi thẳng từng tín hiệu vào bộ xử lý thật, xem panel/SOS phản ứng đúng như trên xe thật.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TEST_SIGNALS.chunked(2).forEach { row ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { signal ->
                    Column(modifier = Modifier.weight(1f)) {
                        OutlinedButton(onClick = { onInject(signal.source) }, modifier = Modifier.fillMaxWidth()) {
                            Text(signal.label, style = MaterialTheme.typography.labelMedium)
                        }
                        Text(
                            signal.note,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
