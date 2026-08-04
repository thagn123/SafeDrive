# SafeDrive AI Backend

SafeDrive AI Slice 1 accepts canonical vehicle signals and exposes the latest
versioned state over REST.

## Implemented endpoints

| Method | Path | Authentication | Purpose |
| --- | --- | --- | --- |
| `GET` | `/health` | None | Process liveness |
| `GET` | `/ready` | None | Startup dependency readiness |
| `POST` | `/api/v1/signals` | `X-SafeDrive-Key` | Ingest 1–100 signals |
| `GET` | `/api/v1/state` | `X-SafeDrive-Key` | Read latest vehicle/trip state |

The mutation endpoint also requires an `Idempotency-Key` matching
`^[A-Za-z0-9._:-]{1,128}$`. Reuse the same key only when retrying the same
logical payload.

## Android Remote Mode compatibility layer

The canonical signal API above remains available. The backend also provides an
additive, session-based compatibility layer for the Android Digital Cockpit
MVP. It is intentionally bounded to structured state and deterministic policy:

| Method | Path | Purpose |
| --- | --- | --- |
| `POST` | `/api/v1/sessions/start` | Start a Remote Mode session (`contractVersion: "v1"`) |
| `POST` | `/api/v1/state/update` | Publish the latest structured cockpit state |
| `GET` | `/api/v1/state?sessionId=...` | Read the session's `StateEnvelope` |
| `POST` | `/api/v1/assistant/query` | Return a context-grounded assistant response |
| `POST` | `/api/v1/events` | Record a bounded simulator or assistant event |
| `POST` | `/api/v1/actions/confirm` | Confirm an allowed simulated action |
| `GET` | `/api/v1/emergency/{id}` | Read the backend-authoritative emergency workflow |
| `POST` | `/api/v1/emergency/{id}/respond` | Submit an explicit occupant response |

`GET /api/v1/state` chooses its response shape by query mode. Supplying
`sessionId` returns the Android `StateEnvelope`; supplying the canonical
`vehicle_id` and `trip_id` preserves the authenticated signal projection.

### Safety and SOS boundary

The compatibility layer converts fresh structured state into a compact Context
Pack and evaluates a deterministic safety policy. It does not send raw video,
audio, CAN traffic, or sensor streams to an LLM. In Remote Mode, the backend is
authoritative for emergency state transitions:

```text
VERIFYING_EVIDENCE (5s) -> AWAITING_USER_RESPONSE (15s)
-> FINAL_COUNTDOWN (10s) -> SOS_SIMULATED_SENT
```

For a fresh `crashDetected + passengerResponse=NO_RESPONSE` condition, the
backend prepares a **simulation-only Rescue Brief** containing a short factual
vehicle-status summary, last known location when available, evidence and
freshness. The final step calls only `mock://safedrive-rescue-gateway/v1/events`
and returns a mock acknowledgement. `realEmergencyDispatchEnabled` is always
`false`: no real emergency service, roadside provider, medical diagnosis, or
network dispatch is implemented.

## Development setup

Install Python 3.11 and `uv`, then from this repository:

```powershell
uv sync --locked --extra dev
Copy-Item .env.example .env
```

For local Android integration, start the server with an explicit development
key:

```powershell
$env:ENVIRONMENT="development"
$env:ACTIVE_PROFILE="PRODUCTION_NO_DMS"
$env:SAFEDRIVE_API_KEY="local-android-debug-key"

uv run --locked uvicorn app.main:app --host 0.0.0.0 --port 8000
```

Do not commit `.env` or a real API key. A relative `SIGNAL_REGISTRY_PATH` is
resolved from the backend project root, not the shell's current directory.

Check startup:

```powershell
Invoke-RestMethod http://127.0.0.1:8000/health
Invoke-RestMethod http://127.0.0.1:8000/ready
```

Both responses include `request_id`, a UTC `timestamp`, and
`schema_version: "1.0"`. Readiness returns HTTP 503 with `status: "not_ready"`
if service initialization fails.

## Send a sample speed and read state

```powershell
$apiKey = "local-android-debug-key"
$vehicleId = "veh_demo_01"
$tripId = "trip_01"
$occurredAt = [DateTime]::UtcNow.ToString("o")

$headers = @{
  "X-SafeDrive-Key" = $apiKey
  "X-Request-ID" = "powershell-speed-001"
  "Idempotency-Key" = "speed-operation-001"
}

$body = @{
  signals = @(
    @{
      signal_id = "speed-signal-001"
      source = "SIMULATOR"
      signal_type = "vehicle.speed_kmh"
      occurred_at = $occurredAt
      value = @{ value = 62.0 }
      quality = "VALID"
      vehicle_id = $vehicleId
      trip_id = $tripId
      sequence = 1
      metadata = @{ simulated = $true }
    }
  )
} | ConvertTo-Json -Depth 6

Invoke-RestMethod `
  -Method Post `
  -Uri http://127.0.0.1:8000/api/v1/signals `
  -Headers $headers `
  -ContentType "application/json" `
  -Body $body

Invoke-RestMethod `
  -Method Get `
  -Uri "http://127.0.0.1:8000/api/v1/state?vehicle_id=$vehicleId&trip_id=$tripId" `
  -Headers @{ "X-SafeDrive-Key" = $apiKey }
```

The POST response should report `accepted: 1` and `state_version: 1`. The state
response then contains `components.vehicle.speed_kmh.value.value`,
`freshness.status`, `freshness.age_ms`, and the same state version.

Equivalent curl:

```bash
curl -X POST http://127.0.0.1:8000/api/v1/signals \
  -H "Content-Type: application/json" \
  -H "X-SafeDrive-Key: local-android-debug-key" \
  -H "X-Request-ID: curl-speed-001" \
  -H "Idempotency-Key: curl-speed-operation-001" \
  --data-binary @contracts/examples/signals_request.json
```

## Android connectivity

Configure the Android debug build with:

```text
SAFEDRIVE_API_KEY=local-android-debug-key
```

Choose one base URL, including its trailing slash:

- USB/emulator with reverse port: run `adb reverse tcp:8000 tcp:8000`, then use
  `SAFEDRIVE_BASE_URL=http://127.0.0.1:8000/`.
- Android Studio emulator without reverse port: use
  `SAFEDRIVE_BASE_URL=http://10.0.2.2:8000/`.
- Physical device over Wi-Fi: use
  `SAFEDRIVE_BASE_URL=http://<LAPTOP_LAN_IP>:8000/`; the laptop and device must
  share a network, and Uvicorn must bind to `0.0.0.0`.

Cleartext HTTP is for debug builds only. Do not disable TLS verification or
enable global cleartext traffic in release builds.

If Windows Firewall blocks a physical device, review and run an elevated,
scoped rule only if you approve it:

```powershell
New-NetFirewallRule `
  -DisplayName "SafeDrive Backend TCP 8000" `
  -Direction Inbound `
  -Protocol TCP `
  -LocalPort 8000 `
  -Action Allow `
  -Profile Private
```

## Troubleshooting

- `401`: the `X-SafeDrive-Key` header is absent or does not match the server
  setting.
- `404`: no state exists for that exact `vehicle_id`/`trip_id`; POST a valid
  signal first.
- `413`: the request exceeds the 1 MiB payload limit.
- `422`: request shape, UTC timestamp, identifier, metadata count, signal
  value, or `Idempotency-Key` is invalid.
- `503`: `/ready` is not ready, authentication is not configured, or startup
  registry/settings validation failed. Check safe structured logs; API
  responses intentionally omit raw paths and exception details.
- Connection refused: confirm Uvicorn is running on port 8000 and select the
  correct emulator, reverse-port, or LAN URL.

## Quality gates

```powershell
uv run --locked --extra dev pytest
uv run --locked --extra dev python -m mypy app tests create_fixtures.py
uv run --locked --extra dev ruff check .
uv run --locked --extra dev ruff format --check .
uv run --locked --extra dev python -m openapi_spec_validator contracts/openapi.yaml
uv run --locked --extra dev uv pip check
uv build
```

`create_fixtures.py` requires an explicit `--output-dir` and refuses to replace
existing fixture files unless `--overwrite` is supplied.

CI also installs the built wheel into a fresh virtual environment and initializes
application services from a working directory outside the source tree. This
verifies that the packaged default signal registry is available at runtime.
