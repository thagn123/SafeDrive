package vn.edu.haui.hvs.safedrive.data.remote.dto

import kotlinx.serialization.Serializable

@Serializable
data class SafeDriveActionDto(
    val id: String,
    val type: String,
    val title: String,
    val requiresConfirmation: Boolean,
    val hvacTargetTemperatureC: Float? = null,
)

@Serializable
data class ChatMessageDto(
    val id: String,
    val sender: String,
    val text: String,
    val timestampMs: Long,
    val risk: RiskAssessmentDto? = null,
    val actions: List<SafeDriveActionDto> = emptyList(),
    val route: String? = null,
    val latencyMs: Long? = null,
)

@Serializable
data class AssistantContextDto(
    val stateVersion: Long,
    val screen: String,
)

/**
 * Backend contract **candidate** (docs/android-mvp-plan/12 W7.3, `openapi/safedrive-v1.yaml`) — not
 * "frozen": Gate E additionally requires device QA and a human backend/Android owner review of the
 * handoff package, neither of which has happened yet, so this shape may still change. `source` is one
 * of `TEXT`/`VOICE`/`QUICK_PROMPT`/`RETRY`. `clientAttemptOf` is `null` on a first attempt.
 */
@Serializable
data class AssistantQueryRequestDto(
    val sessionId: String,
    val requestId: String,
    val text: String,
    val source: String = "TEXT",
    val locale: String = "vi-VN",
    val clientAttemptOf: String? = null,
    val context: AssistantContextDto,
)

/**
 * `serverProcessingMs`/`model`/`finishReason` are additive/optional (W7.4) — an older or newer
 * backend omitting them is not a parse error (`ignoreUnknownKeys` + nullable defaults).
 * `safetyMetadata` is reserved for a future phase (see `openapi/safedrive-v1.yaml`) and is
 * deliberately not declared here yet: an unknown field on the wire is silently ignored by
 * `ignoreUnknownKeys = true`, so adding it here later is a strictly additive change.
 */
@Serializable
data class AssistantQueryResponseDto(
    val requestId: String,
    val message: ChatMessageDto,
    val serverTimeMs: Long,
    val serverProcessingMs: Long? = null,
    val model: String? = null,
    val finishReason: String? = null,
    // Additive/optional, same compatibility contract as serverProcessingMs/model/finishReason
    // above -- an older backend omitting them must parse with these safe defaults, never a
    // parse failure (ignoreUnknownKeys + nullable/defaulted fields).
    val llmUsed: Boolean = false,
    val fallback: Boolean = false,
    val fallbackReason: String? = null,
)
