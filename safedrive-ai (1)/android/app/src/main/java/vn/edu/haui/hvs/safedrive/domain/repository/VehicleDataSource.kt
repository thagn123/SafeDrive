package vn.edu.haui.hvs.safedrive.domain.repository

import kotlinx.coroutines.flow.StateFlow
import vn.edu.haui.hvs.safedrive.core.model.DriverSupportSignals
import vn.edu.haui.hvs.safedrive.core.model.ScenarioPreset
import vn.edu.haui.hvs.safedrive.core.model.VehicleState

/**
 * Raw vehicle telemetry boundary. MVP always uses the mock implementation in the `vehicle` package;
 * a VHAL adapter is a future phase but must satisfy this same interface without features knowing about it.
 */
interface VehicleDataSource {
    val vehicleState: StateFlow<VehicleState>
    val driverSupportSignals: StateFlow<DriverSupportSignals>

    fun applyScenario(preset: ScenarioPreset)

    fun updateManual(vehicleState: VehicleState, driverSupportSignals: DriverSupportSignals)

    fun reset()
}
