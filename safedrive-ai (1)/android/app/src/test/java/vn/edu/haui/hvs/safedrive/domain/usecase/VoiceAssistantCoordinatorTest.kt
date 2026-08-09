package vn.edu.haui.hvs.safedrive.domain.usecase

import com.google.common.truth.Truth.assertThat
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import vn.edu.haui.hvs.safedrive.core.common.GatewayError
import vn.edu.haui.hvs.safedrive.core.common.GatewayResult
import vn.edu.haui.hvs.safedrive.core.common.UuidIdGenerator
import vn.edu.haui.hvs.safedrive.core.model.AssistantQueryRequest
import vn.edu.haui.hvs.safedrive.core.model.AssistantQueryResult
import vn.edu.haui.hvs.safedrive.core.model.AssistantTurnSource
import vn.edu.haui.hvs.safedrive.core.model.AssistantTurnState
import vn.edu.haui.hvs.safedrive.core.model.ChatMessage
import vn.edu.haui.hvs.safedrive.core.model.ChatSender
import vn.edu.haui.hvs.safedrive.core.model.CockpitSnapshot
import vn.edu.haui.hvs.safedrive.core.model.EmergencyResponseType
import vn.edu.haui.hvs.safedrive.core.model.EmergencySnapshot
import vn.edu.haui.hvs.safedrive.core.model.EmergencyState
import vn.edu.haui.hvs.safedrive.core.model.HealthCapabilities
import vn.edu.haui.hvs.safedrive.core.model.HealthStatus
import vn.edu.haui.hvs.safedrive.core.model.SystemConnectionStatus
import vn.edu.haui.hvs.safedrive.core.model.VoiceState
import vn.edu.haui.hvs.safedrive.core.observability.AssistantTurnMetricsRecorder
import vn.edu.haui.hvs.safedrive.core.testing.FakeClock
import vn.edu.haui.hvs.safedrive.core.testing.FakeEmergencyRepository
import vn.edu.haui.hvs.safedrive.core.testing.FakeTtsController
import vn.edu.haui.hvs.safedrive.core.testing.FakeVoiceController
import vn.edu.haui.hvs.safedrive.core.testing.MainDispatcherRule
import vn.edu.haui.hvs.safedrive.data.local.InMemoryConversationRepository
import vn.edu.haui.hvs.safedrive.data.mock.MockFixtures
import vn.edu.haui.hvs.safedrive.data.mock.MockPolicyEvaluator
import vn.edu.haui.hvs.safedrive.data.mock.MockSafeDriveGateway
import vn.edu.haui.hvs.safedrive.domain.repository.AppPreferences
import vn.edu.haui.hvs.safedrive.domain.repository.GatewayProvider
import vn.edu.haui.hvs.safedrive.domain.repository.SafeDriveGateway
import vn.edu.haui.hvs.safedrive.voice.VoiceController
import vn.edu.haui.hvs.safedrive.voice.VoiceInputEvent

/**
 * Covers docs/android-mvp-plan/12 W2's mandatory test list: voice transcripts create chat history
 * and an assistant reply (W2.6/W2.7), while emergency-active transcripts never reach chat and route
 * only through the exact-match cancel allowlist (W2.5), matching the pre-W2 behavior that used to
 * live directly inside `AndroidSpeechRecognizerController`.
 */
class VoiceAssistantCoordinatorTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val clock = FakeClock(initialMs = 1_000L)
    private val idGenerator = UuidIdGenerator()
    private val fixtures = MockFixtures(clock)
    private val gateway: SafeDriveGateway = MockSafeDriveGateway(clock, idGenerator, fixtures, MockPolicyEvaluator(clock))
    private val cockpitSnapshot: StateFlow<CockpitSnapshot?> = MutableStateFlow(null)

    private fun buildScenario(
        turnScope: kotlinx.coroutines.CoroutineScope,
        voiceScope: kotlinx.coroutines.CoroutineScope,
        metricsRecorder: AssistantTurnMetricsRecorder = AssistantTurnMetricsRecorder(),
        gatewayOverride: SafeDriveGateway = gateway,
        autoStart: Boolean = true,
        initialMessages: List<ChatMessage> = emptyList(),
        lastHealthStatus: StateFlow<HealthStatus?> = MutableStateFlow(null),
        voiceCompletionScope: kotlinx.coroutines.CoroutineScope = voiceScope,
    ): Scenario {
        val provider = object : GatewayProvider {
            override fun current() = gatewayOverride
        }
        val appPreferences = MutableStateFlow(AppPreferences())
        val sessionCoordinator = SessionCoordinator(provider, appPreferences, idGenerator, clock, "test")
        val conversationRepository = InMemoryConversationRepository(initialMessages)
        val turnCoordinator = AssistantTurnCoordinator(
            conversationRepository = conversationRepository,
            assistantQueryUseCase = AssistantQueryUseCase(sessionCoordinator, clock),
            cockpitSnapshot = cockpitSnapshot,
            appPreferences = appPreferences,
            ttsController = FakeTtsController(),
            metricsRecorder = metricsRecorder,
            lastHealthStatus = lastHealthStatus,
            idGenerator = idGenerator,
            clock = clock,
            externalScope = turnScope,
        )
        val voiceController = FakeVoiceController()
        val emergencyRepository = FakeEmergencyRepository()
        val coordinator = VoiceAssistantCoordinator(
            voiceController = voiceController,
            assistantTurnCoordinator = turnCoordinator,
            emergencyRepository = emergencyRepository,
            ttsController = FakeTtsController(),
            externalScope = voiceScope,
            completionScope = voiceCompletionScope,
        )
        if (autoStart) coordinator.start()
        return Scenario(coordinator, voiceController, conversationRepository, emergencyRepository, metricsRecorder, turnCoordinator)
    }

    private data class Scenario(
        val coordinator: VoiceAssistantCoordinator,
        val voiceController: FakeVoiceController,
        val conversationRepository: InMemoryConversationRepository,
        val emergencyRepository: FakeEmergencyRepository,
        val metricsRecorder: AssistantTurnMetricsRecorder,
        val turnCoordinator: AssistantTurnCoordinator,
    )

    @Test
    fun `final transcript with no active emergency creates a user bubble and a SAFEDRIVE reply`() =
        runTest(mainDispatcherRule.dispatcher) {
            val scenario = buildScenario(turnScope = this, voiceScope = backgroundScope)
            scenario.voiceController.emitFinalTranscript("Kiểm tra nhiệt độ động cơ", screen = "cockpit")
            advanceUntilIdle()

            val messages = scenario.conversationRepository.state.value.messages
            assertThat(messages).hasSize(2)
            assertThat(messages[0].sender.name).isEqualTo("USER")
            assertThat(messages[1].sender.name).isEqualTo("SAFEDRIVE")
        }

    @Test
    fun `final transcript clears the voice controller processing state once the turn finishes`() =
        runTest(mainDispatcherRule.dispatcher) {
            val scenario = buildScenario(turnScope = this, voiceScope = backgroundScope)
            scenario.voiceController.setState(vn.edu.haui.hvs.safedrive.voice.VoiceUiState(state = VoiceState.PROCESSING))
            scenario.voiceController.emitFinalTranscript("Tốc độ hiện tại?")
            advanceUntilIdle()

            assertThat(scenario.voiceController.clearProcessingStateCallCount).isEqualTo(1)
            assertThat(scenario.voiceController.state.value.state).isEqualTo(VoiceState.IDLE)
        }

    @Test
    fun `emergency active and exact cancel phrase cancels SOS and never reaches chat`() =
        runTest(mainDispatcherRule.dispatcher) {
            val scenario = buildScenario(turnScope = this, voiceScope = backgroundScope)
            scenario.emergencyRepository.setSnapshot(
                EmergencySnapshot("emg_1", EmergencyState.AWAITING_USER_RESPONSE, 10_000L, emptyList()),
            )

            scenario.voiceController.emitFinalTranscript("Tôi ổn", screen = "emergency")
            advanceUntilIdle()

            assertThat(scenario.emergencyRepository.lastResponse).isEqualTo(EmergencyResponseType.CANCEL_SOS)
            assertThat(scenario.conversationRepository.state.value.messages).isEmpty()
        }

    @Test
    fun `emergency active but non-cancel phrase does not cancel SOS and never reaches chat`() =
        runTest(mainDispatcherRule.dispatcher) {
            val scenario = buildScenario(turnScope = this, voiceScope = backgroundScope)
            scenario.emergencyRepository.setSnapshot(
                EmergencySnapshot("emg_2", EmergencyState.FINAL_COUNTDOWN, 5_000L, emptyList()),
            )

            // "Tôi không ổn" must NOT be treated as a cancel — exact-match only, no substring/contains.
            scenario.voiceController.emitFinalTranscript("Tôi không ổn", screen = "emergency")
            advanceUntilIdle()

            assertThat(scenario.emergencyRepository.lastResponse).isNull()
            assertThat(scenario.conversationRepository.state.value.messages).isEmpty()
        }

    @Test
    fun `emergency no longer active routes normally to the assistant pipeline`() = runTest(mainDispatcherRule.dispatcher) {
        val scenario = buildScenario(turnScope = this, voiceScope = backgroundScope)
        scenario.emergencyRepository.setSnapshot(
            EmergencySnapshot("emg_3", EmergencyState.CANCELLED, null, emptyList()),
        )

        scenario.voiceController.emitFinalTranscript("Xe có lỗi gì không?")
        advanceUntilIdle()

        assertThat(scenario.conversationRepository.state.value.messages).hasSize(2)
    }

    @Test
    fun `mic-recognizer capture timings from the voice controller reach the recorded metrics (W4)`() =
        runTest(mainDispatcherRule.dispatcher) {
            val recorder = AssistantTurnMetricsRecorder()
            val scenario = buildScenario(turnScope = this, voiceScope = backgroundScope, metricsRecorder = recorder)
            val captureTimings = vn.edu.haui.hvs.safedrive.core.observability.VoiceCaptureTimings(
                micRequestedAtMs = 1_000L,
                recognizerReadyAtMs = 1_100L,
                firstPartialAtMs = 1_300L,
                finalTranscriptAtMs = 1_800L,
            )
            scenario.voiceController.emitFinalTranscript("Tốc độ hiện tại?", captureTimings = captureTimings)
            advanceUntilIdle()

            val metrics = recorder.lastTurn.value
            assertThat(metrics).isNotNull()
            assertThat(metrics!!.voiceCapture).isEqualTo(captureTimings)
            assertThat(metrics.micStartToReadyMs).isEqualTo(100L)
            assertThat(metrics.speechToFirstPartialMs).isEqualTo(200L)
        }

    // --- Remediation item 6: turnOutcome lets VoiceOverlay show the voice turn's actual reply/error
    // instead of a generic spinner, without VoiceController ever owning that data (W6.10). ---

    @Test
    fun `a successful voice turn publishes its reply text as a Success turnOutcome`() =
        runTest(mainDispatcherRule.dispatcher) {
            val scenario = buildScenario(turnScope = this, voiceScope = backgroundScope)
            scenario.voiceController.emitFinalTranscript("Kiểm tra nhiệt độ động cơ", screen = "cockpit")
            advanceUntilIdle()

            val outcome = scenario.coordinator.turnOutcome.value
            assertThat(outcome).isInstanceOf(VoiceTurnOutcome.Success::class.java)
            assertThat((outcome as VoiceTurnOutcome.Success).replyText).isNotEmpty()
        }

    @Test
    fun `a failed voice turn publishes the error message as a Failure turnOutcome`() =
        runTest(mainDispatcherRule.dispatcher) {
            val failingGateway = object : SafeDriveGateway by gateway {
                override suspend fun queryAssistant(
                    request: vn.edu.haui.hvs.safedrive.core.model.AssistantQueryRequest,
                ): vn.edu.haui.hvs.safedrive.core.common.GatewayResult<vn.edu.haui.hvs.safedrive.core.model.AssistantQueryResult> =
                    vn.edu.haui.hvs.safedrive.core.common.GatewayResult.Failure(vn.edu.haui.hvs.safedrive.core.common.GatewayError.Timeout)
            }
            val scenario = buildScenario(turnScope = this, voiceScope = backgroundScope, gatewayOverride = failingGateway)
            scenario.voiceController.emitFinalTranscript("Xe có lỗi gì?")
            advanceUntilIdle()

            val outcome = scenario.coordinator.turnOutcome.value
            assertThat(outcome).isInstanceOf(VoiceTurnOutcome.Failure::class.java)
            assertThat((outcome as VoiceTurnOutcome.Failure).errorMessage).isNotEmpty()
        }

    @Test
    fun `a cancelled voice turn publishes no turnOutcome at all`() = runTest(mainDispatcherRule.dispatcher) {
        val slowGateway = object : SafeDriveGateway by gateway {
            override suspend fun queryAssistant(
                request: vn.edu.haui.hvs.safedrive.core.model.AssistantQueryRequest,
            ): vn.edu.haui.hvs.safedrive.core.common.GatewayResult<vn.edu.haui.hvs.safedrive.core.model.AssistantQueryResult> {
                kotlinx.coroutines.delay(1_000L)
                return gateway.queryAssistant(request)
            }
        }
        val scenario = buildScenario(turnScope = this, voiceScope = backgroundScope, gatewayOverride = slowGateway)
        scenario.voiceController.emitFinalTranscript("Câu hỏi sẽ bị hủy")
        advanceTimeBy(10L) // let the turn actually start (in-flight) before cancelling
        scenario.turnCoordinator.cancelCurrent()
        advanceUntilIdle()

        assertThat(scenario.coordinator.turnOutcome.value).isNull()
    }

    @Test
    fun `dismissTurnOutcome clears the currently-shown outcome without touching chat history`() =
        runTest(mainDispatcherRule.dispatcher) {
            val scenario = buildScenario(turnScope = this, voiceScope = backgroundScope)
            scenario.voiceController.emitFinalTranscript("Kiểm tra nhiệt độ động cơ")
            advanceUntilIdle()
            assertThat(scenario.coordinator.turnOutcome.value).isNotNull()

            scenario.coordinator.dismissTurnOutcome()

            assertThat(scenario.coordinator.turnOutcome.value).isNull()
            assertThat(scenario.conversationRepository.state.value.messages).hasSize(2) // history untouched
        }

    @Test
    fun `a text-sourced turn never publishes a turnOutcome, so a text reply can never leak into the voice overlay`() =
        runTest(mainDispatcherRule.dispatcher) {
            val scenario = buildScenario(turnScope = this, voiceScope = backgroundScope)
            scenario.turnCoordinator.submit(
                "Kiểm tra nhiệt độ động cơ",
                vn.edu.haui.hvs.safedrive.core.model.AssistantTurnSource.TEXT,
                "assistant",
            )
            advanceUntilIdle()

            assertThat(scenario.conversationRepository.state.value.messages).hasSize(2) // the text turn did complete
            assertThat(scenario.coordinator.turnOutcome.value).isNull() // but never touched turnOutcome
        }

    // --- Remediation item 7: a transcript emitted before start() registers its collector must still
    // be delivered, not lost — the whole point of switching FakeVoiceController.events to a Channel. ---

    @Test
    fun `a transcript emitted before start() is called is still processed exactly once, not lost`() =
        runTest(mainDispatcherRule.dispatcher) {
            val scenario = buildScenario(turnScope = this, voiceScope = backgroundScope, autoStart = false)
            val emitted = scenario.voiceController.emitFinalTranscript("Kiểm tra nhiệt độ động cơ", screen = "cockpit")
            assertThat(emitted).isTrue()

            scenario.coordinator.start()
            advanceUntilIdle()

            val messages = scenario.conversationRepository.state.value.messages
            assertThat(messages).hasSize(2)
            assertThat(messages[0].sender.name).isEqualTo("USER")
        }

    @Test
    fun `calling start() twice never registers a second collector, no duplicate turn`() =
        runTest(mainDispatcherRule.dispatcher) {
            val scenario = buildScenario(turnScope = this, voiceScope = backgroundScope)
            scenario.coordinator.start() // second call — must be a no-op

            scenario.voiceController.emitFinalTranscript("Kiểm tra nhiệt độ động cơ")
            advanceUntilIdle()

            // If two collectors had registered, a Channel-backed event is only ever delivered to one of
            // them — but if start() were *not* idempotent in a way that mattered here, this would still
            // only show one exchange; the real risk (checked structurally) is two independent onEach
            // pipelines both mutating shared state. One clean exchange is the expected, safe outcome.
            assertThat(scenario.conversationRepository.state.value.messages).hasSize(2)
        }

    // --- Independent re-audit follow-up, item 4: turnOutcome must correlate on the voice turn's own
    // requestId/generation, never be inferred from "whichever turn just finished" ambient state. ---

    @Test
    fun `a pre-existing SAFEDRIVE message from before this voice turn is never used as its outcome`() =
        runTest(mainDispatcherRule.dispatcher) {
            // Reproduces the exact old bug: the coordinator used to pick
            // `messages.lastOrNull { sender == SAFEDRIVE }` as the voice turn's reply — which a seeded,
            // unrelated, older SAFEDRIVE message would have satisfied if the collector woke up even one
            // instant before the new reply was actually appended.
            val staleMessage = ChatMessage(
                id = "msg_stale",
                sender = ChatSender.SAFEDRIVE,
                text = "Đây là câu trả lời CŨ, không liên quan gì tới câu hỏi mới",
                timestampMs = 0L,
            )
            val scenario = buildScenario(turnScope = this, voiceScope = backgroundScope, initialMessages = listOf(staleMessage))
            scenario.voiceController.emitFinalTranscript("Kiểm tra nhiệt độ động cơ", screen = "cockpit")
            advanceUntilIdle()

            val outcome = scenario.coordinator.turnOutcome.value
            assertThat(outcome).isInstanceOf(VoiceTurnOutcome.Success::class.java)
            assertThat((outcome as VoiceTurnOutcome.Success).replyText).isNotEqualTo(staleMessage.text)
        }

    @Test
    fun `a text turn submitted immediately after a voice turn completes never overwrites the voice turn's own outcome`() =
        runTest(mainDispatcherRule.dispatcher) {
            val scenario = buildScenario(turnScope = this, voiceScope = backgroundScope)
            scenario.voiceController.emitFinalTranscript("Kiểm tra nhiệt độ động cơ", screen = "cockpit")
            advanceUntilIdle()
            val voiceOutcome = scenario.coordinator.turnOutcome.value
            assertThat(voiceOutcome).isInstanceOf(VoiceTurnOutcome.Success::class.java)

            // A second, unrelated text turn starts and completes right after the voice turn — single
            // flight guarantees no overlap, but the fix must also hold up structurally: nothing in
            // VoiceAssistantCoordinator re-touches turnOutcome for a turn it didn't itself start.
            scenario.turnCoordinator.submit("Một câu hỏi khác bằng text", vn.edu.haui.hvs.safedrive.core.model.AssistantTurnSource.TEXT, "assistant")
            advanceUntilIdle()

            assertThat(scenario.coordinator.turnOutcome.value).isEqualTo(voiceOutcome)
        }

    @Test
    fun `real-thread concurrency - a slow voice turn's outcome correlates correctly even with a concurrent rejected text submit`() {
        val slowGateway = object : SafeDriveGateway by gateway {
            override suspend fun queryAssistant(
                request: vn.edu.haui.hvs.safedrive.core.model.AssistantQueryRequest,
            ): vn.edu.haui.hvs.safedrive.core.common.GatewayResult<vn.edu.haui.hvs.safedrive.core.model.AssistantQueryResult> {
                delay(300L)
                return gateway.queryAssistant(request)
            }
        }
        val realScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val scenario = buildScenario(turnScope = realScope, voiceScope = realScope, gatewayOverride = slowGateway)
            scenario.voiceController.emitFinalTranscript("Kiểm tra nhiệt độ động cơ", screen = "cockpit")

            // While the voice turn is genuinely in flight (a real 300ms delay on a real dispatcher),
            // attempt a concurrent text submit from this thread — must be rejected by global
            // single-flight, never allowed to race the voice turn's own correlation.
            Thread.sleep(50)
            val textAccepted = scenario.turnCoordinator.submit(
                "Câu hỏi text đồng thời",
                vn.edu.haui.hvs.safedrive.core.model.AssistantTurnSource.TEXT,
                "assistant",
            )
            assertThat(textAccepted).isFalse()

            val deadline = System.currentTimeMillis() + 5_000
            while (scenario.coordinator.turnOutcome.value == null && System.currentTimeMillis() < deadline) {
                Thread.sleep(20)
            }
            assertThat(scenario.coordinator.turnOutcome.value).isInstanceOf(VoiceTurnOutcome.Success::class.java)
        } finally {
            realScope.cancel()
        }
    }

    // --- Blocker 2: start() must be thread-safe. The old guard (`if (collectorStarted) return;
    // collectorStarted = true`) is a plain, non-atomic read-then-write: two OS threads calling start()
    // at the same instant could both observe `false` before either writes `true`, and both would then
    // register a collector on voiceController.events. This does not rely on `events` being
    // Channel-backed (which only guarantees a given *value* reaches one collector, not that only one
    // collector is ever registered) — it directly instruments the Flow to count how many times
    // `collect` is actually entered. ---

    @Test
    fun `start() called concurrently from 32 real threads registers exactly one collector on voiceController events`() {
        val realScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val subscriptionCount = AtomicInteger(0)
            val baseVoiceController = FakeVoiceController()
            // Wraps events with onStart {} — fires exactly once per independent collect() invocation,
            // giving a direct, Channel-semantics-independent count of how many collectors ever attached.
            val instrumentedVoiceController = object : VoiceController by baseVoiceController {
                override val events: Flow<VoiceInputEvent> = baseVoiceController.events.onStart { subscriptionCount.incrementAndGet() }
            }
            val appPreferences = MutableStateFlow(AppPreferences())
            val sessionCoordinator = SessionCoordinator(fakeProvider { gateway }, appPreferences, idGenerator, clock, "test")
            val conversationRepository = InMemoryConversationRepository(emptyList())
            val turnCoordinator = AssistantTurnCoordinator(
                conversationRepository = conversationRepository,
                assistantQueryUseCase = AssistantQueryUseCase(sessionCoordinator, clock),
                cockpitSnapshot = cockpitSnapshot,
                appPreferences = appPreferences,
                ttsController = FakeTtsController(),
                metricsRecorder = AssistantTurnMetricsRecorder(),
                lastHealthStatus = MutableStateFlow(null),
                idGenerator = idGenerator,
                clock = clock,
                externalScope = realScope,
            )
            val coordinator = VoiceAssistantCoordinator(
                voiceController = instrumentedVoiceController,
                assistantTurnCoordinator = turnCoordinator,
                emergencyRepository = FakeEmergencyRepository(),
                ttsController = FakeTtsController(),
                externalScope = realScope,
            )

            val threadCount = 32
            val barrier = CyclicBarrier(threadCount)
            val threads = (1..threadCount).map {
                Thread { barrier.await(); coordinator.start() }
            }
            threads.forEach { it.start() }
            threads.forEach { it.join(10_000) }

            // Give the (at most one) launched collector coroutine a moment to actually reach onStart{}.
            val subscribeDeadline = System.currentTimeMillis() + 5_000
            while (subscriptionCount.get() == 0 && System.currentTimeMillis() < subscribeDeadline) Thread.sleep(10)

            assertThat(subscriptionCount.get()).isEqualTo(1)

            // Route one event through and confirm it produces exactly one exchange — not proof by
            // itself (a Channel would only deliver a given value once regardless), but a sanity check
            // that the single surviving collector is actually wired up and working end to end.
            baseVoiceController.emitFinalTranscript("Kiểm tra nhiệt độ động cơ", screen = "cockpit")
            val turnDeadline = System.currentTimeMillis() + 5_000
            while (conversationRepository.state.value.messages.size < 2 && System.currentTimeMillis() < turnDeadline) {
                Thread.sleep(10)
            }
            assertThat(conversationRepository.state.value.messages).hasSize(2)
        } finally {
            realScope.cancel()
        }
    }

    @Test
    fun `start() called twice sequentially never registers a second collector (direct subscription count, not inferred)`() =
        runTest(mainDispatcherRule.dispatcher) {
            val subscriptionCount = AtomicInteger(0)
            val baseVoiceController = FakeVoiceController()
            val instrumentedVoiceController = object : VoiceController by baseVoiceController {
                override val events: Flow<VoiceInputEvent> = baseVoiceController.events.onStart { subscriptionCount.incrementAndGet() }
            }
            val appPreferences = MutableStateFlow(AppPreferences())
            val sessionCoordinator = SessionCoordinator(fakeProvider { gateway }, appPreferences, idGenerator, clock, "test")
            val turnCoordinator = AssistantTurnCoordinator(
                conversationRepository = InMemoryConversationRepository(emptyList()),
                assistantQueryUseCase = AssistantQueryUseCase(sessionCoordinator, clock),
                cockpitSnapshot = cockpitSnapshot,
                appPreferences = appPreferences,
                ttsController = FakeTtsController(),
                metricsRecorder = AssistantTurnMetricsRecorder(),
                lastHealthStatus = MutableStateFlow(null),
                idGenerator = idGenerator,
                clock = clock,
                externalScope = this,
            )
            // The voice-event collector launched by start() runs for the coordinator's whole lifetime
            // (it never completes on its own) — backgroundScope is the TestScope-provided scope meant
            // for exactly this: runTest cancels its children automatically at the end instead of
            // failing with UncompletedCoroutinesError, matching every other scenario in this file.
            val coordinator = VoiceAssistantCoordinator(
                voiceController = instrumentedVoiceController,
                assistantTurnCoordinator = turnCoordinator,
                emergencyRepository = FakeEmergencyRepository(),
                ttsController = FakeTtsController(),
                externalScope = backgroundScope,
            )

            coordinator.start()
            coordinator.start()
            coordinator.start()
            advanceUntilIdle()

            assertThat(subscriptionCount.get()).isEqualTo(1)
        }

    // --- Independent re-audit follow-up (second pass), blocker 3: the existing "a text turn submitted
    // immediately after a voice turn completes never overwrites the voice turn's own outcome" test above
    // only ever exercises the *boring* interleaving — it fully drains the voice turn (including its
    // completion-awaiting consumer coroutine) via advanceUntilIdle() *before* the text turn is even
    // submitted, so it can never catch a bug where the consumer reads stale/wrong data. The actual
    // hazard this class's design must survive is: the voice turn's underlying Deferred resolves, but the
    // coroutine that *reads* it (VoiceAssistantCoordinator.route()'s `externalScope.launch {
    // turn.completion.await() ... }`) has not been scheduled to run yet — and a *different*, later turn
    // gets a chance to overwrite ConversationRepository in that gap. The tests below force exactly that
    // gap deterministically: the voice coordinator's externalScope runs on its own, independently-pumped
    // StandardTestDispatcher, completely decoupled from the scheduler that drives the actual turn/network
    // work — so the consumer coroutine can be proven to sit fully resolved-but-unread across an
    // intervening turn, then finally catch up, with no timing luck involved. ---

    @Test
    fun `a text turn completing while the voice completion consumer has not yet run never overwrites the voice turn's own outcome`() =
        runTest(mainDispatcherRule.dispatcher) {
            // voiceScope is deliberately NOT backgroundScope/this here: a separate, private
            // TestCoroutineScheduler means pumping `this` (turnScope) never has any side effect on what
            // has or hasn't run on voiceScope, and vice versa — the two are only ever advanced by an
            // explicit call naming the one meant to move.
            //
            // The turn's own network call is gated behind a plain CompletableDeferred rather than left
            // to the default (instant) mock gateway: mainDispatcherRule.dispatcher is an
            // UnconfinedTestDispatcher, under which turnScope's launch{} runs its body *inline*,
            // synchronously, on whatever thread called it — including a thread that is itself in the
            // middle of pumping voiceDispatcher's scheduler. Without a real suspension point, the whole
            // turn (and therefore its Deferred) would resolve synchronously within the very first pump
            // below, leaving no window to observe "resolved but not yet read" at all.
            //
            // voiceDispatcher is built with its own brand-new TestCoroutineScheduler, not the zero-arg
            // StandardTestDispatcher() default: kotlinx-coroutines-test's no-arg TestDispatcher factories
            // silently reuse whatever scheduler is currently installed via Dispatchers.setMain (which
            // MainDispatcherRule has already done for mainDispatcherRule.dispatcher) — using that default
            // here would make voiceDispatcher secretly share turnScope's own clock, defeating the whole
            // point of pumping them independently.
            val voiceDispatcher = StandardTestDispatcher(TestCoroutineScheduler())
            val voiceScope = CoroutineScope(voiceDispatcher)
            val queryGate = CompletableDeferred<Unit>()
            val gatedGateway = object : SafeDriveGateway by gateway {
                override suspend fun queryAssistant(
                    request: vn.edu.haui.hvs.safedrive.core.model.AssistantQueryRequest,
                ): vn.edu.haui.hvs.safedrive.core.common.GatewayResult<vn.edu.haui.hvs.safedrive.core.model.AssistantQueryResult> {
                    queryGate.await()
                    return gateway.queryAssistant(request)
                }
            }
            val scenario = buildScenario(turnScope = this, voiceScope = voiceScope, gatewayOverride = gatedGateway)

            scenario.voiceController.emitFinalTranscript("Kiểm tra nhiệt độ động cơ", screen = "cockpit")
            // Runs VoiceAssistantCoordinator's onEach(::route) collector far enough to accept the turn
            // (submit() is synchronous — InFlightAssistantTurn is already published) and to launch its
            // completion-awaiting consumer, which immediately parks on turn.completion.await() (the
            // underlying network call is itself parked on queryGate, still open). The consumer's own
            // launch{} lands on voiceDispatcher (a genuine StandardTestDispatcher, never inline), so it
            // sits enqueued-but-unrun there regardless of what runs inline on turnScope.
            voiceDispatcher.scheduler.advanceUntilIdle()
            assertThat(scenario.conversationRepository.state.value.inFlightTurn).isNotNull()
            assertThat(scenario.coordinator.turnOutcome.value).isNull()

            // Resolves the gate — since turnScope is Unconfined, the rest of the turn (session already
            // resolved, gateway call returns, completeSuccess, completion.complete()) runs inline,
            // synchronously, as part of this very call; no advanceUntilIdle() on turnScope is needed.
            queryGate.complete(Unit)
            val voiceTurnState = scenario.conversationRepository.state.value.turnState
            assertThat(voiceTurnState).isInstanceOf(AssistantTurnState.Success::class.java)
            val voiceReplyText = (voiceTurnState as AssistantTurnState.Success).reply.text
            // The voice turn's own Deferred is resolved right now — but its consumer coroutine is still
            // sitting unread on voiceDispatcher's own, still-unpumped scheduler.
            assertThat(scenario.coordinator.turnOutcome.value).isNull()
            assertThat(scenario.voiceController.clearProcessingStateCallCount).isEqualTo(0)

            // A second, unrelated TEXT turn now starts and completes entirely on turnScope, overwriting
            // ConversationRepository's turnState/messages — nothing about the voice turn remains
            // observable there anymore.
            scenario.turnCoordinator.submit("Một câu hỏi khác bằng text", AssistantTurnSource.TEXT, "assistant")
            advanceUntilIdle()
            val textTurnState = scenario.conversationRepository.state.value.turnState
            assertThat(textTurnState).isInstanceOf(AssistantTurnState.Success::class.java)
            assertThat((textTurnState as AssistantTurnState.Success).reply.text).isNotEqualTo(voiceReplyText)

            // Only now does the voice consumer coroutine actually get to run and read `completion` —
            // which, being a per-turn Deferred rather than a re-read of ConversationRepository's
            // now-overwritten state, still resolves to the voice turn's own value regardless.
            voiceDispatcher.scheduler.advanceUntilIdle()

            val outcome = scenario.coordinator.turnOutcome.value
            assertThat(outcome).isInstanceOf(VoiceTurnOutcome.Success::class.java)
            assertThat((outcome as VoiceTurnOutcome.Success).replyText).isEqualTo(voiceReplyText)
            // Exactly once — never skipped, never doubled by the intervening text turn.
            assertThat(scenario.voiceController.clearProcessingStateCallCount).isEqualTo(1)
        }

    // --- Independent re-audit follow-up (second pass), item D: FakeVoiceController.emitFinalTranscript
    // now mirrors AndroidSpeechRecognizerController's real onResults() by transitioning to PROCESSING
    // before routing (previously it left state untouched, which hid a real production gap:
    // VoiceController.clearProcessingState() unconditionally flips PROCESSING -> IDLE with no notion of
    // *whose* turn it belongs to). Chosen contract: an accepted voice turn owns Processing until its own
    // terminal state; a voice event rejected by single-flight only clears Processing itself if no voice
    // turn of this coordinator's own is still legitimately in flight (VoiceAssistantCoordinator's new
    // voiceTurnInFlight flag) — otherwise it must leave the accepted turn's own indication alone. ---

    @Test
    fun `a voice event rejected by single-flight while a prior voice turn is genuinely in flight never clears the accepted turn's own Processing state early`() =
        runTest(mainDispatcherRule.dispatcher) {
            val slowGateway = object : SafeDriveGateway by gateway {
                override suspend fun queryAssistant(
                    request: vn.edu.haui.hvs.safedrive.core.model.AssistantQueryRequest,
                ): vn.edu.haui.hvs.safedrive.core.common.GatewayResult<vn.edu.haui.hvs.safedrive.core.model.AssistantQueryResult> {
                    delay(200L)
                    return gateway.queryAssistant(request)
                }
            }
            val scenario = buildScenario(turnScope = this, voiceScope = backgroundScope, gatewayOverride = slowGateway)

            // First voice event: accepted, genuinely in flight (parked on the 200ms delay). The fake now
            // mirrors production by transitioning to PROCESSING as part of emitting the transcript.
            scenario.voiceController.emitFinalTranscript("Câu hỏi đầu tiên", screen = "cockpit")
            assertThat(scenario.conversationRepository.state.value.inFlightTurn).isNotNull()
            assertThat(scenario.voiceController.state.value.state).isEqualTo(VoiceState.PROCESSING)

            // Second voice event arrives while the first is still in flight — global single-flight must
            // reject it. The rejection must NOT call clearProcessingState(): doing so would prematurely
            // end turn 1's own still-legitimate Processing indication, even though nothing about turn 1
            // itself changed — turn 1's own eventual completion is the only thing allowed to clear it.
            scenario.voiceController.emitFinalTranscript("Câu hỏi thứ hai", screen = "cockpit")
            assertThat(scenario.voiceController.state.value.state).isEqualTo(VoiceState.PROCESSING)
            assertThat(scenario.voiceController.clearProcessingStateCallCount).isEqualTo(0)

            advanceUntilIdle() // let the first (accepted) turn's delay elapse and complete for real

            val outcome = scenario.coordinator.turnOutcome.value
            assertThat(outcome).isInstanceOf(VoiceTurnOutcome.Success::class.java)
            assertThat((outcome as VoiceTurnOutcome.Success).replyText).isNotEmpty()
            // Only the accepted turn's own real completion ever clears it, exactly once.
            assertThat(scenario.voiceController.clearProcessingStateCallCount).isEqualTo(1)
            assertThat(scenario.voiceController.state.value.state).isEqualTo(VoiceState.IDLE)
        }

    @Test
    fun `a voice event rejected by single-flight while a text turn (not a voice turn) is what's busy still clears Processing itself`() =
        runTest(mainDispatcherRule.dispatcher) {
            val queryGate = CompletableDeferred<Unit>()
            val gatedGateway = object : SafeDriveGateway by gateway {
                override suspend fun queryAssistant(
                    request: vn.edu.haui.hvs.safedrive.core.model.AssistantQueryRequest,
                ): vn.edu.haui.hvs.safedrive.core.common.GatewayResult<vn.edu.haui.hvs.safedrive.core.model.AssistantQueryResult> {
                    queryGate.await()
                    return gateway.queryAssistant(request)
                }
            }
            val scenario = buildScenario(turnScope = this, voiceScope = backgroundScope, gatewayOverride = gatedGateway)

            // A TEXT turn (not routed through VoiceAssistantCoordinator at all) is what's genuinely busy.
            scenario.turnCoordinator.submit("Một câu hỏi bằng text", AssistantTurnSource.TEXT, "assistant")
            assertThat(scenario.conversationRepository.state.value.inFlightTurn).isNotNull()

            // A voice event now arrives and is rejected by the same global single-flight — but
            // VoiceAssistantCoordinator itself has no accepted voice turn in flight (voiceTurnInFlight is
            // false), so nothing else will ever clear this mic-side Processing indication except this
            // rejection itself.
            scenario.voiceController.emitFinalTranscript("Câu hỏi bằng giọng nói", screen = "cockpit")
            assertThat(scenario.voiceController.clearProcessingStateCallCount).isEqualTo(1)
            assertThat(scenario.voiceController.state.value.state).isEqualTo(VoiceState.IDLE)

            queryGate.complete(Unit)
            advanceUntilIdle()
            assertThat(scenario.coordinator.turnOutcome.value).isNull() // the voice event was never accepted
        }

    // --- Independent re-audit follow-up (second pass), item A: a synchronously-completed health-blocked
    // voice turn's outcome must survive a later, overwriting text turn. Note on reproducibility: unlike
    // the async (gated-gateway) overwrite test above, this scenario has *no* real suspension point
    // anywhere between "health-blocked Failure detected" and "the consumer coroutine reads it" — both
    // AssistantTurnCoordinator.submit()'s health-blocked branch and VoiceAssistantCoordinator.route()
    // itself run fully synchronously with no `await`/`delay` in between, so there is no test-controllable
    // gap to force "resolved but not yet read" into, the way the async case's CompletableDeferred gate
    // does. Forcing one would require a production-only test hook with no corresponding real hazard —
    // this test instead proves the property that *is* real and testable: the Deferred correctly survives
    // being overwritten by a later, different turn, exercised through the full VoiceAssistantCoordinator
    // pipeline (route/turnOutcome/clearProcessingState), not just at the AssistantTurnCoordinator/Deferred
    // level (already covered by AssistantTurnCoordinatorTest's own health-blocked overwrite test). ---

    @Test
    fun `a synchronously-completed health-blocked voice turn's Failure outcome survives being overwritten by a later successful text turn`() =
        runTest(mainDispatcherRule.dispatcher) {
            val healthFlow = MutableStateFlow<HealthStatus?>(
                HealthStatus(
                    status = SystemConnectionStatus.NORMAL,
                    serviceName = "test-backend",
                    apiVersion = "v1",
                    serverTimeMs = clock.nowMs(),
                    capabilities = HealthCapabilities(assistant = false, emergencySimulation = true, cockpitStream = false),
                ),
            )
            val scenario = buildScenario(turnScope = this, voiceScope = backgroundScope, lastHealthStatus = healthFlow)

            scenario.voiceController.emitFinalTranscript("Xe có lỗi gì?", screen = "cockpit")
            advanceUntilIdle() // health-blocked resolves synchronously — nothing left to advance, in practice
            val outcome = scenario.coordinator.turnOutcome.value
            assertThat(outcome).isInstanceOf(VoiceTurnOutcome.Failure::class.java)
            val blockedMessage = (outcome as VoiceTurnOutcome.Failure).errorMessage
            assertThat(scenario.voiceController.clearProcessingStateCallCount).isEqualTo(1)

            // Capability recovers, then a fully separate, later TEXT turn succeeds and overwrites
            // ConversationRepository entirely.
            healthFlow.value = null
            scenario.turnCoordinator.submit("Một câu hỏi khác bằng text", AssistantTurnSource.TEXT, "assistant")
            advanceUntilIdle()
            assertThat(scenario.conversationRepository.state.value.turnState).isInstanceOf(AssistantTurnState.Success::class.java)

            // The voice turn's own outcome, set once and never touched by the unrelated text turn.
            assertThat(scenario.coordinator.turnOutcome.value).isEqualTo(VoiceTurnOutcome.Failure(blockedMessage))
            assertThat(scenario.voiceController.clearProcessingStateCallCount).isEqualTo(1) // never a second, spurious clear
        }

    // --- Independent re-audit follow-up (second pass), items B & C: an exception anywhere in the query
    // pipeline (RuntimeException, or a CancellationException not originating from cancelCurrent()) must
    // reach the *full* voice pipeline correctly — never a hang, never a stuck PROCESSING, exactly one
    // clearProcessingState() call. Exercises AssistantTurnCoordinator's blocker-1 exception-safety and
    // second-pass cancellation safety net end to end through VoiceAssistantCoordinator, not just at the
    // AssistantTurnCoordinator/Deferred level. ---

    @Test
    fun `a gateway RuntimeException reaches the voice pipeline as a Failure outcome, never hangs, clears processing exactly once`() =
        runTest(mainDispatcherRule.dispatcher) {
            val throwingGateway = object : SafeDriveGateway by gateway {
                override suspend fun queryAssistant(request: AssistantQueryRequest): GatewayResult<AssistantQueryResult> {
                    throw RuntimeException("boom - simulated gateway bug")
                }
            }
            val scenario = buildScenario(turnScope = this, voiceScope = backgroundScope, gatewayOverride = throwingGateway)

            scenario.voiceController.emitFinalTranscript("Xe có lỗi gì?", screen = "cockpit")
            advanceUntilIdle()

            assertThat(scenario.conversationRepository.state.value.turnState).isInstanceOf(AssistantTurnState.Failure::class.java)
            assertThat((scenario.conversationRepository.state.value.turnState as AssistantTurnState.Failure).error)
                .isInstanceOf(GatewayError.Unexpected::class.java)
            assertThat(scenario.conversationRepository.state.value.inFlightTurn).isNull()
            val outcome = scenario.coordinator.turnOutcome.value
            assertThat(outcome).isInstanceOf(VoiceTurnOutcome.Failure::class.java)
            assertThat((outcome as VoiceTurnOutcome.Failure).errorMessage).isNotEmpty()
            assertThat(scenario.voiceController.clearProcessingStateCallCount).isEqualTo(1)
        }

    @Test
    fun `a gateway CancellationException (not via cancelCurrent) reaches the voice pipeline with no stuck Processing and no Success or Failure outcome`() =
        runTest(mainDispatcherRule.dispatcher) {
            val cancellingGateway = object : SafeDriveGateway by gateway {
                override suspend fun queryAssistant(request: AssistantQueryRequest): GatewayResult<AssistantQueryResult> {
                    throw CancellationException("gateway self-cancelled for an unrelated internal reason")
                }
            }
            val scenario = buildScenario(turnScope = this, voiceScope = backgroundScope, gatewayOverride = cancellingGateway)

            scenario.voiceController.emitFinalTranscript("Xe có lỗi gì?", screen = "cockpit")
            advanceUntilIdle()

            assertThat(scenario.conversationRepository.state.value.turnState).isInstanceOf(AssistantTurnState.Cancelled::class.java)
            assertThat(scenario.conversationRepository.state.value.inFlightTurn).isNull()
            // Cancelled must never surface as a Success/Failure voice outcome — matches the existing
            // user-initiated-cancel contract ("a cancelled voice turn publishes no turnOutcome at all").
            assertThat(scenario.coordinator.turnOutcome.value).isNull()
            assertThat(scenario.voiceController.state.value.state).isEqualTo(VoiceState.IDLE)
            assertThat(scenario.voiceController.clearProcessingStateCallCount).isEqualTo(1)
        }

    // --- Independent re-audit follow-up, sixth pass: the ABA race. The fifth/sixth-pass `AtomicBoolean
    // voiceTurnInFlight` tracked only *whether* some voice turn was in flight, not *which* one. This let a
    // first voice turn's own completion consumer, running late — after AssistantTurnCoordinator's global
    // single-flight had already freed up and a *second* voice turn had been accepted — incorrectly steal
    // the second turn's Processing indicator and/or publish its own stale outcome over the second turn's.
    // `VoiceTurnOwner` (a per-turn identity token, compared by reference under a single lock shared by
    // every ownership-affecting operation) closes this: a late consumer can tell it is no longer the
    // current owner and becomes a safe no-op. Every test below is fully deterministic — real enqueue-order
    // control on independently-pumped TestCoroutineSchedulers, never Thread.sleep/timing luck (except the
    // supplementary real-thread stress test at the very end, which is explicitly allowed to poll). ---

    @Test
    fun `ABA race Success - turn A's stale completion never overwrites turn B's Processing, outcome or owner`() =
        runTest(mainDispatcherRule.dispatcher) {
            var callCount = 0
            val queryGateA = CompletableDeferred<Unit>()
            val queryGateB = CompletableDeferred<Unit>()
            val gw = object : SafeDriveGateway by gateway {
                override suspend fun queryAssistant(request: AssistantQueryRequest): GatewayResult<AssistantQueryResult> {
                    if (callCount++ == 0) queryGateA.await() else queryGateB.await()
                    return gateway.queryAssistant(request)
                }
            }
            val voiceDispatcher = StandardTestDispatcher(TestCoroutineScheduler())
            val voiceScope = CoroutineScope(voiceDispatcher)
            val scenario = buildScenario(turnScope = this, voiceScope = voiceScope, gatewayOverride = gw)

            // Turn A: accepted, genuinely in flight (parked on queryGateA). Its own completion consumer
            // is launched and immediately parks on turn.completion.await() — A's Deferred is not resolved
            // yet.
            scenario.voiceController.emitFinalTranscript("Câu hỏi A duy nhất", screen = "cockpit")
            voiceDispatcher.scheduler.advanceUntilIdle()
            assertThat(scenario.conversationRepository.state.value.inFlightTurn).isNotNull()

            // Turn B's transcript is queued into the channel *now*, while the collector is idle waiting
            // for the next event — this enqueues "resume collector, process B" onto voiceDispatcher's
            // scheduler *before* anything related to A's resolution exists there.
            scenario.voiceController.emitFinalTranscript("Câu hỏi B duy nhất", screen = "cockpit")

            // Resolves A's gateway call inline (turnScope is Unconfined, from mainDispatcherRule) —
            // completes A's turn for real (completeSuccess, completion.complete(SuccessA)) and, as a side
            // effect, resumes A's own voice-consumer's turn.completion.await() — which enqueues onto
            // voiceDispatcher's scheduler *after* B's already-queued task above, since this line runs
            // after the emit above.
            queryGateA.complete(Unit)
            val replyA = (scenario.conversationRepository.state.value.turnState as AssistantTurnState.Success).reply.text

            // Draining the queue now runs, in enqueue order: (1) B's route() — single-flight is already
            // free (A completed above), so B is accepted and claims ownership; (2) A's now-late consumer —
            // finds it is no longer the owner and becomes a stale no-op.
            voiceDispatcher.scheduler.advanceUntilIdle()

            assertThat(scenario.conversationRepository.state.value.inFlightTurn).isNotNull() // B genuinely in flight
            assertThat(scenario.voiceController.state.value.state).isEqualTo(VoiceState.PROCESSING)
            assertThat(scenario.voiceController.clearProcessingStateCallCount).isEqualTo(0) // A's stale consumer never cleared it
            assertThat(scenario.coordinator.turnOutcome.value).isNull() // A's stale Success was never published

            // Now let B's own gateway call resolve for real and its consumer run.
            queryGateB.complete(Unit)
            val replyB = (scenario.conversationRepository.state.value.turnState as AssistantTurnState.Success).reply.text
            assertThat(replyB).isNotEqualTo(replyA)
            voiceDispatcher.scheduler.advanceUntilIdle()

            val outcome = scenario.coordinator.turnOutcome.value
            assertThat(outcome).isInstanceOf(VoiceTurnOutcome.Success::class.java)
            assertThat((outcome as VoiceTurnOutcome.Success).replyText).isEqualTo(replyB)
            assertThat(scenario.voiceController.state.value.state).isEqualTo(VoiceState.IDLE)
            // Exactly once, total — from B's own completion, never from A's stale one.
            assertThat(scenario.voiceController.clearProcessingStateCallCount).isEqualTo(1)
        }

    @Test
    fun `ABA race Failure - turn A's stale completion never overwrites turn B's own outcome`() =
        runTest(mainDispatcherRule.dispatcher) {
            var callCount = 0
            val queryGateA = CompletableDeferred<Unit>()
            val queryGateB = CompletableDeferred<Unit>()
            val gw = object : SafeDriveGateway by gateway {
                override suspend fun queryAssistant(request: AssistantQueryRequest): GatewayResult<AssistantQueryResult> {
                    if (callCount++ == 0) {
                        queryGateA.await()
                        return GatewayResult.Failure(GatewayError.Timeout)
                    }
                    queryGateB.await()
                    return gateway.queryAssistant(request)
                }
            }
            val voiceDispatcher = StandardTestDispatcher(TestCoroutineScheduler())
            val voiceScope = CoroutineScope(voiceDispatcher)
            val scenario = buildScenario(turnScope = this, voiceScope = voiceScope, gatewayOverride = gw)

            scenario.voiceController.emitFinalTranscript("Câu hỏi A sẽ thất bại", screen = "cockpit")
            voiceDispatcher.scheduler.advanceUntilIdle()
            assertThat(scenario.conversationRepository.state.value.inFlightTurn).isNotNull()

            scenario.voiceController.emitFinalTranscript("Câu hỏi B sẽ thành công", screen = "cockpit")
            queryGateA.complete(Unit) // A resolves to Failure, freeing single-flight
            voiceDispatcher.scheduler.advanceUntilIdle() // B claims ownership; A's stale consumer is a no-op

            assertThat(scenario.conversationRepository.state.value.inFlightTurn).isNotNull() // B genuinely in flight
            assertThat(scenario.coordinator.turnOutcome.value).isNull() // A's stale Failure was never published
            assertThat(scenario.voiceController.clearProcessingStateCallCount).isEqualTo(0)

            queryGateB.complete(Unit)
            voiceDispatcher.scheduler.advanceUntilIdle()

            val outcome = scenario.coordinator.turnOutcome.value
            assertThat(outcome).isInstanceOf(VoiceTurnOutcome.Success::class.java) // B's own outcome, never A's stale Failure
            assertThat(scenario.voiceController.clearProcessingStateCallCount).isEqualTo(1)
            assertThat(scenario.voiceController.state.value.state).isEqualTo(VoiceState.IDLE)
        }

    @Test
    fun `ABA race Cancelled - turn A cancelled after turn B owns never clears turn B's Processing or owner`() =
        runTest(mainDispatcherRule.dispatcher) {
            var callCount = 0
            val queryGateA = CompletableDeferred<Unit>() // never completed — cancelCurrent() cancels this coroutine instead
            val queryGateB = CompletableDeferred<Unit>()
            val gw = object : SafeDriveGateway by gateway {
                override suspend fun queryAssistant(request: AssistantQueryRequest): GatewayResult<AssistantQueryResult> {
                    if (callCount++ == 0) queryGateA.await() else queryGateB.await()
                    return gateway.queryAssistant(request)
                }
            }
            val voiceDispatcher = StandardTestDispatcher(TestCoroutineScheduler())
            val voiceScope = CoroutineScope(voiceDispatcher)
            val scenario = buildScenario(turnScope = this, voiceScope = voiceScope, gatewayOverride = gw)

            scenario.voiceController.emitFinalTranscript("Câu hỏi A sẽ bị hủy", screen = "cockpit")
            voiceDispatcher.scheduler.advanceUntilIdle()
            assertThat(scenario.conversationRepository.state.value.inFlightTurn).isNotNull()

            // B's route() is enqueued onto voiceDispatcher's scheduler *before* cancelCurrent() runs, so it
            // drains first once we pump — identical enqueue-order technique to the Success/Failure variants
            // above, just triggered by cancelCurrent() instead of a resolved gateway call.
            scenario.voiceController.emitFinalTranscript("Câu hỏi B", screen = "cockpit")
            scenario.turnCoordinator.cancelCurrent() // synchronously resolves A to Cancelled, frees single-flight
            voiceDispatcher.scheduler.advanceUntilIdle() // B claims ownership; A's stale (Cancelled) consumer is a no-op

            assertThat(scenario.conversationRepository.state.value.inFlightTurn).isNotNull() // B genuinely in flight
            assertThat(scenario.coordinator.turnOutcome.value).isNull()
            assertThat(scenario.voiceController.clearProcessingStateCallCount).isEqualTo(0)
            assertThat(scenario.voiceController.state.value.state).isEqualTo(VoiceState.PROCESSING)

            queryGateB.complete(Unit)
            voiceDispatcher.scheduler.advanceUntilIdle()

            val outcome = scenario.coordinator.turnOutcome.value
            assertThat(outcome).isInstanceOf(VoiceTurnOutcome.Success::class.java)
            assertThat(scenario.voiceController.clearProcessingStateCallCount).isEqualTo(1)
            assertThat(scenario.voiceController.state.value.state).isEqualTo(VoiceState.IDLE)
        }

    @Test
    fun `ABA race health-blocked - turn A's late consumer on its own dispatcher is a stale no-op once turn B owns`() =
        runTest(mainDispatcherRule.dispatcher) {
            val healthFlow = MutableStateFlow<HealthStatus?>(
                HealthStatus(
                    status = SystemConnectionStatus.NORMAL,
                    serviceName = "test-backend",
                    apiVersion = "v1",
                    serverTimeMs = clock.nowMs(),
                    capabilities = HealthCapabilities(assistant = false, emergencySimulation = true, cockpitStream = false),
                ),
            )
            // The collector (route()) runs on voiceScope; each turn's own completion consumer runs on a
            // SEPARATE completionScope — necessary here (unlike the gated-gateway variants above) because
            // the health-blocked path resolves turn.completion synchronously, inside submit() itself: there
            // is no suspension point anywhere to hang an "accepted but not yet read" gap on the way an
            // in-flight network call does. Decoupling which dispatcher runs the completion consumer from
            // which dispatcher runs route() itself creates exactly that gap, deterministically, instead of
            // requiring a production-only test hook or declaring the gap untestable.
            val voiceDispatcher = StandardTestDispatcher(TestCoroutineScheduler())
            val voiceScope = CoroutineScope(voiceDispatcher)
            val completionDispatcher = StandardTestDispatcher(TestCoroutineScheduler())
            val turnCompletionScope = CoroutineScope(completionDispatcher)
            val scenario = buildScenario(
                turnScope = this,
                voiceScope = voiceScope,
                lastHealthStatus = healthFlow,
                voiceCompletionScope = turnCompletionScope,
            )

            // Turn A: health-blocked, resolves to Failure synchronously inside submit() — but its
            // completion consumer, launched onto completionDispatcher, has not been pumped yet, so it has
            // not run: nothing has been published, nothing cleared.
            scenario.voiceController.emitFinalTranscript("Câu hỏi A bị chặn", screen = "cockpit")
            voiceDispatcher.scheduler.advanceUntilIdle()
            assertThat(scenario.coordinator.turnOutcome.value).isNull()
            assertThat(scenario.voiceController.clearProcessingStateCallCount).isEqualTo(0)

            // Health capability recovers; turn B is accepted (global single-flight was never even occupied
            // by the health-blocked turn A) and claims ownership — its own completion consumer is also
            // enqueued onto completionDispatcher, right after A's, but not yet pumped either.
            healthFlow.value = null
            scenario.voiceController.emitFinalTranscript("Câu hỏi B thành công", screen = "cockpit")
            voiceDispatcher.scheduler.advanceUntilIdle()

            // Neither turn's own completion consumer has run yet — this is the gap being proven.
            assertThat(scenario.coordinator.turnOutcome.value).isNull()
            assertThat(scenario.voiceController.clearProcessingStateCallCount).isEqualTo(0)

            // Drains both pending consumers in enqueue order: A's first — finds it is no longer the owner,
            // becomes a stale no-op; B's second — still genuinely current owner.
            advanceUntilIdle() // let B's real gateway call (on turnScope) fully resolve first
            completionDispatcher.scheduler.advanceUntilIdle()

            val outcome = scenario.coordinator.turnOutcome.value
            assertThat(outcome).isInstanceOf(VoiceTurnOutcome.Success::class.java) // B's own outcome, never A's stale Failure
            assertThat(scenario.voiceController.clearProcessingStateCallCount).isEqualTo(1)
            assertThat(scenario.voiceController.state.value.state).isEqualTo(VoiceState.IDLE)
        }

    // Independent re-audit follow-up, ninth pass (P1-3): this test's own polling condition — not any
    // production code — was diagnosed as the source of a one-off flake (`turnOutcome.value == null` could
    // exit on A's own legitimate, transient outcome before B's route() reset it; see the fix above this
    // test's final wait loop). Now supplementary only — the deterministic test immediately below forces
    // every iteration's exact ordering with zero timing dependency and is the primary proof; this real-
    // thread version is retained for its independent, genuine-OS-scheduling coverage of the same
    // invariant, not as the thing the P1-3 gate requirement depends on.
    @Test
    fun `stress - rapid voice turn pairs on real threads never leak stale state across turns`() {
        val realScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val gates = mutableListOf<CompletableDeferred<Unit>>()
            var callIndex = 0
            // A fresh gate per call, resolved by the test driver in strict order — keeps each turn
            // genuinely suspended until the test explicitly lets it proceed, so the two turns in each pair
            // stay deterministically ordered relative to each other despite running on a real thread pool.
            val gw = object : SafeDriveGateway by gateway {
                override suspend fun queryAssistant(request: AssistantQueryRequest): GatewayResult<AssistantQueryResult> {
                    val gate = synchronized(gates) { gates[callIndex++] }
                    gate.await()
                    return gateway.queryAssistant(request)
                }
            }
            val scenario = buildScenario(turnScope = realScope, voiceScope = realScope, gatewayOverride = gw)

            repeat(20) { iteration ->
                val gateA = CompletableDeferred<Unit>()
                val gateB = CompletableDeferred<Unit>()
                synchronized(gates) {
                    gates.add(gateA)
                    gates.add(gateB)
                }

                scenario.voiceController.emitFinalTranscript("Câu hỏi A vòng $iteration", screen = "cockpit")
                val deadlineA = System.currentTimeMillis() + 5_000
                while (scenario.conversationRepository.state.value.inFlightTurn?.text?.contains("A vòng $iteration") != true &&
                    System.currentTimeMillis() < deadlineA
                ) {
                    Thread.sleep(5)
                }
                assertThat(scenario.conversationRepository.state.value.inFlightTurn?.text).contains("A vòng $iteration")

                gateA.complete(Unit) // frees A's single-flight slot
                val deadlineADone = System.currentTimeMillis() + 5_000
                while (scenario.conversationRepository.state.value.inFlightTurn != null && System.currentTimeMillis() < deadlineADone) {
                    Thread.sleep(5)
                }
                assertThat(scenario.conversationRepository.state.value.inFlightTurn).isNull() // A done at the coordinator level —
                // guarantees B below will be accepted, not rejected by a real-thread scheduling fluke. A's
                // own voice-consumer coroutine (on realScope) racing to check ownership against B's
                // acceptance below remains a genuine, unsynchronized race — exactly what this stress test
                // exercises.

                scenario.voiceController.emitFinalTranscript("Câu hỏi B vòng $iteration", screen = "cockpit")

                val deadlineB = System.currentTimeMillis() + 5_000
                while (scenario.conversationRepository.state.value.inFlightTurn?.text?.contains("B vòng $iteration") != true &&
                    System.currentTimeMillis() < deadlineB
                ) {
                    Thread.sleep(5)
                }
                assertThat(scenario.conversationRepository.state.value.inFlightTurn?.text).contains("B vòng $iteration")

                gateB.complete(Unit)
                val deadlineOutcome = System.currentTimeMillis() + 5_000
                // Waits for B's own, specific outcome — not merely "any non-null value" (independent
                // re-audit follow-up, ninth pass, P1-3 flake diagnosis). A's own completion consumer can
                // legitimately publish A's outcome first if it happens to run before B's route() claims
                // ownership — the pre-claim window itself is correct, already-documented behavior (see
                // the "pre-claim race" tests above) — and B's route() then resets turnOutcome to null
                // before B's own completion later publishes the real value. The old condition
                // (`turnOutcome.value == null`) could exit the moment it sampled that legitimate,
                // transient A-outcome and then fail as if B's outcome were wrong — this was the actual,
                // diagnosed root cause of this test's one-off flake (a race in this test's *own* polling
                // condition, not in production code). Waiting for the specific expected value instead
                // means the loop simply keeps polling straight through that transient state.
                while (
                    (scenario.coordinator.turnOutcome.value as? VoiceTurnOutcome.Success)?.replyText
                        ?.contains("B vòng $iteration") != true &&
                    System.currentTimeMillis() < deadlineOutcome
                ) {
                    Thread.sleep(5)
                }
                val outcome = scenario.coordinator.turnOutcome.value
                assertThat(outcome).isInstanceOf(VoiceTurnOutcome.Success::class.java)
                assertThat((outcome as VoiceTurnOutcome.Success).replyText).contains("B vòng $iteration")
                scenario.coordinator.dismissTurnOutcome()
            }
        } finally {
            realScope.cancel()
        }
    }

    // Independent re-audit follow-up, ninth pass (P1-3): the deterministic replacement for the real-
    // thread stress test above. Instead of hoping a real thread pool happens to interleave A's completion
    // consumer against B's route() acceptance across many iterations, this forces BOTH possible orderings
    // explicitly, every iteration, with zero timing dependency — the same independently-pumped-scheduler
    // technique as the single-shot "pre-claim race" tests above, just repeated across 20 A/B pairs on one
    // long-lived coordinator (so state cannot silently accumulate across iterations either). This is the
    // primary proof for the pre-claim/ABA invariant holding under repeated turns; the real-thread test
    // above is retained only as supplementary, independent coverage.
    @Test
    fun `deterministic stress equivalent - 20 consecutive voice turn pairs with forced ordering never leak stale state`() =
        runTest(mainDispatcherRule.dispatcher) {
            var callCount = 0
            val gates = mutableListOf<CompletableDeferred<Unit>>()
            val gw = object : SafeDriveGateway by gateway {
                override suspend fun queryAssistant(request: AssistantQueryRequest): GatewayResult<AssistantQueryResult> {
                    val gate = gates[callCount++]
                    gate.await()
                    return gateway.queryAssistant(request)
                }
            }
            val voiceDispatcher = StandardTestDispatcher(TestCoroutineScheduler())
            val voiceScope = CoroutineScope(voiceDispatcher)
            val completionDispatcher = StandardTestDispatcher(TestCoroutineScheduler())
            val turnCompletionScope = CoroutineScope(completionDispatcher)
            val scenario = buildScenario(
                turnScope = this,
                voiceScope = voiceScope,
                gatewayOverride = gw,
                voiceCompletionScope = turnCompletionScope,
            )

            repeat(20) { iteration ->
                val gateA = CompletableDeferred<Unit>()
                val gateB = CompletableDeferred<Unit>()
                gates.add(gateA)
                gates.add(gateB)
                val genA = (iteration * 2 + 1) * 100L
                val genB = (iteration * 2 + 2) * 100L

                scenario.voiceController.emitFinalTranscript("A $iteration", screen = "cockpit", generation = genA)
                voiceDispatcher.scheduler.advanceUntilIdle()
                assertThat(scenario.conversationRepository.state.value.inFlightTurn?.text).contains("A $iteration")

                gateA.complete(Unit) // frees A's single-flight slot at the AssistantTurnCoordinator level
                val replyA = (scenario.conversationRepository.state.value.turnState as AssistantTurnState.Success).reply.text

                scenario.voiceController.emitFinalTranscript("B $iteration", screen = "cockpit", generation = genB)

                // Alternates which side runs first across iterations — forcing both orderings the
                // real-thread stress test could only ever hope to eventually hit by chance.
                if (iteration % 2 == 0) {
                    completionDispatcher.scheduler.advanceUntilIdle() // A's consumer runs first
                    voiceDispatcher.scheduler.advanceUntilIdle() // B claims ownership second
                } else {
                    voiceDispatcher.scheduler.advanceUntilIdle() // B claims ownership first
                    completionDispatcher.scheduler.advanceUntilIdle() // A's now-stale consumer second
                }

                // Either order: B is genuinely in flight and A's stale outcome never survives as the
                // final, visible value.
                assertThat(scenario.conversationRepository.state.value.inFlightTurn?.text).contains("B $iteration")
                assertThat(scenario.voiceController.state.value.state).isEqualTo(VoiceState.PROCESSING)
                assertThat(scenario.voiceController.state.value.generation).isEqualTo(genB)

                gateB.complete(Unit)
                val replyB = (scenario.conversationRepository.state.value.turnState as AssistantTurnState.Success).reply.text
                assertThat(replyB).isNotEqualTo(replyA)
                completionDispatcher.scheduler.advanceUntilIdle()
                voiceDispatcher.scheduler.advanceUntilIdle()

                val outcome = scenario.coordinator.turnOutcome.value
                assertThat(outcome).isInstanceOf(VoiceTurnOutcome.Success::class.java)
                assertThat((outcome as VoiceTurnOutcome.Success).replyText).isEqualTo(replyB)
                assertThat(scenario.voiceController.state.value.state).isEqualTo(VoiceState.IDLE)
                scenario.coordinator.dismissTurnOutcome()
            }
        }

    // --- Independent re-audit follow-up, eighth pass: the PRE-CLAIM race. The seventh pass's
    // VoiceTurnOwner/voiceOwnershipLock correctly close the ABA race where a stale turn's completion
    // consumer runs *after* a newer turn has already claimed activeOwner. One race remained *before* that
    // claim: AndroidSpeechRecognizerController.onResults() (and FakeVoiceController.emitFinalTranscript)
    // always publish PROCESSING/generation unconditionally the instant a transcript arrives, independent
    // of whether/when route() ever gets to accept or reject it. If an older, still-genuinely-current-owner
    // turn's completion consumer runs in the window between "a newer session published its own
    // PROCESSING/generation" and "route() actually claims activeOwner for it", the older turn *is still*
    // activeOwner (nothing has superseded it yet at the assistant-turn level) — so under the seventh
    // pass's ownership check alone, its own clearProcessingState call would have gone through and wiped
    // out the newer session's legitimate Processing indication before that newer turn even existed.
    // VoiceInputEvent.generation / VoiceTurnOwner.voiceGeneration close this: clearProcessingState /
    // reassignProcessingOwner are keyed to the exact recognizer generation, never to which turn currently
    // "owns" the slot at the assistant-turn level — so the older turn's clear becomes a no-op the instant
    // the visible generation has already moved on, even while it is still, technically, activeOwner. ---

    @Test
    fun `pre-claim race - A's still-current-owner completion cannot clear B's newer generation before B claims ownership`() =
        runTest(mainDispatcherRule.dispatcher) {
            var callCount = 0
            val queryGateA = CompletableDeferred<Unit>()
            val queryGateB = CompletableDeferred<Unit>()
            val gw = object : SafeDriveGateway by gateway {
                override suspend fun queryAssistant(request: AssistantQueryRequest): GatewayResult<AssistantQueryResult> {
                    if (callCount++ == 0) queryGateA.await() else queryGateB.await()
                    return gateway.queryAssistant(request)
                }
            }
            // Two independently-pumped schedulers: voiceDispatcher drives the collector (route()) calls;
            // completionDispatcher drives each accepted turn's own completion-awaiting consumer. This lets
            // A's consumer be run *before* route(B) ever executes — proving the pre-claim window exists
            // regardless of whether A is still, technically, activeOwner at that exact moment.
            val voiceDispatcher = StandardTestDispatcher(TestCoroutineScheduler())
            val voiceScope = CoroutineScope(voiceDispatcher)
            val completionDispatcher = StandardTestDispatcher(TestCoroutineScheduler())
            val turnCompletionScope = CoroutineScope(completionDispatcher)
            val scenario = buildScenario(
                turnScope = this,
                voiceScope = voiceScope,
                gatewayOverride = gw,
                voiceCompletionScope = turnCompletionScope,
            )

            // 1. Route and accept voice turn A.
            scenario.voiceController.emitFinalTranscript("Câu hỏi A", screen = "cockpit", generation = 100L)
            voiceDispatcher.scheduler.advanceUntilIdle()
            assertThat(scenario.conversationRepository.state.value.inFlightTurn).isNotNull()

            // 2/3. A completes at the AssistantTurnCoordinator level, freeing global single-flight — but
            // A's own completion consumer, launched onto completionDispatcher, has not been pumped yet.
            queryGateA.complete(Unit)
            val replyA = (scenario.conversationRepository.state.value.turnState as AssistantTurnState.Success).reply.text

            // 4. Emit transcript B — a genuinely newer recognizer session/generation. The event collector
            // has not run route(B) yet (voiceDispatcher untouched since step 1's pump), so activeOwner is
            // still A's.
            scenario.voiceController.emitFinalTranscript("Câu hỏi B", screen = "cockpit", generation = 200L)
            assertThat(scenario.voiceController.state.value.state).isEqualTo(VoiceState.PROCESSING)
            assertThat(scenario.voiceController.state.value.generation).isEqualTo(200L)

            // 5. Run A's completion consumer *before* route(B). A is still, technically, activeOwner, so
            // it legitimately publishes its own outcome and relinquishes ownership; but the actual
            // clearProcessingState(100) call it makes must be a no-op, since the visible generation is
            // already 200.
            completionDispatcher.scheduler.advanceUntilIdle()

            // 6. B's Processing must survive completely untouched by A's completion.
            assertThat(scenario.voiceController.state.value.state).isEqualTo(VoiceState.PROCESSING)
            assertThat(scenario.voiceController.state.value.generation).isEqualTo(200L)
            // A's own outcome was legitimately published here (it was still activeOwner) — this is not
            // itself a bug: the fix is specifically that the *recognizer UI* is untouched, not that A's
            // own turn-level completion is somehow suppressed.
            assertThat(scenario.coordinator.turnOutcome.value).isEqualTo(VoiceTurnOutcome.Success(replyA))

            // 7. Run the voice collector so B is finally accepted and claims ownership — also proves B's
            // acceptance clears A's transient outcome (never leaks into what the user sees for B).
            voiceDispatcher.scheduler.advanceUntilIdle()
            assertThat(scenario.coordinator.turnOutcome.value).isNull()

            // 8/9. B remains genuinely in flight and PROCESSING (its own gateway call still gated).
            assertThat(scenario.conversationRepository.state.value.inFlightTurn).isNotNull()
            assertThat(scenario.voiceController.state.value.state).isEqualTo(VoiceState.PROCESSING)
            assertThat(scenario.voiceController.state.value.generation).isEqualTo(200L)

            // 10. Complete B.
            queryGateB.complete(Unit)
            val replyB = (scenario.conversationRepository.state.value.turnState as AssistantTurnState.Success).reply.text
            assertThat(replyB).isNotEqualTo(replyA)
            completionDispatcher.scheduler.advanceUntilIdle()

            // 11. Only B publishes the final visible outcome; PROCESSING -> IDLE exactly once; no stale A
            // clear/outcome after B's claim.
            val outcome = scenario.coordinator.turnOutcome.value
            assertThat(outcome).isInstanceOf(VoiceTurnOutcome.Success::class.java)
            assertThat((outcome as VoiceTurnOutcome.Success).replyText).isEqualTo(replyB)
            assertThat(scenario.voiceController.state.value.state).isEqualTo(VoiceState.IDLE)
            // Two clearProcessingState calls total: A's (a no-op, generation mismatch) and B's (the real
            // transition) — proving the mechanism actually engaged, not merely "never called".
            assertThat(scenario.voiceController.clearProcessingStateCallCount).isEqualTo(2)
        }

    @Test
    fun `an old generation's clearProcessingState call cannot clear a newer generation's Processing state`() {
        val voiceController = FakeVoiceController()
        voiceController.emitFinalTranscript("Phiên cũ", generation = 1L)
        assertThat(voiceController.state.value.state).isEqualTo(VoiceState.PROCESSING)

        voiceController.emitFinalTranscript("Phiên mới", generation = 2L) // a newer session supersedes
        assertThat(voiceController.state.value.generation).isEqualTo(2L)

        voiceController.clearProcessingState(1L) // the OLD generation attempts to clear
        assertThat(voiceController.state.value.state).isEqualTo(VoiceState.PROCESSING) // untouched
        assertThat(voiceController.state.value.generation).isEqualTo(2L)

        voiceController.clearProcessingState(2L) // the CURRENT generation clears correctly
        assertThat(voiceController.state.value.state).isEqualTo(VoiceState.IDLE)
    }

    @Test
    fun `pre-claim race - A resolving to Failure cannot clear B's newer generation before B claims ownership`() =
        runTest(mainDispatcherRule.dispatcher) {
            var callCount = 0
            val queryGateA = CompletableDeferred<Unit>()
            val queryGateB = CompletableDeferred<Unit>()
            val gw = object : SafeDriveGateway by gateway {
                override suspend fun queryAssistant(request: AssistantQueryRequest): GatewayResult<AssistantQueryResult> {
                    if (callCount++ == 0) {
                        queryGateA.await()
                        return GatewayResult.Failure(GatewayError.Timeout)
                    }
                    queryGateB.await()
                    return gateway.queryAssistant(request)
                }
            }
            val voiceDispatcher = StandardTestDispatcher(TestCoroutineScheduler())
            val voiceScope = CoroutineScope(voiceDispatcher)
            val completionDispatcher = StandardTestDispatcher(TestCoroutineScheduler())
            val turnCompletionScope = CoroutineScope(completionDispatcher)
            val scenario = buildScenario(
                turnScope = this,
                voiceScope = voiceScope,
                gatewayOverride = gw,
                voiceCompletionScope = turnCompletionScope,
            )

            scenario.voiceController.emitFinalTranscript("Câu hỏi A sẽ thất bại", screen = "cockpit", generation = 100L)
            voiceDispatcher.scheduler.advanceUntilIdle()

            queryGateA.complete(Unit) // A resolves to Failure, freeing single-flight
            scenario.voiceController.emitFinalTranscript("Câu hỏi B", screen = "cockpit", generation = 200L)
            completionDispatcher.scheduler.advanceUntilIdle() // A's completion runs before route(B)

            assertThat(scenario.voiceController.state.value.state).isEqualTo(VoiceState.PROCESSING)
            assertThat(scenario.voiceController.state.value.generation).isEqualTo(200L)
            assertThat(scenario.coordinator.turnOutcome.value).isInstanceOf(VoiceTurnOutcome.Failure::class.java) // A's own, legitimate

            voiceDispatcher.scheduler.advanceUntilIdle() // B claims ownership
            assertThat(scenario.coordinator.turnOutcome.value).isNull()

            queryGateB.complete(Unit)
            completionDispatcher.scheduler.advanceUntilIdle()

            val outcome = scenario.coordinator.turnOutcome.value
            assertThat(outcome).isInstanceOf(VoiceTurnOutcome.Success::class.java) // B's own, never A's stale Failure
            assertThat(scenario.voiceController.state.value.state).isEqualTo(VoiceState.IDLE)
        }

    @Test
    fun `pre-claim race - A cancelled cannot clear B's newer generation before B claims ownership`() =
        runTest(mainDispatcherRule.dispatcher) {
            var callCount = 0
            val queryGateA = CompletableDeferred<Unit>() // never completed — cancelCurrent() cancels this coroutine instead
            val queryGateB = CompletableDeferred<Unit>()
            val gw = object : SafeDriveGateway by gateway {
                override suspend fun queryAssistant(request: AssistantQueryRequest): GatewayResult<AssistantQueryResult> {
                    if (callCount++ == 0) queryGateA.await() else queryGateB.await()
                    return gateway.queryAssistant(request)
                }
            }
            val voiceDispatcher = StandardTestDispatcher(TestCoroutineScheduler())
            val voiceScope = CoroutineScope(voiceDispatcher)
            val completionDispatcher = StandardTestDispatcher(TestCoroutineScheduler())
            val turnCompletionScope = CoroutineScope(completionDispatcher)
            val scenario = buildScenario(
                turnScope = this,
                voiceScope = voiceScope,
                gatewayOverride = gw,
                voiceCompletionScope = turnCompletionScope,
            )

            scenario.voiceController.emitFinalTranscript("Câu hỏi A sẽ bị hủy", screen = "cockpit", generation = 100L)
            voiceDispatcher.scheduler.advanceUntilIdle()

            scenario.turnCoordinator.cancelCurrent() // synchronously resolves A to Cancelled, frees single-flight
            scenario.voiceController.emitFinalTranscript("Câu hỏi B", screen = "cockpit", generation = 200L)
            completionDispatcher.scheduler.advanceUntilIdle() // A's completion (Cancelled) runs before route(B)

            assertThat(scenario.voiceController.state.value.state).isEqualTo(VoiceState.PROCESSING)
            assertThat(scenario.voiceController.state.value.generation).isEqualTo(200L)
            assertThat(scenario.coordinator.turnOutcome.value).isNull() // Cancelled never surfaces as an outcome

            voiceDispatcher.scheduler.advanceUntilIdle() // B claims ownership

            queryGateB.complete(Unit)
            completionDispatcher.scheduler.advanceUntilIdle()

            val outcome = scenario.coordinator.turnOutcome.value
            assertThat(outcome).isInstanceOf(VoiceTurnOutcome.Success::class.java)
            assertThat(scenario.voiceController.state.value.state).isEqualTo(VoiceState.IDLE)
        }

    @Test
    fun `pre-claim race - health-blocked A resolving synchronously cannot clear B's newer generation before B claims ownership`() =
        runTest(mainDispatcherRule.dispatcher) {
            val healthFlow = MutableStateFlow<HealthStatus?>(
                HealthStatus(
                    status = SystemConnectionStatus.NORMAL,
                    serviceName = "test-backend",
                    apiVersion = "v1",
                    serverTimeMs = clock.nowMs(),
                    capabilities = HealthCapabilities(assistant = false, emergencySimulation = true, cockpitStream = false),
                ),
            )
            val voiceDispatcher = StandardTestDispatcher(TestCoroutineScheduler())
            val voiceScope = CoroutineScope(voiceDispatcher)
            val completionDispatcher = StandardTestDispatcher(TestCoroutineScheduler())
            val turnCompletionScope = CoroutineScope(completionDispatcher)
            val scenario = buildScenario(
                turnScope = this,
                voiceScope = voiceScope,
                lastHealthStatus = healthFlow,
                voiceCompletionScope = turnCompletionScope,
            )

            // A: health-blocked, resolves to Failure synchronously inside submit() — its completion
            // consumer is launched onto completionDispatcher but not yet pumped.
            scenario.voiceController.emitFinalTranscript("Câu hỏi A bị chặn", screen = "cockpit", generation = 100L)
            voiceDispatcher.scheduler.advanceUntilIdle()

            // Capability recovers; B is a genuinely newer recognizer session.
            healthFlow.value = null
            scenario.voiceController.emitFinalTranscript("Câu hỏi B", screen = "cockpit", generation = 200L)

            // Run A's consumer before route(B) ever executes.
            completionDispatcher.scheduler.advanceUntilIdle()
            assertThat(scenario.voiceController.state.value.state).isEqualTo(VoiceState.PROCESSING)
            assertThat(scenario.voiceController.state.value.generation).isEqualTo(200L)
            assertThat(scenario.coordinator.turnOutcome.value).isInstanceOf(VoiceTurnOutcome.Failure::class.java) // A's own

            voiceDispatcher.scheduler.advanceUntilIdle() // B claims ownership
            assertThat(scenario.coordinator.turnOutcome.value).isNull()
            advanceUntilIdle() // let B's real gateway call (on turnScope) resolve
            completionDispatcher.scheduler.advanceUntilIdle() // B's own consumer runs

            val outcome = scenario.coordinator.turnOutcome.value
            assertThat(outcome).isInstanceOf(VoiceTurnOutcome.Success::class.java)
            assertThat(scenario.voiceController.state.value.state).isEqualTo(VoiceState.IDLE)
        }

    // --- Rejected-event and emergency semantics must resolve the *rejected event's own* generation, never
    // an unrelated one — either by clearing it directly (no voice turn currently owns Processing) or by
    // transferring visible ownership back to whichever voice turn genuinely still does (so that turn's own
    // later clearProcessingState call still correctly matches instead of being silently stranded). ---

    @Test
    fun `B rejected while accepted voice A is still in flight transfers Processing ownership back to A instead of clearing it`() =
        runTest(mainDispatcherRule.dispatcher) {
            val queryGateA = CompletableDeferred<Unit>()
            val gw = object : SafeDriveGateway by gateway {
                override suspend fun queryAssistant(request: AssistantQueryRequest): GatewayResult<AssistantQueryResult> {
                    queryGateA.await()
                    return gateway.queryAssistant(request)
                }
            }
            val scenario = buildScenario(turnScope = this, voiceScope = backgroundScope, gatewayOverride = gw)

            // A: accepted, genuinely in flight.
            scenario.voiceController.emitFinalTranscript("Câu hỏi A", screen = "cockpit", generation = 100L)
            assertThat(scenario.conversationRepository.state.value.inFlightTurn).isNotNull()

            // B: a newer recognizer session's transcript arrives while A still occupies single-flight —
            // emitFinalTranscript already published PROCESSING/200 unconditionally before route(B) runs.
            scenario.voiceController.emitFinalTranscript("Câu hỏi B", screen = "cockpit", generation = 200L)

            // B is rejected (A still busy) — the visible generation must be transferred back to A's (100),
            // not cleared to IDLE: A's assistant turn is still genuinely running, and if this isn't
            // restored, A's own eventual clearProcessingState(100) would never match again (the visible
            // generation would be stuck at 200 forever, since nothing else has a reason to ever touch it).
            assertThat(scenario.voiceController.state.value.state).isEqualTo(VoiceState.PROCESSING)
            assertThat(scenario.voiceController.state.value.generation).isEqualTo(100L)
            assertThat(scenario.voiceController.reassignProcessingOwnerCallCount).isEqualTo(1)
            assertThat(scenario.voiceController.clearProcessingStateCallCount).isEqualTo(0)

            // A completes for real — its own clear now correctly matches (100 == 100) and transitions.
            queryGateA.complete(Unit)
            advanceUntilIdle()

            assertThat(scenario.voiceController.state.value.state).isEqualTo(VoiceState.IDLE)
            assertThat(scenario.voiceController.clearProcessingStateCallCount).isEqualTo(1)
            assertThat(scenario.coordinator.turnOutcome.value).isInstanceOf(VoiceTurnOutcome.Success::class.java)
        }

    @Test
    fun `a voice event rejected while a text turn is busy clears using its own generation, with no voice owner to transfer to`() =
        runTest(mainDispatcherRule.dispatcher) {
            val queryGate = CompletableDeferred<Unit>()
            val gw = object : SafeDriveGateway by gateway {
                override suspend fun queryAssistant(request: AssistantQueryRequest): GatewayResult<AssistantQueryResult> {
                    queryGate.await()
                    return gateway.queryAssistant(request)
                }
            }
            val scenario = buildScenario(turnScope = this, voiceScope = backgroundScope, gatewayOverride = gw)

            scenario.turnCoordinator.submit("Một câu hỏi bằng text", AssistantTurnSource.TEXT, "assistant")
            assertThat(scenario.conversationRepository.state.value.inFlightTurn).isNotNull()

            scenario.voiceController.emitFinalTranscript("Câu hỏi bằng giọng nói", screen = "cockpit", generation = 42L)
            assertThat(scenario.voiceController.clearProcessingStateCallCount).isEqualTo(1)
            assertThat(scenario.voiceController.reassignProcessingOwnerCallCount).isEqualTo(0) // nothing to transfer to
            assertThat(scenario.voiceController.state.value.state).isEqualTo(VoiceState.IDLE)

            queryGate.complete(Unit)
            advanceUntilIdle()
            assertThat(scenario.coordinator.turnOutcome.value).isNull() // the voice event was never accepted
        }

    @Test
    fun `emergency-phrase event with no accepted voice turn clears using its own generation`() =
        runTest(mainDispatcherRule.dispatcher) {
            val scenario = buildScenario(turnScope = this, voiceScope = backgroundScope)
            scenario.emergencyRepository.setSnapshot(
                EmergencySnapshot("emg_gen_1", EmergencyState.AWAITING_USER_RESPONSE, 10_000L, emptyList()),
            )

            scenario.voiceController.emitFinalTranscript("Tôi ổn", screen = "emergency", generation = 7L)

            assertThat(scenario.emergencyRepository.lastResponse).isEqualTo(EmergencyResponseType.CANCEL_SOS)
            assertThat(scenario.voiceController.clearProcessingStateCallCount).isEqualTo(1)
            assertThat(scenario.voiceController.reassignProcessingOwnerCallCount).isEqualTo(0)
            assertThat(scenario.voiceController.state.value.state).isEqualTo(VoiceState.IDLE)
        }

    @Test
    fun `emergency-phrase event while a voice turn is genuinely still in flight transfers Processing back to that turn`() =
        runTest(mainDispatcherRule.dispatcher) {
            val queryGate = CompletableDeferred<Unit>()
            val gw = object : SafeDriveGateway by gateway {
                override suspend fun queryAssistant(request: AssistantQueryRequest): GatewayResult<AssistantQueryResult> {
                    queryGate.await()
                    return gateway.queryAssistant(request)
                }
            }
            val scenario = buildScenario(turnScope = this, voiceScope = backgroundScope, gatewayOverride = gw)

            // A: an accepted voice turn, genuinely in flight (not an emergency yet).
            scenario.voiceController.emitFinalTranscript("Câu hỏi bình thường", screen = "cockpit", generation = 11L)
            assertThat(scenario.conversationRepository.state.value.inFlightTurn).isNotNull()

            // Emergency becomes active and an emergency-phrase event arrives from a newer session.
            scenario.emergencyRepository.setSnapshot(
                EmergencySnapshot("emg_gen_2", EmergencyState.AWAITING_USER_RESPONSE, 10_000L, emptyList()),
            )
            scenario.voiceController.emitFinalTranscript("Tôi ổn", screen = "emergency", generation = 22L)

            assertThat(scenario.emergencyRepository.lastResponse).isEqualTo(EmergencyResponseType.CANCEL_SOS)
            // Transferred back to the still-genuinely-in-flight voice turn, not cleared.
            assertThat(scenario.voiceController.state.value.state).isEqualTo(VoiceState.PROCESSING)
            assertThat(scenario.voiceController.state.value.generation).isEqualTo(11L)
            assertThat(scenario.voiceController.reassignProcessingOwnerCallCount).isEqualTo(1)
            assertThat(scenario.voiceController.clearProcessingStateCallCount).isEqualTo(0)

            queryGate.complete(Unit)
            advanceUntilIdle()
            assertThat(scenario.voiceController.state.value.state).isEqualTo(VoiceState.IDLE)
            assertThat(scenario.voiceController.clearProcessingStateCallCount).isEqualTo(1)
        }

    @Test
    fun `trySend failure still produces ERROR and cannot later be cleared by an old generation`() =
        runTest(mainDispatcherRule.dispatcher) {
            // autoStart = false: nothing ever collects VoiceController.events, so the bounded Channel
            // (capacity 4) genuinely fills up rather than merely appearing full.
            val scenario = buildScenario(turnScope = this, voiceScope = backgroundScope, autoStart = false)
            repeat(4) { i -> assertThat(scenario.voiceController.emitFinalTranscript("Câu $i", generation = i.toLong())).isTrue() }
            val overflowed = scenario.voiceController.emitFinalTranscript("Câu tràn", generation = 99L)

            assertThat(overflowed).isFalse()
            assertThat(scenario.voiceController.state.value.state).isEqualTo(VoiceState.ERROR)

            // An old (already-superseded) generation attempts to act on it — must never resurrect/alter
            // the ERROR state via either method.
            scenario.voiceController.clearProcessingState(0L)
            assertThat(scenario.voiceController.state.value.state).isEqualTo(VoiceState.ERROR)
            scenario.voiceController.reassignProcessingOwner(expectedCurrentGeneration = 0L, newOwnerGeneration = 1L)
            assertThat(scenario.voiceController.state.value.state).isEqualTo(VoiceState.ERROR)
        }

    // --- Independent re-audit follow-up, ninth pass: P1-1. resolveUnclaimedEvent's
    // reassignProcessingOwner call previously named only the *new* owner's generation — an unconditional
    // overwrite. A third, even-newer session (C) can publish its own PROCESSING/generation before a
    // rejected, older event (B) is ever resolved (route() processes queued events strictly one at a time,
    // in enqueue order — nothing gates a recognizer session's own publish on that). B's resolution would
    // then blindly stamp the still-in-flight turn's generation over C's, destroying C's legitimate,
    // currently-visible Processing before C's own transcript is even routed. reassignProcessingOwner is
    // now a compare-and-set: it only takes effect when the visible generation is still exactly the
    // caller's own expected-current value. ---

    @Test
    fun `reassignProcessingOwner only transfers ownership when the visible generation still matches the expected current generation`() {
        val voiceController = FakeVoiceController()
        voiceController.emitFinalTranscript("Phiên B", generation = 200L)
        assertThat(voiceController.state.value.generation).isEqualTo(200L)

        voiceController.reassignProcessingOwner(expectedCurrentGeneration = 200L, newOwnerGeneration = 100L)
        assertThat(voiceController.state.value.state).isEqualTo(VoiceState.PROCESSING)
        assertThat(voiceController.state.value.generation).isEqualTo(100L) // succeeded: expectation matched

        voiceController.emitFinalTranscript("Phiên C", generation = 300L) // an even-newer session supersedes
        assertThat(voiceController.state.value.generation).isEqualTo(300L)

        // A stale caller still expects 200 (B's own generation) — but the visible generation has already
        // moved on to C's 300. Must be a safe no-op: never stamp an unrelated generation over C's.
        voiceController.reassignProcessingOwner(expectedCurrentGeneration = 200L, newOwnerGeneration = 999L)
        assertThat(voiceController.state.value.generation).isEqualTo(300L) // untouched
        assertThat(voiceController.state.value.state).isEqualTo(VoiceState.PROCESSING)
    }

    @Test
    fun `P1-1 - a rejected B cannot restamp or clear C's newer Processing state, only C's own resolution can`() =
        runTest(mainDispatcherRule.dispatcher) {
            val queryGateA = CompletableDeferred<Unit>()
            val gw = object : SafeDriveGateway by gateway {
                override suspend fun queryAssistant(request: AssistantQueryRequest): GatewayResult<AssistantQueryResult> {
                    queryGateA.await()
                    return gateway.queryAssistant(request)
                }
            }
            // A separate, independently-pumped scheduler for the collector (route()) — unlike
            // backgroundScope (which shares mainDispatcherRule's UnconfinedTestDispatcher, under which
            // route() would run inline, synchronously, as part of each emitFinalTranscript call below,
            // leaving no window to enqueue B and C without either being routed yet).
            val voiceDispatcher = StandardTestDispatcher(TestCoroutineScheduler())
            val voiceScope = CoroutineScope(voiceDispatcher)
            val scenario = buildScenario(turnScope = this, voiceScope = voiceScope, gatewayOverride = gw)

            // A: accepted, genuinely in flight, owns generation 100.
            scenario.voiceController.emitFinalTranscript("Câu hỏi A", screen = "cockpit", generation = 100L)
            voiceDispatcher.scheduler.advanceUntilIdle()
            assertThat(scenario.conversationRepository.state.value.inFlightTurn).isNotNull()

            // B (200) and C (300) both publish PROCESSING/generation unconditionally as soon as they're
            // emitted — but the collector is not pumped again yet, so neither route(B) nor route(C) has
            // run: both just sit queued, in enqueue order, in the channel. The visible generation is C's
            // (300), the last one to publish.
            scenario.voiceController.emitFinalTranscript("Câu hỏi B", screen = "cockpit", generation = 200L)
            scenario.voiceController.emitFinalTranscript("Câu hỏi C", screen = "cockpit", generation = 300L)
            assertThat(scenario.voiceController.state.value.generation).isEqualTo(300L)

            // Drains the queue: route(B) then route(C), in enqueue order. Both are rejected (A is still
            // busy) — B's own resolveUnclaimedEvent(200) must see the visible generation is already 300,
            // not 200, and become a safe no-op instead of stamping A's 100 over C's 300.
            voiceDispatcher.scheduler.advanceUntilIdle()

            // B and C both end up wanting to transfer to the *same* still-in-flight owner (A/100), so the
            // final converged value alone (100) is identical whether B's stale attempt was correctly
            // rejected or blindly applied — checking only the end state would not actually discriminate
            // an unconditional-overwrite regression from the fix. What *does* discriminate it is the exact
            // argument B's own call named: it must ask for expectedCurrentGeneration = 200 (its own
            // generation), never anything else, and — independently, per the direct FakeVoiceController
            // test above — a 200-vs-300 mismatch must not take effect.
            assertThat(scenario.voiceController.reassignProcessingOwnerCalls)
                .containsExactly(200L to 100L, 300L to 100L)
                .inOrder()
            assertThat(scenario.voiceController.state.value.state).isEqualTo(VoiceState.PROCESSING)
            assertThat(scenario.voiceController.state.value.generation).isEqualTo(100L)
            assertThat(scenario.voiceController.clearProcessingStateCallCount).isEqualTo(0)

            // A completes for real — its own clear now correctly matches (100 == 100).
            queryGateA.complete(Unit)
            advanceUntilIdle() // let A's real gateway call (on turnScope) resolve
            voiceDispatcher.scheduler.advanceUntilIdle() // A's own completion consumer runs
            assertThat(scenario.voiceController.state.value.state).isEqualTo(VoiceState.IDLE)
            assertThat(scenario.voiceController.clearProcessingStateCallCount).isEqualTo(1)
            assertThat(scenario.coordinator.turnOutcome.value).isInstanceOf(VoiceTurnOutcome.Success::class.java)
        }

    @Test
    fun `P1-1 - the same invariant holds for a rejected emergency-phrase B ahead of a newer C`() =
        runTest(mainDispatcherRule.dispatcher) {
            val queryGateA = CompletableDeferred<Unit>()
            val gw = object : SafeDriveGateway by gateway {
                override suspend fun queryAssistant(request: AssistantQueryRequest): GatewayResult<AssistantQueryResult> {
                    queryGateA.await()
                    return gateway.queryAssistant(request)
                }
            }
            val voiceDispatcher = StandardTestDispatcher(TestCoroutineScheduler())
            val voiceScope = CoroutineScope(voiceDispatcher)
            val scenario = buildScenario(turnScope = this, voiceScope = voiceScope, gatewayOverride = gw)

            // A: an accepted voice turn, genuinely in flight (not an emergency yet).
            scenario.voiceController.emitFinalTranscript("Câu hỏi bình thường", screen = "cockpit", generation = 100L)
            voiceDispatcher.scheduler.advanceUntilIdle()
            assertThat(scenario.conversationRepository.state.value.inFlightTurn).isNotNull()

            scenario.emergencyRepository.setSnapshot(
                EmergencySnapshot("emg_p11", EmergencyState.AWAITING_USER_RESPONSE, 10_000L, emptyList()),
            )
            // B and C both arrive as emergency-phrase events before either is routed — C publishes last,
            // so the visible generation is 300.
            scenario.voiceController.emitFinalTranscript("Tôi ổn", screen = "emergency", generation = 200L)
            scenario.voiceController.emitFinalTranscript("Tôi ổn", screen = "emergency", generation = 300L)
            assertThat(scenario.voiceController.state.value.generation).isEqualTo(300L)

            voiceDispatcher.scheduler.advanceUntilIdle() // routes B then C, both through the emergency branch

            // Same reasoning as the single-flight-rejection variant above: B's own call must name exactly
            // its own generation (200) as expectedCurrentGeneration, never anything else.
            assertThat(scenario.voiceController.reassignProcessingOwnerCalls)
                .containsExactly(200L to 100L, 300L to 100L)
                .inOrder()
            assertThat(scenario.voiceController.state.value.state).isEqualTo(VoiceState.PROCESSING)
            // Transferred back to A, never stomped by B's stale 100-over-300 attempt.
            assertThat(scenario.voiceController.state.value.generation).isEqualTo(100L)
            assertThat(scenario.voiceController.clearProcessingStateCallCount).isEqualTo(0)

            queryGateA.complete(Unit)
            advanceUntilIdle()
            voiceDispatcher.scheduler.advanceUntilIdle()
            assertThat(scenario.voiceController.state.value.state).isEqualTo(VoiceState.IDLE)
        }
}

private fun fakeProvider(provider: () -> SafeDriveGateway) = object : GatewayProvider {
    override fun current() = provider()
}
