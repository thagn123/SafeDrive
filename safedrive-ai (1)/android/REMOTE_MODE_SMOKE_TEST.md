# SafeDrive Remote Mode Smoke Test

This runbook is the final integration gate for the hackathon MVP. It proves that
the Android cockpit communicates with the real FastAPI backend over HTTP. It
does not authorize a real emergency call: every rescue result must remain
`SIMULATION_ONLY` and `realEmergencyDispatchEnabled=false`.

## 1. Preconditions

1. Keep the current working trees intact. The MVP follow-up changes are
   uncommitted, so do not reset, clean, or switch branches before the run.
2. Start the backend from `backend AI/safedrive-ai-backend`:

   ```powershell
   $env:ENVIRONMENT = "development"
   $env:ACTIVE_PROFILE = "PRODUCTION_NO_DMS"
   $env:SAFEDRIVE_API_KEY = "local-android-debug-key"
   .venv\Scripts\python.exe -m uvicorn app.main:app --host 0.0.0.0 --port 8002
   ```

3. Confirm `http://127.0.0.1:8002/health` returns `status: "ok"` before
   opening the Android app.
4. Install or run the Android **debug** build. Debug alone permits local HTTP;
   the release build intentionally requires HTTPS.

## 2. Choose One Connection Method

| Target | Android Remote Mode BASE_URL | Required setup |
| --- | --- | --- |
| Android Studio emulator | `http://10.0.2.2:8002/` | Backend bound to `0.0.0.0`. |
| USB device or emulator with reverse port | `http://127.0.0.1:8002/` | Run `adb reverse tcp:8002 tcp:8002`. |
| Phone on same Wi-Fi | `http://<LAPTOP_LAN_IP>:8002/` | Backend bound to `0.0.0.0`; allow only private-network firewall access if required. |

In the app, open **Settings**, enable **Developer Mode**, choose **Remote**,
enter the matching URL with its trailing `/`, save it, then use **Backend
health check**. It must report the configured host and `assistant=true`; it
must not silently fall back to Demo Mode.

## 3. Required MVP Scenarios

Apply mock states through the Simulator only after Remote Mode reports healthy.
For every assistant action, confirm it in the UI when prompted.

### A. Ordinary assistant: HVAC control

1. Use a normal, fresh vehicle state with energy above 20%.
2. Say or enter: `Bật điều hòa`.
3. Expected: SafeDrive explains that no target was specified, proposes `22°C`,
   and presents a confirmable `SET_HVAC_TEMPERATURE` action.
4. Leave the Cockpit open briefly so a normal speed/location telemetry refresh
   can arrive, then confirm the action. Expected: an update that changes only
   speed or location does **not** reject the action; a changed cabin, energy,
   DTC, crash/passenger, or fatigue input correctly requires a new plan.
5. Expected: the Cockpit shows `HVAC 22°C`; state version advances once.
6. Repeat with `Đặt điều hòa 23.5°C` to prove the decimal survives in the
   message, action, and Cockpit state. Entering `31°C` must explain the
   `16-30°C` limit and present no action.

### B. Context-aware hot cabin

1. Apply a fresh hot-cabin state; use a low-energy scenario when available.
2. Say: `Trong xe nóng quá`.
3. Expected: the reply cites current cabin/energy context and proposes `24°C`
   at energy `<=20%`, otherwise `22°C`. Confirm only if the action matches the
   displayed recommendation.

### C. Driver fatigue safety intervention

1. Apply a state with more than four continuous driving hours and fatigue
   evidence.
2. Say: `Tôi hơi buồn ngủ`.
3. Expected: the backend-authoritative risk is high, its reason codes explain
   the evidence, and the assistant recommends a safe rest stop. Cooling is
   described as temporary comfort support, never as making fatigued driving
   safe.

### D. Vehicle fault explanation

1. Apply a state with one active HIGH or CRITICAL DTC.
2. Ask: `Xe đang báo lỗi gì, tôi có nên đi tiếp không?`.
3. Expected: the assistant names the supplied DTC and its supplied
   recommendation. It must not invent a fault that is absent from the current
   state.

### E. Crash/no-response rescue simulation

1. Apply `crashDetected=true` and `passengerResponse=NO_RESPONSE`, with a
   current mock location when the Simulator exposes one.
2. Expected: a deterministic critical Safety Guardian result and an SOS
   countdown appear without waiting for an LLM decision.
3. Open the rescue brief and verify it contains a short vehicle-status summary,
   last known location/freshness when supplied, risk level, evidence, and a
   mock gateway acknowledgement.
4. Verify all UI and payload text says `SIMULATION_ONLY` and shows
   `realEmergencyDispatchEnabled=false`. Do not present this as a real rescue
   dispatch or a medical diagnosis.

## 4. Stop Conditions

Stop the run and record the result in `SAFE_DRIVE_STATUS.md` if any condition
below occurs:

- Remote Mode falls back to Demo Mode or reports success while the backend is
  unavailable.
- Session start rejects `contractVersion="v1"`, a mobile error is not the
  flat contract envelope, or the app cannot read the backend state.
- A confirmed HVAC action does not update the Cockpit state exactly once.
- An unsafe HVAC request creates an action.
- SOS behavior claims to call a real service or exposes a dispatch-enabled flag.

## 5. Evidence to Capture

Capture one short screen recording or screenshots showing:

1. Remote Mode health success and configured host.
2. The generic HVAC command before and after confirmation.
3. The fatigue or DTC explanation grounded by live simulator state.
4. The complete rescue brief and mock acknowledgement with its simulation-only
   label.

After the run, add the device/emulator type, selected BASE_URL, five scenario
outcomes, any latency observation, and any deviation to
`SafeDrive/SAFE_DRIVE_STATUS.md`.
