package vn.edu.haui.hvs.safedrive.feature.diagnostics

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import vn.edu.haui.hvs.safedrive.core.model.CockpitSnapshot
import vn.edu.haui.hvs.safedrive.core.model.RestRecommendation
import vn.edu.haui.hvs.safedrive.core.model.RestRecommendationLevel
import vn.edu.haui.hvs.safedrive.core.model.RiskAssessment
import vn.edu.haui.hvs.safedrive.core.model.Severity
import vn.edu.haui.hvs.safedrive.core.model.SystemConnectionStatus
import vn.edu.haui.hvs.safedrive.core.testing.FakeClock
import vn.edu.haui.hvs.safedrive.core.testing.FakePreferencesRepository
import vn.edu.haui.hvs.safedrive.core.testing.MainDispatcherRule
import vn.edu.haui.hvs.safedrive.data.mock.MockFixtures
import vn.edu.haui.hvs.safedrive.domain.usecase.PendingPromptCoordinator

class DiagnosticsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val clock = FakeClock(1_000L)
    private val fixtures = MockFixtures(clock)

    private fun snapshotWithDtcs(hasDtcs: Boolean): CockpitSnapshot {
        val vehicleState = if (hasDtcs) {
            fixtures.defaultVehicleState().copy(activeDtcs = listOf(fixtures.dtcP0301()))
        } else {
            fixtures.defaultVehicleState()
        }
        return CockpitSnapshot(
            vehicleState = vehicleState,
            driverSupportSignals = fixtures.defaultDriverSupportSignals(),
            riskAssessment = RiskAssessment(Severity.LOW, "t", "m", emptyList()),
            restRecommendation = RestRecommendation(RestRecommendationLevel.NORMAL, "t", "m", vn.edu.haui.hvs.safedrive.core.model.ConfidenceLevel.MEDIUM, emptyList(), 0L),
            connectionStatus = SystemConnectionStatus.NORMAL,
            stateVersion = 1L,
            updatedAtMs = 0L,
        )
    }

    @Test
    fun `state is Loading while no cockpit snapshot has arrived yet`() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = DiagnosticsViewModel(
            MutableStateFlow(null),
            FakePreferencesRepository(),
            PendingPromptCoordinator(),
        )
        assertThat(viewModel.uiState.value).isInstanceOf(DiagnosticsUiState.Loading::class.java)
    }

    @Test
    fun `empty dtc list renders Content with an empty dtcs list`() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = DiagnosticsViewModel(
            MutableStateFlow(snapshotWithDtcs(hasDtcs = false)),
            FakePreferencesRepository(),
            PendingPromptCoordinator(),
        )
        val content = viewModel.uiState.first { it is DiagnosticsUiState.Content } as DiagnosticsUiState.Content
        assertThat(content.dtcs).isEmpty()
    }

    @Test
    fun `active P0301 dtc is surfaced verbatim from the vehicle state`() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = DiagnosticsViewModel(
            MutableStateFlow(snapshotWithDtcs(hasDtcs = true)),
            FakePreferencesRepository(),
            PendingPromptCoordinator(),
        )
        val content = viewModel.uiState.first { it is DiagnosticsUiState.Content } as DiagnosticsUiState.Content
        assertThat(content.dtcs).hasSize(1)
        assertThat(content.dtcs.first().code).isEqualTo("P0301")
    }

    @Test
    fun `askAssistant prefills the pending prompt coordinator and emits a navigation effect`() =
        runTest(mainDispatcherRule.dispatcher) {
            val pendingPromptCoordinator = PendingPromptCoordinator()
            val viewModel = DiagnosticsViewModel(
                MutableStateFlow(snapshotWithDtcs(hasDtcs = true)),
                FakePreferencesRepository(),
                pendingPromptCoordinator,
            )
            val effects = mutableListOf<DiagnosticsUiEffect>()
            val job = launch(Dispatchers.Unconfined) {
                viewModel.effects.collect { effects.add(it) }
            }
            viewModel.askAssistant(fixtures.dtcP0301())
            advanceUntilIdle()

            assertThat(pendingPromptCoordinator.pendingPrompt.value).contains("P0301")
            assertThat(effects).containsExactly(DiagnosticsUiEffect.NavigateToAssistant)
            job.cancel()
        }
}
