# SafeDrive Integration Plan

## Goal

Lam cho `backend AI/safedrive-ai-backend` va `safedrive-ai (1)` khop nhau bang mot contract app-facing duy nhat.

## Repo roles

| Repo | Vai tro | Khong nen lam |
| --- | --- | --- |
| `backend AI/safedrive-ai-backend` | Canonical state, signal ingestion, risk/safety, assistant response, rescue bridge | Khong nen chua UI cockpit |
| `safedrive-ai (1)` | Web/Android cockpit UI, voice UX, emergency UI, simulator | Khong nen tu tinh lai risk critical neu dang remote mode |

## Contract target

Target contract: `safedrive-ai (1)/openapi/safedrive-v1.yaml`.

Backend phai implement cac endpoint app-facing theo contract nay, ngay ca khi ben trong backend van dung canonical signals.

## Phase 1 - Contract compatibility

Them backend route:

```text
GET  /health
POST /api/v1/sessions/start
POST /api/v1/state/update
GET  /api/v1/state?sessionId=...
POST /api/v1/events
```

Acceptance:

- Android DTO parse duoc response.
- Web/app khong crash khi chuyen Remote Mode.
- `/health` tra capabilities assistant=true, emergencySimulation=true, cockpitStream=false.

## Phase 2 - State bridge

`POST /api/v1/state/update` nhan `VehicleStateDto` tu app va map sang canonical signal:

| App field | Canonical signal |
| --- | --- |
| `speedKmh` | `vehicle.speed_kmh` |
| `cabinTemperatureC` | `hvac.temperature` hoac cabin context field |
| `energyPercent` | can add signal neu backend chua co |
| `crashDetected` | `vehicle.crash` |
| `activeDtcs[].code` | `dtc.code` |
| `driverSupportSignals.userReportedFatigue` | user/safety context |
| `passengerResponse` | passenger/no-response context |

Neu registry chua co signal nao thi dung compatibility session cache truoc, sau do mo rong registry co test.

Acceptance:

- State update tang `stateVersion`.
- `GET /api/v1/state?sessionId=...` tra lai latest envelope.
- Freshness/source duoc giu.

## Phase 3 - Assistant bridge

`POST /api/v1/assistant/query` phai:

1. Nhan text/voice transcript.
2. Lay stateVersion/context hien tai.
3. Route intent:
   - normal command
   - comfort
   - vehicle status
   - DTC
   - fatigue/safety
   - emergency/no-response
4. Tao response co `ChatMessageDto`, `risk`, `actions`.

MVP khong can LLM that ngay. Co the dung deterministic intent router truoc, sau do bo sung LLM provider.

Acceptance:

- "Trong xe nong qua" tra comfort suggestion.
- "Toi hoi buon ngu" tra fatigue/rest suggestion.
- "Xe co gi do khong on" tra DTC/vehicle status response.
- "Goi cuu ho" khong goi that, chi tao action/SOS simulation.

## Phase 4 - Rescue bridge

Them:

```text
GET  /api/v1/emergency/{id}
POST /api/v1/emergency/{id}/respond
POST /api/v1/actions/confirm
```

Khi critical:

- Tao `EmergencySnapshot`.
- `realEmergencyDispatchEnabled=false`.
- Tao rescue brief/payload simulation.

Acceptance:

- No-response + crash => SOS countdown/simulation.
- "Toi on" hoac cancel phrase => cancel safely.
- Backend khong bao gio tra `realEmergencyDispatchEnabled=true`.

## Phase 5 - End-to-end

Run:

- backend tests
- Android unit tests
- web build
- manual remote-mode smoke

Manual smoke:

1. Start backend port 8000.
2. Set app remote base URL.
3. Check health.
4. Apply simulator scenario.
5. Ask assistant.
6. Trigger SOS simulation.

## Implementation strategy

Nen them file moi:

```text
backend AI/safedrive-ai-backend/app/api/schemas/mobile.py
backend AI/safedrive-ai-backend/app/api/routes/mobile.py
backend AI/safedrive-ai-backend/app/mobile/session_store.py
backend AI/safedrive-ai-backend/app/mobile/state_bridge.py
backend AI/safedrive-ai-backend/app/mobile/assistant.py
backend AI/safedrive-ai-backend/app/mobile/emergency.py
```

Sau do include router vao `app/api/v1/router.py`.

Khong nen sua lung tung route `signals.py` va `state.py` cu, vi chung dang co nhieu test.

