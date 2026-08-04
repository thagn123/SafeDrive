package vn.edu.haui.hvs.safedrive.feature.settings

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import vn.edu.haui.hvs.safedrive.core.common.UuidIdGenerator
import vn.edu.haui.hvs.safedrive.core.model.BackendMode
import vn.edu.haui.hvs.safedrive.core.model.CockpitSnapshot
import vn.edu.haui.hvs.safedrive.core.observability.AssistantTurnMetricsRecorder
import vn.edu.haui.hvs.safedrive.core.testing.FakeClock
import vn.edu.haui.hvs.safedrive.core.testing.FakePreferencesRepository
import vn.edu.haui.hvs.safedrive.core.testing.MainDispatcherRule
import vn.edu.haui.hvs.safedrive.data.mock.MockFixtures
import vn.edu.haui.hvs.safedrive.data.mock.MockPolicyEvaluator
import vn.edu.haui.hvs.safedrive.data.mock.MockSafeDriveGateway
import vn.edu.haui.hvs.safedrive.domain.repository.ConversationState
import vn.edu.haui.hvs.safedrive.domain.repository.GatewayProvider
import vn.edu.haui.hvs.safedrive.domain.repository.SafeDriveGateway

/**
 * [SettingsViewModel.uiState] is `SharingStarted.WhileSubscribed`, so — matching how
 * `collectAsStateWithLifecycle()` subscribes in production — every test here keeps a background
 * collector job alive on `uiState` before reading `.value`; otherwise the underlying `combine(...)`
 * pipeline never starts and `.value` stays frozen at its initial snapshot.
 */
class SettingsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val clock = FakeClock(1_000L)
    private val idGenerator = UuidIdGenerator()
    private val fixtures = MockFixtures(clock)
    private val gateway: SafeDriveGateway = MockSafeDriveGateway(clock, idGenerator, fixtures, MockPolicyEvaluator(clock))
    private val gatewayProvider = object : GatewayProvider {
        override fun current() = gateway
    }

    private fun buildViewModel(
        preferences: FakePreferencesRepository = FakePreferencesRepository(),
        conversationState: MutableStateFlow<ConversationState> = MutableStateFlow(ConversationState()),
    ) = SettingsViewModel(
        preferencesRepository = preferences,
        gatewayProvider = gatewayProvider,
        metricsRecorder = AssistantTurnMetricsRecorder(),
        cockpitSnapshot = MutableStateFlow<CockpitSnapshot?>(null),
        conversationState = conversationState,
        appVersionLabel = "SafeDrive AI v0.1.0-test",
    )

    private fun TestScope.keepUiStateAlive(viewModel: SettingsViewModel) =
        launch(Dispatchers.Unconfined) { viewModel.uiState.collect {} }

    @Test
    fun `llm status reflects the explicit fields on the last SAFEDRIVE reply, never inferred from model text`() =
        runTest(mainDispatcherRule.dispatcher) {
            val conversation = MutableStateFlow(ConversationState())
            val viewModel = buildViewModel(conversationState = conversation)
            val job = keepUiStateAlive(viewModel)

            assertThat(viewModel.uiState.value.lastLlmUsed).isNull()
            assertThat(llmStatusLabel(viewModel.uiState.value.lastLlmUsed, viewModel.uiState.value.lastFallback))
                .isEqualTo("Chưa rõ (chưa có phản hồi nào)")

            conversation.value = ConversationState(
                messages = listOf(
                    vn.edu.haui.hvs.safedrive.core.model.ChatMessage(
                        id = "msg_1",
                        sender = vn.edu.haui.hvs.safedrive.core.model.ChatSender.SAFEDRIVE,
                        text = "Xe dang chay binh thuong.",
                        timestampMs = 0L,
                        model = "ollama/qwen2.5:7b-instruct-q4_K_M",
                        llmUsed = true,
                        fallback = false,
                    ),
                ),
            )
            advanceUntilIdle()

            assertThat(viewModel.uiState.value.lastLlmUsed).isTrue()
            assertThat(llmStatusLabel(viewModel.uiState.value.lastLlmUsed, viewModel.uiState.value.lastFallback))
                .isEqualTo("Ollama")

            conversation.value = conversation.value.copy(
                messages = conversation.value.messages + vn.edu.haui.hvs.safedrive.core.model.ChatMessage(
                    id = "msg_2",
                    sender = vn.edu.haui.hvs.safedrive.core.model.ChatSender.SAFEDRIVE,
                    text = "Tôi ở đây cùng bạn.",
                    timestampMs = 1L,
                    model = "deterministic-context-router",
                    llmUsed = false,
                    fallback = true,
                    fallbackReason = "provider_unavailable",
                ),
            )
            advanceUntilIdle()

            assertThat(viewModel.uiState.value.lastFallback).isTrue()
            assertThat(viewModel.uiState.value.lastFallbackReason).isEqualTo("provider_unavailable")
            assertThat(llmStatusLabel(viewModel.uiState.value.lastLlmUsed, viewModel.uiState.value.lastFallback))
                .isEqualTo("Deterministic fallback")
            job.cancel()
        }

    @Test
    fun `checkHealth calls the real gateway and reports success, never a fake ping`() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = buildViewModel()
        val job = keepUiStateAlive(viewModel)
        viewModel.checkHealth()
        advanceUntilIdle()
        val status = viewModel.uiState.value.healthStatus
        assertThat(status).isInstanceOf(HealthCheckState.Success::class.java)
        assertThat((status as HealthCheckState.Success).message).contains("safedrive-mock")
        job.cancel()
    }

    @Test
    fun `checkHealth labels Demo Mode as Local Mock and reports the assistant capability (W5_9)`() =
        runTest(mainDispatcherRule.dispatcher) {
            val viewModel = buildViewModel()
            val job = keepUiStateAlive(viewModel)
            viewModel.checkHealth()
            advanceUntilIdle()
            val status = viewModel.uiState.value.healthStatus as HealthCheckState.Success
            assertThat(status.message).contains("Local Mock")
            assertThat(status.message).contains("assistant=true")
            job.cancel()
        }

    @Test
    fun `checkHealth caches the last capability set for AssistantTurnCoordinator to read (W5_10)`() =
        runTest(mainDispatcherRule.dispatcher) {
            var recorded: vn.edu.haui.hvs.safedrive.core.model.HealthStatus? = null
            val viewModel = SettingsViewModel(
                preferencesRepository = FakePreferencesRepository(),
                gatewayProvider = gatewayProvider,
                metricsRecorder = vn.edu.haui.hvs.safedrive.core.observability.AssistantTurnMetricsRecorder(),
                cockpitSnapshot = MutableStateFlow<CockpitSnapshot?>(null),
                conversationState = MutableStateFlow(ConversationState()),
                appVersionLabel = "SafeDrive AI v0.1.0-test",
                onHealthChecked = { recorded = it },
            )
            val job = keepUiStateAlive(viewModel)
            viewModel.checkHealth()
            advanceUntilIdle()
            assertThat(recorded).isNotNull()
            assertThat(recorded!!.capabilities.assistant).isTrue()
            job.cancel()
        }

    @Test
    fun `applying a valid https base url persists it`() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = buildViewModel()
        val job = keepUiStateAlive(viewModel)
        viewModel.applyBaseUrl("https://api.example.com")
        advanceUntilIdle()
        assertThat(viewModel.uiState.value.baseUrl).isEqualTo("https://api.example.com/")
        assertThat(viewModel.uiState.value.baseUrlError).isNull()
        val saveState = viewModel.uiState.value.baseUrlSaveState
        assertThat(saveState).isInstanceOf(BaseUrlSaveState.Saved::class.java)
        assertThat((saveState as BaseUrlSaveState.Saved).normalizedUrl).isEqualTo("https://api.example.com/")
        job.cancel()
    }

    @Test
    fun `applying an invalid url surfaces a validation error and does not persist`() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = buildViewModel()
        val job = keepUiStateAlive(viewModel)
        viewModel.applyBaseUrl("not-a-url")
        advanceUntilIdle()
        assertThat(viewModel.uiState.value.baseUrlError).isNotNull()
        assertThat(viewModel.uiState.value.baseUrl).isEmpty()
        job.cancel()
    }

    @Test
    fun `switching backend mode persists through preferences`() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = buildViewModel()
        val job = keepUiStateAlive(viewModel)
        viewModel.setBackendMode(BackendMode.REMOTE)
        advanceUntilIdle()
        assertThat(viewModel.uiState.value.backendMode).isEqualTo(BackendMode.REMOTE)
        job.cancel()
    }

    @Test
    fun `developer mode toggle is reflected in state`() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = buildViewModel()
        val job = keepUiStateAlive(viewModel)
        advanceUntilIdle()
        assertThat(viewModel.uiState.value.developerMode).isFalse()
        viewModel.setDeveloperMode(true)
        advanceUntilIdle()
        assertThat(viewModel.uiState.value.developerMode).isTrue()
        job.cancel()
    }
}
