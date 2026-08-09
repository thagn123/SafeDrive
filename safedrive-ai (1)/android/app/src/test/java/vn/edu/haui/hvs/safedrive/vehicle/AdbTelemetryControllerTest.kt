package vn.edu.haui.hvs.safedrive.vehicle

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import vn.edu.haui.hvs.safedrive.core.testing.FakeClock
import vn.edu.haui.hvs.safedrive.data.mock.MockFixtures

class AdbTelemetryControllerTest {

    private val clock = FakeClock(42_000L)
    private val vehicleDataSource = MockVehicleDataSource(clock, MockFixtures(clock))

    @Test
    fun `disabled controller rejects command without changing vehicle state`() {
        val before = vehicleDataSource.vehicleState.value
        val controller = controller(initiallyEnabled = false)

        val accepted = controller.submit(AdbTelemetryCommand(speedKmh = 120f, crashDetected = true))

        assertThat(accepted).isFalse()
        assertThat(vehicleDataSource.vehicleState.value).isEqualTo(before)
    }

    @Test
    fun `policy gate rejects command even when operator switch is enabled`() {
        val before = vehicleDataSource.vehicleState.value
        val controller = controller(initiallyEnabled = true, commandsAllowed = false)

        val accepted = controller.submit(AdbTelemetryCommand(speedKmh = 120f))

        assertThat(accepted).isFalse()
        assertThat(vehicleDataSource.vehicleState.value).isEqualTo(before)
    }

    @Test
    fun `accepted command updates speed crash heart rate and DTC with bounded values`() {
        val controller = controller(initiallyEnabled = true)

        val accepted = controller.submit(
            AdbTelemetryCommand(
                speedKmh = 999f,
                crashDetected = true,
                heartRateBpm = 999,
                dtcCode = "CRITICAL_SENSOR_FAULT",
            ),
        )

        assertThat(accepted).isTrue()
        assertThat(vehicleDataSource.vehicleState.value.speedKmh).isEqualTo(300f)
        assertThat(vehicleDataSource.vehicleState.value.crashDetected).isTrue()
        assertThat(vehicleDataSource.vehicleState.value.activeDtcs.map { it.code })
            .contains("CRITICAL_SENSOR_FAULT")
        assertThat(vehicleDataSource.driverSupportSignals.value.wearableHeartRateBpm).isEqualTo(250)
        assertThat(vehicleDataSource.driverSupportSignals.value.wearableLastUpdateMs).isEqualTo(clock.nowMs())
    }

    @Test
    fun `clear command removes DTC and wearable reading`() {
        val controller = controller(initiallyEnabled = true)
        controller.submit(AdbTelemetryCommand(heartRateBpm = 150, dtcCode = "BRAKE_FAILURE"))

        controller.submit(
            AdbTelemetryCommand(
                clearHeartRate = true,
                dtcCode = "BRAKE_FAILURE",
                clearDtc = true,
            ),
        )

        assertThat(vehicleDataSource.vehicleState.value.activeDtcs.map { it.code })
            .doesNotContain("BRAKE_FAILURE")
        assertThat(vehicleDataSource.driverSupportSignals.value.wearableHeartRateBpm).isNull()
        assertThat(vehicleDataSource.driverSupportSignals.value.wearableLastUpdateMs).isNull()
    }

    private fun controller(
        initiallyEnabled: Boolean,
        commandsAllowed: Boolean = true,
    ) = AdbTelemetryController(
        vehicleDataSource = vehicleDataSource,
        clock = clock,
        initiallyEnabled = initiallyEnabled,
        commandsAllowed = { commandsAllowed },
    )
}
