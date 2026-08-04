package vn.edu.haui.hvs.safedrive.data.remote.dto

import kotlinx.serialization.Serializable

@Serializable
data class EvidenceItemDto(
    val code: String,
    val label: String,
    val detectedAtMs: Long,
)

@Serializable
data class RescueLocationDto(
    val latitude: Double,
    val longitude: Double,
    val source: String,
    val ageMs: Long,
    val freshness: String,
)

@Serializable
data class RescueBriefDto(
    val dispatchMode: String,
    val eventType: String,
    val vehicleId: String,
    val timestampMs: Long,
    val lastKnownLocation: RescueLocationDto? = null,
    val locationStatus: String,
    val vehicleStatusSummary: String,
    val riskLevel: String,
    val evidence: List<String>,
    val realEmergencyDispatchEnabled: Boolean = false,
)

@Serializable
data class RescueDispatchReceiptDto(
    val provider: String,
    val endpoint: String,
    val outcome: String,
    val referenceId: String,
    val receivedAtMs: Long,
)

@Serializable
data class EmergencySnapshotDto(
    val emergencyId: String,
    val state: String,
    val deadlineMs: Long? = null,
    val evidence: List<EvidenceItemDto> = emptyList(),
    val rescueBrief: RescueBriefDto? = null,
    val rescueDispatch: RescueDispatchReceiptDto? = null,
    val realEmergencyDispatchEnabled: Boolean = false,
    // Optional, bounded LLM second-opinion explanation (backend
    // app/mobile/emergency_reasoner.py). Advisory only -- display-only field,
    // never affects state/deadlineMs/rescueDispatch on the client either.
    val reasoningSummary: String? = null,
)

@Serializable
data class EmergencyResponseRequestDto(
    val sessionId: String,
    val responseId: String,
    val response: String,
    val clientTimeMs: Long,
)
