package vn.edu.haui.hvs.safedrive.domain.usecase

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.scan
import vn.edu.haui.hvs.safedrive.core.common.AppClock
import vn.edu.haui.hvs.safedrive.core.common.GatewayError
import vn.edu.haui.hvs.safedrive.core.common.GatewayResult
import vn.edu.haui.hvs.safedrive.core.model.CockpitSnapshot
import vn.edu.haui.hvs.safedrive.core.model.StateSource
import vn.edu.haui.hvs.safedrive.core.model.StateUpdateRequest
import vn.edu.haui.hvs.safedrive.core.model.SystemConnectionStatus
import vn.edu.haui.hvs.safedrive.domain.repository.AppPreferences
import vn.edu.haui.hvs.safedrive.domain.repository.RemoteEmergencySnapshotSink
import vn.edu.haui.hvs.safedrive.domain.repository.VehicleDataSource
import vn.edu.haui.hvs.safedrive.core.common.IdGenerator

/** Below the backend's `STATE_FRESHNESS_MS` (10s, `app/mobile/context.py`) with margin, so a manual
 * Simulator "Apply" that isn't followed by another change still reads as FRESH for as long as the
 * user stays on the app — matching what a continuously-sampled real vehicle sensor would report. */
private const val STATE_HEARTBEAT_MS = 4_000L

/**
 * UI → ViewModel → **use case** → gateway per docs/android-mvp-plan/02-android-architecture.md.
 * Pushes raw [VehicleDataSource] telemetry to the current gateway (Mock or Remote) and emits the
 * risk/rest-enriched [CockpitSnapshot] the gateway returns. On failure, the previous snapshot is kept
 * with an updated [SystemConnectionStatus] instead of dropping data — never inferred as "driver ok".
 *
 * [appPreferences] is combined in (not just read ad hoc) so that switching backend mode/BASE_URL
 * alone — with no new vehicle-state emission — still immediately issues a fresh request against the
 * newly-selected gateway (docs/android-mvp-plan/12 W5.8), instead of silently continuing to show
 * data implicitly tied to the previous gateway until the next unrelated state change. The follow-up
 * `updateVehicleState` call is sent through [SessionCoordinator.currentSession]'s resolved gateway,
 * not a separately re-resolved one, so a Settings change concurrent with this emission can never mix
 * a session from one gateway with a state update sent to another (remediation item 3).
 *
 * A [STATE_HEARTBEAT_MS] ticker is combined in alongside real state changes so the gateway keeps
 * receiving `updatedAtMs = clock.nowMs()` even while nothing has changed — otherwise the backend's
 * freshness gate expires ~10s after a one-shot Simulator "Apply", and both the risk assessment and
 * the assistant fall back to "cần làm mới dữ liệu" for a value that is still current.
 */
class ObserveCockpitUseCase(
    private val vehicleDataSource: VehicleDataSource,
    private val sessionCoordinator: SessionCoordinator,
    private val appPreferences: StateFlow<AppPreferences>,
    private val idGenerator: IdGenerator,
    private val clock: AppClock,
    private val remoteEmergencySnapshotSink: RemoteEmergencySnapshotSink? = null,
) {
    private val heartbeat: Flow<Unit> = flow {
        while (true) {
            emit(Unit)
            delay(STATE_HEARTBEAT_MS)
        }
    }

    operator fun invoke(): Flow<CockpitSnapshot> =
        combine(vehicleDataSource.vehicleState, vehicleDataSource.driverSupportSignals, appPreferences, heartbeat) { state, signals, _, _ ->
            state to signals
        }.scan(null as CockpitSnapshot?) { previous, (state, signals) ->
            val sessionResult = sessionCoordinator.currentSession()
            when (sessionResult) {
                is GatewayResult.Failure -> previous?.copy(connectionStatus = mapErrorToConnectionStatus(sessionResult.error))
                    ?: emptySnapshot(state, signals, mapErrorToConnectionStatus(sessionResult.error))

                is GatewayResult.Success -> {
                    val resolved = sessionResult.data
                    val request = StateUpdateRequest(
                        sessionId = resolved.sessionId,
                        vehicleState = state.copy(updatedAtMs = clock.nowMs()),
                        driverSupportSignals = signals,
                        source = StateSource.PHONE_SIMULATOR,
                        clientEventId = idGenerator.next("state"),
                    )
                    when (val result = resolved.gateway.updateVehicleState(request)) {
                        is GatewayResult.Success -> {
                            remoteEmergencySnapshotSink?.publishRemoteSnapshot(result.data.emergency)
                            CockpitSnapshot(
                                vehicleState = result.data.vehicleState,
                                driverSupportSignals = result.data.driverSupportSignals,
                                riskAssessment = result.data.riskAssessment,
                                restRecommendation = result.data.restRecommendation,
                                connectionStatus = SystemConnectionStatus.NORMAL,
                                stateVersion = result.data.stateVersion,
                                updatedAtMs = result.data.acceptedAtMs,
                            )
                        }

                        is GatewayResult.Failure -> {
                            val status = mapErrorToConnectionStatus(result.error)
                            previous?.copy(connectionStatus = status) ?: emptySnapshot(state, signals, status)
                        }
                    }
                }
            }
        }.filterNotNull()

    private fun emptySnapshot(
        state: vn.edu.haui.hvs.safedrive.core.model.VehicleState,
        signals: vn.edu.haui.hvs.safedrive.core.model.DriverSupportSignals,
        status: SystemConnectionStatus,
    ) = CockpitSnapshot(
        vehicleState = state,
        driverSupportSignals = signals,
        riskAssessment = vn.edu.haui.hvs.safedrive.core.model.RiskAssessment(
            level = vn.edu.haui.hvs.safedrive.core.model.Severity.LOW,
            title = "Chưa có dữ liệu",
            message = "Chưa nhận được đánh giá an toàn từ hệ thống.",
            reasonCodes = emptyList(),
        ),
        restRecommendation = vn.edu.haui.hvs.safedrive.core.model.RestRecommendation(
            level = vn.edu.haui.hvs.safedrive.core.model.RestRecommendationLevel.INSUFFICIENT_DATA,
            title = "Chưa đủ dữ liệu",
            message = "Chưa đủ dữ liệu để đưa ra khuyến nghị.",
            confidence = vn.edu.haui.hvs.safedrive.core.model.ConfidenceLevel.LOW,
            reasonCodes = emptyList(),
            updatedAtMs = state.updatedAtMs,
        ),
        connectionStatus = status,
        stateVersion = 0L,
        updatedAtMs = state.updatedAtMs,
    )

    private fun mapErrorToConnectionStatus(error: GatewayError): SystemConnectionStatus = when (error) {
        GatewayError.Offline, GatewayError.Timeout -> SystemConnectionStatus.OFFLINE
        GatewayError.Unsupported -> SystemConnectionStatus.NO_AI_SERVICE
        is GatewayError.Configuration -> SystemConnectionStatus.NO_AI_SERVICE
        else -> SystemConnectionStatus.STALE_DATA
    }
}
