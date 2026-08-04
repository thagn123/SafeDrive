package vn.edu.haui.hvs.safedrive.core.testing

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import vn.edu.haui.hvs.safedrive.core.model.EmergencyResponseType
import vn.edu.haui.hvs.safedrive.core.model.EmergencySnapshot
import vn.edu.haui.hvs.safedrive.core.model.EmergencyState
import vn.edu.haui.hvs.safedrive.core.model.EvidenceItem
import vn.edu.haui.hvs.safedrive.domain.repository.EmergencyRepository

/** In-memory [EmergencyRepository] test double for Compose UI tests — no DataStore/file I/O. */
class FakeEmergencyRepository(initial: EmergencySnapshot? = null) : EmergencyRepository {
    private val _activeSnapshot = MutableStateFlow(initial)
    override val activeSnapshot: StateFlow<EmergencySnapshot?> = _activeSnapshot

    var lastResponse: EmergencyResponseType? = null
        private set

    fun setSnapshot(snapshot: EmergencySnapshot?) {
        _activeSnapshot.value = snapshot
    }

    override suspend fun startCandidate(evidence: List<EvidenceItem>): EmergencySnapshot {
        val snapshot = EmergencySnapshot("emg_fake", EmergencyState.VERIFYING_EVIDENCE, 5_000L, evidence)
        _activeSnapshot.value = snapshot
        return snapshot
    }

    override suspend fun tick() = Unit

    override suspend fun respond(response: EmergencyResponseType): EmergencySnapshot? {
        lastResponse = response
        val current = _activeSnapshot.value ?: return null
        val cancelled = current.copy(state = EmergencyState.CANCELLED, deadlineMs = null)
        _activeSnapshot.value = cancelled
        return cancelled
    }

    override suspend fun refresh(): EmergencySnapshot? = _activeSnapshot.value

    override suspend fun clear() {
        _activeSnapshot.value = null
    }
}
