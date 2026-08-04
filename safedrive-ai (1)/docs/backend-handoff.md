# Backend Handoff — SafeDrive AI v1

**Candidate** contract from `docs/android-mvp-plan/12-mobile-completion-before-ai-backend.md` (W7) —
not "frozen": Gate E also requires device QA and a human backend/Android owner review of this exact
package, neither of which has happened yet. This is the narrative companion to
`openapi/safedrive-v1.yaml`, which is the machine-readable source of truth — if this document and the
OpenAPI file ever disagree, the OpenAPI file wins.

## 1. What the Android client already does (nothing here is speculative)

- **Two runtimes exist in this repo**: `android/` (native Kotlin/Compose, the real client) and `src/`
  (a React/TypeScript AI Studio prototype used only as a UI/copy reference — it does not call any
  backend and is not part of this handoff).
- Android has two gateway implementations behind one interface
  (`domain.repository.SafeDriveGateway`): `MockSafeDriveGateway` (Demo Mode, fully offline) and
  `RemoteSafeDriveGateway` (Retrofit/OkHttp, implements every endpoint in the OpenAPI file). A backend
  team can build entirely against the OpenAPI file and Mock's fixture data
  (`android/app/src/main/java/.../data/mock/MockFixtures.kt`) as a behavioral reference, without
  reading any Compose UI code.
- Every endpoint in the OpenAPI file already has a passing contract test on the Android side
  (`SafeDriveGatewayContractTest` and its `MockSafeDriveGatewayContractTest`/
  `RemoteSafeDriveGatewayContractTest` subclasses, plus `RemoteSafeDriveGatewayErrorMappingTest` for
  the error-mapping table below) — see `android/TEST_REPORT.md` and
  `docs/android-stabilization-progress.md` for current pass counts.

## 2. Sequencing per assistant turn

```text
Android                                          Backend
--------                                         -------
1. User sends text/quick-prompt, OR
   SpeechRecognizer produces a final transcript
   (voice never sends audio — text only)
2. AssistantTurnCoordinator.submit()
   - appends user bubble locally
   - checks: is a turn already in flight? (single-flight — reject if so)
   - checks: last known health said assistant=false? (reject locally, no call)
3. SessionCoordinator.currentSessionId()
   - cached per (backendMode, baseUrl), checked against expiresAtMs
   - on cache miss/expiry: POST /api/v1/sessions/start -----> validates deviceId/appVersion/mode,
                                                                returns sessionId + expiresAtMs
   - on failure: ONE retry only if the error is a connection-level
     error (Offline/Timeout); any other error is NOT retried
   - on failure after retry: the whole turn fails here — no local
     fallback session id is ever fabricated
4. POST /api/v1/assistant/query ----------------> resolves session, applies safety fast path /
   { sessionId, requestId, text, source,           orchestration, returns ChatMessage +
     locale, clientAttemptOf, context }            serverProcessingMs/model/finishReason
                                                    (all three optional/observability-only)
5. Reply appended to chat; if TTS enabled,
   AndroidTextToSpeechController.speak() is
   called exactly once, only for this successful
   reply (never for typed errors/snackbars)
```

Steps 3+4 together must complete within Android's **10 second total deadline**
(`AssistantQueryUseCase`, `docs/android-mvp-plan/12` W5.4/W5.11) — see `docs/latency-budget.md`.
There is no separate budget reserved for session vs. query; a slow session-start eats into the same
10 seconds as the query itself.

## 3. Authority boundaries (who decides what)

| Concern | Authority | Notes |
|---|---|---|
| Risk assessment, rest recommendation, DTC severity | **Backend** (`/api/v1/state/update` response) in Remote Mode | Android never recomputes these; it copies the response verbatim. Demo Mode uses `MockPolicyEvaluator` as a local stand-in with the same output shape. |
| Emergency deadline/state machine | **Backend** (`/api/v1/emergency/{id}`, `.../respond`) in Remote Mode | Demo Mode's authority is a local pure reducer (`EmergencyReducer`) — the two are never mixed; Remote Mode never falls back to the local reducer. |
| `realEmergencyDispatchEnabled` | **Always `false` on the Android side**, regardless of any value the backend sends | This is a hard-coded client invariant (`SessionCoordinator`/`ApiMappers.kt` both discard the wire value). Do not build backend logic that assumes a client will ever honor `true` here — v1 has no real dispatch integration on either side. |
| Session id lifecycle | **Backend issues, client caches with expiry** | Client key = `(backendMode, baseUrl)`. Backend should treat each `startSession` call as potentially a fresh device/session pair — do not assume session reuse beyond `expiresAtMs`. |
| Model selection | **Backend only** | `model` in the assistant response is observability-only; Android has and will have no concept of choosing or requesting a specific model. |

## 4. Timeout and retry contract

See `docs/latency-budget.md` for the full table. Summary the backend should design against:

- Connect 3s / read 8s / write 5s (Android `OkHttpClient` settings).
- Assistant turn total deadline: **10s**, covering session resolution + the query. If your server
  cannot reliably answer within that window, the client will show a typed timeout error — it will
  **not** retry the query automatically, and will **not** fall back to Demo Mode.
- Health check: 5s client-side cap, independent of the above.
- Session/health may be retried **once** by the client, but only for connection-level failures
  (refused/reset/DNS/timeout) — never for a 4xx/422/409, and never for the assistant query itself.

## 5. Error envelope

Every non-2xx response should conform to `#/components/schemas/ErrorEnvelope` in the OpenAPI file
(`code`/`message`/`requestId`/`retryable`/`serverTimeMs`). `code` must be one of the 8 values listed
there — they map 1:1 to the Android `GatewayError` sealed type via HTTP status:

| HTTP status | `GatewayError` / envelope `code` |
|---|---|
| 401/403 | `UNAUTHORIZED` |
| 404 | `UNSUPPORTED` |
| 409 | `CONFLICT` |
| 422 | `VALIDATION` |
| 5xx | `SERVER` |
| (connection refused/reset/DNS failure) | `OFFLINE` |
| (no response within the client's timeout) | `TIMEOUT` |
| (malformed/unparseable JSON body) | `PROTOCOL` |

`CONFIGURATION` is client-local only (Remote Mode selected with no BASE_URL configured) and never
appears on the wire — the backend never needs to produce it.

## 6. Idempotency

- Every assistant query carries a unique `requestId`. The client never re-sends the same `requestId`
  automatically. A user-initiated retry sends a **new** `requestId` with `clientAttemptOf` set to the
  original one — use this to correlate retries in logs/analytics, not to deduplicate a single request
  (there is no automatic duplicate delivery to deduplicate against in v1; this is REST, not
  at-least-once messaging). `clientAttemptOf` is `null` on a retry whenever the *previous* attempt never
  actually reached the network at all (e.g. it failed during session resolution, or was cancelled before
  the query was dispatched) — the backend should never expect to find that id in its own logs in that
  case, since it genuinely never saw it.
- `POST /api/v1/actions/confirm` carries its own `confirmationId`, generated fresh per confirmation —
  same non-auto-retry guarantee.
- `POST /api/v1/events` is fire-and-forget from the client's perspective; losing one is acceptable
  (it is Simulator/analytics telemetry, never a safety-relevant call).

## 7. Voice / transcript-only scope (v1)

There is no raw-audio endpoint and no `multipart`/binary request anywhere in this contract. Voice
input always arrives as `source: "VOICE"` with already-recognized `text` — identical shape to a typed
query. If a future phase needs raw audio, that requires a new ADR, a new ContractVersion, and a
separate consent/retention/privacy review (`docs/android-mvp-plan/12` §3.1) — it is explicitly out of
scope for this handoff.

## 8. What NOT to build yet

- WebSocket cockpit streaming (`cockpitStream` capability flag exists and is always `false`).
- Gemini Live / any streaming assistant response.
- Real emergency dispatch (call/SMS/rescue) behind `realEmergencyDispatchEnabled`.
- Any endpoint not listed in `openapi/safedrive-v1.yaml`.

## 9. Related artifacts

- `openapi/safedrive-v1.yaml` + `openapi/examples/*.json` — the **candidate**, offline-validated
  contract (not "frozen" — see this document's own opening line).
- `docs/assistant-action-allowlist.md` — which `SafeDriveAction.type` values exist and their
  confirmation rules.
- `docs/latency-budget.md` — full timeout table and how Android measures its side.
- `android/MOCK_VS_REMOTE_COVERAGE.md` — endpoint-by-endpoint Mock vs. Remote implementation status.
- `android/KNOWN_LIMITATIONS.md` — what has not been verified on real hardware yet.
