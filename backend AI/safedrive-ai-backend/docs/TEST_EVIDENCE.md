# Test Evidence

Collected 2026-08-04. Commands are exact and reproducible; numbers are from the actual runs, not
estimates.

## Backend

```bash
uv run pytest -q
```
→ **228 passed** in ~9-10s (no skips, no xfails).

```bash
uv run ruff check app tests
```
→ **All checks passed!**

## Android

```bash
gradlew.bat clean testDebugUnitTest lintDebug assembleDebug
```
→ **BUILD SUCCESSFUL**. Parsed from `app/build/test-results/testDebugUnitTest/*.xml` (31 suite
files): **325 tests, 0 failures, 0 errors, 0 skipped**. This was a `clean` build — not cached
results. `lintDebug`: 14 findings, all informational/warning severity (pre-existing Compose icon
deprecation warnings), zero blocking errors.

APK: `app/build/outputs/apk/debug/app-debug.apk`, 20,784,552 bytes.
SHA-256: `b8aa6e8db937395be6f83dd4ce5a6776b62321d22937645a97871c279df98172`

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
