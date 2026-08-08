package vn.edu.haui.hvs.safedrive.vehicle

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Test
import vn.edu.haui.hvs.safedrive.core.model.BackendMode
import vn.edu.haui.hvs.safedrive.domain.repository.AppPreferences
import vn.edu.haui.hvs.safedrive.domain.repository.VehicleActionCommand
import vn.edu.haui.hvs.safedrive.domain.repository.VehicleActionExecution
import vn.edu.haui.hvs.safedrive.domain.repository.VehicleActionExecutor
import vn.edu.haui.hvs.safedrive.domain.repository.VehicleActionMode

class ModeAwareVehicleActionExecutorTest {
    @Test
    fun `demo mode is explicitly simulated and never touches the vehicle`() = runTest {
        var vehicleCalls = 0
        val executor = ModeAwareVehicleActionExecutor(
            preferences = MutableStateFlow(AppPreferences(backendMode = BackendMode.DEMO)),
            realExecutor = VehicleActionExecutor {
                vehicleCalls += 1
                successfulVehicleResult()
            },
        )

        val result = executor.execute(VehicleActionCommand.LockDoors)

        assertThat(vehicleCalls).isEqualTo(0)
        assertThat(result.applied).isTrue()
        assertThat(result.mode).isEqualTo(VehicleActionMode.SIMULATION)
        assertThat(result.readBackVerified).isFalse()
    }

    @Test
    fun `remote mode delegates to the real vehicle executor`() = runTest {
        var received: VehicleActionCommand? = null
        val expected = successfulVehicleResult()
        val executor = ModeAwareVehicleActionExecutor(
            preferences = MutableStateFlow(AppPreferences(backendMode = BackendMode.REMOTE)),
            realExecutor = VehicleActionExecutor {
                received = it
                expected
            },
        )

        val command = VehicleActionCommand.SetHvacTemperature(24f)
        val result = executor.execute(command)

        assertThat(received).isEqualTo(command)
        assertThat(result).isEqualTo(expected)
        assertThat(result.mode).isEqualTo(VehicleActionMode.VEHICLE)
        assertThat(result.readBackVerified).isTrue()
    }

    private fun successfulVehicleResult() = VehicleActionExecution(
        applied = true,
        readBackVerified = true,
        mode = VehicleActionMode.VEHICLE,
        message = "verified",
    )
}
