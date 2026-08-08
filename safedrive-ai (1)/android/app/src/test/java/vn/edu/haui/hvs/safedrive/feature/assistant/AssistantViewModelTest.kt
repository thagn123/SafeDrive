package vn.edu.haui.hvs.safedrive.feature.assistant

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
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
import vn.edu.haui.hvs.safedrive.domain.repository.VehicleActionCommand
import vn.edu.haui.hvs.safedrive.domain.repository.VehicleActionExecution
import vn.edu.haui.hvs.safedrive.domain.repository.VehicleActionExecutor
import vn.edu.haui.hvs.safedrive.domain.repository.VehicleActionMode
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
        vehicleActionExecutor: VehicleActionExecutor? = null,
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
            vehicleActionExecutor = vehicleActionExecutor,
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

    // --- SAFEDRIVE_AGENT_ARMOR_PLAN.md Slice 5A: persist verified HVAC execution into conversation ---

    @Test
    fun `confirming a typed HVAC action appends a verified SafeDrive confirmation message`() =
        runTest(mainDispatcherRule.dispatcher) {
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
            val viewModel = buildViewModel(scope = this, cockpitSnapshotFlow = snapshot, vehicleDataSource = dataSource)
            val action = SafeDriveAction(
                id = "act_hvac_26",
                type = ActionType.SET_HVAC_TEMPERATURE,
                title = "Dat dieu hoa 26 do C",
                requiresConfirmation = true,
                hvacTargetTemperatureC = 26f,
            )

            viewModel.onAction(AssistantUiAction.ExecuteAction(action))
            viewModel.onAction(AssistantUiAction.ConfirmPendingAction)
            advanceUntilIdle()

            val messages = viewModel.state.value.messages
            assertThat(messages).hasSize(1)
            assertThat(messages[0].sender.name).isEqualTo("SAFEDRIVE")
            assertThat(messages[0].text).contains("26")
            // CASE C (execution result vs. proposal): MockSafeDriveGateway.confirmAction() -- and the real
            // backend's confirm_action(), per its "details do not match" rejection test -- only ever
            // returns accepted=true when the confirmed target exactly equals the originally-issued target.
            // action.hvacTargetTemperatureC is therefore the backend-verified value here, not an unverified
            // echo of the proposal. The current contract has no way to construct a case where the applied
            // value legitimately differs from the proposed one -- any mismatch is rejected before
            // execution -- so no test can force execution-result != proposal without contradicting the
            // backend's own validation contract. Documented here rather than faked.
        }

    @Test
    fun `a failed HVAC confirmation appends no SafeDrive confirmation message`() = runTest(mainDispatcherRule.dispatcher) {
        val failingGateway = object : SafeDriveGateway by gateway {
            override suspend fun confirmAction(
                request: vn.edu.haui.hvs.safedrive.core.model.ActionConfirmRequest,
            ) = vn.edu.haui.hvs.safedrive.core.common.GatewayResult.Failure(
                vn.edu.haui.hvs.safedrive.core.common.GatewayError.Server(500, "boom"),
            )
        }
        val viewModel = buildViewModel(this, provider = fakeGatewayProvider { failingGateway })
        val action = SafeDriveAction(
            id = "act_hvac_fail",
            type = ActionType.SET_HVAC_TEMPERATURE,
            title = "Dat dieu hoa 26 do C",
            requiresConfirmation = true,
            hvacTargetTemperatureC = 26f,
        )

        viewModel.onAction(AssistantUiAction.ExecuteAction(action))
        viewModel.onAction(AssistantUiAction.ConfirmPendingAction)
        advanceUntilIdle()

        assertThat(viewModel.state.value.messages).isEmpty()
        assertThat(viewModel.state.value.confirmError).isNotNull()
    }

    @Test
    fun `an accepted=false confirmation appends no SafeDrive message and does not mutate simulated HVAC`() =
        runTest(mainDispatcherRule.dispatcher) {
            val dataSource = MockVehicleDataSource(clock, fixtures)
            val originalTarget = dataSource.vehicleState.value.hvacTargetTemperatureC
            val rejectingGateway = object : SafeDriveGateway by gateway {
                override suspend fun confirmAction(
                    request: vn.edu.haui.hvs.safedrive.core.model.ActionConfirmRequest,
                ) = vn.edu.haui.hvs.safedrive.core.common.GatewayResult.Success(
                    vn.edu.haui.hvs.safedrive.core.model.ActionConfirmResult(
                        accepted = false,
                        actionResult = null,
                        message = "The vehicle context changed. Please review the latest recommendation.",
                        serverTimeMs = clock.nowMs(),
                    ),
                )
            }
            val viewModel = buildViewModel(
                this,
                provider = fakeGatewayProvider { rejectingGateway },
                vehicleDataSource = dataSource,
            )
            val action = SafeDriveAction(
                id = "act_hvac_stale",
                type = ActionType.SET_HVAC_TEMPERATURE,
                title = "Dat dieu hoa 26 do C",
                requiresConfirmation = true,
                hvacTargetTemperatureC = 26f,
            )
            val effects = mutableListOf<AssistantUiEffect>()
            val job = launch(Dispatchers.Unconfined) { viewModel.effects.collect { effects.add(it) } }

            viewModel.onAction(AssistantUiAction.ExecuteAction(action))
            viewModel.onAction(AssistantUiAction.ConfirmPendingAction)
            advanceUntilIdle()

            // Regression guard for the bug this slice fixes: an HTTP 200 + accepted=false response
            // (MobileSessionStore.confirm_action()'s stale/tampered/replay rejections) must not be treated
            // as proof of execution -- previously performEffect() ran unconditionally here, mutating the
            // local simulated vehicle state and showing a "Đã đặt..." toast despite the backend having
            // rejected the confirmation.
            assertThat(viewModel.state.value.messages).isEmpty()
            assertThat(dataSource.vehicleState.value.hvacTargetTemperatureC).isEqualTo(originalTarget)
            assertThat(viewModel.state.value.confirmError).isNull()
            assertThat(viewModel.state.value.pendingAction).isNull()
            assertThat(effects).containsExactly(
                AssistantUiEffect.ShowMessage(
                    "Hành động đã bị từ chối vì trạng thái xe đã thay đổi. Vui lòng xem khuyến nghị mới nhất.",
                ),
            )
            job.cancel()
        }

    @Test
    fun `an accepted=false confirmation shows only rejection feedback for a non-HVAC action`() =
        runTest(mainDispatcherRule.dispatcher) {
            // Action-authority consistency: HVAC is the only type that mutates simulated vehicle
            // state, but acknowledging a backend *rejection* with a success effect is an authority
            // violation for every type. Before the centralized gate, GatewayResult.Success ran
            // performEffect() unconditionally for OPEN_DIAGNOSTICS / SHOW_WARNING /
            // SUGGEST_REST_STOP / START_SOS_COUNTDOWN, so a rejected confirmation still opened the
            // diagnostics screen or showed a "đã được ghi nhận" confirmation toast.
            val rejectingGateway = object : SafeDriveGateway by gateway {
                override suspend fun confirmAction(
                    request: vn.edu.haui.hvs.safedrive.core.model.ActionConfirmRequest,
                ) = vn.edu.haui.hvs.safedrive.core.common.GatewayResult.Success(
                    vn.edu.haui.hvs.safedrive.core.model.ActionConfirmResult(
                        accepted = false,
                        actionResult = null,
                        message = "This action was not issued for the current vehicle context.",
                        serverTimeMs = clock.nowMs(),
                    ),
                )
            }
            val viewModel = buildViewModel(this, provider = fakeGatewayProvider { rejectingGateway })
            val action = SafeDriveAction(
                id = "act_diag_stale",
                type = ActionType.OPEN_DIAGNOSTICS,
                title = "Mở chẩn đoán",
                requiresConfirmation = true,
            )

            val effects = mutableListOf<AssistantUiEffect>()
            val job = launch(Dispatchers.Unconfined) { viewModel.effects.collect { effects.add(it) } }

            viewModel.onAction(AssistantUiAction.ExecuteAction(action))
            viewModel.onAction(AssistantUiAction.ConfirmPendingAction)
            advanceUntilIdle()

            assertThat(effects).containsExactly(
                AssistantUiEffect.ShowMessage(
                    "Hành động đã bị từ chối vì trạng thái xe đã thay đổi. Vui lòng xem khuyến nghị mới nhất.",
                ),
            )
            assertThat(viewModel.state.value.confirmError).isNull()
            assertThat(viewModel.state.value.pendingAction).isNull()
            job.cancel()
        }

    @Test
    fun `an accepted=true confirmation still produces the effect for a non-HVAC action`() =
        runTest(mainDispatcherRule.dispatcher) {
            val viewModel = buildViewModel(this)
            val action = SafeDriveAction(
                id = "act_diag_ok",
                type = ActionType.OPEN_DIAGNOSTICS,
                title = "Mở chẩn đoán",
                requiresConfirmation = true,
            )

            val effects = mutableListOf<AssistantUiEffect>()
            val job = launch(Dispatchers.Unconfined) { viewModel.effects.collect { effects.add(it) } }

            viewModel.onAction(AssistantUiAction.ExecuteAction(action))
            viewModel.onAction(AssistantUiAction.ConfirmPendingAction)
            advanceUntilIdle()

            // Positive control for the test above: the centralized accepted gate must not have
            // broken the ordinary success path for non-HVAC actions.
            assertThat(effects).containsExactly(AssistantUiEffect.OpenDiagnostics)
            assertThat(viewModel.state.value.confirmError).isNull()
            job.cancel()
        }

    @Test
    fun `re-dispatching confirm after success does not duplicate the SafeDrive confirmation message`() =
        runTest(mainDispatcherRule.dispatcher) {
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
            val viewModel = buildViewModel(scope = this, cockpitSnapshotFlow = snapshot, vehicleDataSource = dataSource)
            val action = SafeDriveAction(
                id = "act_hvac_24",
                type = ActionType.SET_HVAC_TEMPERATURE,
                title = "Dat dieu hoa 24 do C",
                requiresConfirmation = true,
                hvacTargetTemperatureC = 24f,
            )

            viewModel.onAction(AssistantUiAction.ExecuteAction(action))
            viewModel.onAction(AssistantUiAction.ConfirmPendingAction)
            advanceUntilIdle()
            assertThat(viewModel.state.value.messages).hasSize(1)

            // pendingAction is already null after a successful confirm -- a second dispatch (e.g. a
            // duplicate button event) must be a no-op, not a second confirmation call/message.
            viewModel.onAction(AssistantUiAction.ConfirmPendingAction)
            advanceUntilIdle()
            assertThat(viewModel.state.value.messages).hasSize(1)
        }

    @Test
    fun `pending prompt from Diagnostics prefills the composer on init`() = runTest(mainDispatcherRule.dispatcher) {
        val coordinator = PendingPromptCoordinator()
        coordinator.prefill("Hãy giải thích mã lỗi P0301")
        val viewModel = buildViewModel(this, pendingPromptCoordinator = coordinator)
        assertThat(viewModel.state.value.composerText).isEqualTo("Hãy giải thích mã lỗi P0301")
    }
    @Test
    fun `accepted vehicle action reaches executor only after authority confirmation`() =
        runTest(mainDispatcherRule.dispatcher) {
            val executed = mutableListOf<VehicleActionCommand>()
            val executor = VehicleActionExecutor { command ->
                executed += command
                VehicleActionExecution(
                    applied = true,
                    readBackVerified = true,
                    mode = VehicleActionMode.VEHICLE,
                    message = "vehicle verified",
                )
            }
            val viewModel = buildViewModel(this, vehicleActionExecutor = executor)
            val action = SafeDriveAction(
                id = "act_lock_doors",
                type = ActionType.LOCK_DOORS,
                title = "Khóa cửa",
                requiresConfirmation = true,
            )

            viewModel.onAction(AssistantUiAction.ExecuteAction(action))
            advanceUntilIdle()
            assertThat(executed).isEmpty()

            viewModel.onAction(AssistantUiAction.ConfirmPendingAction)
            advanceUntilIdle()
            assertThat(executed).containsExactly(VehicleActionCommand.LockDoors)
        }

    @Test
    fun `rejected authority confirmation never reaches vehicle executor`() =
        runTest(mainDispatcherRule.dispatcher) {
            val rejectingGateway = object : SafeDriveGateway by gateway {
                override suspend fun confirmAction(
                    request: vn.edu.haui.hvs.safedrive.core.model.ActionConfirmRequest,
                ) = vn.edu.haui.hvs.safedrive.core.common.GatewayResult.Success(
                    vn.edu.haui.hvs.safedrive.core.model.ActionConfirmResult(
                        accepted = false,
                        actionResult = null,
                        message = "rejected by action authority",
                        serverTimeMs = clock.nowMs(),
                    ),
                )
            }
            var executorCalls = 0
            val executor = VehicleActionExecutor {
                executorCalls += 1
                VehicleActionExecution(true, true, VehicleActionMode.VEHICLE, "unexpected")
            }
            val viewModel = buildViewModel(
                this,
                provider = fakeGatewayProvider { rejectingGateway },
                vehicleActionExecutor = executor,
            )
            val action = SafeDriveAction(
                id = "act_unlock_doors",
                type = ActionType.UNLOCK_DOORS,
                title = "Mở khóa cửa",
                requiresConfirmation = true,
            )

            viewModel.onAction(AssistantUiAction.ExecuteAction(action))
            viewModel.onAction(AssistantUiAction.ConfirmPendingAction)
            advanceUntilIdle()

            assertThat(executorCalls).isEqualTo(0)
        }
}

private fun fakeGatewayProvider(provider: () -> SafeDriveGateway) = object : GatewayProvider {
    override fun current() = provider()
}
