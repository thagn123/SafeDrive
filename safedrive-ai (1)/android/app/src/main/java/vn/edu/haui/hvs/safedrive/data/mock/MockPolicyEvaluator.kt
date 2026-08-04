package vn.edu.haui.hvs.safedrive.data.mock

import vn.edu.haui.hvs.safedrive.core.common.AppClock
import vn.edu.haui.hvs.safedrive.core.model.ConfidenceLevel
import vn.edu.haui.hvs.safedrive.core.model.DriverSupportSignals
import vn.edu.haui.hvs.safedrive.core.model.RestRecommendation
import vn.edu.haui.hvs.safedrive.core.model.RestRecommendationLevel
import vn.edu.haui.hvs.safedrive.core.model.RiskAssessment
import vn.edu.haui.hvs.safedrive.core.model.Severity
import vn.edu.haui.hvs.safedrive.core.model.VehicleState

/**
 * Demo-only stand-in for the backend's deterministic risk/rest policy, ported from
 * src/data/mock/mockRepository.ts (`evaluateRestRecommendation`/`evaluateRisk`). `NO_IMMEDIATE_INDICATION`
 * from the prototype is locked to [RestRecommendationLevel.NORMAL] per
 * docs/android-mvp-plan/09-checklists-and-decisions.md. Never used as a production safety authority.
 */
class MockPolicyEvaluator(private val clock: AppClock) {

    fun evaluateRestRecommendation(vehicleState: VehicleState, signals: DriverSupportSignals): RestRecommendation {
        val now = clock.nowMs()
        val reasons = mutableListOf<String>()

        val wearableStale = vehicleState.wearableConnected &&
            signals.wearableLastUpdateMs != null &&
            (now - signals.wearableLastUpdateMs > 120_000)

        if (!signals.steeringSignalAvailable) reasons += "steering_signal_unavailable"
        if (!signals.seatSensorAvailable) reasons += "seat_sensor_unavailable"
        if (!vehicleState.wearableConnected) reasons += "wearable_not_connected"
        if (wearableStale) reasons += "wearable_data_stale"

        val confidence = when {
            signals.availableSourceCount <= 1 -> ConfidenceLevel.LOW
            signals.availableSourceCount in 2..3 -> ConfidenceLevel.MEDIUM
            signals.availableSourceCount == 4 && !wearableStale -> ConfidenceLevel.HIGH
            else -> ConfidenceLevel.MEDIUM
        }
        if (signals.availableSourceCount <= 1 && "insufficient_signal_sources" !in reasons) {
            reasons += "insufficient_signal_sources"
        }

        val mins = vehicleState.continuousDrivingMinutes

        if (signals.userReportedFatigue == true) {
            return RestRecommendation(
                level = RestRecommendationLevel.REST_RECOMMENDED,
                title = "Khuyến nghị dừng nghỉ",
                message = "Bạn vừa cho biết mình đang mệt. SafeDrive khuyến nghị dừng nghỉ tại vị trí an toàn.",
                confidence = confidence,
                reasonCodes = reasons + "user_reported_fatigue",
                updatedAtMs = now,
            )
        }

        if (mins != null && mins >= 240) {
            return RestRecommendation(
                level = RestRecommendationLevel.REST_RECOMMENDED,
                title = "Khuyến nghị dừng nghỉ",
                message = "Bạn đã lái xe liên tục hơn 4 giờ. SafeDrive khuyến nghị dừng nghỉ tại vị trí an toàn.",
                confidence = confidence,
                reasonCodes = reasons + "continuous_driving_over_4h",
                updatedAtMs = now,
            )
        }

        if (mins != null && mins >= 180) {
            return RestRecommendation(
                level = RestRecommendationLevel.CONSIDER_REST,
                title = "Nên cân nhắc nghỉ",
                message = "Bạn đã lái xe liên tục trong thời gian dài. Hãy cân nhắc nghỉ tại vị trí phù hợp.",
                confidence = confidence,
                reasonCodes = reasons + "continuous_driving_over_3h",
                updatedAtMs = now,
            )
        }

        if ((mins != null && mins >= 120) || wearableStale) {
            val withMinsReason = if (mins != null && mins >= 120) reasons + "continuous_driving_over_2h" else reasons
            return RestRecommendation(
                level = RestRecommendationLevel.MONITOR,
                title = "Nên theo dõi",
                message = "Thời gian lái liên tục đang tăng. Hãy theo dõi tình trạng của bạn.",
                confidence = confidence,
                reasonCodes = withMinsReason,
                updatedAtMs = now,
            )
        }

        if (signals.availableSourceCount <= 1 || mins == null) {
            return RestRecommendation(
                level = RestRecommendationLevel.INSUFFICIENT_DATA,
                title = "Chưa đủ dữ liệu",
                message = "Chưa đủ dữ liệu để đưa ra khuyến nghị.",
                confidence = ConfidenceLevel.LOW,
                reasonCodes = reasons,
                updatedAtMs = now,
            )
        }

        return RestRecommendation(
            level = RestRecommendationLevel.NORMAL,
            title = "Chưa ghi nhận dấu hiệu cần nghỉ",
            message = "Chưa ghi nhận tín hiệu cho thấy cần nghỉ ngay.",
            confidence = confidence,
            reasonCodes = listOf("system_nominal"),
            updatedAtMs = now,
        )
    }

    fun evaluateRisk(vehicleState: VehicleState, restRecommendation: RestRecommendation): RiskAssessment {
        if (vehicleState.crashDetected) {
            val noResponse = vehicleState.passengerResponse.name == "NO_RESPONSE"
            return RiskAssessment(
                level = Severity.CRITICAL,
                title = "Phát hiện va chạm!",
                message = if (noResponse) {
                    "Cảnh báo va chạm nghiêm trọng: Người trong xe không phản hồi. Kích hoạt đếm ngược SOS khẩn cấp."
                } else {
                    "Phát hiện va chạm xe. Vui lòng xác nhận tình trạng an toàn của bạn."
                },
                reasonCodes = buildList {
                    add("crash_detected")
                    if (noResponse) add("passenger_no_response")
                },
            )
        }

        if (restRecommendation.level == RestRecommendationLevel.REST_RECOMMENDED) {
            return RiskAssessment(
                level = Severity.HIGH,
                title = "Khuyến nghị dừng nghỉ",
                message = restRecommendation.message,
                reasonCodes = restRecommendation.reasonCodes,
            )
        }

        // Demo-only fallback policy.
        // Backend policy is authoritative in REMOTE mode (app/mobile/safety.py's
        // ENGINE_WARNING_C/ENGINE_CRITICAL_C). Kept numerically identical (105/115) so Demo
        // Mode never contradicts a real backend response for the same reading.
        if (vehicleState.engineTemperatureC >= 115) {
            return RiskAssessment(
                level = Severity.CRITICAL,
                title = "Động cơ quá nhiệt nghiêm trọng",
                message = "Nhiệt độ động cơ đã đạt ${vehicleState.engineTemperatureC.toInt()}°C, vượt ngưỡng nguy hiểm. " +
                    "Hãy tấp vào lề và tắt máy ngay lập tức để tránh hỏng động cơ.",
                reasonCodes = listOf("engine_overheat_critical"),
            )
        }

        if (vehicleState.engineTemperatureC >= 105) {
            return RiskAssessment(
                level = Severity.HIGH,
                title = "Nhiệt độ động cơ đang tăng cao",
                message = "Nhiệt độ động cơ đang ở ${vehicleState.engineTemperatureC.toInt()}°C. Hãy giảm tốc, tắt điều hòa " +
                    "nếu cần và tìm vị trí an toàn để kiểm tra.",
                reasonCodes = listOf("engine_overheat_warning"),
            )
        }

        if (vehicleState.activeDtcs.isNotEmpty()) {
            val hasHighOrCritical = vehicleState.activeDtcs.any {
                it.severity == Severity.HIGH || it.severity == Severity.CRITICAL
            }
            return RiskAssessment(
                level = if (hasHighOrCritical) Severity.HIGH else Severity.MEDIUM,
                title = if (hasHighOrCritical) "Cảnh báo lỗi kỹ thuật" else "Lỗi chẩn đoán DTC",
                message = "Phát hiện ${vehicleState.activeDtcs.size} mã lỗi cần theo dõi: " +
                    vehicleState.activeDtcs.joinToString(", ") { it.code } + ".",
                reasonCodes = vehicleState.activeDtcs.map { "dtc_active_${it.code.lowercase()}" },
            )
        }

        if (restRecommendation.level == RestRecommendationLevel.CONSIDER_REST ||
            restRecommendation.level == RestRecommendationLevel.MONITOR
        ) {
            return RiskAssessment(
                level = Severity.MEDIUM,
                title = restRecommendation.title,
                message = restRecommendation.message,
                reasonCodes = restRecommendation.reasonCodes,
            )
        }

        return RiskAssessment(
            level = Severity.LOW,
            title = "Mức độ an toàn: THẤP",
            message = "Xe và hành trình đang ở trạng thái ổn định.",
            reasonCodes = listOf("system_nominal"),
        )
    }
}
