package vn.edu.haui.hvs.safedrive.data.remote

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import vn.edu.haui.hvs.safedrive.core.common.AppClock
import vn.edu.haui.hvs.safedrive.core.common.GatewayResult
import vn.edu.haui.hvs.safedrive.core.common.IdGenerator
import vn.edu.haui.hvs.safedrive.core.model.BackendMode
import vn.edu.haui.hvs.safedrive.core.model.EmergencyResponseRequest
import vn.edu.haui.hvs.safedrive.core.model.EmergencyResponseType
import vn.edu.haui.hvs.safedrive.core.model.EmergencySnapshot
import vn.edu.haui.hvs.safedrive.core.model.EmergencyState
import vn.edu.haui.hvs.safedrive.core.model.EvidenceItem
import vn.edu.haui.hvs.safedrive.domain.repository.AppPreferences
import vn.edu.haui.hvs.safedrive.domain.repository.EmergencyRepository
import vn.edu.haui.hvs.safedrive.domain.repository.RemoteEmergencySnapshotSink
import vn.edu.haui.hvs.safedrive.domain.usecase.SessionCoordinator

/**
 * Keeps Demo Mode's local reducer separate from Remote Mode's backend authority. Remote state is
 * never re-evaluated in Android; the app only renders the snapshot returned by the backend and sends
 * explicit user responses back through the session-bound gateway.
 */
class ModeAwareEmergencyRepository(
    private val localRepository: EmergencyRepository,
    private val sessionCoordinator: SessionCoordinator,
    private val appPreferences: StateFlow<AppPreferences>,
    private val clock: AppClock,
    private val idGenerator: IdGenerator,
    externalScope: CoroutineScope,
) : EmergencyRepository, RemoteEmergencySnapshotSink {

    private val remoteSnapshot = MutableStateFlow<EmergencySnapshot?>(null)
    private val _activeSnapshot = MutableStateFlow<EmergencySnapshot?>(null)
    override val activeSnapshot: StateFlow<EmergencySnapshot?> = _activeSnapshot.asStateFlow()

    @Volatile
    private var lastRemoteRefreshAtMs = Long.MIN_VALUE

    init {
        externalScope.launch {
            combine(localRepository.activeSnapshot, remoteSnapshot, appPreferences) { local, remote, preferences ->
                if (preferences.backendMode == BackendMode.DEMO) local else remote
            }.collect { _activeSnapshot.value = it }
        }
    }

    override fun publishRemoteSnapshot(snapshot: EmergencySnapshot?) {
        if (appPreferences.value.backendMode == BackendMode.REMOTE) {
            setRemoteSnapshot(snapshot)
        }
    }

    override suspend fun startCandidate(evidence: List<EvidenceItem>): EmergencySnapshot =
        if (appPreferences.value.backendMode == BackendMode.DEMO) {
            localRepository.startCandidate(evidence)
        } else {
            // Remote candidates come only from the backend after its deterministic policy evaluation.
            remoteSnapshot.value ?: EmergencySnapshot(
                emergencyId = "remote_emergency_not_reported",
                state = EmergencyState.IDLE,
                deadlineMs = null,
                evidence = emptyList(),
                realEmergencyDispatchEnabled = false,
            )
        }

    override suspend fun tick() {
        if (appPreferences.value.backendMode == BackendMode.DEMO) {
            localRepository.tick()
            return
        }

        val current = remoteSnapshot.value ?: return
        if (
            current.state !in setOf(
                EmergencyState.VERIFYING_EVIDENCE,
                EmergencyState.AWAITING_USER_RESPONSE,
                EmergencyState.FINAL_COUNTDOWN,
            ) || current.deadlineMs == null
        ) {
            return
        }
        val now = clock.nowMs()
        if (
            now < current.deadlineMs ||
            (lastRemoteRefreshAtMs != Long.MIN_VALUE && now - lastRemoteRefreshAtMs < 1_000L)
        ) {
            return
        }
        lastRemoteRefreshAtMs = now
        refresh()
    }

    override suspend fun respond(response: EmergencyResponseType): EmergencySnapshot? {
        if (appPreferences.value.backendMode == BackendMode.DEMO) {
            return localRepository.respond(response)
        }
        val current = remoteSnapshot.value ?: return null
        val session = sessionCoordinator.currentSession()
        if (session is GatewayResult.Failure) return current
        val resolved = (session as GatewayResult.Success).data
        return when (
            val result = resolved.gateway.respondEmergency(
                EmergencyResponseRequest(
                    emergencyId = current.emergencyId,
                    sessionId = resolved.sessionId,
                    responseId = idGenerator.next("emergency_response"),
                    response = response,
                    clientTimeMs = clock.nowMs(),
                ),
            )
        ) {
            is GatewayResult.Success -> result.data.also(::setRemoteSnapshot)
            is GatewayResult.Failure -> current
        }
    }

    override suspend fun refresh(): EmergencySnapshot? {
        if (appPreferences.value.backendMode == BackendMode.DEMO) {
            return localRepository.refresh()
        }
        val current = remoteSnapshot.value ?: return null
        val session = sessionCoordinator.currentSession()
        if (session is GatewayResult.Failure) return current
        val resolved = (session as GatewayResult.Success).data
        return when (val result = resolved.gateway.getEmergency(current.emergencyId, resolved.sessionId)) {
            is GatewayResult.Success -> result.data.also(::setRemoteSnapshot)
            is GatewayResult.Failure -> current
        }
    }

    override suspend fun clear() {
        if (appPreferences.value.backendMode == BackendMode.DEMO) {
            localRepository.clear()
        } else {
            setRemoteSnapshot(null)
        }
    }

    private fun setRemoteSnapshot(snapshot: EmergencySnapshot?) {
        remoteSnapshot.value = snapshot
        // The combine collector also handles mode changes, but a direct publish must be immediately
        // observable so an incoming CRITICAL backend state cannot wait for a scheduling turn.
        if (appPreferences.value.backendMode == BackendMode.REMOTE) {
            _activeSnapshot.value = snapshot
        }
    }
}
