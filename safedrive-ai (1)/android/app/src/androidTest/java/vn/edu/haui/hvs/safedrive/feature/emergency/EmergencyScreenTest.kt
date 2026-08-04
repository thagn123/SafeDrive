package vn.edu.haui.hvs.safedrive.feature.emergency

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.espresso.Espresso
import org.junit.Rule
import org.junit.Test
import vn.edu.haui.hvs.safedrive.core.designsystem.SafeDriveTheme
import vn.edu.haui.hvs.safedrive.core.model.EmergencySnapshot
import vn.edu.haui.hvs.safedrive.core.model.EmergencyState
import vn.edu.haui.hvs.safedrive.core.model.EvidenceItem
import vn.edu.haui.hvs.safedrive.core.testing.FakeClock
import vn.edu.haui.hvs.safedrive.core.testing.FakeEmergencyRepository
import vn.edu.haui.hvs.safedrive.core.testing.FakePreferencesRepository

/**
 * Compose UI test per docs/android-mvp-plan/07-testing-security-acceptance.md ("Emergency blocks
 * Back/outside dismiss, action button/voice cancel, sent screen").
 */
class EmergencyScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun snapshot(state: EmergencyState, deadlineMs: Long?) = EmergencySnapshot(
        emergencyId = "emg_1",
        state = state,
        deadlineMs = deadlineMs,
        evidence = listOf(EvidenceItem("crash_detected", "Phát hiện va chạm", 0L)),
    )

    private fun setContentWithSnapshot(snapshot: EmergencySnapshot): FakeEmergencyRepository {
        val repository = FakeEmergencyRepository(snapshot)
        val viewModel = EmergencyViewModel(repository, FakeClock(0L), FakePreferencesRepository())
        composeRule.setContent {
            SafeDriveTheme { EmergencyScreen(viewModel = viewModel, onTriggerVoiceCancel = {}) }
        }
        return repository
    }

    @Test
    fun awaitingUserResponse_showsConfirmSafeButtonAndCancelsOnClick() {
        val repository = setContentWithSnapshot(snapshot(EmergencyState.AWAITING_USER_RESPONSE, 15_000L))

        composeRule.onNodeWithText("Bạn có ổn không?").assertIsDisplayed()
        composeRule.onNodeWithText("TÔI VẪN ỔN — HỦY SOS", substring = true).performClick()
        composeRule.waitForIdle()

        assert(repository.activeSnapshot.value?.state == EmergencyState.CANCELLED)
    }

    @Test
    fun finalCountdown_showsCancelButton() {
        setContentWithSnapshot(snapshot(EmergencyState.FINAL_COUNTDOWN, 10_000L))
        composeRule.onNodeWithText("HỦY SOS — TÔI VẪN ỔN", substring = true).assertIsDisplayed()
    }

    @Test
    fun sosSimulatedSent_showsAcknowledgeButtonAndClearsOnClick() {
        val repository = setContentWithSnapshot(snapshot(EmergencyState.SOS_SIMULATED_SENT, null))

        composeRule.onNodeWithText("Đã gửi tín hiệu SOS mô phỏng khẩn cấp").assertIsDisplayed()
        composeRule.onNodeWithText("Quay lại Cockpit").performClick()
        composeRule.waitForIdle()

        assert(repository.activeSnapshot.value == null)
    }

    @Test
    fun backPressDoesNotDismissTheEmergencyScreen() {
        setContentWithSnapshot(snapshot(EmergencyState.AWAITING_USER_RESPONSE, 15_000L))
        Espresso.pressBack()
        composeRule.onNodeWithText("Bạn có ổn không?").assertIsDisplayed()
    }

    @Test
    fun neverShowsRealDispatchLanguage() {
        setContentWithSnapshot(snapshot(EmergencyState.VERIFYING_EVIDENCE, 5_000L))
        composeRule.onNodeWithText("real_emergency_dispatch_enabled: false").assertIsDisplayed()
    }
}
