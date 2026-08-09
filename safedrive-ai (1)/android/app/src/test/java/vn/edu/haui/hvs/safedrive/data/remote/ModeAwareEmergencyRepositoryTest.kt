package vn.edu.haui.hvs.safedrive.data.remote

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test
import vn.edu.haui.hvs.safedrive.core.common.GatewayError
import vn.edu.haui.hvs.safedrive.core.common.GatewayResult
import vn.edu.haui.hvs.safedrive.core.common.UuidIdGenerator
import vn.edu.haui.hvs.safedrive.core.model.ActionConfirmRequest
import vn.edu.haui.hvs.safedrive.core.model.ActionConfirmResult
import vn.edu.haui.hvs.safedrive.core.model.AssistantQueryRequest
import vn.edu.haui.hvs.safedrive.core.model.AssistantQueryResult
import vn.edu.haui.hvs.safedrive.core.model.BackendMode
import vn.edu.haui.hvs.safedrive.core.model.EmergencyResponseRequest
import vn.edu.haui.hvs.safedrive.core.model.EmergencySnapshot
import vn.edu.haui.hvs.safedrive.core.model.EmergencyState
import vn.edu.haui.hvs.safedrive.core.model.EmergencyResponseType
import vn.edu.haui.hvs.safedrive.core.model.EvidenceItem
import vn.edu.haui.hvs.safedrive.core.model.EventAccepted
import vn.edu.haui.hvs.safedrive.core.model.HealthStatus
import vn.edu.haui.hvs.safedrive.core.model.SafeDriveEvent
import vn.edu.haui.hvs.safedrive.core.model.SessionInfo
import vn.edu.haui.hvs.safedrive.core.model.StartSessionRequest
import vn.edu.haui.hvs.safedrive.core.model.StateEnvelope
import vn.edu.haui.hvs.safedrive.core.model.StateUpdateRequest
import vn.edu.haui.hvs.safedrive.core.testing.FakeClock
import vn.edu.haui.hvs.safedrive.core.testing.FakeEmergencyRepository
import vn.edu.haui.hvs.safedrive.domain.repository.AppPreferences
import vn.edu.haui.hvs.safedrive.domain.repository.GatewayProvider
import vn.edu.haui.hvs.safedrive.domain.repository.SafeDriveGateway
import vn.edu.haui.hvs.safedrive.domain.usecase.SessionCoordinator

class ModeAwareEmergencyRepositoryTest {

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `trusted fused crash opens local emergency immediately in remote mode`() = runTest {
        val clock = FakeClock(initialMs = 1_000L)
        val preferences = MutableStateFlow(AppPreferences(backendMode = BackendMode.REMOTE, baseUrl = "https://demo/"))
        val gateway = EmergencyGateway(clock)
        val localRepository = FakeEmergencyRepository()
        val coordinator = SessionCoordinator(
            gatewayProvider = object : GatewayProvider {
                override fun current(): SafeDriveGateway = gateway
                override fun forPreferences(prefs: AppPreferences): SafeDriveGateway = gateway
            },
            appPreferences = preferences,
            idGenerator = UuidIdGenerator(),
            clock = clock,
            appVersion = "test",
        )
        val repository = ModeAwareEmergencyRepository(
            localRepository = localRepository,
            sessionCoordinator = coordinator,
            appPreferences = preferences,
            clock = clock,
            idGenerator = UuidIdGenerator(),
            externalScope = backgroundScope,
        )
        val evidence = listOf(EvidenceItem("vhal_impact", "VHAL impact", clock.nowMs()))

        repository.startTrustedLocalCrash(evidence)

        assertThat(repository.activeSnapshot.value?.state).isEqualTo(EmergencyState.VERIFYING_EVIDENCE)
        assertThat(repository.activeSnapshot.value?.evidence).isEqualTo(evidence)

        repository.publishRemoteSnapshot(
            EmergencySnapshot(
                emergencyId = "backend_emergency",
                state = EmergencyState.FINAL_COUNTDOWN,
                deadlineMs = 99_000L,
                evidence = emptyList(),
                reasoningSummary = "AI confirms the grounded collision context.",
            ),
        )
        assertThat(repository.activeSnapshot.value?.emergencyId).isEqualTo("emg_fake")
        assertThat(repository.activeSnapshot.value?.state).isEqualTo(EmergencyState.VERIFYING_EVIDENCE)
        assertThat(repository.activeSnapshot.value?.reasoningSummary)
            .isEqualTo("AI confirms the grounded collision context.")

        repository.respond(EmergencyResponseType.USER_OK)
        assertThat(localRepository.lastResponse).isEqualTo(EmergencyResponseType.USER_OK)
        assertThat(repository.activeSnapshot.value?.state).isEqualTo(EmergencyState.CANCELLED)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `remote mode polls backend when the verification response deadline expires`() = runTest {
        val clock = FakeClock(initialMs = 1_000L)
        val preferences = MutableStateFlow(AppPreferences(backendMode = BackendMode.REMOTE, baseUrl = "https://demo/"))
        val gateway = EmergencyGateway(clock).apply {
            emergencyAfterGet = EmergencySnapshot(
                emergencyId = "emg_remote_verify",
                state = EmergencyState.AWAITING_USER_RESPONSE,
                deadlineMs = 17_000L,
                evidence = emptyList(),
            )
        }
        val repository = createRemoteRepository(clock, preferences, gateway, backgroundScope)
        repository.publishRemoteSnapshot(
            EmergencySnapshot(
                emergencyId = "emg_remote_verify",
                state = EmergencyState.VERIFYING_EVIDENCE,
                deadlineMs = 2_000L,
                evidence = emptyList(),
            ),
        )
        advanceUntilIdle()

        repository.tick()
        assertThat(gateway.getEmergencyCalls).isEqualTo(0)

        clock.advanceBy(1_001L)
        repository.tick()
        assertThat(gateway.getEmergencyCalls).isEqualTo(1)
        assertThat(repository.activeSnapshot.value?.state).isEqualTo(EmergencyState.AWAITING_USER_RESPONSE)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `remote mode uses backend snapshot and polls only after the authoritative countdown expires`() = runTest {
        val clock = FakeClock(initialMs = 1_000L)
        val preferences = MutableStateFlow(AppPreferences(backendMode = BackendMode.REMOTE, baseUrl = "https://demo/"))
        val gateway = EmergencyGateway(clock)
        val repository = createRemoteRepository(clock, preferences, gateway, backgroundScope)
        val countdown = EmergencySnapshot(
            emergencyId = "emg_remote_1",
            state = EmergencyState.FINAL_COUNTDOWN,
            deadlineMs = 2_000L,
            evidence = emptyList(),
        )

        repository.publishRemoteSnapshot(countdown)
        advanceUntilIdle()
        assertThat(repository.activeSnapshot.value?.state).isEqualTo(EmergencyState.FINAL_COUNTDOWN)

        repository.tick()
        assertThat(gateway.getEmergencyCalls).isEqualTo(0)

        clock.advanceBy(1_001L)
        repository.tick()
        assertThat(gateway.getEmergencyCalls).isEqualTo(1)
        assertThat(repository.activeSnapshot.value?.state).isEqualTo(EmergencyState.SOS_SIMULATED_SENT)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun createRemoteRepository(
        clock: FakeClock,
        preferences: MutableStateFlow<AppPreferences>,
        gateway: EmergencyGateway,
        externalScope: CoroutineScope,
    ): ModeAwareEmergencyRepository {
        val coordinator = SessionCoordinator(
            gatewayProvider = object : GatewayProvider {
                override fun current(): SafeDriveGateway = gateway
                override fun forPreferences(prefs: AppPreferences): SafeDriveGateway = gateway
            },
            appPreferences = preferences,
            idGenerator = UuidIdGenerator(),
            clock = clock,
            appVersion = "test",
        )
        return ModeAwareEmergencyRepository(
            localRepository = FakeEmergencyRepository(),
            sessionCoordinator = coordinator,
            appPreferences = preferences,
            clock = clock,
            idGenerator = UuidIdGenerator(),
            externalScope = externalScope,
        )
    }
}

private class EmergencyGateway(private val clock: FakeClock) : SafeDriveGateway {
    var getEmergencyCalls = 0
        private set
    var emergencyAfterGet = EmergencySnapshot(
        emergencyId = "emg_remote_default",
        state = EmergencyState.SOS_SIMULATED_SENT,
        deadlineMs = null,
        evidence = emptyList(),
    )

    override suspend fun startSession(request: StartSessionRequest): GatewayResult<SessionInfo> =
        GatewayResult.Success(SessionInfo("session_remote", clock.nowMs() + 60_000L, clock.nowMs(), "v1"))

    override suspend fun getEmergency(emergencyId: String, sessionId: String): GatewayResult<EmergencySnapshot> {
        getEmergencyCalls += 1
        return GatewayResult.Success(emergencyAfterGet.copy(emergencyId = emergencyId))
    }

    override suspend fun checkHealth(): GatewayResult<HealthStatus> = unsupported()
    override suspend fun updateVehicleState(request: StateUpdateRequest): GatewayResult<StateEnvelope> = unsupported()
    override suspend fun queryAssistant(request: AssistantQueryRequest): GatewayResult<AssistantQueryResult> = unsupported()
    override suspend fun sendEvent(event: SafeDriveEvent): GatewayResult<EventAccepted> = unsupported()
    override suspend fun confirmAction(request: ActionConfirmRequest): GatewayResult<ActionConfirmResult> = unsupported()
    override suspend fun respondEmergency(request: EmergencyResponseRequest): GatewayResult<EmergencySnapshot> = unsupported()
    override suspend fun getVehicleState(sessionId: String, sinceVersion: Long?): GatewayResult<StateEnvelope> = unsupported()

    private fun <T> unsupported(): GatewayResult<T> = GatewayResult.Failure(GatewayError.Unsupported)
}
