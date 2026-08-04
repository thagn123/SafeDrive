package vn.edu.haui.hvs.safedrive.data.remote.dto

import kotlinx.serialization.Serializable

/**
 * Wire DTOs per docs/android-mvp-plan/03-data-api-contract.md. Field names are `camelCase` to match
 * the contract's JSON exactly; mapping to/from domain types happens in one place —
 * `data/remote/ApiMappers.kt` — so no other layer ever sees these types.
 */
@Serializable
data class DtcDto(
    val code: String,
    val title: String,
    val description: String,
    val severity: String,
    val recommendation: String,
    val updatedAtMs: Long,
)

@Serializable
data class VehicleLocationDto(
    val latitude: Double,
    val longitude: Double,
    val source: String,
    val capturedAtMs: Long,
)

@Serializable
data class VehicleStateDto(
    val speedKmh: Float,
    val engineTemperatureC: Float,
    val cabinTemperatureC: Float,
    val energyPercent: Int,
    val continuousDrivingMinutes: Int?,
    val steeringLastInteractionSeconds: Int?,
    val driverSeatOccupied: Boolean?,
    val wearableConnected: Boolean,
    val activeDtcs: List<DtcDto>,
    val crashDetected: Boolean,
    val passengerResponse: String,
    val updatedAtMs: Long,
    val hvacTargetTemperatureC: Float? = null,
    val location: VehicleLocationDto? = null,
)

@Serializable
data class DriverSupportSignalsDto(
    val steeringSignalAvailable: Boolean,
    val seatSensorAvailable: Boolean,
    val wearableLastUpdateMs: Long?,
    val wearableHeartRateBpm: Int?,
    val userReportedFatigue: Boolean?,
    val availableSourceCount: Int,
    val totalSourceCount: Int,
)

@Serializable
data class RiskAssessmentDto(
    val level: String,
    val title: String,
    val message: String,
    val reasonCodes: List<String>,
)

@Serializable
data class RestRecommendationDto(
    val level: String,
    val title: String,
    val message: String,
    val confidence: String,
    val reasonCodes: List<String>,
    val updatedAtMs: Long,
)

@Serializable
data class StateUpdateRequestDto(
    val sessionId: String,
    val state: VehicleStateDto,
    val driverSupportSignals: DriverSupportSignalsDto,
    val source: String,
    val clientEventId: String,
)

@Serializable
data class StateEnvelopeDto(
    val state: VehicleStateDto,
    val driverSupportSignals: DriverSupportSignalsDto,
    val riskAssessment: RiskAssessmentDto,
    val restRecommendation: RestRecommendationDto,
    val stateVersion: Long,
    val acceptedAtMs: Long,
    val emergency: EmergencySnapshotDto? = null,
)
