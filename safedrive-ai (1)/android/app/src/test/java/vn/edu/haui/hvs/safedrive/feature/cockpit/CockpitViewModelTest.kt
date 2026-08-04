package vn.edu.haui.hvs.safedrive.feature.cockpit

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
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
import vn.edu.haui.hvs.safedrive.core.testing.FakePreferencesRepository
import vn.edu.haui.hvs.safedrive.core.testing.FakeVoiceController
import vn.edu.haui.hvs.safedrive.core.testing.MainDispatcherRule
import vn.edu.haui.hvs.safedrive.core.testing.driverSupportSignalsFixture
import vn.edu.haui.hvs.safedrive.core.testing.vehicleStateFixture

/**
 * Covers docs/android-mvp-plan/12 W6.3: Cockpit must know whether Developer Mode is on so it can
 * show the Simulator shortcut chip — this is the ViewModel-level half of that wiring.
 */
class CockpitViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private fun snapshot() = CockpitSnapshot(
        vehicleState = vehicleStateFixture(),
        driverSupportSignals = driverSupportSignalsFixture(),
        riskAssessment = RiskAssessment(Severity.LOW, "Bình thường", "OK", emptyList()),
        restRecommendation = RestRecommendation(RestRecommendationLevel.NORMAL, "OK", "OK", vn.edu.haui.hvs.safedrive.core.model.ConfidenceLevel.HIGH, emptyList(), 0L),
        connectionStatus = SystemConnectionStatus.NORMAL,
        stateVersion = 1L,
        updatedAtMs = 0L,
    )

    private fun TestScope.keepAlive(viewModel: CockpitViewModel) =
        launch(Dispatchers.Unconfined) { viewModel.uiState.collect {} }

    @Test
    fun `developerMode false by default and reflects preference changes`() = runTest(mainDispatcherRule.dispatcher) {
        val cockpitSnapshot = MutableStateFlow(snapshot())
        val preferences = FakePreferencesRepository()
        val viewModel = CockpitViewModel(cockpitSnapshot, FakeVoiceController(), preferences)
        val job = keepAlive(viewModel)
        advanceUntilIdle()

        val initial = viewModel.uiState.value as CockpitUiState.Content
        assertThat(initial.developerMode).isFalse()

        preferences.setDeveloperMode(true)
        advanceUntilIdle()

        val updated = viewModel.uiState.value as CockpitUiState.Content
        assertThat(updated.developerMode).isTrue()
        job.cancel()
    }
}
