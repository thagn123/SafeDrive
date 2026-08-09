package vn.edu.haui.hvs.safedrive.feature.emergency

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import vn.edu.haui.hvs.safedrive.core.designsystem.Dimensions
import vn.edu.haui.hvs.safedrive.core.designsystem.LocalSafeDriveStatusColors
import vn.edu.haui.hvs.safedrive.core.model.EmergencyState
import vn.edu.haui.hvs.safedrive.core.model.RescueBrief
import vn.edu.haui.hvs.safedrive.core.model.RescueDispatchReceipt

/**
 * Full-screen Emergency renderer per docs/android-mvp-plan/04-screen-specs.md ("Emergency/SOS"):
 * blocks Back, no swipe/tap-outside dismissal, hides bottom navigation (the caller in
 * `SafeDriveApp.kt` skips the Scaffold bottom bar while this is shown). `realEmergencyDispatchEnabled`
 * is always false — this only ever renders a simulated payload.
 */
@Composable
fun EmergencyScreen(viewModel: EmergencyViewModel, onTriggerVoiceCancel: () -> Unit) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val active = state as? EmergencyUiState.Active ?: return

    // Consume Back entirely — no state change, matching "không dismiss bằng Back/swipe/tap outside".
    BackHandler(enabled = true) {}

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF08131F))
            .padding(Dimensions.screenPadding)
            .semantics {
                contentDescription = "Màn hình khẩn cấp"
                liveRegion = LiveRegionMode.Assertive
            },
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        TopBanner()

        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Icon(
                Icons.Filled.Warning,
                contentDescription = null,
                tint = Color(0xFFF87171),
                modifier = Modifier.padding(top = 24.dp),
            )

            when (active.state) {
                EmergencyState.CANDIDATE_DETECTED, EmergencyState.VERIFYING_EVIDENCE -> VerifyingContent(active.remainingSeconds)
                EmergencyState.AWAITING_USER_RESPONSE -> AwaitingResponseContent(active.remainingSeconds, onConfirmSafe = viewModel::confirmSafe)
                EmergencyState.FINAL_COUNTDOWN -> FinalCountdownContent(active.remainingSeconds, onCancel = viewModel::cancelSos)
                EmergencyState.SOS_SIMULATED_SENT -> SentContent(active, onAcknowledge = viewModel::acknowledgeSent)
                EmergencyState.IDLE, EmergencyState.CANCELLED -> Unit
            }

            if (active.state != EmergencyState.SOS_SIMULATED_SENT) {
                EvidenceCard(active, onTriggerVoiceCancel)
            }
        }

        Text(
            "SafeDrive AI Automotive HMI — HaUI Vehicle Smart Systems (HVS)",
            style = MaterialTheme.typography.labelSmall,
            color = Color(0xFF64748B),
        )
    }
}

@Composable
private fun TopBanner() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            "SOS MÔ PHỎNG (KHÔNG PHẢI CỨU HỘ THẬT)",
            style = MaterialTheme.typography.labelMedium,
            color = Color(0xFFFCA5A5),
        )
        Text(
            "real_emergency_dispatch_enabled: false",
            style = MaterialTheme.typography.labelSmall,
            color = Color(0xFF64748B),
        )
    }
}

@Composable
private fun VerifyingContent(remainingSeconds: Int) {
    Text("BƯỚC 1/3: XÁC MINH BẰNG CHỨNG CẢM BIẾN", style = MaterialTheme.typography.labelMedium, color = Color(0xFFFBBF24))
    Text("Đang xác minh tình huống khẩn cấp...", style = MaterialTheme.typography.headlineSmall, color = Color.White)
    Text(
        "Hệ thống đang đối chiếu dữ liệu cảm biến gia tốc, trạng thái dừng xe và cảm biến ghế lái.",
        style = MaterialTheme.typography.bodyMedium,
        color = Color(0xFFCBD5E1),
    )
    Text("Thời gian xác minh: ${remainingSeconds}s", style = MaterialTheme.typography.headlineMedium, color = Color(0xFFFBBF24))
}

@Composable
private fun AwaitingResponseContent(remainingSeconds: Int, onConfirmSafe: () -> Unit) {
    Text("BƯỚC 2/3: CHỜ XÁC NHẬN TỪ NGƯỜI LÁI", style = MaterialTheme.typography.labelMedium, color = Color(0xFFFDBA74))
    Text("Bạn có ổn không?", style = MaterialTheme.typography.headlineMedium, color = Color.White)
    Text(
        "Nếu bạn vẫn ổn, hãy nói \"Tôi ổn\" hoặc nhấn nút bên dưới.",
        style = MaterialTheme.typography.bodyMedium,
        color = Color(0xFFE2E8F0),
    )
    Text("Thời gian chờ: ${remainingSeconds}s", style = MaterialTheme.typography.headlineMedium, color = Color(0xFFFB923C))
    Button(onClick = onConfirmSafe, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Filled.CheckCircle, contentDescription = null)
        Text(" TÔI VẪN ỔN — HỦY SOS", modifier = Modifier.padding(start = 8.dp))
    }
}

@Composable
private fun FinalCountdownContent(remainingSeconds: Int, onCancel: () -> Unit) {
    Text(
        "BƯỚC 3/3: ĐẾM NGƯỢC GỬI TÍN HIỆU SOS MÔ PHỎNG",
        style = MaterialTheme.typography.labelMedium,
        color = Color(0xFFFCA5A5),
    )
    Text("SOS mô phỏng sẽ được gửi sau ${remainingSeconds}s", style = MaterialTheme.typography.headlineSmall, color = Color.White)
    Text(
        "Không nhận được phản hồi. Hệ thống chuẩn bị phát tín hiệu cứu hộ giả lập.",
        style = MaterialTheme.typography.bodyMedium,
        color = Color(0xFFFECACA),
    )
    Text("${remainingSeconds}s", style = MaterialTheme.typography.displayMedium, color = Color(0xFFF87171))
    Button(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = Color(0xFF34D399))
        Text(" HỦY SOS — TÔI VẪN ỔN", modifier = Modifier.padding(start = 8.dp))
    }
}

@Composable
private fun SentContent(active: EmergencyUiState.Active, onAcknowledge: () -> Unit) {
    val colors = LocalSafeDriveStatusColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF042F1E), RoundedCornerShape(Dimensions.cardCornerRadius))
            .padding(Dimensions.cardPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = colors.normal.icon)
        Text("Đã gửi tín hiệu SOS mô phỏng khẩn cấp", style = MaterialTheme.typography.titleLarge, color = Color.White)
        Text(
            "Bằng chứng sự cố đã được đóng gói trong gói tin thử nghiệm SafeDrive AI. Đây là mô phỏng — không có cuộc gọi hay tin nhắn thật nào được gửi.",
            style = MaterialTheme.typography.bodyMedium,
            color = Color(0xFFE2E8F0),
        )
        active.rescueBrief?.let { brief ->
            RescueBriefSummary(brief, active.rescueDispatch)
        }
        Button(onClick = onAcknowledge, modifier = Modifier.fillMaxWidth()) {
            Text("Quay lại Cockpit")
        }
    }
}

@Composable
private fun RescueBriefSummary(brief: RescueBrief, receipt: RescueDispatchReceipt?) {
    Text("GÓI TIN CỨU HỘ MÔ PHỎNG", style = MaterialTheme.typography.labelMedium, color = Color(0xFFFDE68A))
    Text(brief.vehicleStatusSummary, style = MaterialTheme.typography.bodySmall, color = Color.White)
    val location = brief.lastKnownLocation
    val locationText = if (location == null) {
        "Vị trí cuối: không có dữ liệu GPS/simulator"
    } else {
        "Vị trí cuối: %.4f, %.4f · %s · %d ms".format(
            location.latitude,
            location.longitude,
            location.freshness,
            location.ageMs,
        )
    }
    Text(locationText, style = MaterialTheme.typography.bodySmall, color = Color(0xFFE2E8F0))
    Text(
        receipt?.let { "Mock rescue gateway accepted: ${it.referenceId}" }
            ?: "Đang chờ mock rescue gateway xác nhận.",
        style = MaterialTheme.typography.labelSmall,
        color = Color(0xFF86EFAC),
    )
}

@Composable
private fun EvidenceCard(active: EmergencyUiState.Active, onTriggerVoiceCancel: () -> Unit) {
    val colors = LocalSafeDriveStatusColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF0F1D2C), RoundedCornerShape(Dimensions.cardCornerRadius))
            .padding(Dimensions.cardPadding),
    ) {
        Text(
            "Bằng chứng xác minh gián tiếp (${active.evidence.size} tín hiệu):",
            style = MaterialTheme.typography.labelMedium,
            color = colors.onSurfaceMuted,
        )
        active.evidence.forEach { evidence ->
            Text("• ${evidence.label}", style = MaterialTheme.typography.bodySmall, color = Color.White)
        }
        // Advisory-only LLM second opinion (see EmergencyLLMReasoner on the backend) — never the
        // primary evidence above, never changes the countdown, just an extra explanation when present.
        active.reasoningSummary?.let { reasoning ->
            Text(
                "Nhận định bổ sung từ AI: $reasoning",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF93C5FD),
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        if (active.developerMode) {
            // Technical signal-state view — only shown once Developer Mode is switched on
            // (Settings), so the plain-language evidence list above stays uncluttered by default.
            SpeedSparkline(
                samples = active.speedHistoryKmh,
                modifier = Modifier.padding(top = 12.dp),
            )
            SignalStatePanel(
                evidenceCodes = active.evidence.map { it.code }.toSet(),
                modifier = Modifier.padding(top = 12.dp),
            )
            Text(
                "Reason codes: ${active.evidence.joinToString(", ") { it.code }}",
                style = MaterialTheme.typography.labelSmall,
                color = colors.normal.icon,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        Button(onClick = onTriggerVoiceCancel, modifier = Modifier.padding(top = 12.dp)) {
            Text("Nói \"Tôi ổn\" / \"Hủy SOS\"")
        }
    }
}

private data class SignalIndicator(val code: String, val label: String)

/** Every real VHAL/IMU crash-evidence code SafeDriveContainer's `toEvidenceItem` can emit
 * (SafeDriveContainer.kt) — kept as plain strings here rather than importing the domain
 * `CrashEvidenceSource` enum, so this screen never needs to know about the vehicle-layer type. */
private val KNOWN_CRASH_SIGNALS = listOf(
    SignalIndicator("vhal_impact", "Va chạm VHAL"),
    SignalIndicator("vhal_airbag", "Túi khí"),
    SignalIndicator("device_imu", "Gia tốc điện thoại"),
    SignalIndicator("vhal_speed_drop", "Giảm tốc đột ngột"),
    SignalIndicator("high_speed_context", "Tốc độ cao"),
    SignalIndicator("perimeter_sensor_fault", "Cảm biến quanh xe"),
)

/**
 * Visual state of every known real crash-evidence signal, not just the ones present in
 * [evidenceCodes] — an all-dark panel is itself informative: it means this SOS was triggered by a
 * manual Simulator toggle rather than a real fused vehicle/phone signal (the two evidence
 * vocabularies never overlap — see SafeDriveContainer's evidence-rule collector). Active chips
 * pulse to draw the eye, matching how a real diagnostic dashboard highlights a live alarm.
 */
@Composable
private fun SignalStatePanel(evidenceCodes: Set<String>, modifier: Modifier = Modifier) {
    val colors = LocalSafeDriveStatusColors.current
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            "Trạng thái tín hiệu va chạm:",
            style = MaterialTheme.typography.labelMedium,
            color = colors.onSurfaceMuted,
        )
        KNOWN_CRASH_SIGNALS.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { indicator ->
                    SignalChip(indicator.label, active = indicator.code in evidenceCodes)
                }
            }
        }
    }
}

@Composable
private fun SignalChip(label: String, active: Boolean) {
    val dotColor = if (active) {
        val infiniteTransition = rememberInfiniteTransition(label = "signalPulse")
        val pulseAlpha by infiniteTransition.animateFloat(
            initialValue = 1f,
            targetValue = 0.35f,
            animationSpec = infiniteRepeatable(
                animation = tween(700, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "signalPulseAlpha",
        )
        Color(0xFFF87171).copy(alpha = pulseAlpha)
    } else {
        Color(0xFF334155)
    }
    val textColor = if (active) Color.White else Color(0xFF64748B)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .background(Color(0xFF0B1622), RoundedCornerShape(999.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Box(modifier = Modifier.size(8.dp).background(dotColor, CircleShape))
        Spacer(modifier = Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = textColor)
    }
}

/** Speed as a continuous value needs a graph, not an on/off light — unlike [SignalStatePanel]'s
 * chips, which are correct for the binary VHAL/IMU signals. Sourced from
 * [EmergencyViewModel]'s rolling [EmergencyUiState.Active.speedHistoryKmh] window. */
@Composable
private fun SpeedSparkline(samples: List<Float>, modifier: Modifier = Modifier) {
    val colors = LocalSafeDriveStatusColors.current
    Column(modifier = modifier) {
        Text(
            "Tốc độ theo thời gian (km/h):",
            style = MaterialTheme.typography.labelMedium,
            color = colors.onSurfaceMuted,
        )
        if (samples.size < 2) {
            Text(
                "Chưa đủ dữ liệu để vẽ đồ thị.",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF64748B),
                modifier = Modifier.padding(top = 4.dp),
            )
        } else {
            val minValue = samples.min()
            val maxValue = samples.max()
            val range = (maxValue - minValue).coerceAtLeast(1f)
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(72.dp)
                    .padding(top = 6.dp)
                    .background(Color(0xFF0B1622), RoundedCornerShape(8.dp)),
            ) {
                val stepX = size.width / (samples.size - 1)
                fun yOf(value: Float) = size.height - ((value - minValue) / range) * size.height
                val points = samples.mapIndexed { index, value -> Offset(index * stepX, yOf(value)) }
                val path = Path().apply { moveTo(points[0].x, points[0].y) }
                // Cubic-Bezier through each pair of real points (never averaged/smoothed away) so a
                // gradual multi-sample trend reads as a flowing curve instead of jagged straight-line
                // corners, while a genuine one-interval jump (a real sudden stop) still crosses the
                // same x-distance just as steeply — smoother in general, sharp exactly where the data
                // itself is sharp.
                for (i in 1 until points.size) {
                    val previousPoint = points[i - 1]
                    val currentPoint = points[i]
                    val midX = previousPoint.x + (currentPoint.x - previousPoint.x) / 2
                    path.cubicTo(midX, previousPoint.y, midX, currentPoint.y, currentPoint.x, currentPoint.y)
                }
                drawPath(path, color = Color(0xFF2DD4BF), style = Stroke(width = 4f))
                // A sharp drop between the last two samples is the same shape
                // VHAL_SPEED_DROP/VHAL_SUDDEN_STOP_AT_SPEED are built to catch — highlight it so
                // it reads as visually obvious, not just a line going down.
                val previous = samples[samples.size - 2]
                val last = samples.last()
                if (previous - last >= 20f) {
                    drawCircle(
                        color = Color(0xFFF87171),
                        radius = 6f,
                        center = Offset((samples.size - 1) * stepX, yOf(last)),
                    )
                }
            }
            Text(
                "Hiện tại: ${samples.last().toInt()} km/h",
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFF64748B),
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}
