# Test Evidence

Collected 2026-08-04. Commands are exact and reproducible; numbers are from the actual runs, not
estimates.

## Backend

```bash
uv run pytest -q
```
→ **234 passed** (228 + 6 new in `tests/test_assistant_ws.py`) in ~15s (no skips, no xfails).

```bash
uv run ruff check app tests
```
→ **All checks passed!**

## Android

```bash
gradlew.bat clean testDebugUnitTest lintDebug assembleDebug
```
→ **BUILD SUCCESSFUL**. Parsed from `app/build/test-results/testDebugUnitTest/*.xml` (32 suite
files): **332 tests, 0 failures, 0 errors, 0 skipped**. This was a `clean` build — not cached
results. `lintDebug`: 14 findings, all informational/warning severity (pre-existing Compose icon
deprecation warnings), zero blocking errors.

APK: `app/build/outputs/apk/debug/app-debug.apk`, 20,800,936 bytes.
SHA-256: `1c4f3a56a09ecc61e4ed94d538b3203e0b1ef4b1fa8147822d54a575b17c16cd`

## Real Ollama evidence (live model, not mocked HTTP)

Backend started with `LLM_PROVIDER=ollama`, `LLM_MODEL=qwen2.5:7b-instruct-q4_K_M`,
`LLM_BASE_URL=http://127.0.0.1:11434` (a real, locally running Ollama 0.32.5 with the model
actually pulled — confirmed via `ollama list`).

Request: `companion.conversation` turn ("Noi chuyen voi toi mot chut") against a LOW-risk state
(speed 72 km/h, cabin 25°C, 20 min driving).

```
model: ollama/qwen2.5:7b-instruct-q4_K_M
llmUsed: true, fallback: false, fallbackReason: null
serverProcessingMs: 1836 (cold model load on the very first call of the session: 15856)
text: "Xe đang chạy 72 km/h, cabin 25 độ C. Bạn đã lái khoảng 20 phút rồi. Nếu bạn bắt đầu
       mệt, hãy nói với tôi hoặc dừng ở vị trí an toàn khi có thể."
```
All numbers (speed/cabin/duration) match the input state exactly — no invented facts. Repeated
successfully through the Docker container (`host.docker.internal:11434`), confirming the same
result over the shipped deployment path, not just the bare-metal one.

## Ollama-down fallback evidence (real HTTP failure, not simulated)

Second backend instance started with `LLM_BASE_URL` pointed at an unused port
(`http://127.0.0.1:19999`) — a genuine connection failure, not a code path bypass.

```
model: deterministic-context-router
llmUsed: false, fallback: true, fallbackReason: "provider_unavailable"
text: identical to the deterministic template ("Tôi ở đây cùng bạn. Xe đang chạy 72 km/h...")
risk_before_call == risk_after_call  →  safety state unchanged by the failed LLM attempt
```

## Engine-temperature and DTC-conflict evidence (same backend code, real requests)

| Scenario | risk.level | risk available before LLM | llmUsed | Notes |
|---|---|---|---|---|
| `engineTemperatureC=110` | HIGH | 25.9ms | false | Text: "Nhiệt độ động cơ đang tăng cao... động cơ 110 độ C" |
| `engineTemperatureC=116` | CRITICAL | 1.1ms | false | Text: "Động cơ quá nhiệt nghiêm trọng... động cơ 116 độ C" |
| HIGH DTC + client recommendation "Ban co the tiep tuc lai xe binh thuong" | HIGH | 15.8ms | false | Response: "Không nên tiếp tục hành trình dài..." — the unsafe string never appears |

`llmUsed=false` in all three is correct: HIGH/CRITICAL routes and the DTC route are excluded from
narration entirely (`_can_narrate`), so the LLM is never even called for them — the risk figure and
the reply are both fully synchronous.

## Android device evidence

Device: Xiaomi `24129PN74G` (HyperOS), connected via USB, `adb reverse tcp:8000 tcp:8000`.
Fresh install (`adb uninstall` + `adb install -r app-debug.apk`) to rule out stale state.

- Settings screenshot confirms **Mode: Remote (backend thật)** selected by default on first launch
  — no manual configuration step, matching the new debug-build default.
- Backend access log, real and unprompted (not curl), immediately after app launch:
  ```
  POST /api/v1/sessions/start   200 OK
  POST /api/v1/state/update     200 OK   (repeating every ~4s from the app's own background loop)
  ```
- After switching the backend to the app's actually-persisted `BASE_URL` (`127.0.0.1:8002`, restored
  by MIUI's own reinstall data-restore behavior — see Known Limitations) and relaunching: the app
  correctly received a `404` on a stale session (no crash, no silent retry loop), then established a
  fresh session on the next cold start and resumed continuous accepted `200 OK` state pushes.

**Known gap in this evidence**: this device blocks `adb shell input tap`/`input text`
(`SecurityException: ... requires INJECT_EVENTS permission`, an existing, previously-documented MIUI
restriction, reconfirmed this run). Tapping through the Simulator/Assistant screens to originate
scenarios 1/2/4/5/6 from literal on-device touch input was not possible in this environment. The
connectivity/session evidence above is genuinely device-originated; the specific scenario payloads
(engine temperature, DTC conflict, etc.) were verified against the identical, currently-running
backend code via direct requests rather than via on-device taps. See Known Limitations for the exact
next step to close this gap.

## Manual on-device acceptance testing (human taps, not ADB) — follow-up session

The gap above was closed for scenarios 1/2/4: a human manually tapped through the real device (ADB
input injection is blocked, but the device owner's own touches are not). This surfaced two real,
reproducible bugs neither prior automated pass had caught, both now fixed.

**Bug 1 — cold-start Ollama exceeds the client's fixed 8s read timeout.** Reproduced by replaying
the exact same on-device query against the exact same live backend: `serverProcessingMs=14564`
(cold), while the Android client's `READ_TIMEOUT_S` was `8L`. Real device symptom: "Yêu cầu quá
thời gian chờ" (request timed out) on the very first chat message.

**Bug 2 — ambiguous-phrasing intent reclassification is a second, slow LLM call.** A message the
deterministic router can't confidently route (e.g. "cho tôi lời khuyên để lái xe an toàn hơn")
triggers `OllamaIntentClassifier` before any reply is produced. Measured **9.6-10.1s** for that
classification call alone — exceeding the 8s budget even when the eventual answer is fully
deterministic (`llmUsed=false`).

**Fix**: `/api/v1/ws/assistant` (WebSocket, heartbeat every ~2s) replaces the one-shot HTTP call for
assistant queries; the Android client now waits based on connection liveness instead of a fixed
wall-clock guess. See `docs/ARCHITECTURE.md`'s "Assistant chat transport" section. Verified so far:
backend `tests/test_assistant_ws.py` (6 tests, including a heartbeats-during-a-slow-call case) and
Android `AssistantSocketClientTest`/`RemoteSafeDriveGatewayErrorMappingTest` (real `MockWebServer`
WebSocket round-trips). **Not yet re-verified on the physical device** — the exact on-device retry
of the scenario that originally failed (cold Ollama + ambiguous phrasing) is the next step; see
`docs/KNOWN_LIMITATIONS.md`.

**Scenarios confirmed via genuine on-device taps this session** (screenshots + matching backend
request evidence, not curl):
- **Scenario 1 (normal LLM answer)**: "tôi cần nói chuyện" → `route=companion.conversation`,
  `model=ollama/qwen2.5:7b-instruct-q4_K_M`, `llmUsed=true`, grounded text (99 km/h, cabin 25.68°C,
  135 min — all matching live state).
- **Scenario 2 (rest recommendation)**: "Tôi đang cảm thấy mệt" → deterministic fatigue route,
  recommends a safe stop, no fabricated camera/DMS evidence or POI/distance.
- **Scenario 4 (engine CRITICAL)**: Simulator set to 116.275°C → CRITICAL card,
  `reasonCode=engine_overheat_critical`, `model=deterministic-context-router`, `latency=0ms` (no
  LLM call attempted), no SOS triggered by engine temp alone.

**Also discovered and confirmed as correct behavior (not a bug)**: `emergency_candidate` (set by a
live crash signal) blocks LLM narration independently of the displayed `risk.level` — a lingering
crash toggle in the Simulator caused a MEDIUM-risk chat message to still return a deterministic
reply. This is `_can_narrate`'s `not safety.emergency_candidate` gate working as designed
(`app/mobile/session_store.py`), not a defect.

## WebSocket fix verified through the real Docker deployment path

`docker compose build && docker compose up -d` → container healthy, then a real `websockets`-library
client connected to `ws://127.0.0.1:8000/api/v1/ws/assistant` through the container (not bare-metal),
reaching the real host Ollama:

**Cold call** (first request of the container's session, matching the on-device failure mode above):
```
8 heartbeat frames over 16.2s, then:
type: final, llmUsed: true, fallback: false
model: ollama/qwen2.5:7b-instruct-q4_K_M
```
This is the exact scenario that used to fail at the client's old fixed 8s timeout — the connection
stayed alive and demonstrably working the whole 16.2s via heartbeats, with no timeout.

**Warm call** (immediately after, same container):
```
0 heartbeats, final frame at t+1.9s
serverProcessingMs: 1883, llmUsed: true, fallback: false
text: "Xe đang chạy 72 km/h, cabin 25 độ C. Bạn đã lái khoảng 20 phút rồi. Nếu bạn bắt đầu
       mệt, hãy nói với tôi hoặc dừng ở vị trí an toàn khi có thể."
```
Grounded (72 km/h / 25°C / 20 min all match the input state exactly), fast path unaffected.

**Note**: this run also found and cleaned up an unrelated stale host-level `uvicorn` process (left
running from earlier in this project on port 8000, predating this WebSocket work) that was
intercepting requests intended for the container — a leftover from prior manual testing, not a
defect in this change.
