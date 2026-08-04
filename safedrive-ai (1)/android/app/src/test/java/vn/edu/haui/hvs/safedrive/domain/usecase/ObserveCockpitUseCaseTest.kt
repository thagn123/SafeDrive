package vn.edu.haui.hvs.safedrive.domain.usecase

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test
import vn.edu.haui.hvs.safedrive.core.common.UuidIdGenerator
import vn.edu.haui.hvs.safedrive.core.model.CockpitSnapshot
import vn.edu.haui.hvs.safedrive.core.model.Severity
import vn.edu.haui.hvs.safedrive.core.model.SystemConnectionStatus
import vn.edu.haui.hvs.safedrive.core.testing.FakeClock
import vn.edu.haui.hvs.safedrive.data.mock.MockFixtures
import vn.edu.haui.hvs.safedrive.data.mock.MockPolicyEvaluator
import vn.edu.haui.hvs.safedrive.data.mock.MockSafeDriveGateway
import vn.edu.haui.hvs.safedrive.domain.repository.AppPreferences
import vn.edu.haui.hvs.safedrive.domain.repository.GatewayProvider
import vn.edu.haui.hvs.safedrive.vehicle.MockVehicleDataSource

@OptIn(ExperimentalCoroutinesApi::class)
class ObserveCockpitUseCaseTest {

    private val clock = FakeClock(initialMs = 10_000L)
    private val idGenerator = UuidIdGenerator()
    private val fixtures = MockFixtures(clock)
    private val vehicleDataSource = MockVehicleDataSource(clock, fixtures)
    private val gateway = MockSafeDriveGateway(clock, idGenerator, fixtures, MockPolicyEvaluator(clock))
    private val gatewayProvider = GatewayProvider { gateway }
    private val appPreferences = MutableStateFlow(AppPreferences())
    private val sessionCoordinator = SessionCoordinator(gatewayProvider, appPreferences, idGenerator, clock, "test-1.0")
    private val useCase = ObserveCockpitUseCase(vehicleDataSource, sessionCoordinator, appPreferences, idGenerator, clock)

    @Test
    fun `first emission reflects the default vehicle state enriched with risk and rest`() = runTest(UnconfinedTestDispatcher()) {
        val collected = mutableListOf<CockpitSnapshot>()
        val job = useCase().onEach { collected.add(it) }.launchIn(this)

        assertThat(collected).isNotEmpty()
        val first = collected.first()
        assertThat(first.vehicleState.speedKmh).isEqualTo(fixtures.defaultVehicleState().speedKmh)
        assertThat(first.connectionStatus).isEqualTo(SystemConnectionStatus.NORMAL)

        job.cancel()
    }

    @Test
    fun `applying the crash scenario is reflected as a CRITICAL snapshot`() = runTest(UnconfinedTestDispatcher()) {
        val collected = mutableListOf<CockpitSnapshot>()
        val job = useCase().onEach { collected.add(it) }.launchIn(this)

        val crash = fixtures.scenarioPresets().first { it.id == "crash" }
        vehicleDataSource.applyScenario(crash)

        val last = collected.last()
        assertThat(last.vehicleState.crashDetected).isTrue()
        assertThat(last.riskAssessment.level).isEqualTo(Severity.CRITICAL)

        job.cancel()
    }

    @Test
    fun `stateVersion strictly increases across successive scenario applications`() = runTest(UnconfinedTestDispatcher()) {
        val collected = mutableListOf<CockpitSnapshot>()
        val job = useCase().onEach { collected.add(it) }.launchIn(this)

        vehicleDataSource.applyScenario(fixtures.scenarioPresets().first { it.id == "over_2h" })
        vehicleDataSource.applyScenario(fixtures.scenarioPresets().first { it.id == "overheat" })

        val versions = collected.map { it.stateVersion }
        assertThat(versions).isInOrder()
        assertThat(versions.toSet().size).isEqualTo(versions.size)

        job.cancel()
    }

    @Test
    fun `switching backend mode alone, with no vehicle-state change, still issues a fresh request (W5_8)`() =
        runTest(UnconfinedTestDispatcher()) {
            val collected = mutableListOf<CockpitSnapshot>()
            val job = useCase().onEach { collected.add(it) }.launchIn(this)
            val versionsBefore = collected.map { it.stateVersion }.toSet()

            // No scenario/vehicle-state change here — only a preference change.
            appPreferences.value = appPreferences.value.copy(
                backendMode = vn.edu.haui.hvs.safedrive.core.model.BackendMode.REMOTE,
                baseUrl = "https://staging.example.com/",
            )

            assertThat(collected.map { it.stateVersion }.toSet()).isNotEqualTo(versionsBefore)
            job.cancel()
        }
}

/** SAM constructor helper — [GatewayProvider] is a fun interface-free interface with a single method. */
private fun GatewayProvider(provider: () -> vn.edu.haui.hvs.safedrive.domain.repository.SafeDriveGateway) =
    object : GatewayProvider {
        override fun current() = provider()
    }
