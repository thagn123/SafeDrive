package vn.edu.haui.hvs.safedrive.vehicle

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import vn.edu.haui.hvs.safedrive.core.common.AppClock
import vn.edu.haui.hvs.safedrive.core.model.DriverSupportSignals
import vn.edu.haui.hvs.safedrive.core.model.ScenarioPreset
import vn.edu.haui.hvs.safedrive.core.model.VehicleState
import vn.edu.haui.hvs.safedrive.data.mock.MockFixtures
import vn.edu.haui.hvs.safedrive.domain.repository.VehicleDataSource

/**
 * Demo Mode vehicle telemetry adapter. A VHAL-backed [VehicleDataSource] is a future phase and must
 * satisfy this same interface without [vn.edu.haui.hvs.safedrive.feature] code knowing which one is active.
 */
class MockVehicleDataSource(
    private val clock: AppClock,
    private val fixtures: MockFixtures,
) : VehicleDataSource {

    private val _vehicleState = MutableStateFlow(fixtures.defaultVehicleState())
    override val vehicleState: StateFlow<VehicleState> = _vehicleState.asStateFlow()

    private val _driverSupportSignals = MutableStateFlow(fixtures.defaultDriverSupportSignals())
    override val driverSupportSignals: StateFlow<DriverSupportSignals> = _driverSupportSignals.asStateFlow()

    override fun applyScenario(preset: ScenarioPreset) {
        _vehicleState.value = preset.vehicleState.copy(updatedAtMs = clock.nowMs())
        _driverSupportSignals.value = preset.driverSupportSignals
    }

    override fun updateManual(vehicleState: VehicleState, driverSupportSignals: DriverSupportSignals) {
        _vehicleState.value = vehicleState.copy(updatedAtMs = clock.nowMs())
        _driverSupportSignals.value = driverSupportSignals
    }

    override fun reset() {
        _vehicleState.value = fixtures.defaultVehicleState()
        _driverSupportSignals.value = fixtures.defaultDriverSupportSignals()
    }
}
