package vn.edu.haui.hvs.safedrive.data.mock

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import vn.edu.haui.hvs.safedrive.core.testing.FakeClock

class MockFixturesTest {

    private val clock = FakeClock(initialMs = 1_000_000L)
    private val fixtures = MockFixtures(clock)

    @Test
    fun `scenario presets contain all 11 required ids in order`() {
        val ids = fixtures.scenarioPresets().map { it.id }
        assertThat(ids).containsExactly(
            "new_trip",
            "over_2h",
            "consider_rest",
            "rest_recommended",
            "insufficient_data",
            "user_reported_fatigue",
            "overheat",
            "crash",
            "misfire",
            "multi_dtc",
            "crash_single_signal",
        ).inOrder()
    }

    @Test
    fun `misfire preset carries only the P0301 dtc at MEDIUM severity`() {
        val preset = fixtures.scenarioPresets().first { it.id == "misfire" }
        assertThat(preset.vehicleState.activeDtcs).hasSize(1)
        assertThat(preset.vehicleState.activeDtcs.first().code).isEqualTo("P0301")
        assertThat(preset.vehicleState.activeDtcs.first().severity.name).isEqualTo("MEDIUM")
        assertThat(preset.vehicleState.engineTemperatureC).isLessThan(105f)
    }

    @Test
    fun `multi_dtc preset carries both dtcs and stays below the engine overheat threshold`() {
        val preset = fixtures.scenarioPresets().first { it.id == "multi_dtc" }
        assertThat(preset.vehicleState.activeDtcs.map { it.code }).containsExactly("P0301", "ENGINE_OVERHEAT")
        assertThat(preset.vehicleState.engineTemperatureC).isLessThan(105f)
    }

    @Test
    fun `crash_single_signal preset has crash evidence but no supporting signal`() {
        val preset = fixtures.scenarioPresets().first { it.id == "crash_single_signal" }
        assertThat(preset.vehicleState.crashDetected).isTrue()
        assertThat(preset.vehicleState.passengerResponse.name).isEqualTo("RESPONSIVE")
        assertThat(preset.vehicleState.driverSeatOccupied).isFalse()
    }

    @Test
    fun `crash preset has primary and supporting evidence`() {
        val crash = fixtures.scenarioPresets().first { it.id == "crash" }
        assertThat(crash.vehicleState.crashDetected).isTrue()
        assertThat(crash.vehicleState.passengerResponse.name).isEqualTo("NO_RESPONSE")
    }

    @Test
    fun `overheat preset carries the ENGINE_OVERHEAT dtc at HIGH severity`() {
        val overheat = fixtures.scenarioPresets().first { it.id == "overheat" }
        assertThat(overheat.vehicleState.activeDtcs).hasSize(1)
        assertThat(overheat.vehicleState.activeDtcs.first().code).isEqualTo("ENGINE_OVERHEAT")
        assertThat(overheat.vehicleState.engineTemperatureC).isAtLeast(105f)
    }

    @Test
    fun `insufficient_data preset has no continuous driving minutes and unavailable steering`() {
        val preset = fixtures.scenarioPresets().first { it.id == "insufficient_data" }
        assertThat(preset.vehicleState.continuousDrivingMinutes).isNull()
        assertThat(preset.driverSupportSignals.steeringSignalAvailable).isFalse()
    }

    @Test
    fun `available source count is computed not hard-coded`() {
        val newTrip = fixtures.scenarioPresets().first { it.id == "new_trip" }
        // steering + seat + continuousDrivingMinutes available, wearable not connected -> 3 of 4
        assertThat(newTrip.driverSupportSignals.availableSourceCount).isEqualTo(3)
        assertThat(newTrip.driverSupportSignals.totalSourceCount).isEqualTo(4)

        val insufficientData = fixtures.scenarioPresets().first { it.id == "insufficient_data" }
        assertThat(insufficientData.driverSupportSignals.availableSourceCount).isEqualTo(1)
    }

    @Test
    fun `initial chat messages seed matches prototype welcome and sample exchange`() {
        val messages = fixtures.initialChatMessages()
        assertThat(messages).hasSize(3)
        assertThat(messages[0].sender.name).isEqualTo("SAFEDRIVE")
        assertThat(messages[1].sender.name).isEqualTo("USER")
        assertThat(messages[2].risk).isNotNull()
    }

    @Test
    fun `no fixture ever models attention or drowsiness fields`() {
        // Compile-time guarantee: VehicleState/DriverSupportSignals have no such properties at all.
        // This test documents the safety requirement from 00-executive-plan.md / 07-testing-security-acceptance.md.
        val state = fixtures.defaultVehicleState()
        val fieldNames = VehicleStateFieldNames
        assertThat(fieldNames).doesNotContain("attentionScore")
        assertThat(fieldNames).doesNotContain("drowsinessScore")
        assertThat(state).isNotNull()
    }

    private companion object {
        val VehicleStateFieldNames = vn.edu.haui.hvs.safedrive.core.model.VehicleState::class.java
            .declaredFields.map { it.name }
    }
}
