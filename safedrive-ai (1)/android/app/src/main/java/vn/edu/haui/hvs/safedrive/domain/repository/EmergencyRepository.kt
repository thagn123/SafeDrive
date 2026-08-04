package vn.edu.haui.hvs.safedrive.domain.repository

import kotlinx.coroutines.flow.StateFlow
import vn.edu.haui.hvs.safedrive.core.model.EmergencyResponseType
import vn.edu.haui.hvs.safedrive.core.model.EmergencySnapshot
import vn.edu.haui.hvs.safedrive.core.model.EvidenceItem

/**
 * Owns the single Emergency State Machine snapshot (contract in docs/android-mvp-plan/05-voice-emergency.md).
 * Demo Mode runs a local mock reducer against [vn.edu.haui.hvs.safedrive.core.common.AppClock];
 * Remote Mode defers to the backend as authority. Implemented in Phase 6; interface locked in Phase 1
 * so dependents can be wired without a later contract change.
 */
interface EmergencyRepository {
    val activeSnapshot: StateFlow<EmergencySnapshot?>

    /** Starts a new candidate only when primary + supporting evidence are both present. */
    suspend fun startCandidate(evidence: List<EvidenceItem>): EmergencySnapshot

    /** Advances the reducer if the persisted absolute deadline has passed; idempotent. */
    suspend fun tick()

    suspend fun respond(response: EmergencyResponseType): EmergencySnapshot?

    /** Remote Mode: refetch authoritative snapshot (e.g. on resume/process recreation). */
    suspend fun refresh(): EmergencySnapshot?

    suspend fun clear()
}
