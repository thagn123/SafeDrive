package vn.edu.haui.hvs.safedrive.domain.repository

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

/**
 * Backend boundary per docs/android-mvp-plan/03-data-api-contract.md. [vn.edu.haui.hvs.safedrive.data.mock.MockSafeDriveGateway]
 * and the Phase 7 RemoteSafeDriveGateway both implement this and must pass the same contract tests.
 * Risk, rest recommendation, DTC severity and emergency deadline are always gateway/backend output —
 * never computed in a ViewModel or composable.
 */
interface SafeDriveGateway {
    suspend fun checkHealth(): GatewayResult<HealthStatus>

    suspend fun startSession(request: StartSessionRequest): GatewayResult<SessionInfo>

    suspend fun updateVehicleState(request: StateUpdateRequest): GatewayResult<StateEnvelope>

    suspend fun getVehicleState(sessionId: String, sinceVersion: Long? = null): GatewayResult<StateEnvelope>

    suspend fun queryAssistant(request: AssistantQueryRequest): GatewayResult<AssistantQueryResult>

    suspend fun sendEvent(event: SafeDriveEvent): GatewayResult<EventAccepted>

    suspend fun confirmAction(request: ActionConfirmRequest): GatewayResult<ActionConfirmResult>

    suspend fun getEmergency(emergencyId: String, sessionId: String): GatewayResult<EmergencySnapshot>

    suspend fun respondEmergency(request: EmergencyResponseRequest): GatewayResult<EmergencySnapshot>
}
