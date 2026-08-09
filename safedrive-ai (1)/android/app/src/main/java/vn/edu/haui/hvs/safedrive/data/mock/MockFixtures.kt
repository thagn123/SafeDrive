package vn.edu.haui.hvs.safedrive.data.mock

import vn.edu.haui.hvs.safedrive.core.common.AppClock
import vn.edu.haui.hvs.safedrive.core.model.ChatMessage
import vn.edu.haui.hvs.safedrive.core.model.ChatSender
import vn.edu.haui.hvs.safedrive.core.model.DriverSupportSignals
import vn.edu.haui.hvs.safedrive.core.model.Dtc
import vn.edu.haui.hvs.safedrive.core.model.PassengerResponse
import vn.edu.haui.hvs.safedrive.core.model.RiskAssessment
import vn.edu.haui.hvs.safedrive.core.model.ScenarioPreset
import vn.edu.haui.hvs.safedrive.core.model.Severity
import vn.edu.haui.hvs.safedrive.core.model.VehicleState

/**
 * Fixtures ported from src/data/mock/mockRepository.ts (see docs/android-mvp-plan/01-source-audit.md).
 * Kept as Demo Mode test data only; this is never a production safety-policy source.
 */
class MockFixtures(private val clock: AppClock) {

    fun dtcP0301(): Dtc = Dtc(
        code = "P0301",
        title = "Lỗi đánh lửa xi-lanh số 1",
        description = "Hệ thống OBD-II phát hiện hiện tượng bỏ lửa (misfire) liên tục ở xi-lanh số 1 động cơ.",
        severity = Severity.MEDIUM,
        recommendation = "Giảm tốc độ, tránh tăng tốc mạnh và di chuyển xe đến trạm dịch vụ gần nhất để kiểm tra bugi/cuộn cao áp.",
        updatedAtMs = clock.nowMs(),
    )

    fun dtcOverheat(): Dtc = Dtc(
        code = "ENGINE_OVERHEAT",
        title = "Cảnh báo nhiệt độ động cơ quá cao",
        description = "Nhiệt độ dung dịch làm mát động cơ vượt quá ngưỡng cho phép (110°C).",
        severity = Severity.HIGH,
        recommendation = "Bật quạt sưởi cabin để hỗ trợ xả nhiệt, tấp vào vị trí an toàn và tắt máy chờ động cơ hạ nhiệt.",
        updatedAtMs = clock.nowMs(),
    )

    fun dtcCriticalCrash(): Dtc = Dtc(
        code = "CRITICAL_SENSOR_FAULT",
        title = "Hệ thống cảm biến và túi khí bị lỗi nghiêm trọng",
        description = "Mất kết nối nhiều cảm biến quanh xe và hệ thống túi khí. Khả năng cao do va chạm mạnh làm đứt cáp tín hiệu.",
        severity = Severity.CRITICAL,
        recommendation = "Hệ thống đang tự động kích hoạt SOS và gọi cứu hộ.",
        updatedAtMs = clock.nowMs(),
    )

    private fun signals(
        vehicleState: VehicleState,
        steeringSignalAvailable: Boolean,
        seatSensorAvailable: Boolean,
        wearableLastUpdateMs: Long?,
        wearableHeartRateBpm: Int?,
        userReportedFatigue: Boolean?,
    ): DriverSupportSignals {
        var available = 0
        if (vehicleState.continuousDrivingMinutes != null) available++
        if (steeringSignalAvailable) available++
        if (seatSensorAvailable) available++
        if (vehicleState.wearableConnected) available++
        return DriverSupportSignals(
            steeringSignalAvailable = steeringSignalAvailable,
            seatSensorAvailable = seatSensorAvailable,
            wearableLastUpdateMs = wearableLastUpdateMs,
            wearableHeartRateBpm = wearableHeartRateBpm,
            userReportedFatigue = userReportedFatigue,
            availableSourceCount = available,
            totalSourceCount = 4,
        )
    }

    fun defaultVehicleState(): VehicleState = VehicleState(
        speedKmh = 62f,
        engineTemperatureC = 92f,
        cabinTemperatureC = 25f,
        energyPercent = 74,
        continuousDrivingMinutes = 135,
        steeringLastInteractionSeconds = 12,
        driverSeatOccupied = true,
        wearableConnected = false,
        activeDtcs = emptyList(),
        crashDetected = false,
        passengerResponse = PassengerResponse.RESPONSIVE,
        updatedAtMs = clock.nowMs(),
    )

    fun defaultDriverSupportSignals(): DriverSupportSignals = signals(
        vehicleState = defaultVehicleState(),
        steeringSignalAvailable = true,
        seatSensorAvailable = true,
        wearableLastUpdateMs = null,
        wearableHeartRateBpm = null,
        userReportedFatigue = false,
    )

    fun scenarioPresets(): List<ScenarioPreset> {
        val now = clock.nowMs()

        fun preset(
            id: String,
            title: String,
            subtitle: String,
            description: String,
            iconKey: String,
            vehicleState: VehicleState,
            signals: DriverSupportSignals,
        ) = ScenarioPreset(id, title, subtitle, description, iconKey, vehicleState, signals)

        val newTripState = defaultVehicleState().copy(
            continuousDrivingMinutes = 20,
            steeringLastInteractionSeconds = 5,
            wearableConnected = false,
        )
        val overTwoHoursState = defaultVehicleState().copy(
            continuousDrivingMinutes = 135,
            steeringLastInteractionSeconds = 15,
            wearableConnected = true,
        )
        val considerRestState = defaultVehicleState().copy(
            continuousDrivingMinutes = 200,
            steeringLastInteractionSeconds = 25,
            wearableConnected = true,
        )
        val restRecommendedState = defaultVehicleState().copy(
            continuousDrivingMinutes = 260,
            steeringLastInteractionSeconds = 40,
            wearableConnected = true,
        )
        val insufficientDataState = defaultVehicleState().copy(
            continuousDrivingMinutes = null,
            steeringLastInteractionSeconds = null,
            wearableConnected = false,
        )
        val userReportedFatigueState = defaultVehicleState().copy(
            continuousDrivingMinutes = 90,
            steeringLastInteractionSeconds = 10,
            wearableConnected = true,
        )
        val overheatState = defaultVehicleState().copy(
            speedKmh = 55f,
            engineTemperatureC = 112f,
            activeDtcs = listOf(dtcOverheat()),
        )
        val crashState = defaultVehicleState().copy(
            speedKmh = 85f, // Xe đang chạy tốc độ cao
            activeDtcs = listOf(dtcCriticalCrash()), // Kèm theo lỗi cảm biến nghiêm trọng
            crashDetected = true,
            passengerResponse = PassengerResponse.NO_RESPONSE,
        )
        val misfireState = defaultVehicleState().copy(
            continuousDrivingMinutes = 30,
            activeDtcs = listOf(dtcP0301()),
        )
        val multiDtcState = defaultVehicleState().copy(
            engineTemperatureC = 95f,
            activeDtcs = listOf(dtcP0301(), dtcOverheat()),
        )
        val singleSignalCrashState = defaultVehicleState().copy(
            speedKmh = 0f,
            crashDetected = true,
            passengerResponse = PassengerResponse.RESPONSIVE,
            driverSeatOccupied = false,
        )

        return listOf(
            preset(
                id = "new_trip",
                title = "1. Hành trình mới",
                subtitle = "Nội dung: 20 phút lái",
                description = "Lái xe 20 phút, 3/4 nguồn tín hiệu hợp lệ. Chưa ghi nhận dấu hiệu cần nghỉ.",
                iconKey = "shield_check",
                vehicleState = newTripState,
                signals = signals(newTripState, true, true, null, null, false),
            ),
            preset(
                id = "over_2h",
                title = "2. Đã lái hơn 2 giờ",
                subtitle = "Lái 135 phút (MONITOR)",
                description = "Thời gian lái 135 phút. Khuyến nghị theo dõi tình trạng của bạn.",
                iconKey = "clock",
                vehicleState = overTwoHoursState,
                signals = signals(overTwoHoursState, true, true, now - 20_000, 76, false),
            ),
            preset(
                id = "consider_rest",
                title = "3. Nên cân nhắc nghỉ",
                subtitle = "Lái 200 phút (CONSIDER_REST)",
                description = "Đã lái xe liên tục 200 phút (3h20m). Khuyên bạn nên cân nhắc dừng nghỉ.",
                iconKey = "coffee",
                vehicleState = considerRestState,
                signals = signals(considerRestState, true, true, now - 10_000, 72, false),
            ),
            preset(
                id = "rest_recommended",
                title = "4. Đã lái hơn 4 giờ",
                subtitle = "Lái 260 phút (REST_RECOMMENDED)",
                description = "Thời gian lái xe đã đạt 260 phút (trên 4h). SafeDrive khuyến nghị dừng nghỉ.",
                iconKey = "alert_triangle",
                vehicleState = restRecommendedState,
                signals = signals(restRecommendedState, true, true, now - 15_000, 68, false),
            ),
            preset(
                id = "insufficient_data",
                title = "5. Chưa đủ dữ liệu",
                subtitle = "Chỉ có 1 nguồn cảm biến",
                description = "Thời gian lái chưa xác định, chỉ có cảm biến ghế. Yêu cầu thêm dữ liệu.",
                iconKey = "help_circle",
                vehicleState = insufficientDataState,
                signals = signals(insufficientDataState, false, true, null, null, false),
            ),
            preset(
                id = "user_reported_fatigue",
                title = "6. Người dùng báo đang mệt",
                subtitle = "Chủ động báo mệt (REST_RECOMMENDED)",
                description = "Người dùng vừa phản hồi đang cảm thấy mệt mỏi qua giọng nói hoặc giao diện.",
                iconKey = "user_x",
                vehicleState = userReportedFatigueState,
                signals = signals(userReportedFatigueState, true, true, now - 5_000, 65, true),
            ),
            preset(
                id = "overheat",
                title = "7. Động cơ quá nhiệt",
                subtitle = "Cảnh báo quá nhiệt (HIGH)",
                description = "Nhiệt độ động cơ 112°C. Hệ thống khuyến nghị giảm tải và tấp vào vị trí an toàn.",
                iconKey = "flame",
                vehicleState = overheatState,
                signals = signals(overheatState, true, true, null, null, false),
            ),
            preset(
                id = "crash",
                title = "8. Va chạm giả lập",
                subtitle = "Crash Detected (SOS)",
                description = "Giả lập tín hiệu đâm va ở tốc độ cao (85km/h), cảm biến hỏng nặng, tài xế không phản hồi. Đang kích hoạt SOS.",
                iconKey = "alert_octagon",
                vehicleState = crashState,
                signals = signals(crashState, true, true, now - 1000, 115, false),
            ),
            preset(
                id = "misfire",
                title = "9. Lỗi chẩn đoán mức trung bình",
                subtitle = "Mã lỗi P0301 (MEDIUM)",
                description = "Phát hiện bỏ lửa xi-lanh số 1 (P0301), mức độ trung bình. Khác với preset quá nhiệt (HIGH).",
                iconKey = "wrench",
                vehicleState = misfireState,
                signals = signals(misfireState, true, true, null, null, false),
            ),
            preset(
                id = "multi_dtc",
                title = "10. Nhiều mã lỗi cùng lúc",
                subtitle = "P0301 + ENGINE_OVERHEAT",
                description = "Xe ghi nhận đồng thời 2 mã lỗi. Hệ thống ưu tiên mức nghiêm trọng nhất và liệt kê đầy đủ trong Chẩn đoán.",
                iconKey = "alert_octagon",
                vehicleState = multiDtcState,
                signals = signals(multiDtcState, true, true, null, null, false),
            ),
            preset(
                id = "crash_single_signal",
                title = "11. Cảm biến va chạm đơn lẻ",
                subtitle = "Chưa đủ bằng chứng — KHÔNG tự kích hoạt SOS",
                description = "Chỉ có tín hiệu va chạm, không có bằng chứng bổ sung (ghế trống, hành khách phản hồi bình thường). Theo quy tắc bằng chứng, hệ thống KHÔNG tự mở màn hình SOS — chỉ hiển thị cảnh báo yêu cầu xác nhận.",
                iconKey = "shield_alert",
                vehicleState = singleSignalCrashState,
                signals = signals(singleSignalCrashState, true, true, null, null, false),
            ),
        )
    }

    fun initialChatMessages(): List<ChatMessage> {
        val now = clock.nowMs()
        return listOf(
            ChatMessage(
                id = "msg_0",
                sender = ChatSender.SAFEDRIVE,
                text = "Xin chào! Tôi là Trợ lý SafeDrive AI từ HaUI Vehicle Smart Systems (HVS). Tôi hỗ trợ kiểm tra thông số hành trình, cảnh báo an toàn và chẩn đoán kỹ thuật.",
                timestampMs = now - 120_000,
                latencyMs = 120,
                route = "safety_fast_path",
            ),
            ChatMessage(
                id = "msg_1",
                sender = ChatSender.USER,
                text = "Xe của tôi đã lái bao lâu rồi?",
                timestampMs = now - 60_000,
            ),
            ChatMessage(
                id = "msg_2",
                sender = ChatSender.SAFEDRIVE,
                text = "Xe đã di chuyển liên tục 2 giờ 15 phút. Dựa trên các tín hiệu hỗ trợ gián tiếp (vô lăng, ghế lái), hệ thống khuyến nghị bạn nên theo dõi tình trạng sức khỏe.",
                timestampMs = now - 59_000,
                latencyMs = 186,
                route = "safety_fast_path",
                risk = RiskAssessment(
                    level = Severity.LOW,
                    title = "Nên theo dõi",
                    message = "Thời gian lái liên tục đang tăng. Hãy theo dõi tình trạng của bạn.",
                    reasonCodes = listOf("continuous_driving_over_2h"),
                ),
            ),
        )
    }
}
