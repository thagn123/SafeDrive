package vn.edu.haui.hvs.safedrive.feature.simulator

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import vn.edu.haui.hvs.safedrive.core.common.UuidIdGenerator
import vn.edu.haui.hvs.safedrive.core.model.PassengerResponse
import vn.edu.haui.hvs.safedrive.core.testing.FakeClock
import vn.edu.haui.hvs.safedrive.core.testing.FakePreferencesRepository
import vn.edu.haui.hvs.safedrive.core.testing.MainDispatcherRule
import vn.edu.haui.hvs.safedrive.data.mock.MockFixtures
import vn.edu.haui.hvs.safedrive.data.mock.MockPolicyEvaluator
import vn.edu.haui.hvs.safedrive.data.mock.MockSafeDriveGateway
import vn.edu.haui.hvs.safedrive.domain.repository.AppPreferences
import vn.edu.haui.hvs.safedrive.domain.repository.GatewayProvider
import vn.edu.haui.hvs.safedrive.domain.repository.SafeDriveGateway
import vn.edu.haui.hvs.safedrive.domain.usecase.SessionCoordinator
import vn.edu.haui.hvs.safedrive.vehicle.MockVehicleDataSource

/**
 * Scenario regression per docs/android-mvp-plan/08-claude-prompts.md, Prompt 4: every preset must
 * be deterministic and flow through [vn.edu.haui.hvs.safedrive.domain.repository.VehicleDataSource]
 * so Cockpit/Diagnostics/Assistant/Emergency all observe the same state.
 */
class SimulatorViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val clock = FakeClock(1_000L)
    private val idGenerator = UuidIdGenerator()
    private val fixtures = MockFixtures(clock)
    private val vehicleDataSource = MockVehicleDataSource(clock, fixtures)
    private val gateway: SafeDriveGateway = MockSafeDriveGateway(clock, idGenerator, fixtures, MockPolicyEvaluator(clock))
    private val gatewayProvider = object : GatewayProvider {
        override fun current() = gateway
    }
    private val sessionCoordinator = SessionCoordinator(gatewayProvider, MutableStateFlow(AppPreferences()), idGenerator, clock, "test")

    private fun buildViewModel() = SimulatorViewModel(
        vehicleDataSource = vehicleDataSource,
        fixtures = fixtures,
        preferencesRepository = FakePreferencesRepository(),
        sessionCoordinator = sessionCoordinator,
        idGenerator = idGenerator,
        clock = clock,
        forceDisableRealtimePollingForTest = true,
    )

    @Test
    fun `all 8 presets are deterministic and update the shared VehicleDataSource`() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = buildViewModel()
        fixtures.scenarioPresets().forEach { preset ->
            viewModel.selectPreset(preset)
            advanceUntilIdle()
            assertThat(vehicleDataSource.vehicleState.value.speedKmh).isEqualTo(preset.vehicleState.speedKmh)
            assertThat(vehicleDataSource.vehicleState.value.crashDetected).isEqualTo(preset.vehicleState.crashDetected)
            assertThat(vehicleDataSource.driverSupportSignals.value).isEqualTo(preset.driverSupportSignals)
        }
    }

    @Test
    fun `selecting the crash preset is reflected as NO_RESPONSE passenger state`() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = buildViewModel()
        val crash = fixtures.scenarioPresets().first { it.id == "crash" }
        viewModel.selectPreset(crash)
        assertThat(vehicleDataSource.vehicleState.value.passengerResponse).isEqualTo(PassengerResponse.NO_RESPONSE)
    }

    @Test
    fun `applyManual builds a vehicle state matching the manual form and includes selected DTC`() =
        runTest(mainDispatcherRule.dispatcher) {
            val viewModel = buildViewModel()
            viewModel.updateManual {
                it.copy(speedKmh = 100f, engineTemperatureC = 112f, dtcSelection = DtcSelection.OVERHEAT)
            }
            viewModel.applyManual()

            val state = vehicleDataSource.vehicleState.value
            assertThat(state.speedKmh).isEqualTo(100f)
            assertThat(state.engineTemperatureC).isEqualTo(112f)
            assertThat(state.activeDtcs).hasSize(1)
            assertThat(state.activeDtcs.first().code).isEqualTo("ENGINE_OVERHEAT")
        }

    @Test
    fun `applyManual respects the manually set energy percent instead of the fixture default`() =
        runTest(mainDispatcherRule.dispatcher) {
            val viewModel = buildViewModel()
            viewModel.updateManual { it.copy(energyPercent = 15f) }
            viewModel.applyManual()
            assertThat(vehicleDataSource.vehicleState.value.energyPercent).isEqualTo(15)
        }

    @Test
    fun `applyManual with no driving minutes data sets continuousDrivingMinutes to null`() =
        runTest(mainDispatcherRule.dispatcher) {
            val viewModel = buildViewModel()
            viewModel.updateManual { it.copy(hasDrivingMinutes = false) }
            viewModel.applyManual()
            assertThat(vehicleDataSource.vehicleState.value.continuousDrivingMinutes).isNull()
        }

    @Test
    fun `resetToDefault restores the nominal vehicle state and clears preset selection`() =
        runTest(mainDispatcherRule.dispatcher) {
            val viewModel = buildViewModel()
            val job = launch(Dispatchers.Unconfined) { viewModel.uiState.collect {} }

            viewModel.selectPreset(fixtures.scenarioPresets().first { it.id == "crash" })
            viewModel.resetToDefault()

            assertThat(vehicleDataSource.vehicleState.value.crashDetected).isFalse()
            assertThat(viewModel.uiState.value.selectedPresetId).isNull()
            job.cancel()
        }

    @Test
    fun `json preview never contains an api key or secret-looking field`() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = buildViewModel()
        val job = launch(Dispatchers.Unconfined) { viewModel.uiState.collect {} }

        viewModel.selectPreset(fixtures.scenarioPresets().first { it.id == "overheat" })
        viewModel.showJsonPreview()

        val json = requireNotNull(viewModel.uiState.value.jsonPreview)
        assertThat(json.lowercase()).doesNotContain("api_key")
        assertThat(json.lowercase()).doesNotContain("gemini")
        assertThat(json).contains("engine_temperature_c")
        job.cancel()
    }
}
