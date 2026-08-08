package vn.edu.haui.hvs.safedrive.feature.assistant

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import vn.edu.haui.hvs.safedrive.core.common.UuidIdGenerator
import vn.edu.haui.hvs.safedrive.core.designsystem.SafeDriveTheme
import vn.edu.haui.hvs.safedrive.core.model.CockpitSnapshot
import vn.edu.haui.hvs.safedrive.core.observability.AssistantTurnMetricsRecorder
import vn.edu.haui.hvs.safedrive.core.testing.FakeClock
import vn.edu.haui.hvs.safedrive.core.testing.FakePreferencesRepository
import vn.edu.haui.hvs.safedrive.core.testing.FakeTtsController
import vn.edu.haui.hvs.safedrive.data.local.InMemoryConversationRepository
import vn.edu.haui.hvs.safedrive.data.mock.MockFixtures
import vn.edu.haui.hvs.safedrive.data.mock.MockPolicyEvaluator
import vn.edu.haui.hvs.safedrive.data.mock.MockSafeDriveGateway
import vn.edu.haui.hvs.safedrive.domain.repository.AppPreferences
import vn.edu.haui.hvs.safedrive.domain.repository.GatewayProvider
import vn.edu.haui.hvs.safedrive.domain.repository.SafeDriveGateway
import vn.edu.haui.hvs.safedrive.domain.usecase.AssistantQueryUseCase
import vn.edu.haui.hvs.safedrive.domain.usecase.AssistantTurnCoordinator
import vn.edu.haui.hvs.safedrive.domain.usecase.ConfirmActionUseCase
import vn.edu.haui.hvs.safedrive.domain.usecase.PendingPromptCoordinator
import vn.edu.haui.hvs.safedrive.domain.usecase.SessionCoordinator

/** Compose UI test per docs/android-mvp-plan/07-testing-security-acceptance.md ("Assistant keyboard, retry, ..."). */
class AssistantScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun buildViewModel(): AssistantViewModel {
        val clock = FakeClock(1_000L)
        val idGenerator = UuidIdGenerator()
        val fixtures = MockFixtures(clock)
        val gateway: SafeDriveGateway = MockSafeDriveGateway(clock, idGenerator, fixtures, MockPolicyEvaluator(clock))
        val gatewayProvider = object : GatewayProvider {
            override fun current() = gateway
        }
        val appPreferences = MutableStateFlow(AppPreferences())
        val sessionCoordinator = SessionCoordinator(gatewayProvider, appPreferences, idGenerator, clock, "test")
        val cockpitSnapshot = MutableStateFlow<CockpitSnapshot?>(null)
        val conversationRepository = InMemoryConversationRepository(emptyList())
        val assistantTurnCoordinator = AssistantTurnCoordinator(
            conversationRepository = conversationRepository,
            assistantQueryUseCase = AssistantQueryUseCase(sessionCoordinator, clock),
            cockpitSnapshot = cockpitSnapshot,
            appPreferences = appPreferences,
            ttsController = FakeTtsController(),
            metricsRecorder = AssistantTurnMetricsRecorder(),
            lastHealthStatus = MutableStateFlow(null),
            idGenerator = idGenerator,
            clock = clock,
            externalScope = MainScope(),
        )
        return AssistantViewModel(
            conversationRepository = conversationRepository,
            assistantTurnCoordinator = assistantTurnCoordinator,
            confirmActionUseCase = ConfirmActionUseCase(sessionCoordinator, idGenerator),
            preferencesRepository = FakePreferencesRepository(),
            cockpitSnapshot = cockpitSnapshot,
            pendingPromptCoordinator = PendingPromptCoordinator(),
        )
    }

    @Test
    fun typingAndSendingAQuestion_showsUserBubbleThenAssistantReply() {
        composeRule.setContent {
            SafeDriveTheme { AssistantScreen(viewModel = buildViewModel(), ttsController = FakeTtsController(), onOpenDiagnostics = {}, onTriggerVoice = {}, onOpenSimulator = {}) }
        }

        composeRule.onNode(hasSetTextAction())
            .performTextInput("Kiểm tra tốc độ hiện tại")
        composeRule.onNodeWithContentDescription("Gửi câu hỏi").performClick()

        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("km/h", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        assert(
            composeRule.onAllNodesWithText("Kiểm tra tốc độ hiện tại")
                .fetchSemanticsNodes()
                .isNotEmpty(),
        )
    }

    @Test
    fun quickPromptChip_sendsWithoutManualTyping() {
        composeRule.setContent {
            SafeDriveTheme { AssistantScreen(viewModel = buildViewModel(), ttsController = FakeTtsController(), onOpenDiagnostics = {}, onTriggerVoice = {}, onOpenSimulator = {}) }
        }
        composeRule.onNodeWithText("Kiểm tra nhiệt độ động cơ").performClick()
        // The quick-prompt text itself becomes the new user chat bubble content, so it stays visible.
        composeRule.onNodeWithText("Kiểm tra nhiệt độ động cơ").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun fifthQuickPrompt_restStopSuggestion_isPresent() {
        composeRule.setContent {
            SafeDriveTheme { AssistantScreen(viewModel = buildViewModel(), ttsController = FakeTtsController(), onOpenDiagnostics = {}, onTriggerVoice = {}, onOpenSimulator = {}) }
        }
        // Parity with the AI Studio prototype's 5th quick-suggestion chip (remediation item 8) —
        // previously only 4 of the prototype's 5 chips existed here.
        composeRule.onNodeWithText("Gợi ý điểm dừng nghỉ gần đây").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun ttsUnavailableWhileEnabled_showsVisibleBannerAndSettingsCta() {
        val tts = FakeTtsController(vn.edu.haui.hvs.safedrive.domain.repository.TtsState.MISSING_DATA)
        composeRule.setContent {
            SafeDriveTheme { AssistantScreen(viewModel = buildViewModel(), ttsController = tts, onOpenDiagnostics = {}, onTriggerVoice = {}, onOpenSimulator = {}) }
        }
        // A tinted icon alone is easy to miss and gives no way to fix it — a visible banner with an
        // actionable CTA is required instead (remediation item 7).
        composeRule.onNodeWithText("Thiếu gói dữ liệu giọng đọc tiếng Việt trên máy này.").assertIsDisplayed()
        composeRule.onNodeWithText("Cài đặt giọng đọc").assertIsDisplayed()
    }
}
