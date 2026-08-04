package vn.edu.haui.hvs.safedrive.data.remote.dto

import kotlinx.serialization.Serializable

/**
 * `type` selects which optional field is populated (`reason`/`scenarioId`/`connectionStatus`); this
 * flat shape maps 1:1 to the sealed `EventPayload` in `core.model` without a `Map<String, Any>`
 * anywhere (docs/android-mvp-plan/03-data-api-contract.md, "POST /events").
 */
@Serializable
data class EventRequestDto(
    val sessionId: String,
    val eventId: String,
    val type: String,
    val occurredAtMs: Long,
    val reason: String? = null,
    val scenarioId: String? = null,
    val connectionStatus: String? = null,
)

@Serializable
data class EventAcceptedDto(
    val eventId: String,
    val accepted: Boolean,
    val acceptedAtMs: Long,
    val stateVersion: Long? = null,
)

@Serializable
data class ActionConfirmRequestDto(
    val sessionId: String,
    val actionId: String,
    val actionType: String,
    val confirmed: Boolean,
    val confirmationId: String,
    val contextVersion: Long,
    val hvacTargetTemperatureC: Float? = null,
)

@Serializable
data class ActionConfirmResponseDto(
    val accepted: Boolean,
    val actionResult: String? = null,
    val message: String? = null,
    val serverTimeMs: Long,
)
