package vn.edu.haui.hvs.safedrive.data.remote

import vn.edu.haui.hvs.safedrive.core.common.GatewayError
import vn.edu.haui.hvs.safedrive.core.common.GatewayResult
import vn.edu.haui.hvs.safedrive.core.model.ActionConfirmRequest
import vn.edu.haui.hvs.safedrive.core.model.ActionConfirmResult
import vn.edu.haui.hvs.safedrive.core.model.AssistantQueryRequest
import vn.edu.haui.hvs.safedrive.core.model.AssistantQueryResult
import vn.edu.haui.hvs.safedrive.core.model.EmergencyResponseRequest
import vn.edu.haui.hvs.safedrive.core.model.EmergencySnapshot
import vn.edu.haui.hvs.safedrive.core.model.EventAccepted
import vn.edu.haui.hvs.safedrive.core.model.HealthStatus
import vn.edu.haui.hvs.safedrive.core.model.SafeDriveEvent
import vn.edu.haui.hvs.safedrive.core.model.SessionInfo
import vn.edu.haui.hvs.safedrive.core.model.StartSessionRequest
import vn.edu.haui.hvs.safedrive.core.model.StateEnvelope
import vn.edu.haui.hvs.safedrive.core.model.StateUpdateRequest
import vn.edu.haui.hvs.safedrive.domain.repository.SafeDriveGateway

/**
 * Returned by `GatewayProvider` when Remote Mode is selected with no/invalid BASE_URL
 * (docs/android-mvp-plan/12 W5.6). Every call fails fast with [GatewayError.Configuration] — this
 * class never makes a network call and never silently substitutes Mock behavior, so the UI can never
 * mistake "not configured" for "working."
 */
class ConfigurationErrorGateway(private val reasonCode: String) : SafeDriveGateway {

    private fun <T> fail(): GatewayResult<T> = GatewayResult.Failure(GatewayError.Configuration(reasonCode))

    override suspend fun checkHealth(): GatewayResult<HealthStatus> = fail()
    override suspend fun startSession(request: StartSessionRequest): GatewayResult<SessionInfo> = fail()
    override suspend fun updateVehicleState(request: StateUpdateRequest): GatewayResult<StateEnvelope> = fail()
    override suspend fun getVehicleState(sessionId: String, sinceVersion: Long?): GatewayResult<StateEnvelope> = fail()
    override suspend fun queryAssistant(request: AssistantQueryRequest): GatewayResult<AssistantQueryResult> = fail()
    override suspend fun sendEvent(event: SafeDriveEvent): GatewayResult<EventAccepted> = fail()
    override suspend fun confirmAction(request: ActionConfirmRequest): GatewayResult<ActionConfirmResult> = fail()
    override suspend fun getEmergency(emergencyId: String, sessionId: String): GatewayResult<EmergencySnapshot> = fail()
    override suspend fun respondEmergency(request: EmergencyResponseRequest): GatewayResult<EmergencySnapshot> = fail()
}
