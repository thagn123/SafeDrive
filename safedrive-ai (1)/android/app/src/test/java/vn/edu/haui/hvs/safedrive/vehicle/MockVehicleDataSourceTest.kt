package vn.edu.haui.hvs.safedrive.vehicle

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import vn.edu.haui.hvs.safedrive.core.testing.FakeClock
import vn.edu.haui.hvs.safedrive.data.mock.MockFixtures

class MockVehicleDataSourceTest {

    private val clock = FakeClock(initialMs = 5_000L)
    private val fixtures = MockFixtures(clock)
    private val dataSource = MockVehicleDataSource(clock, fixtures)

    @Test
    fun `initial state matches fixtures default`() {
        assertThat(dataSource.vehicleState.value).isEqualTo(fixtures.defaultVehicleState())
    }

    @Test
    fun `applyScenario updates both vehicle state and driver support signals`() {
        val crash = fixtures.scenarioPresets().first { it.id == "crash" }
        dataSource.applyScenario(crash)
        assertThat(dataSource.vehicleState.value.crashDetected).isTrue()
        assertThat(dataSource.driverSupportSignals.value).isEqualTo(crash.driverSupportSignals)
    }

    @Test
    fun `applyScenario stamps a fresh updatedAtMs from the clock`() {
        clock.setNowMs(99_999L)
        val overheat = fixtures.scenarioPresets().first { it.id == "overheat" }
        dataSource.applyScenario(overheat)
        assertThat(dataSource.vehicleState.value.updatedAtMs).isEqualTo(99_999L)
    }

    @Test
    fun `reset returns to the default nominal state after a scenario was applied`() {
        val crash = fixtures.scenarioPresets().first { it.id == "crash" }
        dataSource.applyScenario(crash)
        dataSource.reset()
        assertThat(dataSource.vehicleState.value.crashDetected).isFalse()
    }
}
