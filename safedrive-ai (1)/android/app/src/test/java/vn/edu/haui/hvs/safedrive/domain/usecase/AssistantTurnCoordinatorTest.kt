package vn.edu.haui.hvs.safedrive.domain.usecase

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Rule
import org.junit.Test
import vn.edu.haui.hvs.safedrive.core.common.GatewayError
import vn.edu.haui.hvs.safedrive.core.common.GatewayResult
import vn.edu.haui.hvs.safedrive.core.common.UuidIdGenerator
import vn.edu.haui.hvs.safedrive.core.model.AssistantQueryRequest
import vn.edu.haui.hvs.safedrive.core.model.AssistantQueryResult
import vn.edu.haui.hvs.safedrive.core.model.AssistantTurnSource
import vn.edu.haui.hvs.safedrive.core.model.AssistantTurnState
import vn.edu.haui.hvs.safedrive.core.model.CockpitSnapshot
import vn.edu.haui.hvs.safedrive.core.model.SessionInfo
import vn.edu.haui.hvs.safedrive.core.model.StartSessionRequest
import vn.edu.haui.hvs.safedrive.core.observability.AssistantTurnMetricsRecorder
import vn.edu.haui.hvs.safedrive.core.testing.FakeClock
import vn.edu.haui.hvs.safedrive.core.testing.FakeTtsController
import vn.edu.haui.hvs.safedrive.core.testing.MainDispatcherRule
import vn.edu.haui.hvs.safedrive.data.local.InMemoryConversationRepository
import vn.edu.haui.hvs.safedrive.data.mock.MockFixtures
import vn.edu.haui.hvs.safedrive.data.mock.MockPolicyEvaluator
import vn.edu.haui.hvs.safedrive.data.mock.MockSafeDriveGateway
import vn.edu.haui.hvs.safedrive.domain.repository.AppPreferences
import vn.edu.haui.hvs.safedrive.domain.repository.ConversationState
import vn.edu.haui.hvs.safedrive.domain.repository.GatewayProvider
import vn.edu.haui.hvs.safedrive.domain.repository.SafeDriveGateway
import vn.edu.haui.hvs.safedrive.domain.repository.TtsController
import vn.edu.haui.hvs.safedrive.domain.repository.TtsState
import vn.edu.haui.hvs.safedrive.domain.repository.TtsUtteranceEvent
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Covers docs/android-mvp-plan/12 W1's mandatory test list at the coordinator level (source-agnostic:
 * these same guarantees apply to text, quick-prompt and, from W2, voice, since all three call
 * [AssistantTurnCoordinator.submit]).
 */
class AssistantTurnCoordinatorTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val clock = FakeClock(initialMs = 1_000L)
    private val idGenerator = UuidIdGenerator()
    private val fixtures = MockFixtures(clock)
    private val gateway: SafeDriveGateway = MockSafeDriveGateway(clock, idGenerator, fixtures, MockPolicyEvaluator(clock))
    private val cockpitSnapshot: StateFlow<CockpitSnapshot?> = MutableStateFlow(null)

    private fun buildCoordinator(
        scope: CoroutineScope,
        gatewayOverride: SafeDriveGateway = gateway,
        appPreferences: StateFlow<AppPreferences> = MutableStateFlow(AppPreferences()),
        ttsController: FakeTtsController = FakeTtsController(),
        metricsRecorder: AssistantTurnMetricsRecorder = AssistantTurnMetricsRecorder(),
        lastHealthStatus: StateFlow<vn.edu.haui.hvs.safedrive.core.model.HealthStatus?> = MutableStateFlow(null),
    ): Triple<AssistantTurnCoordinator, InMemoryConversationRepository, FakeTtsController> {
        val repository = InMemoryConversationRepository(initialMessages = emptyList())
        val sessionCoordinator = SessionCoordinator(fakeProvider { gatewayOverride }, appPreferences, idGenerator, clock, "test")
        val useCase = AssistantQueryUseCase(sessionCoordinator, clock)
        val coordinator = AssistantTurnCoordinator(
            conversationRepository = repository,
            assistantQueryUseCase = useCase,
            cockpitSnapshot = cockpitSnapshot,
            appPreferences = appPreferences,
            ttsController = ttsController,
            metricsRecorder = metricsRecorder,
            lastHealthStatus = lastHealthStatus,
            idGenerator = idGenerator,
            clock = clock,
            externalScope = scope,
        )
        return Triple(coordinator, repository, ttsController)
    }

    /** Demo Mode has no artificial delay by default (W4.3); tests that need a genuine in-flight
     * window (to exercise single-flight/cancel) wrap the real gateway with one explicitly instead of
     * relying on `MockSafeDriveGateway`'s own (now-removed) default delay. */
    private fun slowGateway(delayMs: Long = 100L): SafeDriveGateway = object : SafeDriveGateway by gateway {
        override suspend fun queryAssistant(request: AssistantQueryRequest): GatewayResult<AssistantQueryResult> {
            delay(delayMs)
            return gateway.queryAssistant(request)
        }
    }

    @Test
    fun `blank text is a no-op`() = runTest(mainDispatcherRule.dispatcher) {
        val (coordinator, repository, _) = buildCoordinator(this)
        assertThat(coordinator.submit("   ", AssistantTurnSource.TEXT, "assistant")).isFalse()
        assertThat(repository.state.value.messages).isEmpty()
    }

    @Test
    fun `submit appends user bubble then assistant reply exactly once`() = runTest(mainDispatcherRule.dispatcher) {
        val (coordinator, repository, _) = buildCoordinator(this)
        assertThat(coordinator.submit("Kiểm tra nhiệt độ động cơ", AssistantTurnSource.TEXT, "assistant")).isTrue()
        advanceUntilIdle()

        val messages = repository.state.value.messages
        assertThat(messages).hasSize(2)
        assertThat(messages[0].sender.name).isEqualTo("USER")
        assertThat(messages[1].sender.name).isEqualTo("SAFEDRIVE")
        assertThat(repository.state.value.inFlightTurn).isNull()
    }

    @Test
    fun `quick prompt and voice sources use the same pipeline as text`() = runTest(mainDispatcherRule.dispatcher) {
        val (coordinator, repository, _) = buildCoordinator(this)
        coordinator.submit("Tôi đang cảm thấy mệt", AssistantTurnSource.QUICK_PROMPT, "assistant")
        advanceUntilIdle()
        assertThat(repository.state.value.messages).hasSize(2)

        coordinator.submit("Nhiệt độ động cơ hiện tại?", AssistantTurnSource.VOICE, "cockpit")
        advanceUntilIdle()
        assertThat(repository.state.value.messages).hasSize(4)
    }

    @Test
    fun `second submit while a turn is in flight is rejected (global single-flight)`() = runTest(mainDispatcherRule.dispatcher) {
        // Demo Mode has no artificial delay by default (W4.3) — a slow gateway is used here
        // specifically to create an in-flight window to assert the single-flight guard against.
        val (coordinator, repository, _) = buildCoordinator(this, gatewayOverride = slowGateway())
        assertThat(coordinator.submit("Tốc độ hiện tại?", AssistantTurnSource.TEXT, "assistant")).isTrue()
        assertThat(repository.state.value.inFlightTurn).isNotNull()
        assertThat(repository.state.value.messages).hasSize(1)

        // A second submit — whatever its source — must not start a parallel request.
        assertThat(coordinator.submit("Một câu khác", AssistantTurnSource.VOICE, "cockpit")).isFalse()
        assertThat(repository.state.value.messages).hasSize(1)

        advanceUntilIdle()
        assertThat(repository.state.value.messages).hasSize(2) // only the first turn's reply landed
    }

    @Test
    fun `retry after failure does not duplicate the user bubble and carries clientAttemptOf lineage`() =
        runTest(mainDispatcherRule.dispatcher) {
            var shouldFail = true
            val failingGateway = object : SafeDriveGateway by gateway {
                override suspend fun queryAssistant(request: AssistantQueryRequest): GatewayResult<AssistantQueryResult> =
                    if (shouldFail) {
                        shouldFail = false
                        GatewayResult.Failure(GatewayError.Timeout)
                    } else {
                        gateway.queryAssistant(request)
                    }
            }
            val (coordinator, repository, _) = buildCoordinator(this, gatewayOverride = failingGateway)

            coordinator.submit("Xe có lỗi gì?", AssistantTurnSource.TEXT, "assistant")
            advanceUntilIdle()

            assertThat(repository.state.value.messages).hasSize(1) // only the user bubble; reply failed
            val retryable = repository.state.value.retryableTurn
            assertThat(retryable).isNotNull()
            assertThat(retryable!!.clientAttemptOf).isNotEmpty()

            assertThat(coordinator.retry()).isTrue()
            advanceUntilIdle()

            val messages = repository.state.value.messages
            assertThat(messages).hasSize(2) // same user bubble + one new SAFEDRIVE reply
            assertThat(messages.count { it.sender.name == "USER" }).isEqualTo(1)
            assertThat(repository.state.value.retryableTurn).isNull()
        }

    @Test
    fun `retry with nothing to retry is a no-op`() = runTest(mainDispatcherRule.dispatcher) {
        val (coordinator, _, _) = buildCoordinator(this)
        assertThat(coordinator.retry()).isFalse()
    }

    @Test
    fun `cancel keeps the user bubble, fabricates no assistant reply, and allows retry`() =
        runTest(mainDispatcherRule.dispatcher) {
            val (coordinator, repository, _) = buildCoordinator(this, gatewayOverride = slowGateway())
            coordinator.submit("Câu hỏi sẽ bị hủy", AssistantTurnSource.TEXT, "assistant")
            assertThat(repository.state.value.inFlightTurn).isNotNull()

            coordinator.cancelCurrent()

            assertThat(repository.state.value.inFlightTurn).isNull()
            assertThat(repository.state.value.messages).hasSize(1) // user bubble kept
            assertThat(repository.state.value.messages.single().sender.name).isEqualTo("USER")
            assertThat(repository.state.value.retryableTurn).isNotNull()

            // The cancelled gateway call, if it ever resolves, must not resurrect state (generation guard).
            advanceUntilIdle()
            assertThat(repository.state.value.messages).hasSize(1)
            assertThat(repository.state.value.inFlightTurn).isNull()
        }

    @Test
    fun `cancel with nothing in flight is a no-op`() = runTest(mainDispatcherRule.dispatcher) {
        val (coordinator, repository, _) = buildCoordinator(this)
        coordinator.cancelCurrent()
        assertThat(repository.state.value.retryableTurn).isNull()
    }

    @Test
    fun `a successful reply is spoken exactly once when TTS is enabled (W3_13)`() = runTest(mainDispatcherRule.dispatcher) {
        val (coordinator, _, tts) = buildCoordinator(this, appPreferences = MutableStateFlow(AppPreferences(ttsEnabled = true)))
        coordinator.submit("Kiểm tra nhiệt độ động cơ", AssistantTurnSource.TEXT, "assistant")
        advanceUntilIdle()
        assertThat(tts.speakCallCount).isEqualTo(1)
        assertThat(tts.lastSpokenText).isNotNull()
    }

    @Test
    fun `a successful reply is never spoken when TTS is disabled`() = runTest(mainDispatcherRule.dispatcher) {
        val (coordinator, _, tts) = buildCoordinator(this, appPreferences = MutableStateFlow(AppPreferences(ttsEnabled = false)))
        coordinator.submit("Kiểm tra nhiệt độ động cơ", AssistantTurnSource.TEXT, "assistant")
        advanceUntilIdle()
        assertThat(tts.speakCallCount).isEqualTo(0)
    }

    @Test
    fun `a failed turn is never spoken even when TTS is enabled`() = runTest(mainDispatcherRule.dispatcher) {
        val failingGateway = object : SafeDriveGateway by gateway {
            override suspend fun queryAssistant(request: AssistantQueryRequest): GatewayResult<AssistantQueryResult> =
                GatewayResult.Failure(GatewayError.Timeout)
        }
        val (coordinator, _, tts) = buildCoordinator(
            this,
            gatewayOverride = failingGateway,
            appPreferences = MutableStateFlow(AppPreferences(ttsEnabled = true)),
        )
        coordinator.submit("Xe có lỗi gì?", AssistantTurnSource.TEXT, "assistant")
        advanceUntilIdle()
        assertThat(tts.speakCallCount).isEqualTo(0)
    }

    @Test
    fun `a completed turn records latency metrics with a non-null total and network duration (W4_1)`() =
        runTest(mainDispatcherRule.dispatcher) {
            val recorder = AssistantTurnMetricsRecorder()
            val (coordinator, _, _) = buildCoordinator(this, metricsRecorder = recorder)
            coordinator.submit("Kiểm tra nhiệt độ động cơ", AssistantTurnSource.TEXT, "assistant")
            advanceUntilIdle()

            val metrics = recorder.lastTurn.value
            assertThat(metrics).isNotNull()
            assertThat(metrics!!.totalTurnMs).isNotNull()
            assertThat(metrics.networkMs).isNotNull()
            assertThat(metrics.voiceCapture).isNull() // text turn — no mic/recognizer timings
        }

    @Test
    fun `a failed turn still records metrics without a fabricated tts timestamp`() = runTest(mainDispatcherRule.dispatcher) {
        val recorder = AssistantTurnMetricsRecorder()
        val failingGateway = object : SafeDriveGateway by gateway {
            override suspend fun queryAssistant(request: AssistantQueryRequest): GatewayResult<AssistantQueryResult> =
                GatewayResult.Failure(GatewayError.Timeout)
        }
        val (coordinator, _, _) = buildCoordinator(this, gatewayOverride = failingGateway, metricsRecorder = recorder)
        coordinator.submit("Xe có lỗi gì?", AssistantTurnSource.TEXT, "assistant")
        advanceUntilIdle()

        val metrics = recorder.lastTurn.value
        assertThat(metrics).isNotNull()
        assertThat(metrics!!.ttsRequestedAtMs).isNull()
        assertThat(metrics.responseToTtsStartMs).isNull()
    }

    @Test
    fun `confirmed assistant=false capability blocks the query without ever calling the gateway (W5_10)`() =
        runTest(mainDispatcherRule.dispatcher) {
            var gatewayCalled = false
            val trackingGateway = object : SafeDriveGateway by gateway {
                override suspend fun queryAssistant(request: AssistantQueryRequest): GatewayResult<AssistantQueryResult> {
                    gatewayCalled = true
                    return gateway.queryAssistant(request)
                }
            }
            val healthWithAssistantDisabled = MutableStateFlow(
                vn.edu.haui.hvs.safedrive.core.model.HealthStatus(
                    status = vn.edu.haui.hvs.safedrive.core.model.SystemConnectionStatus.NORMAL,
                    serviceName = "test-backend",
                    apiVersion = "v1",
                    serverTimeMs = clock.nowMs(),
                    capabilities = vn.edu.haui.hvs.safedrive.core.model.HealthCapabilities(
                        assistant = false,
                        emergencySimulation = true,
                        cockpitStream = false,
                    ),
                ),
            )
            val (coordinator, repository, _) = buildCoordinator(
                this,
                gatewayOverride = trackingGateway,
                lastHealthStatus = healthWithAssistantDisabled,
            )

            val started = coordinator.submit("Xe có lỗi gì?", AssistantTurnSource.TEXT, "assistant")
            advanceUntilIdle()

            assertThat(started).isTrue() // user bubble still appended — the block happens after
            assertThat(gatewayCalled).isFalse()
            assertThat(repository.state.value.messages).hasSize(1) // no fabricated SAFEDRIVE reply
            assertThat(repository.state.value.errorMessage).isNotNull()
            assertThat(repository.state.value.retryableTurn).isNotNull()
        }

    @Test
    fun `absent health info (never checked) does not block the query`() = runTest(mainDispatcherRule.dispatcher) {
        val (coordinator, repository, _) = buildCoordinator(this, lastHealthStatus = MutableStateFlow(null))
        coordinator.submit("Kiểm tra nhiệt độ động cơ", AssistantTurnSource.TEXT, "assistant")
        advanceUntilIdle()
        assertThat(repository.state.value.messages).hasSize(2)
    }

    // --- Remediation item 1: requestId is minted exactly once per attempt and clientAttemptOf on a
    // retry references the exact requestId that was actually sent on the wire for the prior attempt
    // (fixing the old bug where the coordinator's internal `turn_*` id and the network `req_*` id were
    // two different values, so a retry's clientAttemptOf never matched anything the backend had seen). ---

    @Test
    fun `retry mints a new requestId and its clientAttemptOf is exactly the previous attempt's real network requestId`() =
        runTest(mainDispatcherRule.dispatcher) {
            var callCount = 0
            var firstAttemptRequestId: String? = null
            var secondAttemptRequestId: String? = null
            var secondAttemptClientAttemptOf: String? = null
            val trackingGateway = object : SafeDriveGateway by gateway {
                override suspend fun queryAssistant(request: AssistantQueryRequest): GatewayResult<AssistantQueryResult> {
                    callCount++
                    return if (callCount == 1) {
                        firstAttemptRequestId = request.requestId
                        GatewayResult.Failure(GatewayError.Timeout)
                    } else {
                        secondAttemptRequestId = request.requestId
                        secondAttemptClientAttemptOf = request.clientAttemptOf
                        gateway.queryAssistant(request)
                    }
                }
            }
            val (coordinator, repository, _) = buildCoordinator(this, gatewayOverride = trackingGateway)

            coordinator.submit("Xe có lỗi gì?", AssistantTurnSource.TEXT, "assistant")
            advanceUntilIdle()
            val retryableClientAttemptOf = repository.state.value.retryableTurn?.clientAttemptOf

            coordinator.retry()
            advanceUntilIdle()

            assertThat(firstAttemptRequestId).isNotNull()
            // The failed turn's retry lineage must reference the exact id attempt 1 actually sent —
            // never a separately-minted internal id.
            assertThat(retryableClientAttemptOf).isEqualTo(firstAttemptRequestId)
            assertThat(secondAttemptRequestId).isNotEqualTo(firstAttemptRequestId) // attempt 2 mints a fresh id
            assertThat(secondAttemptClientAttemptOf).isEqualTo(firstAttemptRequestId)
        }

    @Test
    fun `a health-blocked turn (no network call ever made) sets clientAttemptOf to null, never a fabricated id`() =
        runTest(mainDispatcherRule.dispatcher) {
            val healthWithAssistantDisabled = MutableStateFlow(
                vn.edu.haui.hvs.safedrive.core.model.HealthStatus(
                    status = vn.edu.haui.hvs.safedrive.core.model.SystemConnectionStatus.NORMAL,
                    serviceName = "test-backend",
                    apiVersion = "v1",
                    serverTimeMs = clock.nowMs(),
                    capabilities = vn.edu.haui.hvs.safedrive.core.model.HealthCapabilities(
                        assistant = false,
                        emergencySimulation = true,
                        cockpitStream = false,
                    ),
                ),
            )
            val (coordinator, repository, _) = buildCoordinator(this, lastHealthStatus = healthWithAssistantDisabled)

            coordinator.submit("Xe có lỗi gì?", AssistantTurnSource.TEXT, "assistant")
            advanceUntilIdle()

            assertThat(repository.state.value.retryableTurn?.clientAttemptOf).isNull()
        }

    // --- Remediation item 2: submit/retry/cancel are atomic under genuine multi-thread concurrency,
    // not just single-threaded coroutine-test ordering. Uses real OS threads (not virtual time) so this
    // actually exercises the `synchronized(lock)` critical section, unlike the single-threaded tests
    // above which would have passed even against the old, unsynchronized check-then-act code. ---

    @Test
    fun `concurrent submits from many real threads at once start exactly one turn`() {
        val repository = InMemoryConversationRepository(initialMessages = emptyList())
        val appPreferences = MutableStateFlow(AppPreferences())
        // Gates the single accepted turn's own gateway call so it stays *genuinely* in flight for the
        // entire duration every one of the 24 threads is attempting its own submit() (independent
        // re-audit follow-up, tenth pass). Without this, the real (ungated) mock gateway can answer fast
        // enough that the first accepted turn completes and frees single-flight again before all 24
        // threads have even reached their own submit() call — letting a later thread's submit be
        // *legitimately* re-accepted as a second, genuinely new turn, producing acceptedCount == 2
        // without that proving anything about the single-flight guard's atomicity under contention. This
        // was the actual, diagnosed cause of this test's earlier "expected 1, got 2" failure — a gap in
        // this test's own setup, not a reproduction of a defect in AssistantTurnCoordinator.submit()
        // itself (confirmed below: with the gate in place, acceptedCount is reliably exactly 1).
        val queryGate = CompletableDeferred<Unit>()
        val gatedGateway = object : SafeDriveGateway by gateway {
            override suspend fun queryAssistant(request: AssistantQueryRequest): GatewayResult<AssistantQueryResult> {
                queryGate.await()
                return gateway.queryAssistant(request)
            }
        }
        val sessionCoordinator = SessionCoordinator(fakeProvider { gatedGateway }, appPreferences, idGenerator, clock, "test")
        val useCase = AssistantQueryUseCase(sessionCoordinator, clock)
        val realScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val coordinator = AssistantTurnCoordinator(
            conversationRepository = repository,
            assistantQueryUseCase = useCase,
            cockpitSnapshot = cockpitSnapshot,
            appPreferences = appPreferences,
            ttsController = FakeTtsController(),
            metricsRecorder = AssistantTurnMetricsRecorder(),
            lastHealthStatus = MutableStateFlow(null),
            idGenerator = idGenerator,
            clock = clock,
            externalScope = realScope,
        )

        try {
            val threadCount = 24
            val barrier = CyclicBarrier(threadCount)
            val acceptedCount = AtomicInteger(0)
            val acceptedTurn = AtomicReference<AssistantTurnCoordinator.StartedTurn?>(null)
            val threads = (1..threadCount).map { i ->
                Thread {
                    barrier.await()
                    val started = coordinator.submit("Câu hỏi đồng thời #$i", AssistantTurnSource.TEXT, "assistant") { turn ->
                        acceptedTurn.set(turn)
                    }
                    if (started) acceptedCount.incrementAndGet()
                }
            }
            threads.forEach { it.start() }
            threads.forEach { it.join(10_000) }
            // Every worker thread must have genuinely terminated — silently continuing past a timed
            // join's deadline would hide a real hang instead of failing on it.
            assertThat(threads.all { !it.isAlive }).isTrue()

            assertThat(acceptedCount.get()).isEqualTo(1)
            val turn = acceptedTurn.get()
            assertThat(turn).isNotNull()

            // The single accepted turn's query is genuinely gated, suspended inside the gateway right
            // now — this is what kept it in flight while every one of the other 23 (correctly rejected)
            // submit calls above ran, rather than racing a fast, ungated mock reply to completion.
            assertThat(repository.state.value.inFlightTurn).isNotNull()
            assertThat(repository.state.value.messages.count { it.sender.name == "USER" }).isEqualTo(1)

            queryGate.complete(Unit)

            // Deterministically awaits this exact turn's own terminal state via its per-turn Deferred —
            // never a Thread.sleep polling loop over ConversationRepository's StateFlow.
            val terminal = runBlocking { withTimeout(5_000) { turn!!.completion.await() } }
            assertThat(terminal).isInstanceOf(AssistantTurnState.Success::class.java)

            assertThat(repository.state.value.inFlightTurn).isNull()
            assertThat(repository.state.value.messages.count { it.sender.name == "USER" }).isEqualTo(1)
            assertThat(repository.state.value.messages.count { it.sender.name == "SAFEDRIVE" }).isEqualTo(1)
        } finally {
            realScope.cancel()
        }
    }

    // --- Remediation item 3/5: a session-resolution failure (as opposed to a query failure after a
    // successful session) must never report a fabricated requestSentAtMs/networkMs — no network
    // request was ever actually sent in that case. ---

    @Test
    fun `session resolution failure records metrics with no fabricated requestSentAtMs or networkMs`() =
        runTest(mainDispatcherRule.dispatcher) {
            val recorder = AssistantTurnMetricsRecorder()
            val sessionFailingGateway = object : SafeDriveGateway by gateway {
                override suspend fun startSession(request: StartSessionRequest): GatewayResult<SessionInfo> =
                    GatewayResult.Failure(GatewayError.Unauthorized)
            }
            val (coordinator, _, _) = buildCoordinator(this, gatewayOverride = sessionFailingGateway, metricsRecorder = recorder)

            coordinator.submit("Xe có lỗi gì?", AssistantTurnSource.TEXT, "assistant")
            advanceUntilIdle()

            val metrics = recorder.lastTurn.value
            assertThat(metrics).isNotNull()
            assertThat(metrics!!.requestSentAtMs).isNull()
            assertThat(metrics.networkMs).isNull()
        }

    @Test
    fun `a completed turn records the backend-reported serverProcessingMs (W7_4)`() = runTest(mainDispatcherRule.dispatcher) {
        val recorder = AssistantTurnMetricsRecorder()
        val (coordinator, _, _) = buildCoordinator(this, metricsRecorder = recorder)
        coordinator.submit("Kiểm tra nhiệt độ động cơ", AssistantTurnSource.TEXT, "assistant")
        advanceUntilIdle()

        assertThat(recorder.lastTurn.value?.serverProcessingMs).isNotNull()
    }

    @Test
    fun `the TTS engine's real onStart callback populates ttsStartedAtMs, not just when speak() was called`() =
        runTest(mainDispatcherRule.dispatcher) {
            val recorder = AssistantTurnMetricsRecorder()
            val tts = FakeTtsController()
            val (coordinator, _, _) = buildCoordinator(
                this,
                appPreferences = MutableStateFlow(AppPreferences(ttsEnabled = true)),
                ttsController = tts,
                metricsRecorder = recorder,
            )

            coordinator.submit("Kiểm tra nhiệt độ động cơ", AssistantTurnSource.TEXT, "assistant")

            val utteranceId = tts.lastUtteranceId
            assertThat(utteranceId).isNotNull()
            assertThat(recorder.lastTurn.value?.ttsStartedAtMs).isNull() // onStart hasn't fired yet

            tts.emitStarted(utteranceId!!, clock.nowMs() + 42L)
            advanceUntilIdle()

            val metrics = recorder.lastTurn.value
            assertThat(metrics!!.ttsStartedAtMs).isNotNull()
            assertThat(metrics.responseToTtsStartMs).isNotNull()
        }

    // --- Independent re-audit follow-up, item 1: clientAttemptOf must stay null for every path where
    // the assistant query was never actually dispatched — including cancel-before-send and an
    // incompatible contractVersion — not just the health-blocked path already covered above. ---

    @Test
    fun `cancel while startSession is still suspended never claims a network attempt was sent`() =
        runTest(mainDispatcherRule.dispatcher) {
            val sessionGate = CompletableDeferred<Unit>()
            val suspendingGateway = object : SafeDriveGateway by gateway {
                override suspend fun startSession(request: StartSessionRequest): GatewayResult<SessionInfo> {
                    sessionGate.await()
                    return gateway.startSession(request)
                }
            }
            val (coordinator, repository, _) = buildCoordinator(this, gatewayOverride = suspendingGateway)

            coordinator.submit("Xe có lỗi gì?", AssistantTurnSource.TEXT, "assistant")
            // Session resolution is parked on sessionGate — no query has been sent yet.
            coordinator.cancelCurrent()
            sessionGate.complete(Unit)
            advanceUntilIdle()

            assertThat(repository.state.value.retryableTurn?.clientAttemptOf).isNull()
            assertThat(repository.state.value.retryableTurn?.wasCancelled).isTrue()
        }

    @Test
    fun `cancel after the query has actually been sent still preserves clientAttemptOf lineage for retry`() =
        runTest(mainDispatcherRule.dispatcher) {
            val queryGate = CompletableDeferred<Unit>()
            var capturedRequestId: String? = null
            val suspendingGateway = object : SafeDriveGateway by gateway {
                override suspend fun queryAssistant(request: AssistantQueryRequest): GatewayResult<AssistantQueryResult> {
                    capturedRequestId = request.requestId
                    queryGate.await()
                    return gateway.queryAssistant(request)
                }
            }
            val (coordinator, repository, _) = buildCoordinator(this, gatewayOverride = suspendingGateway)

            coordinator.submit("Xe có lỗi gì?", AssistantTurnSource.TEXT, "assistant")
            // onTiming has already fired (the query was actually dispatched) and is now parked mid-flight.
            assertThat(capturedRequestId).isNotNull()
            coordinator.cancelCurrent()
            queryGate.complete(Unit)
            advanceUntilIdle()

            assertThat(repository.state.value.retryableTurn?.clientAttemptOf).isEqualTo(capturedRequestId)
            assertThat(repository.state.value.retryableTurn?.wasCancelled).isTrue()
        }

    @Test
    fun `an incompatible contractVersion failure never claims a network attempt was sent`() =
        runTest(mainDispatcherRule.dispatcher) {
            val incompatibleGateway = object : SafeDriveGateway by gateway {
                override suspend fun startSession(request: StartSessionRequest): GatewayResult<SessionInfo> =
                    GatewayResult.Success(
                        SessionInfo(
                            sessionId = "sess_x",
                            expiresAtMs = clock.nowMs() + 60_000L,
                            serverTimeMs = clock.nowMs(),
                            contractVersion = "v2",
                        ),
                    )
            }
            val (coordinator, repository, _) = buildCoordinator(this, gatewayOverride = incompatibleGateway)

            coordinator.submit("Xe có lỗi gì?", AssistantTurnSource.TEXT, "assistant")
            advanceUntilIdle()

            assertThat(repository.state.value.retryableTurn?.clientAttemptOf).isNull()
            assertThat(repository.state.value.errorMessage).isNotNull()
        }

    // --- Independent re-audit follow-up, item 5: an explicit wasCancelled discriminator so a reader
    // of ConversationState (e.g. VoiceOverlay) never has to infer "was this cancelled or did it really
    // fail" from the incidental fact that cancel happens not to set errorMessage. ---

    @Test
    fun `cancel marks the retryable turn wasCancelled=true and sets no error message`() =
        runTest(mainDispatcherRule.dispatcher) {
            val (coordinator, repository, _) = buildCoordinator(this, gatewayOverride = slowGateway())
            coordinator.submit("Câu hỏi sẽ bị hủy", AssistantTurnSource.TEXT, "assistant")
            coordinator.cancelCurrent()

            assertThat(repository.state.value.retryableTurn?.wasCancelled).isTrue()
            assertThat(repository.state.value.errorMessage).isNull()
        }

    @Test
    fun `a real gateway failure marks the retryable turn wasCancelled=false and sets an error message`() =
        runTest(mainDispatcherRule.dispatcher) {
            val failingGateway = object : SafeDriveGateway by gateway {
                override suspend fun queryAssistant(request: AssistantQueryRequest): GatewayResult<AssistantQueryResult> =
                    GatewayResult.Failure(GatewayError.Timeout)
            }
            val (coordinator, repository, _) = buildCoordinator(this, gatewayOverride = failingGateway)
            coordinator.submit("Xe có lỗi gì?", AssistantTurnSource.TEXT, "assistant")
            advanceUntilIdle()

            assertThat(repository.state.value.retryableTurn?.wasCancelled).isFalse()
            assertThat(repository.state.value.errorMessage).isNotNull()
        }

    @Test
    fun `full turn state transition matrix - idle to in-flight to success, to failure-then-retry, and to cancelled`() =
        runTest(mainDispatcherRule.dispatcher) {
            // idle -> in-flight -> success (a slow gateway widens the in-flight window enough to
            // observe it — Demo Mode's own default gateway has no artificial delay, see slowGateway()'s
            // KDoc, so a fast default gateway can complete synchronously under UnconfinedTestDispatcher
            // before there is any window left to observe "in-flight" in).
            val (coordinator, repository, _) = buildCoordinator(this, gatewayOverride = slowGateway())
            assertThat(repository.state.value.inFlightTurn).isNull() // idle
            coordinator.submit("Kiểm tra nhiệt độ động cơ", AssistantTurnSource.TEXT, "assistant")
            assertThat(repository.state.value.inFlightTurn).isNotNull() // in-flight
            advanceUntilIdle()
            assertThat(repository.state.value.inFlightTurn).isNull()
            assertThat(repository.state.value.messages.last().sender.name).isEqualTo("SAFEDRIVE") // success
            assertThat(repository.state.value.retryableTurn).isNull()

            // idle -> in-flight -> failure -> retry mints a new requestId -> success
            var callCount = 0
            val requestIds = mutableListOf<String>()
            val failThenSucceed = object : SafeDriveGateway by gateway {
                override suspend fun queryAssistant(request: AssistantQueryRequest): GatewayResult<AssistantQueryResult> {
                    callCount++
                    requestIds.add(request.requestId)
                    return if (callCount == 1) GatewayResult.Failure(GatewayError.Timeout) else gateway.queryAssistant(request)
                }
            }
            val (coordinator2, repository2, _) = buildCoordinator(this, gatewayOverride = failThenSucceed)
            coordinator2.submit("Câu hỏi khác", AssistantTurnSource.TEXT, "assistant")
            advanceUntilIdle()
            assertThat(repository2.state.value.retryableTurn).isNotNull() // failure
            assertThat(repository2.state.value.retryableTurn!!.wasCancelled).isFalse()
            coordinator2.retry()
            advanceUntilIdle()
            assertThat(requestIds).hasSize(2)
            assertThat(requestIds[0]).isNotEqualTo(requestIds[1]) // retry mints a new requestId
            assertThat(repository2.state.value.retryableTurn).isNull() // success now

            // idle -> in-flight -> cancelled -> a stale success arriving later must not resurrect it
            val (coordinator3, repository3, _) = buildCoordinator(this, gatewayOverride = slowGateway())
            coordinator3.submit("Câu hỏi bị hủy", AssistantTurnSource.TEXT, "assistant")
            coordinator3.cancelCurrent()
            assertThat(repository3.state.value.retryableTurn?.wasCancelled).isTrue() // cancelled
            advanceUntilIdle() // the cancelled gateway call, if it ever resolves, must not resurrect state
            assertThat(repository3.state.value.messages.none { it.sender.name == "SAFEDRIVE" }).isTrue()
            assertThat(repository3.state.value.inFlightTurn).isNull()
        }

    // --- Independent re-audit follow-up, item 4: TtsController.events collection must be registered
    // *before* speak() is called, or an engine whose onStart fires synchronously (or faster than the
    // coordinator's own coroutine gets scheduled) would emit into a stream nobody is listening to yet. ---

    @Test
    fun `TTS onStart firing synchronously inside speak() is still captured, not lost to the subscription race`() =
        runTest(mainDispatcherRule.dispatcher) {
            val recorder = AssistantTurnMetricsRecorder()
            val tts = FakeTtsController(autoEmitStartAtMs = { clock.nowMs() + 5L })
            val (coordinator, _, _) = buildCoordinator(
                this,
                appPreferences = MutableStateFlow(AppPreferences(ttsEnabled = true)),
                ttsController = tts,
                metricsRecorder = recorder,
            )

            coordinator.submit("Kiểm tra nhiệt độ động cơ", AssistantTurnSource.TEXT, "assistant")
            advanceUntilIdle()

            assertThat(recorder.lastTurn.value?.ttsStartedAtMs).isNotNull()
        }

    @Test
    fun `a tts onStart event for a different utteranceId is ignored, the correct one still populates ttsStartedAtMs`() =
        runTest(mainDispatcherRule.dispatcher) {
            val recorder = AssistantTurnMetricsRecorder()
            val tts = FakeTtsController()
            val (coordinator, _, _) = buildCoordinator(
                this,
                appPreferences = MutableStateFlow(AppPreferences(ttsEnabled = true)),
                ttsController = tts,
                metricsRecorder = recorder,
            )

            coordinator.submit("Kiểm tra nhiệt độ động cơ", AssistantTurnSource.TEXT, "assistant")
            val utteranceId = tts.lastUtteranceId!!

            // Both emissions happen before any advanceUntilIdle(): a single advanceUntilIdle() after a
            // *real* (non-timeout) completion never needs to fast-forward virtual time, unlike calling
            // it after only the non-matching emission would (which would spuriously burn through the
            // whole 5s bounded wait and make the second, correct emission arrive too late to matter).
            tts.emitStarted("some-other-utterance-id", clock.nowMs() + 1L)
            tts.emitStarted(utteranceId, clock.nowMs() + 2L)
            advanceUntilIdle()

            // Proves the wrong id was actually filtered, not just "some value or other" got recorded:
            // if the mismatched event had matched, this would equal +1L instead.
            assertThat(recorder.lastTurn.value?.ttsStartedAtMs).isEqualTo(clock.nowMs() + 2L)
        }

    @Test
    fun `TTS disabled never creates a tts-start waiter and never fabricates ttsStartedAtMs`() =
        runTest(mainDispatcherRule.dispatcher) {
            val recorder = AssistantTurnMetricsRecorder()
            val (coordinator, _, tts) = buildCoordinator(
                this,
                appPreferences = MutableStateFlow(AppPreferences(ttsEnabled = false)),
                metricsRecorder = recorder,
            )
            coordinator.submit("Kiểm tra nhiệt độ động cơ", AssistantTurnSource.TEXT, "assistant")
            advanceUntilIdle()

            assertThat(tts.speakCallCount).isEqualTo(0)
            assertThat(recorder.lastTurn.value?.ttsStartedAtMs).isNull()
        }

    @Test
    fun `if the TTS onStart callback never arrives, ttsStartedAtMs stays null after the bounded wait times out`() =
        runTest(mainDispatcherRule.dispatcher) {
            val recorder = AssistantTurnMetricsRecorder()
            val tts = FakeTtsController() // never emits a start event — simulates a dropped/unsupported callback
            val (coordinator, _, _) = buildCoordinator(
                this,
                appPreferences = MutableStateFlow(AppPreferences(ttsEnabled = true)),
                ttsController = tts,
                metricsRecorder = recorder,
            )

            coordinator.submit("Kiểm tra nhiệt độ động cơ", AssistantTurnSource.TEXT, "assistant")
            advanceUntilIdle() // fast-forwards virtual time through the 5s bounded wait

            assertThat(recorder.lastTurn.value?.ttsStartedAtMs).isNull()
        }

    // --- Independent re-audit follow-up, item 3: AssistantTurnState terminal transitions must be
    // published atomically — a collector must never observe inFlightTurn == null with the reply not
    // yet appended / turnState not yet terminal. ---

    @Test
    fun `every published state where inFlightTurn becomes null already carries the terminal turnState and, on success, the appended reply`() =
        runTest(mainDispatcherRule.dispatcher) {
            val (coordinator, repository, _) = buildCoordinator(this, gatewayOverride = slowGateway())
            val observed = mutableListOf<ConversationState>()
            val collectJob = launch { repository.state.collect { observed.add(it) } }

            coordinator.submit("Kiểm tra nhiệt độ động cơ", AssistantTurnSource.TEXT, "assistant")
            advanceUntilIdle()
            collectJob.cancel()

            // Never a state where the turn already looks "done" (no in-flight turn) but the state
            // machine still thinks it's in flight — this exact combination was possible with the old
            // separate setInFlightTurn(null)/addAssistantMessage publishes.
            assertThat(observed.none { it.inFlightTurn == null && it.turnState is AssistantTurnState.InFlight }).isTrue()

            val terminal = observed.first { it.inFlightTurn == null && it.turnState is AssistantTurnState.Success }
            val terminalTurnState = terminal.turnState as AssistantTurnState.Success
            // The exact same published ConversationState that cleared inFlightTurn already has the
            // reply appended to messages and it is the same object the turnState carries.
            assertThat(terminal.messages.last().sender.name).isEqualTo("SAFEDRIVE")
            assertThat(terminalTurnState.reply).isEqualTo(terminal.messages.last())
        }

    @Test
    fun `a failed turn's terminal state carries the exact requestId, generation and source of the turn that failed`() =
        runTest(mainDispatcherRule.dispatcher) {
            val failingGateway = object : SafeDriveGateway by gateway {
                override suspend fun queryAssistant(request: AssistantQueryRequest): GatewayResult<AssistantQueryResult> =
                    GatewayResult.Failure(GatewayError.Timeout)
            }
            val (coordinator, repository, _) = buildCoordinator(this, gatewayOverride = failingGateway)
            coordinator.submit("Xe có lỗi gì?", AssistantTurnSource.VOICE, "cockpit")
            advanceUntilIdle()

            val turnState = repository.state.value.turnState
            assertThat(turnState).isInstanceOf(AssistantTurnState.Failure::class.java)
            val failure = turnState as AssistantTurnState.Failure
            assertThat(failure.requestId).isEqualTo(repository.state.value.retryableTurn?.clientAttemptOf)
            assertThat(failure.source).isEqualTo(AssistantTurnSource.VOICE)
            assertThat(failure.generation).isEqualTo(1L)
        }

    @Test
    fun `a cancelled turn's terminal state is Cancelled with the exact requestId of the turn that was cancelled`() =
        runTest(mainDispatcherRule.dispatcher) {
            val (coordinator, repository, _) = buildCoordinator(this, gatewayOverride = slowGateway())
            coordinator.submit("Câu hỏi sẽ bị hủy", AssistantTurnSource.TEXT, "assistant")
            val inFlightRequestId = repository.state.value.inFlightTurn?.requestId
            coordinator.cancelCurrent()

            val turnState = repository.state.value.turnState
            assertThat(turnState).isInstanceOf(AssistantTurnState.Cancelled::class.java)
            assertThat((turnState as AssistantTurnState.Cancelled).requestId).isEqualTo(inFlightRequestId)
        }

    // --- Independent re-audit follow-up, item 5: recordTtsStarted() must never lose a real TTS onStart
    // callback to a race against the base metrics record — proven here with a genuine background
    // thread firing the callback as fast as possible, not a virtual-time trick that can't reproduce the
    // real production race at all. ---

    @Test
    fun `a TTS onStart event fired from a genuine background thread the instant speak is called is still merged, never lost to the race`() {
        val recorder = AssistantTurnMetricsRecorder()
        val realScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        // Simulates a real TextToSpeech engine whose UtteranceProgressListener.onStart() callback
        // arrives on the engine's own internal thread, potentially before the calling thread even
        // returns from speak() — the actual production race, not FakeTtsController's single-threaded
        // synchronous emission.
        val racingTts = object : TtsController {
            private val _state = MutableStateFlow(TtsState.READY)
            override val state: StateFlow<TtsState> = _state
            private val _events = MutableSharedFlow<TtsUtteranceEvent>(extraBufferCapacity = 4)
            override val events: SharedFlow<TtsUtteranceEvent> = _events

            override fun speak(text: String, utteranceId: String) {
                _state.value = TtsState.SPEAKING
                Thread { _events.tryEmit(TtsUtteranceEvent(utteranceId, clock.nowMs())) }.start()
            }

            override fun stop() {
                _state.value = TtsState.READY
            }
        }
        val repository = InMemoryConversationRepository(initialMessages = emptyList())
        val appPreferences = MutableStateFlow(AppPreferences(ttsEnabled = true))
        val sessionCoordinator = SessionCoordinator(fakeProvider { gateway }, appPreferences, idGenerator, clock, "test")
        val useCase = AssistantQueryUseCase(sessionCoordinator, clock)
        val coordinator = AssistantTurnCoordinator(
            conversationRepository = repository,
            assistantQueryUseCase = useCase,
            cockpitSnapshot = cockpitSnapshot,
            appPreferences = appPreferences,
            ttsController = racingTts,
            metricsRecorder = recorder,
            lastHealthStatus = MutableStateFlow(null),
            idGenerator = idGenerator,
            clock = clock,
            externalScope = realScope,
        )

        try {
            assertThat(coordinator.submit("Kiểm tra nhiệt độ động cơ", AssistantTurnSource.TEXT, "assistant")).isTrue()

            val deadline = System.currentTimeMillis() + 5_000
            while (recorder.lastTurn.value?.ttsStartedAtMs == null && System.currentTimeMillis() < deadline) {
                Thread.sleep(10)
            }

            val metrics = recorder.lastTurn.value
            assertThat(metrics).isNotNull()
            assertThat(metrics!!.requestId).isNotEmpty()
            assertThat(metrics.ttsStartedAtMs).isNotNull() // never lost, even racing on a real OS thread
        } finally {
            realScope.cancel()
        }
    }

    // --- Blocker 1: a turn's completion must resolve to its own terminal state regardless of what
    // ConversationRepository.state has since moved on to. The old design (VoiceAssistantCoordinator
    // subscribing to `conversationRepository.state.map { it.turnState }.first { matches }`) has a real
    // hang hazard: that StateFlow only ever holds the *latest* turnState, so if a turn's terminal value
    // is published and then superseded by a *different* turn before anything ever subscribes looking
    // for the first turn's id, such a `.first { }` collector would search the current (already
    // different) value forever — StateFlow does not replay superseded history. These tests construct
    // that exact "already overwritten" scenario deterministically (not via timing luck) and prove the
    // new `StartedTurn.completion: Deferred<AssistantTurnState>` design has no such hazard: a Deferred
    // resolves to the exact value it was completed with regardless of when `.await()` is called. ---

    @Test
    fun `a turn's completion resolves to its own terminal state even after ConversationRepository's turnState has since moved on to a different, later turn`() =
        runTest(mainDispatcherRule.dispatcher) {
            val (coordinator, repository, _) = buildCoordinator(this)

            var firstTurn: AssistantTurnCoordinator.StartedTurn? = null
            coordinator.submit("Kiểm tra nhiệt độ động cơ", AssistantTurnSource.VOICE, "cockpit") { firstTurn = it }
            advanceUntilIdle()
            val firstReplyText = repository.state.value.messages.last().text

            // A second, unrelated turn starts and completes, overwriting ConversationRepository's
            // turnState/messages entirely — nothing about turn 1 remains observable there anymore.
            var secondTurn: AssistantTurnCoordinator.StartedTurn? = null
            coordinator.submit("Tốc độ hiện tại?", AssistantTurnSource.TEXT, "assistant") { secondTurn = it }
            advanceUntilIdle()
            assertThat(repository.state.value.turnState).isInstanceOf(AssistantTurnState.Success::class.java)
            assertThat((repository.state.value.turnState as AssistantTurnState.Success).requestId).isEqualTo(secondTurn!!.requestId)

            // Despite ConversationRepository having no memory of turn 1 left, its own completion —
            // captured at turn-start time, long before any of this happened — still resolves correctly.
            val firstOutcome = firstTurn!!.completion.await()
            assertThat(firstOutcome).isInstanceOf(AssistantTurnState.Success::class.java)
            assertThat((firstOutcome as AssistantTurnState.Success).requestId).isEqualTo(firstTurn!!.requestId)
            assertThat(firstOutcome.reply.text).isEqualTo(firstReplyText)
        }

    @Test
    fun `a synchronously-completed health-blocked turn's completion also resolves correctly after being overwritten by a later successful turn`() =
        runTest(mainDispatcherRule.dispatcher) {
            val healthFlow = MutableStateFlow<vn.edu.haui.hvs.safedrive.core.model.HealthStatus?>(
                vn.edu.haui.hvs.safedrive.core.model.HealthStatus(
                    status = vn.edu.haui.hvs.safedrive.core.model.SystemConnectionStatus.NORMAL,
                    serviceName = "test-backend",
                    apiVersion = "v1",
                    serverTimeMs = clock.nowMs(),
                    capabilities = vn.edu.haui.hvs.safedrive.core.model.HealthCapabilities(
                        assistant = false,
                        emergencySimulation = true,
                        cockpitStream = false,
                    ),
                ),
            )
            val (coordinator, repository, _) = buildCoordinator(this, lastHealthStatus = healthFlow)

            var blockedTurn: AssistantTurnCoordinator.StartedTurn? = null
            coordinator.submit("Xe có lỗi gì?", AssistantTurnSource.VOICE, "cockpit") { blockedTurn = it }
            // Resolved synchronously — the health-blocked path never reaches the network at all.
            assertThat(blockedTurn!!.completion.isCompleted).isTrue()

            healthFlow.value = null // capability check no longer blocks — the next turn succeeds for real
            var secondTurn: AssistantTurnCoordinator.StartedTurn? = null
            coordinator.submit("Một câu khác", AssistantTurnSource.TEXT, "assistant") { secondTurn = it }
            advanceUntilIdle()
            assertThat(repository.state.value.turnState).isInstanceOf(AssistantTurnState.Success::class.java)
            assertThat((repository.state.value.turnState as AssistantTurnState.Success).requestId).isEqualTo(secondTurn!!.requestId)

            val outcome = blockedTurn!!.completion.await()
            assertThat(outcome).isInstanceOf(AssistantTurnState.Failure::class.java)
            assertThat((outcome as AssistantTurnState.Failure).requestId).isEqualTo(blockedTurn!!.requestId)
        }

    @Test
    fun `cancelling a turn resolves its completion to Cancelled, releasing any waiter without a Success or Failure outcome`() =
        runTest(mainDispatcherRule.dispatcher) {
            val (coordinator, _, _) = buildCoordinator(this, gatewayOverride = slowGateway())
            var turn: AssistantTurnCoordinator.StartedTurn? = null
            coordinator.submit("Câu hỏi sẽ bị hủy", AssistantTurnSource.TEXT, "assistant") { turn = it }
            assertThat(turn!!.completion.isCompleted).isFalse() // genuinely in flight, not yet resolved

            coordinator.cancelCurrent()

            val outcome = turn!!.completion.await() // must not hang
            assertThat(outcome).isInstanceOf(AssistantTurnState.Cancelled::class.java)
        }

    @Test
    fun `a cancelled turn's completion is resolved exactly once - a later stale gateway resolution can never complete it again`() =
        runTest(mainDispatcherRule.dispatcher) {
            val (coordinator, _, _) = buildCoordinator(this, gatewayOverride = slowGateway())
            var turn: AssistantTurnCoordinator.StartedTurn? = null
            coordinator.submit("Câu hỏi sẽ bị hủy", AssistantTurnSource.TEXT, "assistant") { turn = it }
            coordinator.cancelCurrent()
            val firstRead = turn!!.completion.await()

            advanceUntilIdle() // let the now-superseded gateway call, if it ever resolves, try to run

            // Deferred identity guarantees a second await() returns the exact same completed value —
            // proving the generation-guarded coroutine never called completion.complete() a second time.
            val secondRead = turn!!.completion.await()
            assertThat(secondRead).isSameInstanceAs(firstRead)
            assertThat(firstRead).isInstanceOf(AssistantTurnState.Cancelled::class.java)
        }

    // --- Independent re-audit follow-up, blocker 1: the coordinator's launched coroutine used to call
    // assistantQueryUseCase() with no try/catch at all. If it ever threw (a mapper bug, a
    // RuntimeException from deep inside session resolution/the gateway, anything other than a typed
    // GatewayResult.Failure) the exception propagated out of the coroutine uncaught: currentJob/
    // currentAttempt/currentCompletion were never cleared, ConversationState stayed InFlight forever,
    // and StartedTurn.completion never resolved — a real, permanent hang for any waiter (e.g.
    // VoiceAssistantCoordinator.route()'s completion.await()). These tests reproduce that exact
    // exception shape (a throw, not a Failure return) and prove the turn now still reaches a real
    // terminal state. On the old (pre-fix) code every one of these tests fails for real: `runTest`
    // propagates an uncaught exception from a coroutine launched directly on its own TestScope, so the
    // unwrapped RuntimeException below would fail the test itself, not just leave a wrong value. ---

    @Test
    fun `the gateway throwing an unexpected exception during the query still terminates the turn as Failure, not a hang`() =
        runTest(mainDispatcherRule.dispatcher) {
            val throwingGateway = object : SafeDriveGateway by gateway {
                override suspend fun queryAssistant(request: AssistantQueryRequest): GatewayResult<AssistantQueryResult> {
                    throw RuntimeException("boom - simulated mapper/gateway bug")
                }
            }
            val (coordinator, repository, _) = buildCoordinator(this, gatewayOverride = throwingGateway)

            var turn: AssistantTurnCoordinator.StartedTurn? = null
            assertThat(coordinator.submit("Xe có lỗi gì?", AssistantTurnSource.TEXT, "assistant") { turn = it }).isTrue()
            advanceUntilIdle()

            assertThat(repository.state.value.inFlightTurn).isNull()
            assertThat(repository.state.value.turnState).isInstanceOf(AssistantTurnState.Failure::class.java)
            assertThat(repository.state.value.retryableTurn).isNotNull()
            // onTiming fires immediately before queryAssistant() is called (see AssistantQueryUseCase),
            // so it already ran by the time this override throws — the query really was dispatched, so
            // clientAttemptOf correctly still references it for retry lineage (remediation item 1); only
            // a *session-resolution* exception (next test) must leave it null.
            assertThat(repository.state.value.retryableTurn?.clientAttemptOf).isEqualTo(repository.state.value.turnState.let { (it as AssistantTurnState.Failure).requestId })

            val outcome = withTimeout(5_000) { turn!!.completion.await() } // must not hang
            assertThat(outcome).isInstanceOf(AssistantTurnState.Failure::class.java)
        }

    @Test
    fun `session resolution throwing an unexpected exception still terminates the turn as Failure, not a hang`() =
        runTest(mainDispatcherRule.dispatcher) {
            val throwingGateway = object : SafeDriveGateway by gateway {
                override suspend fun startSession(request: StartSessionRequest): GatewayResult<SessionInfo> {
                    throw IllegalStateException("boom - simulated session bug")
                }
            }
            val (coordinator, repository, _) = buildCoordinator(this, gatewayOverride = throwingGateway)

            var turn: AssistantTurnCoordinator.StartedTurn? = null
            coordinator.submit("Xe có lỗi gì?", AssistantTurnSource.TEXT, "assistant") { turn = it }
            advanceUntilIdle()

            assertThat(repository.state.value.inFlightTurn).isNull()
            assertThat(repository.state.value.turnState).isInstanceOf(AssistantTurnState.Failure::class.java)
            assertThat(repository.state.value.retryableTurn?.clientAttemptOf).isNull() // never dispatched

            val outcome = withTimeout(5_000) { turn!!.completion.await() } // must not hang
            assertThat(outcome).isInstanceOf(AssistantTurnState.Failure::class.java)
        }

    @Test
    fun `an onStarted callback that throws never leaves an in-flight turn without a job to resolve it`() =
        runTest(mainDispatcherRule.dispatcher) {
            val (coordinator, repository, _) = buildCoordinator(this)

            // submit() itself must not propagate the caller callback's own bug — on the old code this
            // throw happened before currentJob was ever assigned, permanently stranding the turn
            // InFlight with nothing left to resolve it.
            val accepted = coordinator.submit("Kiểm tra nhiệt độ động cơ", AssistantTurnSource.TEXT, "assistant") {
                throw RuntimeException("buggy caller callback")
            }
            assertThat(accepted).isTrue()
            advanceUntilIdle()

            assertThat(repository.state.value.inFlightTurn).isNull()
            assertThat(repository.state.value.turnState).isInstanceOf(AssistantTurnState.Success::class.java)
        }

    @Test
    fun `an exception thrown by metrics recording after a Success is already committed never reverses the terminal state or hangs the turn`() =
        runTest(mainDispatcherRule.dispatcher) {
            val throwingRecorder = AssistantTurnMetricsRecorder(logger = { throw RuntimeException("boom in logger") })
            val (coordinator, repository, _) = buildCoordinator(this, metricsRecorder = throwingRecorder)

            var turn: AssistantTurnCoordinator.StartedTurn? = null
            coordinator.submit("Kiểm tra nhiệt độ động cơ", AssistantTurnSource.TEXT, "assistant") { turn = it }
            advanceUntilIdle() // must not crash despite the logger throwing on every record() call

            assertThat(repository.state.value.turnState).isInstanceOf(AssistantTurnState.Success::class.java)
            assertThat(repository.state.value.inFlightTurn).isNull()
            val outcome = withTimeout(5_000) { turn!!.completion.await() } // must not hang
            assertThat(outcome).isInstanceOf(AssistantTurnState.Success::class.java)
        }

    @Test
    fun `an exception thrown by the TTS engine after a Success is already committed never reverses the terminal state or hangs the turn`() =
        runTest(mainDispatcherRule.dispatcher) {
            val throwingTts = object : TtsController {
                private val _state = MutableStateFlow(TtsState.READY)
                override val state: StateFlow<TtsState> = _state
                override val events: SharedFlow<TtsUtteranceEvent> = MutableSharedFlow()
                override fun speak(text: String, utteranceId: String) {
                    throw RuntimeException("boom in tts engine")
                }
                override fun stop() {
                    _state.value = TtsState.READY
                }
            }
            val repository = InMemoryConversationRepository(initialMessages = emptyList())
            val appPreferences = MutableStateFlow(AppPreferences(ttsEnabled = true))
            val sessionCoordinator = SessionCoordinator(fakeProvider { gateway }, appPreferences, idGenerator, clock, "test")
            val useCase = AssistantQueryUseCase(sessionCoordinator, clock)
            val coordinator = AssistantTurnCoordinator(
                conversationRepository = repository,
                assistantQueryUseCase = useCase,
                cockpitSnapshot = cockpitSnapshot,
                appPreferences = appPreferences,
                ttsController = throwingTts,
                metricsRecorder = AssistantTurnMetricsRecorder(),
                lastHealthStatus = MutableStateFlow(null),
                idGenerator = idGenerator,
                clock = clock,
                externalScope = this,
            )

            var turn: AssistantTurnCoordinator.StartedTurn? = null
            coordinator.submit("Kiểm tra nhiệt độ động cơ", AssistantTurnSource.TEXT, "assistant") { turn = it }
            advanceUntilIdle() // must not crash despite speak() throwing

            assertThat(repository.state.value.turnState).isInstanceOf(AssistantTurnState.Success::class.java)
            val outcome = withTimeout(5_000) { turn!!.completion.await() } // must not hang
            assertThat(outcome).isInstanceOf(AssistantTurnState.Success::class.java)
        }

    @Test
    fun `an exception thrown by an already-cancelled turn's coroutine after cancelCurrent never re-completes its Deferred or crashes the scope`() {
        // Two real (non-coroutine-aware) blocking latches, not one: inFlightTurn is published
        // *synchronously* inside submit(), before the coroutine that actually calls queryAssistant() on
        // a real thread pool even starts running — so merely waiting for `inFlightTurn != null` (as an
        // earlier version of this test did) proves nothing about whether the gateway itself has actually
        // been entered yet. enteredGateway is the one authoritative signal that the query coroutine is
        // genuinely running and about to block; only once that is observed is it safe to cancel and know
        // the cancellation is racing a coroutine that is truly in flight, not one that hasn't started.
        // releaseGateway then lets it proceed to throw, past cancellation's reach — CountDownLatch.await()
        // is not a coroutine suspension point, so Job.cancel() cannot interrupt it, unlike delay()/await()
        // on a CompletableDeferred, which would instead be cancelled at the gate itself.
        val enteredGateway = CountDownLatch(1)
        val releaseGateway = CountDownLatch(1)
        val throwsAfterLatch = object : SafeDriveGateway by gateway {
            override suspend fun queryAssistant(request: AssistantQueryRequest): GatewayResult<AssistantQueryResult> {
                enteredGateway.countDown()
                releaseGateway.await()
                throw RuntimeException("stale exception from an already-superseded generation")
            }
        }
        val repository = InMemoryConversationRepository(initialMessages = emptyList())
        val appPreferences = MutableStateFlow(AppPreferences())
        val sessionCoordinator = SessionCoordinator(fakeProvider { throwsAfterLatch }, appPreferences, idGenerator, clock, "test")
        val useCase = AssistantQueryUseCase(sessionCoordinator, clock)
        val uncaughtExceptions = java.util.Collections.synchronizedList(mutableListOf<Throwable>())
        // A SupervisorJob alone would silently swallow an uncaught child exception without failing this
        // test either way — this handler makes "did the old code crash the coroutine" directly and
        // deterministically observable instead of merely inferring it from state staying unchanged.
        val exceptionHandler = CoroutineExceptionHandler { _, throwable -> uncaughtExceptions.add(throwable) }
        val realScope = CoroutineScope(SupervisorJob() + Dispatchers.Default + exceptionHandler)
        val coordinator = AssistantTurnCoordinator(
            conversationRepository = repository,
            assistantQueryUseCase = useCase,
            cockpitSnapshot = cockpitSnapshot,
            appPreferences = appPreferences,
            ttsController = FakeTtsController(),
            metricsRecorder = AssistantTurnMetricsRecorder(),
            lastHealthStatus = MutableStateFlow(null),
            idGenerator = idGenerator,
            clock = clock,
            externalScope = realScope,
        )

        try {
            var turn: AssistantTurnCoordinator.StartedTurn? = null
            assertThat(coordinator.submit("Câu hỏi sẽ bị hủy", AssistantTurnSource.TEXT, "assistant") { turn = it }).isTrue()

            // Proof the query coroutine is genuinely running and blocked inside the gateway — not merely
            // that InFlightAssistantTurn was published (which submit() does synchronously, before the
            // coroutine even starts).
            assertThat(enteredGateway.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue()

            coordinator.cancelCurrent()
            val cancelledOutcome = kotlinx.coroutines.runBlocking { withTimeout(5_000) { turn!!.completion.await() } }
            assertThat(cancelledOutcome).isInstanceOf(AssistantTurnState.Cancelled::class.java)
            // No fabricated assistant reply, and retry lineage reflects that the query really was
            // dispatched (querySent was set before enteredGateway even fired, inside AssistantQueryUseCase's
            // onTiming, immediately before the real network call).
            assertThat(repository.state.value.messages.none { it.sender.name == "SAFEDRIVE" }).isTrue()
            assertThat(repository.state.value.retryableTurn?.clientAttemptOf).isEqualTo((cancelledOutcome as AssistantTurnState.Cancelled).requestId)

            releaseGateway.countDown() // release the superseded coroutine — it now throws, past cancellation's reach
            val settleDeadline = System.currentTimeMillis() + 5_000
            while (uncaughtExceptions.isEmpty() && System.currentTimeMillis() < settleDeadline) {
                Thread.sleep(10)
            }

            // Deferred identity proves complete() was never invoked a second time for this turn.
            val secondRead = kotlinx.coroutines.runBlocking { turn!!.completion.await() }
            assertThat(secondRead).isSameInstanceAs(cancelledOutcome)
            assertThat(repository.state.value.turnState).isInstanceOf(AssistantTurnState.Cancelled::class.java)
            // The whole point of the fix: the stale exception must be caught internally and folded into
            // a discarded (generation-mismatched) Failure result, never surfacing as an uncaught
            // exception on the scope.
            assertThat(uncaughtExceptions).isEmpty()
        } finally {
            realScope.cancel()
        }
    }

    // --- Independent re-audit follow-up (second pass): a CancellationException reaching the turn's
    // coroutine does NOT always mean cancelCurrent() already handled cleanup for it. These tests force
    // that exact scenario (a cancellation that never went through cancelCurrent()) and prove the
    // Job.invokeOnCompletion safety net in beginTurnLocked always terminalizes the turn as Cancelled
    // regardless — never leaving it abandoned InFlight with an unresolved Deferred. ---

    @Test
    fun `the gateway throwing CancellationException directly, not via cancelCurrent, while still current still terminalizes as Cancelled - not a hang, not mapped to Unexpected`() {
        val throwsCancellation = object : SafeDriveGateway by gateway {
            override suspend fun queryAssistant(request: AssistantQueryRequest): GatewayResult<AssistantQueryResult> {
                throw CancellationException("gateway self-cancelled for an unrelated internal reason")
            }
        }
        val repository = InMemoryConversationRepository(initialMessages = emptyList())
        val appPreferences = MutableStateFlow(AppPreferences())
        val sessionCoordinator = SessionCoordinator(fakeProvider { throwsCancellation }, appPreferences, idGenerator, clock, "test")
        val useCase = AssistantQueryUseCase(sessionCoordinator, clock)
        val uncaughtExceptions = java.util.Collections.synchronizedList(mutableListOf<Throwable>())
        val exceptionHandler = CoroutineExceptionHandler { _, throwable -> uncaughtExceptions.add(throwable) }
        val realScope = CoroutineScope(SupervisorJob() + Dispatchers.Default + exceptionHandler)
        val coordinator = AssistantTurnCoordinator(
            conversationRepository = repository,
            assistantQueryUseCase = useCase,
            cockpitSnapshot = cockpitSnapshot,
            appPreferences = appPreferences,
            ttsController = FakeTtsController(),
            metricsRecorder = AssistantTurnMetricsRecorder(),
            lastHealthStatus = MutableStateFlow(null),
            idGenerator = idGenerator,
            clock = clock,
            externalScope = realScope,
        )
        try {
            var turn: AssistantTurnCoordinator.StartedTurn? = null
            assertThat(coordinator.submit("Câu hỏi", AssistantTurnSource.TEXT, "assistant") { turn = it }).isTrue()

            val outcome = kotlinx.coroutines.runBlocking { withTimeout(5_000) { turn!!.completion.await() } } // must not hang
            assertThat(outcome).isInstanceOf(AssistantTurnState.Cancelled::class.java)
            // onTiming (and therefore attempt.querySent) fires immediately before queryAssistant() is
            // called (see AssistantQueryUseCase) — the query really was dispatched before this override
            // threw, so retry lineage correctly still references it.
            assertThat(repository.state.value.retryableTurn?.clientAttemptOf)
                .isEqualTo((outcome as AssistantTurnState.Cancelled).requestId)

            val deadline = System.currentTimeMillis() + 5_000
            while (repository.state.value.inFlightTurn != null && System.currentTimeMillis() < deadline) Thread.sleep(5)
            assertThat(repository.state.value.inFlightTurn).isNull() // never abandoned InFlight
            assertThat(repository.state.value.turnState).isInstanceOf(AssistantTurnState.Cancelled::class.java)
            assertThat(uncaughtExceptions).isEmpty() // never surfaced uncaught, never crashed the scope
        } finally {
            realScope.cancel()
        }
    }

    @Test
    fun `session resolution throwing CancellationException before the query is ever sent terminalizes as Cancelled with clientAttemptOf null`() {
        val throwsCancellation = object : SafeDriveGateway by gateway {
            override suspend fun startSession(request: StartSessionRequest): GatewayResult<SessionInfo> {
                throw CancellationException("session resolution self-cancelled")
            }
        }
        val repository = InMemoryConversationRepository(initialMessages = emptyList())
        val appPreferences = MutableStateFlow(AppPreferences())
        val sessionCoordinator = SessionCoordinator(fakeProvider { throwsCancellation }, appPreferences, idGenerator, clock, "test")
        val useCase = AssistantQueryUseCase(sessionCoordinator, clock)
        val uncaughtExceptions = java.util.Collections.synchronizedList(mutableListOf<Throwable>())
        val exceptionHandler = CoroutineExceptionHandler { _, throwable -> uncaughtExceptions.add(throwable) }
        val realScope = CoroutineScope(SupervisorJob() + Dispatchers.Default + exceptionHandler)
        val coordinator = AssistantTurnCoordinator(
            conversationRepository = repository,
            assistantQueryUseCase = useCase,
            cockpitSnapshot = cockpitSnapshot,
            appPreferences = appPreferences,
            ttsController = FakeTtsController(),
            metricsRecorder = AssistantTurnMetricsRecorder(),
            lastHealthStatus = MutableStateFlow(null),
            idGenerator = idGenerator,
            clock = clock,
            externalScope = realScope,
        )
        try {
            var turn: AssistantTurnCoordinator.StartedTurn? = null
            assertThat(coordinator.submit("Câu hỏi", AssistantTurnSource.TEXT, "assistant") { turn = it }).isTrue()

            val outcome = kotlinx.coroutines.runBlocking { withTimeout(5_000) { turn!!.completion.await() } } // must not hang
            assertThat(outcome).isInstanceOf(AssistantTurnState.Cancelled::class.java)
            assertThat(repository.state.value.retryableTurn?.clientAttemptOf).isNull() // query never dispatched
            assertThat(repository.state.value.inFlightTurn).isNull()
            assertThat(uncaughtExceptions).isEmpty()
        } finally {
            realScope.cancel()
        }
    }

    @Test
    fun `the parent scope being cancelled after a turn is accepted still resolves its completion instead of abandoning it InFlight`() {
        val enteredGateway = CountDownLatch(1)
        val neverCompletes = CompletableDeferred<Unit>()
        val suspendingGateway = object : SafeDriveGateway by gateway {
            override suspend fun queryAssistant(request: AssistantQueryRequest): GatewayResult<AssistantQueryResult> {
                enteredGateway.countDown()
                neverCompletes.await() // never resolves on its own — only the parent-scope cancellation below ends it
                return gateway.queryAssistant(request)
            }
        }
        val repository = InMemoryConversationRepository(initialMessages = emptyList())
        val appPreferences = MutableStateFlow(AppPreferences())
        val sessionCoordinator = SessionCoordinator(fakeProvider { suspendingGateway }, appPreferences, idGenerator, clock, "test")
        val useCase = AssistantQueryUseCase(sessionCoordinator, clock)
        // A distinct parent Job we control directly — cancelling *it* (not calling cancelCurrent())
        // simulates the application scope itself being torn down (e.g. process shutdown), which
        // AssistantTurnCoordinator has no special-case handling for beyond the generic safety net.
        val parentJob = SupervisorJob()
        val realScope = CoroutineScope(parentJob + Dispatchers.Default)
        val coordinator = AssistantTurnCoordinator(
            conversationRepository = repository,
            assistantQueryUseCase = useCase,
            cockpitSnapshot = cockpitSnapshot,
            appPreferences = appPreferences,
            ttsController = FakeTtsController(),
            metricsRecorder = AssistantTurnMetricsRecorder(),
            lastHealthStatus = MutableStateFlow(null),
            idGenerator = idGenerator,
            clock = clock,
            externalScope = realScope,
        )
        var turn: AssistantTurnCoordinator.StartedTurn? = null
        assertThat(coordinator.submit("Câu hỏi", AssistantTurnSource.TEXT, "assistant") { turn = it }).isTrue()
        assertThat(enteredGateway.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue() // genuinely in flight

        parentJob.cancel() // NOT cancelCurrent() — simulates the whole application scope shutting down

        val outcome = kotlinx.coroutines.runBlocking { withTimeout(5_000) { turn!!.completion.await() } } // must not hang
        assertThat(outcome).isInstanceOf(AssistantTurnState.Cancelled::class.java)
        // completeCancelled() always runs before completion.complete() inside the safety net, so by the
        // time await() unblocks, ConversationRepository is guaranteed to already reflect it — no
        // Thread.sleep/polling needed to observe this.
        assertThat(repository.state.value.turnState).isInstanceOf(AssistantTurnState.Cancelled::class.java)
        assertThat(repository.state.value.inFlightTurn).isNull()
    }

    @Test
    fun `submit on an already-cancelled scope never leaves an accepted turn stuck InFlight`() {
        val repository = InMemoryConversationRepository(initialMessages = emptyList())
        val appPreferences = MutableStateFlow(AppPreferences())
        val sessionCoordinator = SessionCoordinator(fakeProvider { gateway }, appPreferences, idGenerator, clock, "test")
        val useCase = AssistantQueryUseCase(sessionCoordinator, clock)
        val parentJob = SupervisorJob()
        parentJob.cancel() // the scope is already dead *before* submit()/launch is ever called
        val deadScope = CoroutineScope(parentJob + Dispatchers.Default)
        val coordinator = AssistantTurnCoordinator(
            conversationRepository = repository,
            assistantQueryUseCase = useCase,
            cockpitSnapshot = cockpitSnapshot,
            appPreferences = appPreferences,
            ttsController = FakeTtsController(),
            metricsRecorder = AssistantTurnMetricsRecorder(),
            lastHealthStatus = MutableStateFlow(null),
            idGenerator = idGenerator,
            clock = clock,
            externalScope = deadScope,
        )

        var turn: AssistantTurnCoordinator.StartedTurn? = null
        val accepted = coordinator.submit("Câu hỏi", AssistantTurnSource.TEXT, "assistant") { turn = it }

        if (accepted) {
            // This implementation's chosen contract (documented in beginTurnLocked's KDoc): the turn is
            // accepted and immediately terminalized via the Job.invokeOnCompletion safety net, rather
            // than submit() pre-checking scope liveness and rejecting cleanly — either design is
            // acceptable per the master prompt; this asserts the one this codebase actually implements.
            val outcome = kotlinx.coroutines.runBlocking { withTimeout(5_000) { turn!!.completion.await() } }
            assertThat(outcome).isInstanceOf(AssistantTurnState.Cancelled::class.java)
        } else {
            assertThat(repository.state.value.messages).isEmpty()
        }
        // The one hard invariant regardless of which design: never permanently in flight.
        assertThat(repository.state.value.inFlightTurn).isNull()
    }

    @Test
    fun `an onStarted callback that throws CancellationException still terminalizes the turn as Cancelled before rethrowing, never stranding it InFlight`() =
        runTest(mainDispatcherRule.dispatcher) {
            val (coordinator, repository, _) = buildCoordinator(this)
            var caught: CancellationException? = null
            try {
                coordinator.submit("Kiểm tra nhiệt độ động cơ", AssistantTurnSource.TEXT, "assistant") {
                    throw CancellationException("buggy caller callback cancelling")
                }
            } catch (e: CancellationException) {
                caught = e
            }
            assertThat(caught).isNotNull() // cooperative cancellation must still propagate to the caller

            assertThat(repository.state.value.inFlightTurn).isNull() // never abandoned
            assertThat(repository.state.value.turnState).isInstanceOf(AssistantTurnState.Cancelled::class.java)
        }

    @Test
    fun `an onStarted callback that throws a RuntimeException is logged, not silently swallowed`() =
        runTest(mainDispatcherRule.dispatcher) {
            val logs = mutableListOf<String>()
            val repository = InMemoryConversationRepository(initialMessages = emptyList())
            val appPreferences = MutableStateFlow(AppPreferences())
            val sessionCoordinator = SessionCoordinator(fakeProvider { gateway }, appPreferences, idGenerator, clock, "test")
            val useCase = AssistantQueryUseCase(sessionCoordinator, clock)
            val coordinator = AssistantTurnCoordinator(
                conversationRepository = repository,
                assistantQueryUseCase = useCase,
                cockpitSnapshot = cockpitSnapshot,
                appPreferences = appPreferences,
                ttsController = FakeTtsController(),
                metricsRecorder = AssistantTurnMetricsRecorder(),
                lastHealthStatus = MutableStateFlow(null),
                idGenerator = idGenerator,
                clock = clock,
                externalScope = this,
                logger = logs::add,
            )

            val accepted = coordinator.submit("Kiểm tra nhiệt độ động cơ", AssistantTurnSource.TEXT, "assistant") {
                throw RuntimeException("buggy caller callback")
            }
            assertThat(accepted).isTrue() // submit() itself must not propagate the callback's exception
            advanceUntilIdle()

            assertThat(repository.state.value.inFlightTurn).isNull()
            assertThat(repository.state.value.turnState).isInstanceOf(AssistantTurnState.Success::class.java)
            // Never silent: exactly one log line naming the callback context, never the exception's own
            // message (which could echo transcript/response content) and never the transcript itself.
            assertThat(logs.any { it.contains("onStarted callback") }).isTrue()
            assertThat(logs.none { it.contains("Kiểm tra nhiệt độ động cơ") }).isTrue()
        }

    // --- Independent re-audit follow-up, blocker 2: appending the user message and starting/rejecting
    // the turn used to be two separate ConversationRepository publishes (addUserMessage() then
    // beginTurn()/completeFailure()). synchronized(lock) only ever serializes AssistantTurnCoordinator's
    // own callers against each other — it says nothing about a StateFlow collector on another
    // thread/dispatcher, which really could observe the new user bubble already appended while
    // inFlightTurn/turnState/retryableTurn/errorMessage still reflected the *previous* turn. These tests
    // collect the repository's full state history (relying on this test suite's UnconfinedTestDispatcher,
    // which resumes a suspended collector inline/synchronously on every StateFlow emission — the same
    // technique the existing "every published state where inFlightTurn becomes null..." test above
    // already relies on) and prove that forbidden intermediate state is never observed. ---

    @Test
    fun `no published state ever pairs a freshly-appended user message with the previous turn's still-lingering Failure, retry or error state`() =
        runTest(mainDispatcherRule.dispatcher) {
            // Turn 1 fails; turn 2 (a different, later submit) must succeed for real — so any Failure/
            // retryableTurn/errorMessage seen alongside turn 2's own message can only be *stale* leftover
            // from turn 1, never turn 2's own (legitimately absent) outcome.
            var callCount = 0
            val failsOnceThenSucceeds = object : SafeDriveGateway by gateway {
                override suspend fun queryAssistant(request: AssistantQueryRequest): GatewayResult<AssistantQueryResult> {
                    callCount++
                    return if (callCount == 1) GatewayResult.Failure(GatewayError.Timeout) else gateway.queryAssistant(request)
                }
            }
            val (coordinator, repository, _) = buildCoordinator(this, gatewayOverride = failsOnceThenSucceeds)
            coordinator.submit("Câu hỏi đầu tiên", AssistantTurnSource.TEXT, "assistant")
            advanceUntilIdle()
            assertThat(repository.state.value.retryableTurn).isNotNull() // turn 1 failed, now retryable

            val observed = mutableListOf<ConversationState>()
            val collectJob = launch { repository.state.collect { observed.add(it) } }

            coordinator.submit("Câu hỏi thứ hai", AssistantTurnSource.TEXT, "assistant")
            advanceUntilIdle()
            collectJob.cancel()

            assertThat(observed.any { it.messages.any { m -> m.text == "Câu hỏi thứ hai" } }).isTrue()
            // Turn 2 must have genuinely succeeded — confirms the Failure/retryableTurn assertions below
            // can only be catching *stale* turn-1 leftovers, never turn 2's own (absent) failure.
            assertThat(repository.state.value.turnState).isInstanceOf(AssistantTurnState.Success::class.java)

            // The forbidden combination: the new bubble already appended, but inFlightTurn still null
            // and turnState/retryableTurn/errorMessage still reflecting turn 1's Failure — exactly what
            // a bare addUserMessage()-then-beginTurn() two-step publish could produce.
            val forbidden = observed.any { state ->
                state.messages.any { it.text == "Câu hỏi thứ hai" } &&
                    state.inFlightTurn == null &&
                    (state.turnState is AssistantTurnState.Failure || state.retryableTurn != null || state.errorMessage != null)
            }
            assertThat(forbidden).isFalse()
        }

    @Test
    fun `a health-blocked submit publishes exactly one new state carrying both the user message and the Failure together`() =
        runTest(mainDispatcherRule.dispatcher) {
            val healthWithAssistantDisabled = MutableStateFlow(
                vn.edu.haui.hvs.safedrive.core.model.HealthStatus(
                    status = vn.edu.haui.hvs.safedrive.core.model.SystemConnectionStatus.NORMAL,
                    serviceName = "test-backend",
                    apiVersion = "v1",
                    serverTimeMs = clock.nowMs(),
                    capabilities = vn.edu.haui.hvs.safedrive.core.model.HealthCapabilities(
                        assistant = false,
                        emergencySimulation = true,
                        cockpitStream = false,
                    ),
                ),
            )
            val (coordinator, repository, _) = buildCoordinator(this, lastHealthStatus = healthWithAssistantDisabled)
            val observed = mutableListOf<ConversationState>()
            val collectJob = launch { repository.state.collect { observed.add(it) } }

            coordinator.submit("Xe có lỗi gì?", AssistantTurnSource.TEXT, "assistant")
            collectJob.cancel()

            // Never observe the user bubble appended alone with the turn not yet Failure.
            val forbidden = observed.any {
                it.messages.any { m -> m.text == "Xe có lỗi gì?" } && it.turnState !is AssistantTurnState.Failure
            }
            assertThat(forbidden).isFalse()
            assertThat(repository.state.value.turnState).isInstanceOf(AssistantTurnState.Failure::class.java)
            assertThat(repository.state.value.messages.any { it.text == "Xe có lỗi gì?" }).isTrue()
        }
}

private fun fakeProvider(provider: () -> SafeDriveGateway) = object : GatewayProvider {
    override fun current() = provider()
}
