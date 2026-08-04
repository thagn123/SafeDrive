package vn.edu.haui.hvs.safedrive.feature.assistant

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import vn.edu.haui.hvs.safedrive.core.common.UuidIdGenerator
import vn.edu.haui.hvs.safedrive.core.model.ActionType
import vn.edu.haui.hvs.safedrive.core.model.CockpitSnapshot
import vn.edu.haui.hvs.safedrive.core.model.SafeDriveAction
import vn.edu.haui.hvs.safedrive.core.observability.AssistantTurnMetricsRecorder
import vn.edu.haui.hvs.safedrive.core.testing.FakeClock
import vn.edu.haui.hvs.safedrive.core.testing.FakePreferencesRepository
import vn.edu.haui.hvs.safedrive.core.testing.FakeTtsController
import vn.edu.haui.hvs.safedrive.core.testing.MainDispatcherRule
import vn.edu.haui.hvs.safedrive.data.local.InMemoryConversationRepository
import vn.edu.haui.hvs.safedrive.data.mock.MockFixtures
import vn.edu.haui.hvs.safedrive.data.mock.MockPolicyEvaluator
import vn.edu.haui.hvs.safedrive.data.mock.MockSafeDriveGateway
import vn.edu.haui.hvs.safedrive.domain.repository.AppPreferences
import vn.edu.haui.hvs.safedrive.domain.repository.ConversationRepository
import vn.edu.haui.hvs.safedrive.domain.repository.GatewayProvider
import vn.edu.haui.hvs.safedrive.domain.repository.SafeDriveGateway
import vn.edu.haui.hvs.safedrive.domain.repository.VehicleDataSource
import vn.edu.haui.hvs.safedrive.domain.usecase.AssistantQueryUseCase
import vn.edu.haui.hvs.safedrive.domain.usecase.AssistantTurnCoordinator
import vn.edu.haui.hvs.safedrive.domain.usecase.ConfirmActionUseCase
import vn.edu.haui.hvs.safedrive.domain.usecase.PendingPromptCoordinator
import vn.edu.haui.hvs.safedrive.domain.usecase.SessionCoordinator
import vn.edu.haui.hvs.safedrive.vehicle.MockVehicleDataSource

/**
 * docs/android-mvp-plan/12 W1: [AssistantViewModel] is now a thin presentation delegate over the
 * shared [ConversationRepository]/[AssistantTurnCoordinator]. Turn-lifecycle guarantees (duplicate
 * guard, cancel, retry lineage) are covered exhaustively in `AssistantTurnCoordinatorTest`; this
 * class only covers the ViewModel's own responsibilities (composer text, action confirmation, and
 * that it correctly reflects/delegates to the shared repository/coordinator).
 */
class AssistantViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val clock = FakeClock(initialMs = 1_000L)
    private val idGenerator = UuidIdGenerator()
    private val fixtures = MockFixtures(clock)
    private val gateway: SafeDriveGateway = MockSafeDriveGateway(clock, idGenerator, fixtures, MockPolicyEvaluator(clock))
    private val gatewayProvider = fakeGatewayProvider { gateway }
    private val appPreferences = MutableStateFlow(AppPreferences())
    private val cockpitSnapshot: StateFlow<CockpitSnapshot?> = MutableStateFlow(null)

    private fun buildViewModel(
        scope: CoroutineScope,
        conversationRepository: ConversationRepository = InMemoryConversationRepository(emptyList()),
        preferences: FakePreferencesRepository = FakePreferencesRepository(),
        pendingPromptCoordinator: PendingPromptCoordinator = PendingPromptCoordinator(),
        provider: GatewayProvider = gatewayProvider,
        cockpitSnapshotFlow: StateFlow<CockpitSnapshot?> = cockpitSnapshot,
        vehicleDataSource: VehicleDataSource? = null,
    ): AssistantViewModel {
        // Built fresh per call from `provider` (not a shared class-level instance) so a per-test
        // gateway override (e.g. `slowGateway()`/a failing gateway) is actually the gateway the turn's
        // query runs against — `AssistantQueryUseCase` now sends its query through the exact gateway
        // `SessionCoordinator` resolved the session against (remediation item 3), so the two must never
        // be built from different providers.
        val sessionCoordinator = SessionCoordinator(provider, appPreferences, idGenerator, clock, "test")
        val coordinator = AssistantTurnCoordinator(
            conversationRepository = conversationRepository,
            assistantQueryUseCase = AssistantQueryUseCase(sessionCoordinator, clock),
            cockpitSnapshot = cockpitSnapshotFlow,
            appPreferences = appPreferences,
            ttsController = FakeTtsController(),
            metricsRecorder = AssistantTurnMetricsRecorder(),
            lastHealthStatus = MutableStateFlow(null),
            idGenerator = idGenerator,
            clock = clock,
            externalScope = scope,
        )
        return AssistantViewModel(
            conversationRepository = conversationRepository,
            assistantTurnCoordinator = coordinator,
            confirmActionUseCase = ConfirmActionUseCase(sessionCoordinator, idGenerator),
            preferencesRepository = preferences,
            cockpitSnapshot = cockpitSnapshotFlow,
            pendingPromptCoordinator = pendingPromptCoordinator,
            vehicleDataSource = vehicleDataSource,
            clock = clock,
        )
    }

    /** Demo Mode has no artificial delay by default (W4.3); tests that need a genuine in-flight
     * window wrap the real gateway with one explicitly instead of relying on
     * `MockSafeDriveGateway`'s own (now-removed) default delay. */
    private fun slowGateway(delayMs: Long = 100L): SafeDriveGateway = object : SafeDriveGateway by gateway {
        override suspend fun queryAssistant(
            request: vn.edu.haui.hvs.safedrive.core.model.AssistantQueryRequest,
        ): vn.edu.haui.hvs.safedrive.core.common.GatewayResult<vn.edu.haui.hvs.safedrive.core.model.AssistantQueryResult> {
            delay(delayMs)
            return gateway.queryAssistant(request)
        }
    }

    @Test
    fun `blank composer text never sends a message`() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = buildViewModel(this)
        viewModel.onAction(AssistantUiAction.ComposerChanged("   "))
        viewModel.onAction(AssistantUiAction.Send)
        advanceUntilIdle()
        assertThat(viewModel.state.value.messages).isEmpty()
    }

    @Test
    fun `sending a question appends a user bubble then a SAFEDRIVE reply`() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = buildViewModel(this)
        viewModel.onAction(AssistantUiAction.ComposerChanged("Kiểm tra nhiệt độ động cơ"))
        viewModel.onAction(AssistantUiAction.Send)
        advanceUntilIdle()

        val messages = viewModel.state.value.messages
        assertThat(messages).hasSize(2)
        assertThat(messages[0].sender.name).isEqualTo("USER")
        assertThat(messages[1].sender.name).isEqualTo("SAFEDRIVE")
        assertThat(viewModel.state.value.isSending).isFalse()
        assertThat(viewModel.state.value.composerText).isEmpty()
    }

    @Test
    fun `duplicate submit is blocked while the first request is still in flight`() = runTest(mainDispatcherRule.dispatcher) {
        // Demo Mode has no artificial delay by default (W4.3) — a slow gateway creates the in-flight
        // window this test needs to assert the duplicate-submit guard against.
        val viewModel = buildViewModel(this, provider = fakeGatewayProvider { slowGateway() })
        viewModel.onAction(AssistantUiAction.ComposerChanged("Tốc độ hiện tại?"))
        viewModel.onAction(AssistantUiAction.Send)
        assertThat(viewModel.state.value.isSending).isTrue()
        assertThat(viewModel.state.value.messages).hasSize(1) // user bubble added, reply still pending

        viewModel.onAction(AssistantUiAction.ComposerChanged("Một câu hỏi khác"))
        viewModel.onAction(AssistantUiAction.Send) // must be a no-op: a request is already in flight
        assertThat(viewModel.state.value.messages).hasSize(1)
        // Composer text is NOT cleared for a rejected submit — the user's second draft is preserved.
        assertThat(viewModel.state.value.composerText).isEqualTo("Một câu hỏi khác")

        advanceUntilIdle()
        assertThat(viewModel.state.value.messages).hasSize(2) // first request's reply lands, nothing else
        assertThat(viewModel.state.value.isSending).isFalse()
    }

    @Test
    fun `cancelling the current turn keeps the user bubble and enables retry`() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = buildViewModel(this, provider = fakeGatewayProvider { slowGateway() })
        viewModel.onAction(AssistantUiAction.ComposerChanged("Câu hỏi sẽ bị hủy"))
        viewModel.onAction(AssistantUiAction.Send)
        assertThat(viewModel.state.value.isSending).isTrue()

        viewModel.onAction(AssistantUiAction.CancelTurn)

        assertThat(viewModel.state.value.isSending).isFalse()
        assertThat(viewModel.state.value.messages).hasSize(1)
        assertThat(viewModel.state.value.canRetry).isTrue()

        advanceUntilIdle() // the cancelled gateway call must not resurrect state if it later resolves
        assertThat(viewModel.state.value.messages).hasSize(1)
    }

    @Test
    fun `retry after a failure does not duplicate the user bubble`() = runTest(mainDispatcherRule.dispatcher) {
        var shouldFail = true
        val failingGateway = object : SafeDriveGateway by gateway {
            override suspend fun queryAssistant(request: vn.edu.haui.hvs.safedrive.core.model.AssistantQueryRequest) =
                if (shouldFail) {
                    shouldFail = false
                    vn.edu.haui.hvs.safedrive.core.common.GatewayResult.Failure(vn.edu.haui.hvs.safedrive.core.common.GatewayError.Timeout)
                } else {
                    gateway.queryAssistant(request)
                }
        }
        val viewModel = buildViewModel(this, provider = fakeGatewayProvider { failingGateway })

        viewModel.onAction(AssistantUiAction.ComposerChanged("Xe có lỗi gì?"))
        viewModel.onAction(AssistantUiAction.Send)
        advanceUntilIdle()

        assertThat(viewModel.state.value.messages).hasSize(1) // only the user bubble; reply failed
        assertThat(viewModel.state.value.canRetry).isTrue()

        viewModel.onAction(AssistantUiAction.Retry)
        advanceUntilIdle()

        val messages = viewModel.state.value.messages
        assertThat(messages).hasSize(2) // same user bubble + one new SAFEDRIVE reply
        assertThat(messages.count { it.sender.name == "USER" }).isEqualTo(1)
    }

    @Test
    fun `conversation survives ViewModel recreation via the shared repository`() = runTest(mainDispatcherRule.dispatcher) {
        val sharedRepository = InMemoryConversationRepository(emptyList())
        val firstViewModel = buildViewModel(this, conversationRepository = sharedRepository)
        firstViewModel.onAction(AssistantUiAction.ComposerChanged("Câu hỏi đầu tiên"))
        firstViewModel.onAction(AssistantUiAction.Send)
        advanceUntilIdle()
        assertThat(firstViewModel.state.value.messages).hasSize(2)

        // Simulate rotation/tab navigation recreating the ViewModel against the same application-scoped repository.
        val recreatedViewModel = buildViewModel(this, conversationRepository = sharedRepository)
        assertThat(recreatedViewModel.state.value.messages).hasSize(2)
    }

    @Test
    fun `action requiring confirmation sets pendingAction instead of executing immediately`() =
        runTest(mainDispatcherRule.dispatcher) {
            val viewModel = buildViewModel(this)
            val action = SafeDriveAction("act_1", ActionType.SUGGEST_REST_STOP, "Đề xuất dừng nghỉ", requiresConfirmation = true)
            viewModel.onAction(AssistantUiAction.ExecuteAction(action))
            assertThat(viewModel.state.value.pendingAction).isEqualTo(action)
        }

    @Test
    fun `confirming a pending action clears it and does not crash on NONE action type`() =
        runTest(mainDispatcherRule.dispatcher) {
            val viewModel = buildViewModel(this)
            val action = SafeDriveAction("act_none", ActionType.NONE, "No-op", requiresConfirmation = true)
            viewModel.onAction(AssistantUiAction.ExecuteAction(action))
            viewModel.onAction(AssistantUiAction.ConfirmPendingAction)
            advanceUntilIdle()
            assertThat(viewModel.state.value.pendingAction).isNull()
        }

    @Test
    fun `cancelling a pending action clears it without executing`() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = buildViewModel(this)
        val action = SafeDriveAction("act_2", ActionType.START_SOS_COUNTDOWN, "SOS", requiresConfirmation = true)
        viewModel.onAction(AssistantUiAction.ExecuteAction(action))
        viewModel.onAction(AssistantUiAction.CancelPendingAction)
        assertThat(viewModel.state.value.pendingAction).isNull()
    }

    @Test
    fun `confirming a typed HVAC action updates the simulated vehicle state`() = runTest(mainDispatcherRule.dispatcher) {
        val dataSource = MockVehicleDataSource(clock, fixtures)
        val vehicleState = dataSource.vehicleState.value
        val signals = dataSource.driverSupportSignals.value
        val policy = MockPolicyEvaluator(clock)
        val rest = policy.evaluateRestRecommendation(vehicleState, signals)
        val snapshot = MutableStateFlow(
            CockpitSnapshot(
                vehicleState = vehicleState,
                driverSupportSignals = signals,
                riskAssessment = policy.evaluateRisk(vehicleState, rest),
                restRecommendation = rest,
                connectionStatus = vn.edu.haui.hvs.safedrive.core.model.SystemConnectionStatus.NORMAL,
                stateVersion = 1L,
                updatedAtMs = clock.nowMs(),
            ),
        )
        val viewModel = buildViewModel(
            scope = this,
            cockpitSnapshotFlow = snapshot,
            vehicleDataSource = dataSource,
        )
        val action = SafeDriveAction(
            id = "act_hvac_23",
            type = ActionType.SET_HVAC_TEMPERATURE,
            title = "Dat dieu hoa 23 do C",
            requiresConfirmation = true,
            hvacTargetTemperatureC = 23f,
        )

        viewModel.onAction(AssistantUiAction.ExecuteAction(action))
        viewModel.onAction(AssistantUiAction.ConfirmPendingAction)
        advanceUntilIdle()

        assertThat(viewModel.state.value.pendingAction).isNull()
        assertThat(dataSource.vehicleState.value.hvacTargetTemperatureC).isEqualTo(23f)
        assertThat(dataSource.vehicleState.value.updatedAtMs).isEqualTo(clock.nowMs())
    }

    @Test
    fun `pending prompt from Diagnostics prefills the composer on init`() = runTest(mainDispatcherRule.dispatcher) {
        val coordinator = PendingPromptCoordinator()
        coordinator.prefill("Hãy giải thích mã lỗi P0301")
        val viewModel = buildViewModel(this, pendingPromptCoordinator = coordinator)
        assertThat(viewModel.state.value.composerText).isEqualTo("Hãy giải thích mã lỗi P0301")
    }
}

private fun fakeGatewayProvider(provider: () -> SafeDriveGateway) = object : GatewayProvider {
    override fun current() = provider()
}
