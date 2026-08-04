package vn.edu.haui.hvs.safedrive.data.remote

import vn.edu.haui.hvs.safedrive.core.model.ActionConfirmRequest
import vn.edu.haui.hvs.safedrive.core.model.ActionConfirmResult
import vn.edu.haui.hvs.safedrive.core.model.ActionType
import vn.edu.haui.hvs.safedrive.core.model.AssistantQueryRequest
import vn.edu.haui.hvs.safedrive.core.model.AssistantQueryResult
import vn.edu.haui.hvs.safedrive.core.model.ChatMessage
import vn.edu.haui.hvs.safedrive.core.model.ChatSender
import vn.edu.haui.hvs.safedrive.core.model.DriverSupportSignals
import vn.edu.haui.hvs.safedrive.core.model.Dtc
import vn.edu.haui.hvs.safedrive.core.model.EmergencyResponseRequest
import vn.edu.haui.hvs.safedrive.core.model.EmergencySnapshot
import vn.edu.haui.hvs.safedrive.core.model.EmergencyState
import vn.edu.haui.hvs.safedrive.core.model.EventAccepted
import vn.edu.haui.hvs.safedrive.core.model.EventPayload
import vn.edu.haui.hvs.safedrive.core.model.EvidenceItem
import vn.edu.haui.hvs.safedrive.core.model.HealthCapabilities
import vn.edu.haui.hvs.safedrive.core.model.HealthStatus
import vn.edu.haui.hvs.safedrive.core.model.PassengerResponse
import vn.edu.haui.hvs.safedrive.core.model.RestRecommendation
import vn.edu.haui.hvs.safedrive.core.model.RestRecommendationLevel
import vn.edu.haui.hvs.safedrive.core.model.RescueBrief
import vn.edu.haui.hvs.safedrive.core.model.RescueDispatchReceipt
import vn.edu.haui.hvs.safedrive.core.model.RescueLocation
import vn.edu.haui.hvs.safedrive.core.model.RiskAssessment
import vn.edu.haui.hvs.safedrive.core.model.SafeDriveAction
import vn.edu.haui.hvs.safedrive.core.model.SafeDriveEvent
import vn.edu.haui.hvs.safedrive.core.model.SessionInfo
import vn.edu.haui.hvs.safedrive.core.model.Severity
import vn.edu.haui.hvs.safedrive.core.model.StartSessionRequest
import vn.edu.haui.hvs.safedrive.core.model.StateEnvelope
import vn.edu.haui.hvs.safedrive.core.model.StateUpdateRequest
import vn.edu.haui.hvs.safedrive.core.model.SystemConnectionStatus
import vn.edu.haui.hvs.safedrive.core.model.VehicleLocation
import vn.edu.haui.hvs.safedrive.core.model.VehicleState
import vn.edu.haui.hvs.safedrive.data.remote.dto.ActionConfirmRequestDto
import vn.edu.haui.hvs.safedrive.data.remote.dto.ActionConfirmResponseDto
import vn.edu.haui.hvs.safedrive.data.remote.dto.AssistantContextDto
import vn.edu.haui.hvs.safedrive.data.remote.dto.AssistantQueryRequestDto
import vn.edu.haui.hvs.safedrive.data.remote.dto.AssistantQueryResponseDto
import vn.edu.haui.hvs.safedrive.data.remote.dto.ChatMessageDto
import vn.edu.haui.hvs.safedrive.data.remote.dto.DriverSupportSignalsDto
import vn.edu.haui.hvs.safedrive.data.remote.dto.DtcDto
import vn.edu.haui.hvs.safedrive.data.remote.dto.EmergencyResponseRequestDto
import vn.edu.haui.hvs.safedrive.data.remote.dto.EmergencySnapshotDto
import vn.edu.haui.hvs.safedrive.data.remote.dto.EventAcceptedDto
import vn.edu.haui.hvs.safedrive.data.remote.dto.EventRequestDto
import vn.edu.haui.hvs.safedrive.data.remote.dto.EvidenceItemDto
import vn.edu.haui.hvs.safedrive.data.remote.dto.HealthResponseDto
import vn.edu.haui.hvs.safedrive.data.remote.dto.RestRecommendationDto
import vn.edu.haui.hvs.safedrive.data.remote.dto.RescueBriefDto
import vn.edu.haui.hvs.safedrive.data.remote.dto.RescueDispatchReceiptDto
import vn.edu.haui.hvs.safedrive.data.remote.dto.RescueLocationDto
import vn.edu.haui.hvs.safedrive.data.remote.dto.RiskAssessmentDto
import vn.edu.haui.hvs.safedrive.data.remote.dto.SafeDriveActionDto
import vn.edu.haui.hvs.safedrive.data.remote.dto.StartSessionRequestDto
import vn.edu.haui.hvs.safedrive.data.remote.dto.StartSessionResponseDto
import vn.edu.haui.hvs.safedrive.data.remote.dto.StateEnvelopeDto
import vn.edu.haui.hvs.safedrive.data.remote.dto.StateUpdateRequestDto
import vn.edu.haui.hvs.safedrive.data.remote.dto.VehicleLocationDto
import vn.edu.haui.hvs.safedrive.data.remote.dto.VehicleStateDto

/**
 * The single place DTO↔domain mapping happens (docs/android-mvp-plan/02-android-architecture.md,
 * "data/remote map DTO ↔ domain tại một chỗ"). An unrecognized enum string from the backend never
 * crashes — it falls back to a safe, allowlisted default (`ActionType.NONE`, `Severity.LOW`, etc.)
 * so an "unknown action/severity" is a no-op, not a crash.
 */

private inline fun <reified T : Enum<T>> safeEnumOf(value: String, default: T): T =
    enumValues<T>().firstOrNull { it.name == value } ?: default

// ---- Health ----

fun HealthResponseDto.toDomain(): HealthStatus = HealthStatus(
    // The signal-first backend preserves its existing liveness value "ok" while
    // the mobile contract uses NORMAL. Treat the two success values identically.
    status = if (status == "ok") SystemConnectionStatus.NORMAL else safeEnumOf(status, SystemConnectionStatus.OFFLINE),
    serviceName = service,
    apiVersion = apiVersion,
    serverTimeMs = serverTimeMs,
    capabilities = HealthCapabilities(
        assistant = capabilities.assistant,
        emergencySimulation = capabilities.emergencySimulation,
        cockpitStream = capabilities.cockpitStream,
    ),
)

// ---- Session ----

fun StartSessionRequest.toDto(): StartSessionRequestDto = StartSessionRequestDto(
    deviceId = deviceId,
    appVersion = appVersion,
    platform = platform,
    mode = mode.name,
    clientTimeMs = clientTimeMs,
)

fun StartSessionResponseDto.toDomain(): SessionInfo = SessionInfo(
    sessionId = sessionId,
    expiresAtMs = expiresAtMs,
    serverTimeMs = serverTimeMs,
    contractVersion = contractVersion,
    realEmergencyDispatchEnabled = false, // MVP invariant; never trust a backend-sent `true`.
)

// ---- Vehicle state / driver signals ----

fun Dtc.toDto(): DtcDto = DtcDto(code, title, description, severity.name, recommendation, updatedAtMs)

fun DtcDto.toDomain(): Dtc = Dtc(
    code = code,
    title = title,
    description = description,
    severity = safeEnumOf(severity, Severity.LOW),
    recommendation = recommendation,
    updatedAtMs = updatedAtMs,
)

fun VehicleState.toDto(): VehicleStateDto = VehicleStateDto(
    speedKmh = speedKmh,
    engineTemperatureC = engineTemperatureC,
    cabinTemperatureC = cabinTemperatureC,
    energyPercent = energyPercent,
    continuousDrivingMinutes = continuousDrivingMinutes,
    steeringLastInteractionSeconds = steeringLastInteractionSeconds,
    driverSeatOccupied = driverSeatOccupied,
    wearableConnected = wearableConnected,
    activeDtcs = activeDtcs.map { it.toDto() },
    crashDetected = crashDetected,
    passengerResponse = passengerResponse.name,
    updatedAtMs = updatedAtMs,
    hvacTargetTemperatureC = hvacTargetTemperatureC,
    location = location?.toDto(),
)

fun VehicleStateDto.toDomain(): VehicleState = VehicleState(
    speedKmh = speedKmh,
    engineTemperatureC = engineTemperatureC,
    cabinTemperatureC = cabinTemperatureC,
    energyPercent = energyPercent,
    continuousDrivingMinutes = continuousDrivingMinutes,
    steeringLastInteractionSeconds = steeringLastInteractionSeconds,
    driverSeatOccupied = driverSeatOccupied,
    wearableConnected = wearableConnected,
    activeDtcs = activeDtcs.map { it.toDomain() },
    crashDetected = crashDetected,
    passengerResponse = safeEnumOf(passengerResponse, PassengerResponse.UNKNOWN),
    updatedAtMs = updatedAtMs,
    hvacTargetTemperatureC = hvacTargetTemperatureC,
    location = location?.toDomain(),
)

fun VehicleLocation.toDto(): VehicleLocationDto = VehicleLocationDto(
    latitude = latitude,
    longitude = longitude,
    source = source,
    capturedAtMs = capturedAtMs,
)

fun VehicleLocationDto.toDomain(): VehicleLocation = VehicleLocation(
    latitude = latitude,
    longitude = longitude,
    source = source,
    capturedAtMs = capturedAtMs,
)

fun DriverSupportSignals.toDto(): DriverSupportSignalsDto = DriverSupportSignalsDto(
    steeringSignalAvailable = steeringSignalAvailable,
    seatSensorAvailable = seatSensorAvailable,
    wearableLastUpdateMs = wearableLastUpdateMs,
    wearableHeartRateBpm = wearableHeartRateBpm,
    userReportedFatigue = userReportedFatigue,
    availableSourceCount = availableSourceCount,
    totalSourceCount = totalSourceCount,
)

fun DriverSupportSignalsDto.toDomain(): DriverSupportSignals = DriverSupportSignals(
    steeringSignalAvailable = steeringSignalAvailable,
    seatSensorAvailable = seatSensorAvailable,
    wearableLastUpdateMs = wearableLastUpdateMs,
    wearableHeartRateBpm = wearableHeartRateBpm,
    userReportedFatigue = userReportedFatigue,
    availableSourceCount = availableSourceCount,
    totalSourceCount = totalSourceCount,
)

fun RiskAssessmentDto.toDomain(): RiskAssessment = RiskAssessment(
    level = safeEnumOf(level, Severity.LOW),
    title = title,
    message = message,
    reasonCodes = reasonCodes,
)

fun RestRecommendationDto.toDomain(): RestRecommendation = RestRecommendation(
    level = safeEnumOf(level, RestRecommendationLevel.INSUFFICIENT_DATA),
    title = title,
    message = message,
    confidence = safeEnumOf(confidence, vn.edu.haui.hvs.safedrive.core.model.ConfidenceLevel.LOW),
    reasonCodes = reasonCodes,
    updatedAtMs = updatedAtMs,
)

fun StateUpdateRequest.toDto(): StateUpdateRequestDto = StateUpdateRequestDto(
    sessionId = sessionId,
    state = vehicleState.toDto(),
    driverSupportSignals = driverSupportSignals.toDto(),
    source = source.name,
    clientEventId = clientEventId,
)

fun StateEnvelopeDto.toDomain(): StateEnvelope = StateEnvelope(
    vehicleState = state.toDomain(),
    driverSupportSignals = driverSupportSignals.toDomain(),
    riskAssessment = riskAssessment.toDomain(),
    restRecommendation = restRecommendation.toDomain(),
    stateVersion = stateVersion,
    acceptedAtMs = acceptedAtMs,
    emergency = emergency?.toDomain(),
)

// ---- Assistant ----

fun AssistantQueryRequest.toDto(): AssistantQueryRequestDto = AssistantQueryRequestDto(
    sessionId = sessionId,
    requestId = requestId,
    text = text,
    source = source.name,
    locale = locale,
    clientAttemptOf = clientAttemptOf,
    context = AssistantContextDto(context.stateVersion, context.screen),
)

fun SafeDriveActionDto.toDomain(): SafeDriveAction = SafeDriveAction(
    id = id,
    type = safeEnumOf(type, ActionType.NONE),
    title = title,
    requiresConfirmation = requiresConfirmation,
    hvacTargetTemperatureC = hvacTargetTemperatureC,
)

fun ChatMessageDto.toDomain(): ChatMessage = ChatMessage(
    id = id,
    sender = safeEnumOf(sender, ChatSender.SAFEDRIVE),
    text = text,
    timestampMs = timestampMs,
    risk = risk?.toDomain(),
    actions = actions.map { it.toDomain() },
    route = route,
    latencyMs = latencyMs,
)

fun AssistantQueryResponseDto.toDomain(): AssistantQueryResult = AssistantQueryResult(
    requestId = requestId,
    message = message.toDomain(),
    serverTimeMs = serverTimeMs,
    serverProcessingMs = serverProcessingMs,
    model = model,
    finishReason = finishReason,
    llmUsed = llmUsed,
    fallback = fallback,
    fallbackReason = fallbackReason,
)

// ---- Events ----

fun SafeDriveEvent.toDto(): EventRequestDto = EventRequestDto(
    sessionId = sessionId,
    eventId = eventId,
    type = type.name,
    occurredAtMs = occurredAtMs,
    reason = (payload as? EventPayload.VoiceError)?.reason,
    scenarioId = (payload as? EventPayload.ScenarioApplied)?.scenarioId,
    connectionStatus = (payload as? EventPayload.ConnectionChanged)?.status?.name,
)

fun EventAcceptedDto.toDomain(): EventAccepted = EventAccepted(eventId, accepted, acceptedAtMs, stateVersion)

// ---- Actions ----

fun ActionConfirmRequest.toDto(): ActionConfirmRequestDto = ActionConfirmRequestDto(
    sessionId = sessionId,
    actionId = actionId,
    actionType = actionType.name,
    confirmed = confirmed,
    confirmationId = confirmationId,
    contextVersion = contextVersion,
    hvacTargetTemperatureC = hvacTargetTemperatureC,
)

fun ActionConfirmResponseDto.toDomain(): ActionConfirmResult = ActionConfirmResult(accepted, actionResult, message, serverTimeMs)

// ---- Emergency ----

fun EvidenceItemDto.toDomain(): EvidenceItem = EvidenceItem(code, label, detectedAtMs)

fun EmergencySnapshotDto.toDomain(): EmergencySnapshot = EmergencySnapshot(
    emergencyId = emergencyId,
    state = safeEnumOf(state, EmergencyState.IDLE),
    deadlineMs = deadlineMs,
    evidence = evidence.map { it.toDomain() },
    rescueBrief = rescueBrief?.toDomain(),
    rescueDispatch = rescueDispatch?.toDomain(),
    realEmergencyDispatchEnabled = false, // MVP invariant regardless of what the backend sends.
    reasoningSummary = reasoningSummary,
)

fun RescueLocationDto.toDomain(): RescueLocation = RescueLocation(
    latitude = latitude,
    longitude = longitude,
    source = source,
    ageMs = ageMs,
    freshness = freshness,
)

fun RescueBriefDto.toDomain(): RescueBrief = RescueBrief(
    dispatchMode = dispatchMode,
    eventType = eventType,
    vehicleId = vehicleId,
    timestampMs = timestampMs,
    lastKnownLocation = lastKnownLocation?.toDomain(),
    locationStatus = locationStatus,
    vehicleStatusSummary = vehicleStatusSummary,
    riskLevel = safeEnumOf(riskLevel, Severity.LOW),
    evidence = evidence,
    realEmergencyDispatchEnabled = false, // The wire never enables real dispatch in this MVP.
)

fun RescueDispatchReceiptDto.toDomain(): RescueDispatchReceipt = RescueDispatchReceipt(
    provider = provider,
    endpoint = endpoint,
    outcome = outcome,
    referenceId = referenceId,
    receivedAtMs = receivedAtMs,
)

fun EmergencyResponseRequest.toDto(): EmergencyResponseRequestDto = EmergencyResponseRequestDto(
    sessionId = sessionId,
    responseId = responseId,
    response = response.name,
    clientTimeMs = clientTimeMs,
)
