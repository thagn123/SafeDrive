# Contract Delta Draft (W0.9)

Draft of the fields plan 12 §7 (W7 Contract freeze) will lock into `openapi/safedrive-v1.yaml`.
Written **before** touching DTOs/Remote implementation so W1's domain model changes and W7's OpenAPI
freeze describe the same shape from day one, instead of drifting and needing reconciliation later.
This file is superseded by `openapi/safedrive-v1.yaml` once W7 lands; until then it is the single
place tracking "what the wire contract needs to grow into."

## Current shape (verified in source, `core/model/GatewayContracts.kt` + `data/remote/dto/AssistantDtos.kt`)

```kotlin
data class AssistantQueryRequest(
    val sessionId: String,
    val requestId: String,
    val text: String,
    val context: AssistantContext, // { stateVersion, screen, locale = "vi-VN" }
)

data class AssistantQueryResult(
    val requestId: String,
    val message: ChatMessage,
    val serverTimeMs: Long,
)
```

## Delta needed for W1 (unified turn coordinator) and W7 (OpenAPI freeze)

| Field | Where it goes | Why |
|---|---|---|
| `source` | Top-level request, `AssistantTurnSource`: `TEXT` \| `VOICE` \| `QUICK_PROMPT` \| `RETRY` | Plan 12 §4.2/W1.1. Needed so backend/observability can distinguish input channel; today `screen: String` is overloaded to mean this (`"assistant"` / `"voice"`) which is not a closed/typed set. |
| `locale` | Move from nested `AssistantContext.locale` to top-level request field | Matches plan 12's proposed request shape (§7 "Assistant request v1 đề xuất"); avoids implying locale is display-context rather than a request-level parameter. |
| `clientAttemptOf` | Top-level request, `String?` | Plan 12 W1.12: retry creates a new `requestId` but must point back to the original failed request for lineage/observability. `null` on first attempt. |
| `serverProcessingMs` | Response, `Long?` (Developer Mode observability only) | Plan 12 §7 response shape. Mock can report the same value it already computes as `ChatMessage.latencyMs` today; Remote reports real server time. |
| `model` | Response, `String?` (Developer Mode observability only) | Plan 12 §7: "chỉ phục vụ observability trong Developer Mode; Android không tự chọn model production." Not used for any routing decision client-side. |
| `finishReason` | Response, `String?` | Plan 12 §7 example (`"STOP"`). Additive/optional — Mock can hard-code `"STOP"`, Remote passes through backend value. |
| `safetyMetadata` | Response, optional/nullable, shape TBD by backend | Explicitly optional per plan 12 §7 "additive rules" — not required for Gate E, just reserved so adding it later isn't a breaking change. |
| Error envelope: `code/message/requestId/retryable/serverTimeMs` | New shared DTO for all non-2xx responses | Plan 12 W7.2. Today `GatewayError` is a good typed Kotlin sealed type but the **wire shape** of an error body from Remote is not yet formally specified anywhere — `ApiMappers.kt` infers it from HTTP status only, not from a parsed error body. |
| `GatewayError.Configuration("REMOTE_BASE_URL_MISSING")` | New `GatewayError` variant (Kotlin-side only, not a wire field) | Plan 12 W5.6. Needed before Remote can fail-fast instead of silently returning Mock. |

## Explicitly NOT changing in this delta

- No raw-audio field or endpoint anywhere in v1 (plan 12 §3.1, §11). Voice always sends `text` +
  `source=VOICE`, never audio bytes/URI.
- No change to `StartSessionRequest`/`SessionInfo` shape — W5's session fixes are about *when* and
  *how often* these are called (timeout budget, cache key, no `sess_local` fabrication on Remote
  failure), not the DTO fields themselves.
- No change to `EmergencySnapshot`/`EmergencyResponseRequest` shape — Emergency contract is already
  stable from the original MVP build; W2 only changes which component *calls* `EmergencyRepository`,
  not the wire contract.

## Sequencing

1. W1 adds `source`/`clientAttemptOf` to the **domain** `AssistantTurnSource`/turn state (not yet the
   wire DTO) so the coordinator can be built and tested against fakes first.
2. W5 adds `GatewayError.Configuration` (Kotlin-only).
3. W7 is where the **wire** DTOs (`AssistantQueryRequestDto`, `AssistantQueryResponseDto`) actually
   gain these fields, together with the OpenAPI file, examples, and updated contract tests — so the
   Kotlin-side and wire-side changes land together instead of the DTO drifting ahead of or behind the
   spec.
