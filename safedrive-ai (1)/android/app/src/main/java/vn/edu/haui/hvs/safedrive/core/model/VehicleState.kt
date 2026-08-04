package vn.edu.haui.hvs.safedrive.core.model

/**
 * Raw vehicle telemetry. No attention/drowsiness score and no driver-alert conclusion is ever
 * modeled here — only indirect, observable signals (see docs/android-mvp-plan/03-data-api-contract.md).
 */
data class VehicleState(
    val speedKmh: Float,
    val engineTemperatureC: Float,
    val cabinTemperatureC: Float,
    val energyPercent: Int,
    val continuousDrivingMinutes: Int?,
    val steeringLastInteractionSeconds: Int?,
    val driverSeatOccupied: Boolean?,
    val wearableConnected: Boolean,
    val activeDtcs: List<Dtc>,
    val crashDetected: Boolean,
    val passengerResponse: PassengerResponse,
    val updatedAtMs: Long,
    /** Current requested HVAC setpoint, sourced from VHAL or the simulated control tool. */
    val hvacTargetTemperatureC: Float? = null,
    val location: VehicleLocation? = null,
)

/** Last known position supplied by a GPS or simulator adapter; it is never inferred by the assistant. */
data class VehicleLocation(
    val latitude: Double,
    val longitude: Double,
    val source: String,
    val capturedAtMs: Long,
)

/** Availability/metadata of the indirect driver-support signal sources; counts are computed, never hard-coded. */
data class DriverSupportSignals(
    val steeringSignalAvailable: Boolean,
    val seatSensorAvailable: Boolean,
    val wearableLastUpdateMs: Long?,
    val wearableHeartRateBpm: Int?,
    val userReportedFatigue: Boolean?,
    val availableSourceCount: Int,
    val totalSourceCount: Int,
)

data class RestRecommendation(
    val level: RestRecommendationLevel,
    val title: String,
    val message: String,
    val confidence: ConfidenceLevel,
    val reasonCodes: List<String>,
    val updatedAtMs: Long,
)

data class Dtc(
    val code: String,
    val title: String,
    val description: String,
    val severity: Severity,
    val recommendation: String,
    val updatedAtMs: Long,
)

data class RiskAssessment(
    val level: Severity,
    val title: String,
    val message: String,
    val reasonCodes: List<String>,
)

/** A demo/simulator scenario; kept as test fixture only, never a production policy source. */
data class ScenarioPreset(
    val id: String,
    val title: String,
    val subtitle: String,
    val description: String,
    val iconKey: String,
    val vehicleState: VehicleState,
    val driverSupportSignals: DriverSupportSignals,
)

/** Aggregate the Cockpit (and other screens) render. Risk/rest are gateway output, never computed in UI. */
data class CockpitSnapshot(
    val vehicleState: VehicleState,
    val driverSupportSignals: DriverSupportSignals,
    val riskAssessment: RiskAssessment,
    val restRecommendation: RestRecommendation,
    val connectionStatus: SystemConnectionStatus,
    val stateVersion: Long,
    val updatedAtMs: Long,
)
