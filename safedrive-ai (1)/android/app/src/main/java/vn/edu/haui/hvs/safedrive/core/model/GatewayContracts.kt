package vn.edu.haui.hvs.safedrive.core.model

/**
 * Domain-level request/response types shared by [vn.edu.haui.hvs.safedrive.data.mock.MockSafeDriveGateway]
 * and the Phase 7 RemoteSafeDriveGateway. These are NOT wire DTOs: data/remote maps JSON DTOs to
 * these types in one place (see docs/android-mvp-plan/03-data-api-contract.md).
 */

data class HealthCapabilities(
    val assistant: Boolean,
    val emergencySimulation: Boolean,
    val cockpitStream: Boolean,
)

data class HealthStatus(
    val status: SystemConnectionStatus,
    val serviceName: String,
    val apiVersion: String,
    val serverTimeMs: Long,
    val capabilities: HealthCapabilities,
)

data class StartSessionRequest(
    val deviceId: String,
    val appVersion: String,
    val platform: String,
    val mode: BackendMode,
    val clientTimeMs: Long,
)

data class SessionInfo(
    val sessionId: String,
    val expiresAtMs: Long,
    val serverTimeMs: Long,
    val contractVersion: String,
    val realEmergencyDispatchEnabled: Boolean = false,
)

data class StateUpdateRequest(
    val sessionId: String,
    val vehicleState: VehicleState,
    val driverSupportSignals: DriverSupportSignals,
    val source: StateSource,
    val clientEventId: String,
)

data class StateEnvelope(
    val vehicleState: VehicleState,
    val driverSupportSignals: DriverSupportSignals,
    val riskAssessment: RiskAssessment,
    val restRecommendation: RestRecommendation,
    val stateVersion: Long,
    val acceptedAtMs: Long,
    val emergency: EmergencySnapshot? = null,
)

data class AssistantContext(
    val stateVersion: Long,
    val screen: String,
)

/**
 * `source`/`locale`/`clientAttemptOf` are top-level per the frozen contract
 * (docs/android-mvp-plan/12 W7.3; see docs/contract-delta-draft.md for why `locale` moved out of
 * [AssistantContext]). `clientAttemptOf` is `null` on a first attempt, the original turn's
 * `requestId` on a user-initiated retry (docs/android-mvp-plan/12 W1.12/W7).
 */
data class AssistantQueryRequest(
    val sessionId: String,
    val requestId: String,
    val text: String,
    val source: AssistantTurnSource = AssistantTurnSource.TEXT,
    val locale: String = "vi-VN",
    val clientAttemptOf: String? = null,
    val context: AssistantContext,
)

/**
 * [serverProcessingMs]/[model]/[finishReason] are additive/optional (docs/android-mvp-plan/12 W7.4):
 * Mock populates deterministic values, Remote passes through whatever the backend sends (or omits —
 * `ignoreUnknownKeys`/nullable defaults mean an absent field is never a parse failure). `model` is for
 * Developer Mode observability only; Android never chooses or depends on a specific model.
 */
data class AssistantQueryResult(
    val requestId: String,
    val message: ChatMessage,
    val serverTimeMs: Long,
    val serverProcessingMs: Long? = null,
    val model: String? = null,
    val finishReason: String? = null,
    val llmUsed: Boolean = false,
    val fallback: Boolean = false,
    val fallbackReason: String? = null,
)

sealed interface EventPayload {
    data object UserReportedFatigue : EventPayload
    data class VoiceError(val reason: String) : EventPayload
    data class ScenarioApplied(val scenarioId: String) : EventPayload
    data class ConnectionChanged(val status: SystemConnectionStatus) : EventPayload
}

data class SafeDriveEvent(
    val sessionId: String,
    val eventId: String,
    val type: SafeDriveEventType,
    val occurredAtMs: Long,
    val payload: EventPayload,
)

data class EventAccepted(
    val eventId: String,
    val accepted: Boolean,
    val acceptedAtMs: Long,
    val stateVersion: Long? = null,
)

data class ActionConfirmRequest(
    val sessionId: String,
    val actionId: String,
    val actionType: ActionType,
    val confirmed: Boolean,
    val confirmationId: String,
    val contextVersion: Long,
    val hvacTargetTemperatureC: Float? = null,
)

data class ActionConfirmResult(
    val accepted: Boolean,
    val actionResult: String?,
    val message: String?,
    val serverTimeMs: Long,
)

data class EmergencyResponseRequest(
    val emergencyId: String,
    val sessionId: String,
    val responseId: String,
    val response: EmergencyResponseType,
    val clientTimeMs: Long,
)
