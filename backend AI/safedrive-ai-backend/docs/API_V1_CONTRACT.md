# SafeDrive `/api/v1` Contract (as actually implemented)

This documents the routes Android's `SafeDriveApi.kt` genuinely calls (`app/api/routes/mobile.py`),
verified against runtime by `tests/test_mobile_compatibility.py`. There are two independent route
groups under `/api/v1` in this backend — do not confuse them:

- **Mobile compatibility routes** (below) — what the Android app uses. No API key.
- **Signal ingestion routes** (`/api/v1/signals`, `/api/v1/state` with `vehicle_id`/`trip_id` query
  params) — a separate, API-key-authenticated canonical-signal pipeline, pinned by
  `contracts/openapi.yaml`. Not used by the mobile assistant/chat/emergency flow.

`GET /health` and `GET /ready` are deliberately **outside** `/api/v1` (see the docstring in
`SafeDriveApi.kt`) — this was an intentional decision already made in the Android codebase, not an
oversight.

## Routes

| Method | Path | Purpose |
|---|---|---|
| POST | `/api/v1/sessions/start` | Start a session (Demo or Remote) |
| POST | `/api/v1/state/update` | Push a vehicle-state snapshot; returns `riskAssessment` computed synchronously, before any LLM call |
| GET  | `/api/v1/state?sessionId=` | Read the latest state envelope |
| POST | `/api/v1/assistant/query` | Text/voice-transcript turn; may narrate via Ollama for a small allow-listed set of routes only |
| POST | `/api/v1/events` | Non-critical events (e.g. `USER_REPORTED_FATIGUE`) |
| POST | `/api/v1/actions/confirm` | Confirm a server-issued action (only `SET_HVAC_TEMPERATURE` has a real side effect today) |
| GET  | `/api/v1/emergency/{id}?sessionId=` | Read simulated SOS state machine |
| POST | `/api/v1/emergency/{id}/respond` | User response during the SOS flow (`USER_OK`/`CANCEL_SOS`/`NO_RESPONSE`) |

No legacy aliases exist for these routes — Android has always called the `/api/v1/*` paths above.

## Not implemented

`WS /api/v1/ws/cockpit` does **not** exist. The system uses client-driven polling (`GET
/api/v1/state`, `GET /api/v1/emergency/{id}`) instead. This was a deliberate scope decision for this
pass — building a new WebSocket push channel was judged higher-risk/lower-value than the seven demo
scenarios, all of which work correctly over polling. See `docs/KNOWN_LIMITATIONS.md`.

## `AssistantQueryResponse` observability fields

Added for the competition MVP — additive/optional, so an older client or backend still parses fine:

```json
{
  "requestId": "req_1",
  "message": { "...": "..." },
  "serverTimeMs": 1785843990273,
  "model": "ollama/qwen2.5:7b-instruct-q4_K_M",
  "llmUsed": true,
  "fallback": false,
  "fallbackReason": null
}
```

- `llmUsed=false, fallback=false` — this route never calls an LLM at all (deterministic by design:
  DTC, fatigue, HVAC, status).
- `llmUsed=true, fallback=false` — a real Ollama call produced this reply.
- `llmUsed=false, fallback=true, fallbackReason="provider_unavailable"` — an LLM attempt was made
  for a narratable route (e.g. `companion.conversation`) and failed/was rejected; the deterministic
  reply was used instead. Android must read these fields explicitly, never infer them from the
  `model` string.
