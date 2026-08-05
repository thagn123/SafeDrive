# SafeDrive AI — Competition Demo Script

Follow this exactly; no source reading required.

## Pre-demo checklist

1. Ollama running with the model pulled:
   ```bash
   ollama serve   # if not already running as a service
   ollama pull qwen2.5:7b-instruct-q4_K_M
   ```
2. Backend running (pick one):
   ```bash
   # Local
   ENVIRONMENT=development ACTIVE_PROFILE=PRODUCTION_NO_DMS SAFEDRIVE_API_KEY=local-android-debug-key \
   LLM_PROVIDER=ollama LLM_MODEL=qwen2.5:7b-instruct-q4_K_M LLM_BASE_URL=http://127.0.0.1:11434 \
   uvicorn app.main:app --host 0.0.0.0 --port 8000

   # Or Docker (Ollama stays on the host either way)
   docker compose up --build
   ```
3. Confirm health: `curl http://127.0.0.1:8000/health` → `"status":"ok"`.
4. USB device: `adb reverse tcp:8000 tcp:8000`. Emulator: no reverse needed, use `10.0.2.2:8000`.
5. Install the APK: `adb install -r app/build/outputs/apk/debug/app-debug.apk` (built from `safedrive-ai (1)/android`).
6. Open Settings → confirm **Mode: Remote**, **BASE_URL** matches step 4/5, tap **Kiểm tra sức khỏe backend** → expect a Success line with `assistant=true`.

## Demo order

1. **Normal LLM response** — Assistant tab → type/say "Xe của tôi hiện tại thế nào?" with a nominal simulator state. Expect a grounded, natural Vietnamese reply and `model` starting with `ollama/` in Developer Mode.
2. **Rest recommendation** — Simulator → set `continuousDrivingMinutes` past 240. Ask "Tôi hơi mệt." Expect a concrete rest recommendation, no invented distance.
3. **Engine HIGH** — Simulator → `engineTemperatureC = 110`. Expect an immediate HIGH warning on Cockpit before/independent of any assistant reply.
4. **DTC conflict** — Simulator → add a HIGH-severity DTC with an unsafe recommendation string. Ask "Xe có vấn đề gì không?" Expect the backend-owned severity-aware guidance, never the unsafe string.
4b. **DTC by code, active** — Simulator → add a DTC with code `U0100`. Ask "Mã U0100 nghĩa là gì?" (a phrasing that matches no keyword, only the code itself). Expect `route=vehicle.fault_concern`, the code preserved exactly, and the same backend-owned guidance as scenario 4 — proves code-shaped questions route correctly regardless of phrasing.
4c. **DTC by code, not active** — Simulator → no active DTCs. Ask "Mã U0100 nghĩa là gì?" again. Expect the static-catalog meaning ("mất giao tiếp với ECM/PCM") *plus* an explicit statement that it is not currently active — never presented as a live fault.
4d. **DTC by code, unknown** — Ask "Mã P0130 nghĩa là gì?" (a real code not in this system's small catalog). Expect an honest "not in my reference catalog" reply, no invented meaning.
4e. **Unsupported code-like token** — Ask "XYZ123 nghĩa là gì?" (not DTC-shaped). Expect a short, honest "no verified data about this code" reply — never a fabricated explanation, and `llmUsed=false` (this is a deterministic intercept, not a model decision).
5. **Crash → simulated SOS** — Simulator → trigger crash + no response. Expect the verify → countdown → simulated-SOS sequence; confirm **Cancel** works in a separate run.
6. **(Optional) Ollama-down fallback** — Stop Ollama, repeat scenario 1. Expect `fallback=true` in Developer Mode, a still-useful deterministic reply, and the app not crashing. Restart Ollama afterward.

## Recovery

- **Ollama unreachable**: backend automatically falls back to the deterministic reply (`llmUsed=false`, `fallback=true`). No restart needed; the next request retries the LLM independently.
- **Backend disconnected**: Settings shows "Backend: Disconnected"; app stays on its last known state, never crashes, never silently switches to Demo Mode.
- **adb reverse dropped** (USB replugged): rerun `adb reverse tcp:8000 tcp:8000`.
