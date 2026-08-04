# Assistant Action Allowlist — SafeDrive AI v1

**Candidate** from `docs/android-mvp-plan/12` W7.5 — not "frozen" until Gate E's device-QA and
human-review criteria also pass. `SafeDriveAction.type` is a closed enum — an
unrecognized value from the backend is mapped to `NONE` by Android's `safeEnumOf()` helper
(`data/remote/ApiMappers.kt`) and is always a safe no-op, never a crash. The backend must not invent
new action types without a contract version bump and a corresponding Android release, since an
unknown type degrades to doing nothing rather than failing loudly.

| `type` | `requiresConfirmation` | What Android does once executed | Notes |
|---|---|---|---|
| `SHOW_WARNING` | `false` (always, per current Mock fixtures) | Shows a one-line snackbar acknowledging the warning was recorded. No navigation, no state change. | Backend may still send `requiresConfirmation: true`; Android will honor whatever the backend sends per-response (see "Confirmation rule" below) — the `false` above just reflects today's Mock fixtures, not a hard client assumption. |
| `OPEN_DIAGNOSTICS` | `false` | Navigates to the Diagnostics tab. | Used when a reply references an active DTC. |
| `SUGGEST_REST_STOP` | `true` | Shows a snackbar acknowledging the rest suggestion was recorded. | Confirmation dialog must be accepted first; declining leaves chat history untouched. |
| `START_SOS_COUNTDOWN` | `true` | **Does not start a real emergency.** Currently shows a placeholder acknowledgement snackbar — the actual Emergency state machine (`EmergencyReducer`) is only ever entered via the on-device crash-evidence rule (`SafeDriveContainer`'s crash+signal combiner) or the Simulator's crash preset, never directly from an assistant action in v1. | If a future phase wants an assistant reply to able to *trigger* the real Emergency flow, that needs an explicit ADR — this action type existing today does not imply that capability. |
| `SET_HVAC_TEMPERATURE` | `true` (always) | Applies the typed `hvacTargetTemperatureC` (16-30°C) to the local vehicle datasource — `AssistantViewModel.applySimulatedHvacTarget()` — then shows a confirmation snackbar. The value propagates reactively into `CockpitUiState.Content.vehicleState` via `ObserveCockpitUseCase`, but no Cockpit-screen composable renders it yet (`VehicleMetricsPanel` only shows `cabinTemperatureC`) — the only visible feedback today is the Assistant-screen snackbar. | Added for the MVP-core HVAC command flow (backend: deterministic Vietnamese/English temperature-phrase parser in `app/mobile/intent.py`). This is the one exception to "no free-form payload" below — see that section. |
| `NONE` | n/a | No-op. Never crashes, never silently "succeeds" with a fabricated effect. | This is also the safe fallback for any type string the client doesn't recognize. |

## Confirmation rule (client-side, applies to all types)

1. If `SafeDriveAction.requiresConfirmation == true`, Android shows a confirmation dialog
   (`ConfirmActionDialog`) and does **not** run the action's local effect until the user accepts.
2. Accepting calls `POST /api/v1/actions/confirm` with `confirmed: true` and the action's
   `confirmationId` (freshly generated client-side per action instance, not reused across retries).
3. Only on a `200`/`accepted: true` response does Android run the local effect (navigate/snackbar).
4. Declining calls nothing — no network request — and simply clears the pending action locally.
5. If `requiresConfirmation == false`, Android runs the local effect immediately upon the action
   being surfaced in a chat message, with no server round-trip at all.

## What the backend controls vs. what Android controls

- **Backend controls**: whether an action is offered at all, its `title` (display text), and
  `requiresConfirmation` per response. This can differ turn-to-turn for the same `type` if the
  backend's policy changes (e.g., requiring confirmation only above a certain risk level).
  `contextVersion` in the confirm request lets the backend detect "the vehicle state moved on since
  this action was offered" and reject a stale confirmation with `CONFLICT` (HTTP 409) if appropriate.
- **Android controls**: the actual UI effect for each `type` (table above) — this is fixed client-side
  code, not configurable by the backend. A new desired *effect* (not just a new confirmation policy)
  requires an Android release.

## Explicitly not supported in v1

- No free-form/dynamic action payloads (no arbitrary JSON "params" bag). `SafeDriveAction` carries
  `id`, `type`, `title`, `requiresConfirmation`, plus one narrow, typed, bounded exception —
  `hvacTargetTemperatureC` (`float`, 16-30 inclusive), present only when `type == SET_HVAC_TEMPERATURE`
  — added as an additive contract change specifically to avoid a generic parameter bag while still
  representing a real cockpit control. See `#/components/schemas/SafeDriveAction` in
  `openapi/safedrive-v1.yaml`.
- No action can trigger a phone call, SMS, or real emergency dispatch — this is a hard safety
  invariant (`realEmergencyDispatchEnabled` is always `false`), not a current-implementation gap.
