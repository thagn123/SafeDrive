# Mock vs Remote Gateway Coverage

Both `MockSafeDriveGateway` (`data/mock/`) and `RemoteSafeDriveGateway` (`data/remote/`) implement
the same `domain.repository.SafeDriveGateway` interface and pass the same shared contract test
(`SafeDriveGatewayContractTest`, see `TEST_REPORT.md`). **`openapi/safedrive-v1.yaml`, a **candidate**
from W7 (not yet frozen — Gate E also requires device QA and a human review, both still pending), is
the machine-readable source of truth for the wire shape** — this table is the narrative endpoint-status
view; `03-data-api-contract.md` explains rationale but never overrides the OpenAPI file.

| Endpoint | Mock (Demo Mode) | Remote (Retrofit) | Notes |
|---|---|---|---|
| `GET /health` | ✅ Always `NORMAL`, all capabilities `true` except `cockpitStream` | ✅ Real HTTP call, maps `status`/`capabilities` | 5s client-side timeout cap (W5.9). Contract + error-mapping tests on both. |
| `POST /api/v1/sessions/start` | ✅ Generates a local session id | ✅ Real HTTP call | `mode` now reflects the **live** backend mode (`SessionCoordinator`, W5.1 — no longer hard-coded `DEMO`). `realEmergencyDispatchEnabled` hard-coded `false` on both sides regardless of server response. Session cached per `(mode, baseUrl)` with expiry (W5.2/W5.3); a Remote failure never falls back to a fabricated local session id (W5.5). |
| `POST /api/v1/state/update` | ✅ Runs `MockPolicyEvaluator` (risk/rest) | ✅ Real HTTP call, maps `StateEnvelopeDto` | Also now re-issued on a mode/BASE_URL change alone, with no vehicle-state change (W5.8). |
| `GET /api/v1/state` | ✅ Returns last envelope pushed by `updateVehicleState` | ✅ Real HTTP call | Bootstrap/polling only; no WebSocket (out of scope, see `KNOWN_LIMITATIONS.md`). |
| `POST /api/v1/assistant/query` | ✅ Text-matching fixture replies; reports `serverProcessingMs`/`model="safedrive-mock-rules"`/`finishReason="STOP"` on every reply (W7.4) | ✅ Real HTTP call, request/response carry `source`/`locale`/`clientAttemptOf` (request) and `serverProcessingMs`/`model`/`finishReason` (response) per the candidate contract (W7.3/W7.4) | Single entry point for text/quick-prompt/voice via `AssistantTurnCoordinator` (W1/W2) — no other assistant request path exists anywhere in the app. `AssistantTurnCoordinator` mints exactly one `requestId` per attempt and passes it into `AssistantQueryUseCase`, which sends that exact id on the wire — no second, independently-minted id (remediation item 1; previously the coordinator's internal id and the network id were two different values). 10s total timeout covers session + query together (W5.4/W5.11). Never auto-retried by the client (W5.14/W1.12) — user retry sends a new `requestId` with `clientAttemptOf` set to the exact previous attempt's real network id. Blocked entirely (no network call) when the last known health reported `assistant=false` (W5.10). |
| `POST /api/v1/events` | ✅ Accepts and echoes `eventId` | ✅ Real HTTP call | Used for `SCENARIO_APPLIED` (Simulator); fire-and-forget, silently skipped if session resolution itself fails. |
| `POST /api/v1/actions/confirm` | ✅ Echoes `confirmed` as `accepted` | ✅ Real HTTP call | Only called for actions with `requiresConfirmation = true` — see `docs/assistant-action-allowlist.md` for the candidate per-type rule. |
| `GET /api/v1/emergency/{id}` | ❌ Returns `GatewayError.Unsupported` (Demo's Emergency authority is the local `DataStoreEmergencyRepository`/`EmergencyReducer`, not this gateway) | ✅ Implemented, maps `EmergencySnapshotDto` | Unchanged this pass. Matches `05-voice-emergency.md`: "Mock reducer chỉ cho Demo; Remote backend là authority". |
| `POST /api/v1/emergency/{id}/respond` | ❌ Same as above | ✅ Implemented | Unchanged this pass. |
| `WS /api/v1/ws/cockpit` | Not applicable | ❌ Not implemented | Explicitly optional/deferred past the REST gate; `cockpitStream` capability flag is always `false`. |

## Error mapping (both implementations return the same typed `GatewayError`)

| Condition | `GatewayError` | Wire `ErrorEnvelope.code` |
|---|---|---|
| Timeout / connection never responds | `Timeout` | `TIMEOUT` |
| Connection refused/reset, DNS failure | `Offline` | `OFFLINE` |
| HTTP 401/403 | `Unauthorized` | `UNAUTHORIZED` |
| HTTP 404 | `Unsupported` | `UNSUPPORTED` |
| HTTP 409 | `Conflict` | `CONFLICT` |
| HTTP 422 | `Validation` | `VALIDATION` |
| HTTP 5xx | `Server` | `SERVER` |
| Unparseable JSON body | `Protocol` | `PROTOCOL` |
| **Remote Mode selected with no/blank BASE_URL** | **`Configuration`** (new, W5.6) | *(client-local only — never appears on the wire; see `ConfigurationErrorGateway`)* |

`MockSafeDriveGateway` does not produce most of these (it has no network), except `Unsupported` for
the two Emergency endpoints above. `RemoteSafeDriveGateway` parses the response's typed `ErrorEnvelope`
body (`code`/`message`/`requestId`/`retryable`/`serverTimeMs`, matching `openapi/safedrive-v1.yaml`
exactly) and maps `code` to the `GatewayError` above — the HTTP status alone is only a fallback for a
missing/malformed body or an unrecognized future code (remediation item 4; previously the body was
never read at all and every error was inferred from HTTP status alone). Verified end-to-end by
`RemoteSafeDriveGatewayErrorMappingTest` against a real `MockWebServer`, including envelope/status
mismatches and malformed/empty bodies. `GatewayError.Configuration` (both
`REMOTE_BASE_URL_MISSING` and the newer `CONTRACT_VERSION_INCOMPATIBLE` reason codes) is verified by
`ConfigurationErrorGatewayTest`/`SessionCoordinatorTest` — every case fails fast, never silently
substitutes Mock behavior or proceeds against a backend whose contract version this client doesn't
understand.

## Switching between them

`SafeDriveContainer.gatewayProvider` is the single seam: it reads
`PreferencesRepository`'s current `AppPreferences.backendMode`/`baseUrl` and returns either the
singleton `MockSafeDriveGateway`, a cached-by-URL `RemoteSafeDriveGateway`, or (W5.6) a
`ConfigurationErrorGateway` when Remote Mode has no BASE_URL configured — no other code in the app
ever branches on which mode is active. Its `forPreferences(prefs)` overload resolves directly from an
already-captured `AppPreferences` snapshot instead of re-reading the live flow — `SessionCoordinator`
uses this exclusively (never the ambient `current()`) so the gateway a session was started against and
the gateway a follow-up query/event/action is sent through are always the exact same instance
(remediation item 3; previously two independent reads of the same preferences flow a few statements
apart could in principle observe different values). Switching mode/URL in Settings invalidates the
current session, **cancels any in-flight assistant turn, and clears the cached health/capability
status** (`SafeDriveContainer`'s init block, W5.7 + remediation item 3), so nothing keeps running
against — or stays gated by stale capability info from — the gateway the user just switched away from.

## Contract candidate artifacts (W7, pending Gate E)

- `openapi/safedrive-v1.yaml` — full OpenAPI 3.0.3 spec, validated offline with
  `openapi-spec-validator`.
- `openapi/examples/*.json` — one example per endpoint/direction, decoded verbatim by
  `AssistantDtoSerializationTest`.
- `docs/backend-handoff.md`, `docs/assistant-action-allowlist.md`, `docs/latency-budget.md`.
