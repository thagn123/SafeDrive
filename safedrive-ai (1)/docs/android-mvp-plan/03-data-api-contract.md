# 03 — Data model và API contract

> **Trạng thái:** `12-mobile-completion-before-ai-backend.md` supersede tài liệu này trên đúng 3 điểm
> nếu có xung đột: Remote không fallback sang Mock, total assistant-turn timeout bao gồm cả session,
> và lịch/gate stabilization trước AI Backend. Nội dung timeout/error-mapping bên dưới (mục "Timeout
> và lỗi") đã được viết khớp với các quyết định đó. OpenAPI `openapi/safedrive-v1.yaml` (từ W7) là
> source of truth máy đọc được khi hoàn tất; tài liệu này chỉ còn vai trò giải thích.

## Quy ước chung

- JSON dùng `camelCase`; timestamps dùng Unix milliseconds UTC.
- Mọi response có `requestId` hoặc `eventId` khi là mutation.
- Dữ liệu thiếu giữ `null`, không suy diễn thành `false`, `0` hoặc “tài xế ổn”.
- `updatedAtMs` dùng để phát hiện stale; client hiển thị trạng thái stale, không tự sửa dữ liệu.
- Backend là authority cho risk/rest recommendation, DTC severity và emergency deadline khi Remote Mode.
- Mock Gateway phải trả cùng domain result và lỗi tương đương Remote Gateway.
- Contract được version bằng OpenAPI; Android pin `contractVersion` và phải fail rõ ràng khi major version không tương thích.

## Domain model Android

```text
VehicleState
  speedKmh: Float
  engineTemperatureC: Float
  cabinTemperatureC: Float
  energyPercent: Int
  continuousDrivingMinutes: Int?
  steeringLastInteractionSeconds: Int?
  driverSeatOccupied: Boolean?
  wearableConnected: Boolean
  activeDtcs: List<Dtc>
  crashDetected: Boolean
  passengerResponse: RESPONSIVE | NO_RESPONSE | UNKNOWN
  updatedAtMs: Long

DriverSupportSignals
  steeringSignalAvailable: Boolean
  seatSensorAvailable: Boolean
  wearableLastUpdateMs: Long?
  wearableHeartRateBpm: Int?
  userReportedFatigue: Boolean?
  availableSourceCount: Int
  totalSourceCount: Int

RestRecommendation
  level: NORMAL | MONITOR | CONSIDER_REST | REST_RECOMMENDED | INSUFFICIENT_DATA
  title: String
  message: String
  confidence: LOW | MEDIUM | HIGH
  reasonCodes: List<String>       # chỉ hiển thị Developer Mode
  updatedAtMs: Long

Dtc
  code: String
  title: String
  description: String
  severity: LOW | MEDIUM | HIGH | CRITICAL
  recommendation: String
  updatedAtMs: Long

RiskAssessment
  level: LOW | MEDIUM | HIGH | CRITICAL
  title: String
  message: String
  reasonCodes: List<String>       # không render ngoài Developer Mode

ChatMessage
  id: String
  sender: USER | SAFEDRIVE
  text: String
  timestampMs: Long
  risk: RiskAssessment?
  actions: List<SafeDriveAction>
  route: String?
  latencyMs: Long?

SafeDriveAction
  id: String
  type: SHOW_WARNING | OPEN_DIAGNOSTICS | SUGGEST_REST_STOP |
        START_SOS_COUNTDOWN | NONE
  title: String
  requiresConfirmation: Boolean

EmergencySnapshot
  emergencyId: String
  state: IDLE | CANDIDATE_DETECTED | VERIFYING_EVIDENCE |
         AWAITING_USER_RESPONSE | FINAL_COUNTDOWN |
         SOS_SIMULATED_SENT | CANCELLED
  deadlineMs: Long?
  evidence: List<EvidenceItem>
  realEmergencyDispatchEnabled: false
```

Android không tạo model field cho `attentionScore`, `drowsinessScore`, `driverIsAlert` hoặc `driverIsDrowsy`.

## Ownership và freshness

| Dữ liệu | Authority Remote | Authority Demo | Freshness mặc định |
|---|---|---|---|
| Vehicle telemetry | Backend/vehicle adapter | MockVehicleDataSource | fresh ≤5s; stale 5–30s; unavailable >30s |
| Wearable | Backend integration | Fixture | stale sau 120s như prototype |
| Risk/rest | Deterministic backend | Mock fixture evaluator | cùng `stateVersion` với vehicle state |
| DTC severity/recommendation | Backend diagnostic policy | Fixture | theo `updatedAtMs`; không tự đổi phía UI |
| Chat response/action | Backend assistant | MockSafeDriveGateway | gắn `requestId`/context version |
| Emergency state/deadline | Backend emergency service | Mock reducer + FakeClock | authoritative theo `serverTimeMs` |
| Settings | Android DataStore | Android DataStore | local, phát Flow |

Các ngưỡng freshness là cấu hình domain, không hard-code trong composable. Khi client/server lệch giờ, deadline render theo offset tính từ `serverTimeMs`; không sửa đồng hồ hệ thống.

## Ma trận endpoint triển khai

| Endpoint | Request DTO | Response DTO | Repository method | Loading/error | Phase |
|---|---|---|---|---|---|
| `GET /health` | — | `HealthResponseDto` | `checkHealth()` | timeout 5s; manual retry | MVP |
| `POST /sessions/start` | `StartSessionRequestDto` | `StartSessionResponseDto` | `startSession()` | no infinite retry; protocol mismatch blocks Remote | MVP |
| `POST /state/update` | `StateUpdateRequestDto` | `StateEnvelopeDto` | `updateVehicleState()` | coalesce; validation keeps old state | MVP Remote |
| `GET /state` | query `sessionId/sinceVersion` | `StateEnvelopeDto` | `getVehicleState()` | cached/stale fallback | MVP |
| `POST /assistant/query` | `AssistantQueryRequestDto` | `AssistantQueryResponseDto` | `queryAssistant()` | total turn timeout 10s gồm session; retry/duplicate guard | MVP |
| `POST /events` | `EventRequestDto` | `EventAcceptedDto` | `sendEvent()` | queued/bounded retry; idempotent | MVP Remote |
| `POST /actions/confirm` | `ActionConfirmRequestDto` | `ActionConfirmResponseDto` | `confirmAction()` | conflict refetch; unknown no-op | MVP |
| `GET /emergency/{id}` | path + session query | `EmergencySnapshotDto` | `getEmergency()` | refresh on resume/recreate | MVP |
| `POST /emergency/{id}/respond` | `EmergencyResponseRequestDto` | `EmergencySnapshotDto` | `respondEmergency()` | idempotent; never dispatch real | MVP |
| `WS /ws/cockpit` | handshake/session | versioned socket messages | `observeCockpit()` | reconnect + version check + polling | Optional after REST gate |

Base path đầy đủ là `/api/v1`. Tên DTO trong bảng là tên bắt buộc để Claude tạo file và contract test; field-level schema nằm ở từng mục dưới đây.

## REST endpoints

### `GET /health` — MVP Remote

Mục đích: kiểm tra backend và phiên bản contract.

Response tối thiểu:

```json
{
  "status": "ok",
  "service": "safedrive-backend",
  "apiVersion": "v1",
  "serverTimeMs": 1760000000000,
  "capabilities": { "assistant": true, "emergencySimulation": true, "cockpitStream": false }
}
```

Android hiển thị `NORMAL`, `NO_AI_SERVICE` hoặc `OFFLINE`; timeout 5 giây, retry thủ công ở Settings.

### `POST /api/v1/sessions/start` — MVP Remote

Request: `deviceId`, `appVersion`, `platform`, `mode`, `clientTimeMs`.

Response: `sessionId`, `expiresAtMs`, `serverTimeMs`, `contractVersion`, `realEmergencyDispatchEnabled: false`.

Repository: `startSession()`; gọi khi Remote Mode khởi tạo hoặc session hết hạn. Không retry vô hạn.

### `POST /api/v1/state/update` — MVP Remote

Request: `sessionId`, `state`, `source`, `clientEventId`.

`source` là `mock`, `phone_simulator` hoặc `vehicle_adapter`; không gửi raw camera/DMS.

Response: `state`, `riskAssessment`, `restRecommendation`, `acceptedAtMs`, `stateVersion`.

Repository: `updateVehicleState()`; debounce/coalesce telemetry nếu tần suất cao. Lỗi validation giữ snapshot cũ và hiển thị stale/error.

### `GET /api/v1/state` — MVP Remote

Query: `sessionId`, tùy chọn `sinceVersion`.

Response: cùng shape với state update. Dùng bootstrap, retry sau reconnect và fallback khi WebSocket chưa có.

### `POST /api/v1/assistant/query` — MVP Remote

**Candidate** at W7 — not "frozen" until Gate E's device-QA/human-review criteria also pass. The
exact wire shape lives in `openapi/safedrive-v1.yaml`
(`AssistantQueryRequest`/`AssistantQueryResponse`) and `openapi/examples/assistant-text-query.json` /
`assistant-voice-query.json` / `assistant-response.json`; the excerpt below is illustrative only.
`locale` moved from `context.locale` to a top-level request field, and `source`/`clientAttemptOf`
were added at the top level, in W7 — do not nest them back under `context`.

```json
{
  "sessionId": "sess_demo_001",
  "requestId": "req_123",
  "text": "Nhiệt độ động cơ hiện tại là bao nhiêu?",
  "source": "TEXT",
  "locale": "vi-VN",
  "clientAttemptOf": null,
  "context": { "stateVersion": 12, "screen": "assistant" }
}
```

Response:

```json
{
  "requestId": "req_123",
  "message": {
    "id": "msg_456",
    "sender": "SAFEDRIVE",
    "text": "Nhiệt độ động cơ hiện tại là 92°C.",
    "timestampMs": 1760000000200,
    "risk": null,
    "actions": [],
    "route": "safety_fast_path"
  },
  "serverTimeMs": 1760000000200,
  "serverProcessingMs": 180,
  "model": "backend-selected",
  "finishReason": "STOP"
}
```

`serverProcessingMs`/`model`/`finishReason` are additive/optional (Developer Mode observability only
— Android never selects or depends on a model). `safetyMetadata` is reserved for a future phase and
intentionally not yet parsed by the client (see `openapi/safedrive-v1.yaml`).

Repository: `queryAssistant()`. Disable send khi request đang chạy (global single-flight qua
`AssistantTurnCoordinator`); retry phải tạo `requestId` mới và đặt `clientAttemptOf` về request trước
để tránh duplicate — không tự động retry sau khi đã gửi. Total assistant-turn timeout mặc định
10 giây, bao gồm start-session nếu cần. Khi user đã chọn Remote, lỗi/timeout không được đổi sang Mock;
BASE_URL trống trả `GatewayError.Configuration` cục bộ, không gọi network. Offline allowlist, nếu
được bật, phải là response local có nhãn rõ và không giả vờ gọi AI. Chi tiết action allowlist/confirmation:
`docs/assistant-action-allowlist.md`. Chi tiết timeout budget đầy đủ: `docs/latency-budget.md`.

### `POST /api/v1/events` — MVP Remote

Gửi telemetry/event không phải command: `sessionId`, `eventId`, `type`, `occurredAtMs`, `payload`. Dùng cho `USER_REPORTED_FATIGUE`, `VOICE_ERROR`, `SCENARIO_APPLIED`, `CONNECTION_CHANGED`. Idempotency theo `eventId`.

Response: `eventId`, `accepted`, `acceptedAtMs`, `stateVersion?`. Payload phải là typed event DTO theo `type`; không dùng `Map<String, Any>` trong domain/ViewModel.

### `POST /api/v1/actions/confirm` — MVP Remote

Request: `sessionId`, `actionId`, `actionType`, `confirmed`, `confirmationId`, `contextVersion`.

Response: `accepted`, `actionResult`, `message`, `serverTimeMs`. Chỉ allowlist action; unknown action phải bỏ qua. Action có tác động an toàn phải yêu cầu confirmation UI trước khi gọi.

### `GET /api/v1/emergency/{id}` — MVP Remote

Response: `EmergencySnapshot`, gồm state hiện tại, `deadlineMs`, evidence summary, `responseRequired`, `realEmergencyDispatchEnabled: false`.

Repository: `getEmergency()`, dùng khi app resume/process recreation. Nếu deadline đã qua, server trả state mới; client không tự “đoán” bước tiếp theo khi Remote Mode.

### `POST /api/v1/emergency/{id}/respond` — MVP Remote

Request: `sessionId`, `responseId`, `response` (`USER_OK`, `CANCEL_SOS`, `NO_RESPONSE`), `clientTimeMs`.

Response: authoritative `EmergencySnapshot`. `USER_OK` và `CANCEL_SOS` là idempotent. MVP chỉ được chuyển tới `SOS_SIMULATED_SENT`, không dispatch thật.

### `WS /api/v1/ws/cockpit` — optional sau REST gate

Chỉ triển khai sau khi REST/polling pass. Message types: `state_updated`, `risk_updated`, `emergency_updated`, `heartbeat`, `error`. Phải có reconnect backoff, sequence/version check và fallback polling; WebSocket không được là dependency duy nhất để render cockpit.

## Gateway error mapping

| Lỗi | Domain error | UI behavior |
|---|---|---|
| Timeout | `Timeout` | Banner rõ ràng, retry |
| DNS/connect refused | `Offline` | Hiển thị cached state + stale marker |
| 401/403 | `Unauthorized` | Kết thúc session; không loop retry |
| 404 contract | `Unsupported` | `NO_AI_SERVICE`, log developer |
| 409 duplicate/version | `Conflict` | Refetch state/action result |
| 422 | `Validation` | Hiển thị lỗi input, không đổi state |
| Local Remote config thiếu/sai | `Configuration` | Không gọi network/Mock; hướng dẫn cấu hình BASE_URL |
| 5xx | `Server` | Retry có giới hạn; không đổi Remote sang Mock; local allowlist phải gắn nhãn rõ |
| JSON invalid | `Protocol` | Không parse tùy ý; giữ state cũ |

## Mock parity

`MockSafeDriveGateway` phải implement đủ các method cần cho UI và trả fixture theo scenario. Chuyển Demo → Remote chỉ thay dependency; không được có `if (demoMode)` rải trong composable.

## Contract freeze và change control

- Android/backend owner tạo contract-delta draft trong W0 của plan `12`; OpenAPI `v1` và fixtures
  phải validate và được freeze tại Gate E trước khi bắt đầu AI Backend.
- Android sinh/viết DTO từ schema đã review; domain model không phụ thuộc generated type.
- Thay đổi additive optional field: cập nhật mapper/test, không tăng major.
- Rename/remove/đổi semantics field: tăng contract major hoặc thêm field mới; không sửa âm thầm.
- Mỗi PR contract phải cập nhật example JSON, Mock Gateway fixture và serialization test.
- API chưa tồn tại không chặn Demo Mode; Remote feature phải hiển thị capability từ `/health`.
