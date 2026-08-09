package vn.edu.haui.hvs.safedrive.feature.emergency

import vn.edu.haui.hvs.safedrive.core.model.EmergencyState
import vn.edu.haui.hvs.safedrive.core.model.EvidenceItem
import vn.edu.haui.hvs.safedrive.core.model.RescueBrief
import vn.edu.haui.hvs.safedrive.core.model.RescueDispatchReceipt

/** Emergency screen state per docs/android-mvp-plan/04-screen-specs.md ("Emergency/SOS"). */
sealed interface EmergencyUiState {
    data object Hidden : EmergencyUiState

    data class Active(
        val emergencyId: String,
        val state: EmergencyState,
        val remainingSeconds: Int,
        val evidence: List<EvidenceItem>,
        val rescueBrief: RescueBrief? = null,
        val rescueDispatch: RescueDispatchReceipt? = null,
        val developerMode: Boolean = false,
        /** Optional, bounded LLM second-opinion explanation. Display-only. */
        val reasoningSummary: String? = null,
        /** Recent speed readings (oldest first), for the Developer-Mode sparkline. Display-only —
         * never fed back into any safety decision. */
        val speedHistoryKmh: List<Float> = emptyList(),
    ) : EmergencyUiState
}
