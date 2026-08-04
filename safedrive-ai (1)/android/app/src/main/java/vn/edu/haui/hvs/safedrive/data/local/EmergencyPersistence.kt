package vn.edu.haui.hvs.safedrive.data.local

import kotlinx.serialization.Serializable
import vn.edu.haui.hvs.safedrive.core.model.EmergencySnapshot
import vn.edu.haui.hvs.safedrive.core.model.EmergencyState
import vn.edu.haui.hvs.safedrive.core.model.EvidenceItem

/**
 * Local-storage-only mirror of [EmergencySnapshot]. Kept out of `core.model` so domain types stay
 * free of serialization annotations (data/local owns this mapping the same way data/remote will own
 * DTO↔domain mapping in Phase 7) — see android/README.md, "Deviations".
 */
@Serializable
data class PersistedEvidenceItem(val code: String, val label: String, val detectedAtMs: Long)

@Serializable
data class PersistedEmergencySnapshot(
    val emergencyId: String,
    val state: String,
    val deadlineMs: Long?,
    val evidence: List<PersistedEvidenceItem>,
)

fun EmergencySnapshot.toPersisted(): PersistedEmergencySnapshot = PersistedEmergencySnapshot(
    emergencyId = emergencyId,
    state = state.name,
    deadlineMs = deadlineMs,
    evidence = evidence.map { PersistedEvidenceItem(it.code, it.label, it.detectedAtMs) },
)

fun PersistedEmergencySnapshot.toDomain(): EmergencySnapshot? {
    val parsedState = runCatching { EmergencyState.valueOf(state) }.getOrNull() ?: return null
    return EmergencySnapshot(
        emergencyId = emergencyId,
        state = parsedState,
        deadlineMs = deadlineMs,
        evidence = evidence.map { EvidenceItem(it.code, it.label, it.detectedAtMs) },
        realEmergencyDispatchEnabled = false,
    )
}
