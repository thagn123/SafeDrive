package vn.edu.haui.hvs.safedrive.core.model

data class EvidenceItem(
    val code: String,
    val label: String,
    val detectedAtMs: Long,
)

/**
 * `realEmergencyDispatchEnabled` is always `false` in this MVP — no call/SMS/rescue dispatch is
 * ever performed. See docs/android-mvp-plan/05-voice-emergency.md.
 */
data class EmergencySnapshot(
    val emergencyId: String,
    val state: EmergencyState,
    val deadlineMs: Long?,
    val evidence: List<EvidenceItem>,
    val rescueBrief: RescueBrief? = null,
    val rescueDispatch: RescueDispatchReceipt? = null,
    val realEmergencyDispatchEnabled: Boolean = false,
    /** Optional, bounded LLM second-opinion explanation. Display-only — never
     * influences state/deadlineMs/rescueDispatch on the client. */
    val reasoningSummary: String? = null,
)

/** Compact emergency handoff shown only for the explicitly simulated rescue workflow. */
data class RescueBrief(
    val dispatchMode: String,
    val eventType: String,
    val vehicleId: String,
    val timestampMs: Long,
    val lastKnownLocation: RescueLocation?,
    val locationStatus: String,
    val vehicleStatusSummary: String,
    val riskLevel: Severity,
    val evidence: List<String>,
    val realEmergencyDispatchEnabled: Boolean = false,
)

data class RescueLocation(
    val latitude: Double,
    val longitude: Double,
    val source: String,
    val ageMs: Long,
    val freshness: String,
)

data class RescueDispatchReceipt(
    val provider: String,
    val endpoint: String,
    val outcome: String,
    val referenceId: String,
    val receivedAtMs: Long,
)
