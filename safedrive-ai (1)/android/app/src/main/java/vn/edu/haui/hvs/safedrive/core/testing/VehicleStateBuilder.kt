package vn.edu.haui.hvs.safedrive.core.testing

import vn.edu.haui.hvs.safedrive.core.model.DriverSupportSignals
import vn.edu.haui.hvs.safedrive.core.model.PassengerResponse
import vn.edu.haui.hvs.safedrive.core.model.VehicleState

/** Test/preview builder for [VehicleState] with safe defaults; avoids repeating the full constructor. */
fun vehicleStateFixture(
    speedKmh: Float = 60f,
    engineTemperatureC: Float = 90f,
    cabinTemperatureC: Float = 24f,
    energyPercent: Int = 70,
    continuousDrivingMinutes: Int? = 30,
    steeringLastInteractionSeconds: Int? = 5,
    driverSeatOccupied: Boolean? = true,
    wearableConnected: Boolean = false,
    activeDtcs: List<vn.edu.haui.hvs.safedrive.core.model.Dtc> = emptyList(),
    crashDetected: Boolean = false,
    passengerResponse: PassengerResponse = PassengerResponse.RESPONSIVE,
    updatedAtMs: Long = 0L,
    hvacTargetTemperatureC: Float? = null,
): VehicleState = VehicleState(
    speedKmh = speedKmh,
    engineTemperatureC = engineTemperatureC,
    cabinTemperatureC = cabinTemperatureC,
    energyPercent = energyPercent,
    continuousDrivingMinutes = continuousDrivingMinutes,
    steeringLastInteractionSeconds = steeringLastInteractionSeconds,
    driverSeatOccupied = driverSeatOccupied,
    wearableConnected = wearableConnected,
    activeDtcs = activeDtcs,
    crashDetected = crashDetected,
    passengerResponse = passengerResponse,
    updatedAtMs = updatedAtMs,
    hvacTargetTemperatureC = hvacTargetTemperatureC,
)

fun driverSupportSignalsFixture(
    steeringSignalAvailable: Boolean = true,
    seatSensorAvailable: Boolean = true,
    wearableLastUpdateMs: Long? = null,
    wearableHeartRateBpm: Int? = null,
    userReportedFatigue: Boolean? = false,
    availableSourceCount: Int = 3,
    totalSourceCount: Int = 4,
): DriverSupportSignals = DriverSupportSignals(
    steeringSignalAvailable = steeringSignalAvailable,
    seatSensorAvailable = seatSensorAvailable,
    wearableLastUpdateMs = wearableLastUpdateMs,
    wearableHeartRateBpm = wearableHeartRateBpm,
    userReportedFatigue = userReportedFatigue,
    availableSourceCount = availableSourceCount,
    totalSourceCount = totalSourceCount,
)
