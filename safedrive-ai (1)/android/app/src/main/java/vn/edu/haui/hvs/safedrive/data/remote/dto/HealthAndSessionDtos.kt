package vn.edu.haui.hvs.safedrive.data.remote.dto

import kotlinx.serialization.Serializable

@Serializable
data class HealthCapabilitiesDto(
    val assistant: Boolean,
    val emergencySimulation: Boolean,
    val cockpitStream: Boolean,
)

@Serializable
data class HealthResponseDto(
    val status: String,
    val service: String,
    val apiVersion: String,
    val serverTimeMs: Long,
    val capabilities: HealthCapabilitiesDto,
)

/** `/health` proves process liveness; `/ready` proves the SafeDrive services can serve sessions. */
@Serializable
data class ReadinessResponseDto(
    val status: String,
)

@Serializable
data class StartSessionRequestDto(
    val deviceId: String,
    val appVersion: String,
    val platform: String,
    val mode: String,
    val clientTimeMs: Long,
)

@Serializable
data class StartSessionResponseDto(
    val sessionId: String,
    val expiresAtMs: Long,
    val serverTimeMs: Long,
    val contractVersion: String,
    val realEmergencyDispatchEnabled: Boolean,
)
