package vn.edu.haui.hvs.safedrive.data.mock

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.delay
import vn.edu.haui.hvs.safedrive.core.common.AppClock
import vn.edu.haui.hvs.safedrive.core.common.GatewayResult
import vn.edu.haui.hvs.safedrive.core.common.IdGenerator
import vn.edu.haui.hvs.safedrive.core.model.ActionConfirmRequest
import vn.edu.haui.hvs.safedrive.core.model.ActionConfirmResult
import vn.edu.haui.hvs.safedrive.core.model.ActionType
import vn.edu.haui.hvs.safedrive.core.model.AssistantQueryRequest
import vn.edu.haui.hvs.safedrive.core.model.AssistantQueryResult
import vn.edu.haui.hvs.safedrive.core.model.ChatMessage
import vn.edu.haui.hvs.safedrive.core.model.ChatSender
import vn.edu.haui.hvs.safedrive.core.model.EmergencyResponseRequest
import vn.edu.haui.hvs.safedrive.core.model.EmergencySnapshot
import vn.edu.haui.hvs.safedrive.core.model.EmergencyState
import vn.edu.haui.hvs.safedrive.core.model.EventAccepted
import vn.edu.haui.hvs.safedrive.core.model.HealthCapabilities
import vn.edu.haui.hvs.safedrive.core.model.HealthStatus
import vn.edu.haui.hvs.safedrive.core.model.SafeDriveAction
import vn.edu.haui.hvs.safedrive.core.model.SafeDriveEvent
import vn.edu.haui.hvs.safedrive.core.model.SessionInfo
import vn.edu.haui.hvs.safedrive.core.model.SimulatedLatencyProfile
import vn.edu.haui.hvs.safedrive.core.model.StartSessionRequest
import vn.edu.haui.hvs.safedrive.core.model.StateEnvelope
import vn.edu.haui.hvs.safedrive.core.model.StateUpdateRequest
import vn.edu.haui.hvs.safedrive.core.model.SystemConnectionStatus
import vn.edu.haui.hvs.safedrive.domain.repository.SafeDriveGateway

/** Timeout profile deliberately exceeds any realistic outer assistant-turn deadline so it reliably
 * produces a genuine [vn.edu.haui.hvs.safedrive.core.common.GatewayError.Timeout] via the caller's own
 * timeout, instead of this class fabricating that error directly (docs/android-mvp-plan/12 W4.4). */
private const val SIMULATED_TIMEOUT_DELAY_MS = 20_000L

/**
 * Demo Mode backend stand-in. Implements the exact same [SafeDriveGateway] contract the Phase 7
 * RemoteSafeDriveGateway will implement, so the UI never branches on `if (demoMode)`.
 *
 * [latencyProfileProvider] defaults to always-NONE (no artificial delay — docs/android-mvp-plan/12
 * W4.3: "Demo Mode mặc định không có artificial delay"). Production wires it to
 * `SafeDriveContainer.appPreferences.value.developerLatencyProfile` so Developer Mode can opt into a
 * simulated profile for demoing slow/timeout UI; this is never on by default.
 */
class MockSafeDriveGateway(
    private val clock: AppClock,
    private val idGenerator: IdGenerator,
    private val fixtures: MockFixtures,
    private val policyEvaluator: MockPolicyEvaluator,
    private val latencyProfileProvider: () -> SimulatedLatencyProfile = { SimulatedLatencyProfile.NONE },
) : SafeDriveGateway {

    private val stateVersion = AtomicLong(0)

    @Volatile
    private var lastEnvelope: StateEnvelope = buildEnvelope(
        fixtures.defaultVehicleState(),
        fixtures.defaultDriverSupportSignals(),
    )

    private fun buildEnvelope(
        vehicleState: vn.edu.haui.hvs.safedrive.core.model.VehicleState,
        signals: vn.edu.haui.hvs.safedrive.core.model.DriverSupportSignals,
    ): StateEnvelope {
        val rest = policyEvaluator.evaluateRestRecommendation(vehicleState, signals)
        val risk = policyEvaluator.evaluateRisk(vehicleState, rest)
        return StateEnvelope(
            vehicleState = vehicleState,
            driverSupportSignals = signals,
            riskAssessment = risk,
            restRecommendation = rest,
            stateVersion = stateVersion.incrementAndGet(),
            acceptedAtMs = clock.nowMs(),
        )
    }

    override suspend fun checkHealth(): GatewayResult<HealthStatus> = GatewayResult.Success(
        HealthStatus(
            status = SystemConnectionStatus.NORMAL,
            serviceName = "safedrive-mock",
            apiVersion = "v1",
            serverTimeMs = clock.nowMs(),
            capabilities = HealthCapabilities(assistant = true, emergencySimulation = true, cockpitStream = false),
        ),
    )

    override suspend fun startSession(request: StartSessionRequest): GatewayResult<SessionInfo> = GatewayResult.Success(
        SessionInfo(
            sessionId = idGenerator.next("sess"),
            expiresAtMs = clock.nowMs() + SESSION_TTL_MS,
            serverTimeMs = clock.nowMs(),
            contractVersion = "v1",
            realEmergencyDispatchEnabled = false,
        ),
    )

    override suspend fun updateVehicleState(request: StateUpdateRequest): GatewayResult<StateEnvelope> {
        val envelope = buildEnvelope(request.vehicleState, request.driverSupportSignals)
        lastEnvelope = envelope
        return GatewayResult.Success(envelope)
    }

    override suspend fun getVehicleState(sessionId: String, sinceVersion: Long?): GatewayResult<StateEnvelope> =
        GatewayResult.Success(lastEnvelope)

    override suspend fun queryAssistant(request: AssistantQueryRequest): GatewayResult<AssistantQueryResult> {
        val latencyMs = when (latencyProfileProvider()) {
            SimulatedLatencyProfile.NONE -> 0L
            SimulatedLatencyProfile.MS_100 -> 100L
            SimulatedLatencyProfile.MS_500 -> 500L
            SimulatedLatencyProfile.MS_2000 -> 2_000L
            SimulatedLatencyProfile.TIMEOUT -> SIMULATED_TIMEOUT_DELAY_MS
        }
        if (latencyMs > 0) delay(latencyMs)

        val vehicleState = lastEnvelope.vehicleState
        val lower = request.text.lowercase()
        var actions = emptyList<SafeDriveAction>()
        val route = "safety_fast_path"

        val replyText = when {
            "nhiệt" in lower -> {
                val base = "Nhiệt độ động cơ hiện tại là ${vehicleState.engineTemperatureC.toInt()}°C và " +
                    "nhiệt độ cabin là ${vehicleState.cabinTemperatureC.toInt()}°C. "
                if (vehicleState.engineTemperatureC >= 108f) {
                    actions = listOf(
                        SafeDriveAction(
                            id = "act_overheat",
                            type = ActionType.SHOW_WARNING,
                            title = "Mở cảnh báo quá nhiệt",
                            requiresConfirmation = false,
                        ),
                    )
                    base + "Động cơ đang ở mức quá nhiệt! Vui lòng giảm tốc độ và kiểm tra hệ thống làm mát."
                } else {
                    base + "Hệ thống làm mát vẫn làm việc bình thường."
                }
            }

            "lỗi" in lower || "dtc" in lower || "p0301" in lower -> {
                if (vehicleState.activeDtcs.isNotEmpty()) {
                    actions = listOf(
                        SafeDriveAction(
                            id = "act_dtc_nav",
                            type = ActionType.OPEN_DIAGNOSTICS,
                            title = "Xem chi tiết chẩn đoán",
                            requiresConfirmation = false,
                        ),
                    )
                    val dtcList = vehicleState.activeDtcs.joinToString("; ") { "${it.code}: ${it.title}" }
                    "Hệ thống ghi nhận ${vehicleState.activeDtcs.size} mã lỗi: $dtcList. Khuyên bạn nên đưa xe đến trạm dịch vụ chuyên nghiệp."
                } else {
                    "Hiện tại không phát hiện bất kỳ mã lỗi chẩn đoán DTC nào trên hệ thống điều khiển xe."
                }
            }

            "buồn ngủ" in lower || "mệt" in lower || "lái lâu" in lower || "nghỉ" in lower -> {
                actions = listOf(
                    SafeDriveAction(
                        id = "act_rest_stop",
                        type = ActionType.SUGGEST_REST_STOP,
                        title = "Đề xuất dừng nghỉ",
                        requiresConfirmation = true,
                    ),
                )
                val mins = vehicleState.continuousDrivingMinutes
                val timeStr = if (mins != null) "${mins / 60} giờ ${mins % 60} phút" else "chưa xác định"
                "Thời gian lái xe liên tục hiện tại: $timeStr. Dựa trên các tín hiệu hỗ trợ gián tiếp (vô lăng, ghế lái), SafeDrive khuyên bạn nên cân nhắc dừng nghỉ tại vị trí an toàn."
            }

            "sos" in lower || "va chạm" in lower || "cứu hộ" in lower -> {
                actions = listOf(
                    SafeDriveAction(
                        id = "act_sos_start",
                        type = ActionType.START_SOS_COUNTDOWN,
                        title = "Mở đếm ngược SOS mô phỏng",
                        requiresConfirmation = true,
                    ),
                )
                "Đang kiểm tra tình trạng an toàn khẩn cấp. Phát hiện yêu cầu trợ giúp cứu hộ!"
            }

            "tốc độ" in lower || "vận tốc" in lower ->
                "Xe đang di chuyển với vận tốc ${vehicleState.speedKmh.toInt()} km/h. Giới hạn an toàn trên tuyến đường là 80 km/h."

            "pin" in lower || "nhiên liệu" in lower -> {
                val estimatedKm = (vehicleState.energyPercent * 4.2).toInt()
                "Mức năng lượng/nhiên liệu còn lại: ${vehicleState.energyPercent}%. Ước tính còn di chuyển được khoảng $estimatedKm km."
            }

            else -> {
                val mins = vehicleState.continuousDrivingMinutes
                val timeStr = if (mins != null) "${mins / 60} giờ ${mins % 60} phút" else "chưa xác định"
                "Tôi đã nhận câu hỏi: \"${request.text}\". Các thông số vận hành xe (Tốc độ: ${vehicleState.speedKmh.toInt()}km/h, " +
                    "Thời gian lái liên tục: $timeStr) đang được tổng hợp và theo dõi theo thời gian thực."
            }
        }

        val message = ChatMessage(
            id = idGenerator.next("msg"),
            sender = ChatSender.SAFEDRIVE,
            text = replyText,
            timestampMs = clock.nowMs(),
            risk = policyEvaluator.evaluateRisk(vehicleState, lastEnvelope.restRecommendation),
            actions = actions,
            route = route,
            latencyMs = latencyMs,
        )
        return GatewayResult.Success(
            AssistantQueryResult(
                requestId = request.requestId,
                message = message,
                serverTimeMs = clock.nowMs(),
                serverProcessingMs = latencyMs,
                model = "safedrive-mock-rules",
                finishReason = "STOP",
                // Demo Mode is rule-based, not an LLM -- never "used" and never a "fallback"
                // from a failed attempt, the same deterministic-by-design semantics as a
                // never-narrated backend route.
                llmUsed = false,
                fallback = false,
                fallbackReason = null,
            ),
        )
    }

    override suspend fun sendEvent(event: SafeDriveEvent): GatewayResult<EventAccepted> = GatewayResult.Success(
        EventAccepted(
            eventId = event.eventId,
            accepted = true,
            acceptedAtMs = clock.nowMs(),
            stateVersion = lastEnvelope.stateVersion,
        ),
    )

    override suspend fun confirmAction(request: ActionConfirmRequest): GatewayResult<ActionConfirmResult> =
        GatewayResult.Success(
            ActionConfirmResult(
                accepted = request.confirmed,
                actionResult = if (request.confirmed) "ACKNOWLEDGED" else "DECLINED",
                message = null,
                serverTimeMs = clock.nowMs(),
            ),
        )

    // Demo Mode emergency authority is the local mock reducer (EmergencyRepository), not this gateway;
    // these two methods only exist so Remote Mode can share the same SafeDriveGateway interface.
    override suspend fun getEmergency(emergencyId: String, sessionId: String): GatewayResult<EmergencySnapshot> =
        GatewayResult.Failure(vn.edu.haui.hvs.safedrive.core.common.GatewayError.Unsupported)

    override suspend fun respondEmergency(request: EmergencyResponseRequest): GatewayResult<EmergencySnapshot> =
        GatewayResult.Failure(vn.edu.haui.hvs.safedrive.core.common.GatewayError.Unsupported)

    private companion object {
        const val SESSION_TTL_MS = 12 * 60 * 60 * 1000L
    }
}
