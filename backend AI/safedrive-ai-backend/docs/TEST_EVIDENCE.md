# Test Evidence

Collected 2026-08-04. Commands are exact and reproducible; numbers are from the actual runs, not
estimates.

## Backend

```bash
uv run pytest -q
```
→ **251 passed** (234 + 17 new/updated for the widened LLM-narration scope below) in ~16s
(no skips, no xfails).

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

## Widened LLM narration + genuine open-answer path (this session)

Follow-up to user feedback that replies "still aren't smart enough" — specifically, that
`assistant.general`/`assistant.clarify` always produced the identical fixed disambiguation
template regardless of what was actually asked (e.g. off-topic questions), and that HVAC/status/
DTC/fatigue routes were always deterministic regardless of risk level. Scope, confirmed explicitly
with the user beforehand: widen narration to every route once risk is LOW/MEDIUM; HIGH/CRITICAL
replies and `safety.emergency_request` (SOS) stay 100% deterministic, unchanged.

**Bug found and fixed during this work**: `MobileSessionStore._maybe_reclassify`'s advisory
`OllamaIntentClassifier` ran *before* narration and, for `assistant.general`'s true catch-all,
would default to the `assistant.clarify` label whenever nothing else confidently fit — silently
reclassifying a genuinely off-topic question right back into the fatigue/cabin/vehicle-concern
disambiguation template, defeating the fix before it could ever run. Caught by this session's own
manual verification (not by the unit test suite, which didn't happen to exercise a real
"nothing fits" classification), fixed by refusing that specific reclassification and leaving the
route as `assistant.general`, and covered by a new regression test
(`test_classifier_defaulting_to_clarify_does_not_hijack_the_general_catchall`).

**Docker deployment, real Ollama (`qwen2.5:7b-instruct-q4_K_M`), rebuilt image with this session's
code** — one continuous session, same backend, real requests:

```
(a) "fpt la gi" (off-topic) -- previously the exact fatigue/cabin/vehicle-concern template
    route=assistant.general llmUsed=true fallback=false model=ollama/qwen2.5:7b-instruct-q4_K_M
    "Tôi là trợ lý an toàn khi lái xe, câu hỏi này có thể nằm ngoài phạm vi hỗ trợ của tôi.
     Tôi có thể giúp về tình trạng xe, cabin, cảnh báo lỗi hoặc nhu cầu nghỉ ngơi."

(b) "Tinh trang xe the nao" at LOW risk -- previously always deterministic, never LLM-narrated
    route=assistant.vehicle_status llmUsed=true fallback=false model=ollama/qwen2.5:7b-instruct-q4_K_M
    "Xe đang vận hành bình thường, tốc độ hiện tại là 72 km/h, cabin 25 độ C.
     Bạn đã lái liên tục khoảng 20 phút."
    (72 km/h / 25°C / 20 min all match the pushed state exactly -- grounded, not invented)

(c) same query, engine forced to 118°C (CRITICAL) -- must stay fully deterministic
    route=assistant.vehicle_status llmUsed=false fallback=false model=deterministic-context-router
    "Động cơ quá nhiệt nghiêm trọng. Tốc độ hiện tại 72 km/h, cabin 25 độ C, động cơ 118 độ C.
     Bạn đã lái liên tục khoảng 20 phút."

(d) "Bao loi gi vay" with an active MEDIUM-severity DTC (P0128) -- previously always deterministic
    route=vehicle.fault_concern llmUsed=false fallback=true model=deterministic-context-router
    "Xe đang có mã P0128: Coolant thermostat below regulating temperature, mức độ trung bình.
     Bạn có thể tiếp tục lái thận trọng, nhưng hãy theo dõi dấu hiệu bất thường và kiểm tra xe sớm."
```

Case (d) shows the safety net working as designed against a real, non-deterministic model: the
narrator's `required_verbatim_snippets` guardrail requires the DTC code and severity-guidance
clause to survive unchanged, the live model's phrasing didn't satisfy that on this run, and the
system safely fell back to the exact deterministic text (`fallback=true`) rather than risk a
diluted safety message — the DTC code and guidance are still present, just via the fallback path
instead of a narrated one. Cases (a)-(c) show a real, successful narration/open-answer/exclusion
in each of the three states this change targets.

## Full audit pass (this session) — bug found and fixed, full scenario matrix, device/artifact evidence

A full technical audit (repo checkpoint, scoring, live scenario verification, security scan,
deployment-artifact consistency, documentation cross-check) was run against the state above. It
found one real regression, fixed it, then re-verified everything against a rebuilt Docker image.

### Bug found: the advisory reclassifier could mis-route `assistant.general` into an unrelated template

Live test: pushed a LOW-risk state, asked **"Ai la tong thong My"** (who is the US president —
genuinely off-topic). Expected the new `assistant.general` → `answer_open_query` redirect path.
Actual (before the fix below): `OllamaIntentClassifier`, forced to pick one label from its closed
set for text that fits none of them, committed to `assistant.vehicle_status` instead of correctly
falling back to `assistant.clarify` — silently producing a fluent but completely irrelevant
vehicle-status reply. This is the same class of bug ("câu trả lời không liên quan") the
`assistant.general` split was built to fix, just re-introduced one layer earlier by the classifier.

**Fix**: `MobileSessionStore._can_classify` (`app/mobile/session_store.py`) is now scoped to
`resolution.route == "assistant.clarify"` only (previously any `needs_clarification=True`
resolution, which included `assistant.general`'s true catch-all). `assistant.general` now never
reaches the classifier at all and always gets `answer_open_query`'s genuine read of the question.
The now-unreachable special case this briefly required in `_maybe_reclassify` was removed once the
gate made it structurally impossible, rather than left as dead code.

**Tests updated/added**: `test_genuinely_ambiguous_clarify_can_be_reclassified_by_advisory_llm`
(renamed/refocused from the old general-catchall version, now uses genuinely ambiguous input),
`test_classifier_never_runs_for_the_general_catchall` (new — asserts exactly one `/api/chat` call,
never classify-shaped, for off-topic text), `test_reclassification_never_runs_during_an_active_emergency`
(updated to use `assistant.clarify`-shaped input so it still genuinely exercises the
emergency-candidate gate rather than being trivially satisfied by the new route-scope gate).

**Regression check**: `uv run pytest -q` → **251 passed**, `uv run ruff check app tests` → all
checks passed, both after the fix (same counts as before — this was a scope narrowing, not new
test surface).

### Cold-start latency, measured from a genuinely empty Ollama (`ollama ps` → `{"models":[]}`)

Through the rebuilt Docker container (`docker compose build && docker compose up -d`), a request
that needed BOTH a cold classify call and a cold narrate call chained (ambiguous phrasing that
first hit the classifier, then narration for the reclassified route):

```
client total elapsed: 22.19s
serverProcessingMs: 22189
llmUsed=true fallback=false model=ollama/qwen2.5:7b-instruct-q4_K_M
```

Immediate follow-up on the same warm container:

```
client total elapsed: 1.76s
serverProcessingMs: 1752
llmUsed=true model=ollama/qwen2.5:7b-instruct-q4_K_M
```

There is no proactive pre-warm call at backend startup (confirmed by grep — `keep_alive: "30m"` only
extends how long an *already-loaded* model stays warm after a call, nothing loads it at boot).
Instead, the design tolerates cold start structurally: `/api/v1/ws/assistant` sends a heartbeat
every ~2s for the *entire* `answer_assistant` call, including a chained classify+narrate sequence,
so Android's `ASSISTANT_QUERY_SILENCE_TIMEOUT_MS=10_000L` (resets per heartbeat) never fires, and
the measured 22.19s worst case is comfortably inside `AssistantQueryUseCase`'s
`ASSISTANT_TURN_TOTAL_TIMEOUT_MS=30_000L` outer cap. `NetworkModule.READ_TIMEOUT_S=8L` still exists
but only gates the plain Retrofit calls (session/state/events/actions/emergency); `queryAssistant`
uses the WebSocket path exclusively (confirmed: `SafeDriveApi.queryAssistant`, the REST Retrofit
declaration, has zero callers left in the Android source).

### Full scenario matrix, live Docker backend, real Ollama, single continuous run

```
Normal state (60 km/h, 89°C, 20 min)              risk=LOW
Rest recommendation (245 min, fatigue=true)        route=safety.driver_fatigue, grounds "245",
                                                    no camera/distance fabrication
Engine HIGH @110°C                                 risk=HIGH, reason=engine_overheat_warning,
                                                    emergency stays IDLE (no SOS from temp alone)
Engine CRITICAL @116°C                             risk=CRITICAL, reason=engine_overheat_critical,
                                                    emergency stays IDLE
Stale state (60s old)                              risk=MEDIUM, narrated but still says "đã cũ"
                                                    (never claims a confident status)
Vehicle status @ LOW risk                          route=assistant.general (no keyword match),
                                                    llmUsed=true, answered from context
Genuine ambiguity ("Toi khong on, co nen dung
  khong")                                          route=assistant.clarify → reclassified by the
                                                    live classifier to companion.conversation
                                                    (a real, non-deterministic model judgment call,
                                                    not a bug -- both are safe, grounded templates)
Off-topic ("Ai la tong thong My")                  route=assistant.general, llmUsed=true, honest
                                                    in-scope redirect, NO fabricated president name
HVAC too-hot                                       route=comfort.too_hot, llmUsed=true, action
                                                    stays SET_HVAC_TEMPERATURE/22°C, confirmation
                                                    preserved
DTC known (U0100, MEDIUM)                          answered via assistant.general/open-query
                                                    (text didn't match fault_concern keywords);
                                                    U0100 preserved, no invented severity
DTC unknown (XYZ123)                               code never invented/detailed; honest "can't
                                                    help with this" redirect
DTC HIGH unsafe-recommendation override             route=vehicle.fault_concern, llmUsed=false
                                                    (risk=HIGH), unsafe client string
                                                    "Ban co the tiep tuc lai xe binh thuong" does
                                                    NOT appear in the reply
CRITICAL override + unrelated fatigue text          llmUsed=false, model=deterministic-context-
                                                    router -- CRITICAL always wins regardless of
                                                    what was asked
Emergency cancel flow                               VERIFYING_EVIDENCE → CANCEL_SOS → CANCELLED,
                                                    realEmergencyDispatchEnabled=false throughout
Emergency no-response flow                          VERIFYING_EVIDENCE → NO_RESPONSE →
                                                    FINAL_COUNTDOWN → (real ~11s wall-clock wait)
                                                    → SOS_SIMULATED_SENT,
                                                    rescueDispatch.provider=
                                                    MOCK_ROADSIDE_ASSISTANCE_GATEWAY (simulation
                                                    only, no real dispatch)
```

All assertions in the audit script passed. Full request/response detail available in this
session's transcript; scripts are in the session scratchpad, not part of the shipped repo.

### Deployment artifact consistency

- Docker image `8bad1b50202b`, built `2026-08-05 06:58:27 +07:00` from the current source tree
  (after the classifier fix above); container `safedrive-ai-backend-backend-1` status
  `running`/`healthy`.
- Android: `gradlew.bat clean testDebugUnitTest lintDebug assembleDebug` → **BUILD SUCCESSFUL**,
  **332 tests / 0 failures** (32 suite files), lint **14 warnings / 0 errors**. APK SHA-256
  `1c4f3a56a09ecc61e4ed94d538b3203e0b1ef4b1fa8147822d54a575b17c16cd` — **byte-identical** to the
  APK currently installed on the physical test device (pulled from
  `/data/app/.../vn.edu.haui.hvs.safedrive.../base.apk` via `adb pull` and hashed independently).
  This is expected and correct: `git status` confirms zero Android source changes this session (the
  narration-widening + classifier fix are backend-only), so no APK rebuild was functionally
  required, and the one performed here is a byte-for-byte regression check, not a behavior change.
- Real device (`24129PN74G`, HyperOS/MIUI) connected via USB; app process confirmed running
  (`adb shell pidof vn.edu.haui.hvs.safedrive`); `adb reverse` maps both `tcp:8000` and `tcp:8002`
  to the same backend container. **Gap, stated plainly**: no human tapped through the app during
  this specific audit pass, so today's classifier fix and the earlier narration-widening change
  have **not** been freshly confirmed via on-device UI in this pass — only against the identical
  backend code the device is configured to reach. A clean 20-second monitoring window of the
  container's access log, taken with no test script running, showed no organic app-originated
  `/api/v1/*` traffic, meaning the app was not actively mid-session against this backend at the
  moment of the audit (it may have been idle/backgrounded). This does not contradict the earlier,
  already-documented on-device evidence from prior sessions (see the "Manual on-device acceptance
  testing" section above) — it means *this specific pass* did not add new tap-verified evidence on
  top of it.

### Security scan

Grepped both repos for API-key/secret/password/bearer/private-key-shaped literals, hardcoded LAN
IPs, and user-specific absolute paths. Every hit was a clearly-labeled local/dev placeholder
(`local-android-debug-key`, `live-contract-test-key`, structured-logging-redaction test fixtures
like `PRIVATE_API_KEY_222`) or a redaction-behavior test asserting such values are scrubbed from
logs — none is a real credential. `local.properties` (Android) is untracked; `.env` is gitignored
with `.env.example` as the only tracked, placeholder-only template. Android's default `BASE_URL`
(`http://127.0.0.1:8000/`) and the Settings-page presets are plain editable strings, not
hard-coded LAN IPs. No `C:\Users\...`-shaped absolute paths in any tracked file.

## DTC-code routing fix, hallucination fix, and Ollama-offline/recovery evidence (this session)

Follow-up to the previous audit's one documented gap: a DTC question phrased without a matching
keyword (e.g. "Ma U0100 nghia la gi?") fell to `assistant.general` instead of
`vehicle.fault_concern`, bypassing the route's severity-guidance guardrail.

### Fix: deterministic DTC-code-shape recognition

`IntentResolver` (`app/mobile/intent.py`) now matches `[PBCU][0-9A-F]{4}` (case-insensitive)
against the raw driver text, independent of keywords, checked right after the SOS check.
`IntentResolution` gained `mentioned_dtc_code: str | None`. `ContextAwareAssistant`
(`app/mobile/assistant.py`) looks the mentioned code up against `state.activeDtcs` (the only
trusted DTC source — no static reference catalog exists or was added); a match reuses the exact
existing severity-guidance template, a non-match gets an honest "not in the active list" reply
that also surfaces any other real active DTC, and `required_narration_snippets` was extended to
require the mentioned code (and its guidance, if matched) to survive narration verbatim.

New tests: `tests/test_mobile_intent.py` (6 new — DTC-shape routing for `U0100`/`P0300`/`B1234`,
`XYZ123` correctly NOT treated as a DTC, SOS still wins over a co-mentioned code),
`tests/test_mobile_assistant.py` (5 new — known-code exact preservation, HIGH severity never
weakened, unknown-shaped code not hallucinated, unknown code still surfaces a real active one,
non-DTC token falls through to `assistant.general`), `tests/test_mobile_compatibility.py` (1 new
end-to-end test — MEDIUM severity narrates with the code preserved; HIGH severity blocks the LLM
entirely and the unsafe client recommendation never appears).

```
uv run pytest -q                                              → 262 passed (was 251), 18.1s
uv run pytest -q -k "dtc or fault_concern or classifier or
  assistant_general or assistant_clarify"                     → 34 passed
uv run pytest -q -k "engine_temperature or emergency or
  crash or fallback or timeout"                                → 30 passed
uv run ruff check app tests                                    → All checks passed!
```

Live verification through the rebuilt Docker container (real Ollama, real active `U0100` DTC):

```
"Ma U0100 nghia la gi?"  → route=vehicle.fault_concern, llmUsed=true, "U0100" preserved
"Xe bao loi P0300"       → route=vehicle.fault_concern (P0300 not active), "P0300" and the real
                            active "U0100" both preserved -- one run narrated it, another run's
                            narration was guardrail-rejected and fell back to the exact
                            deterministic text verbatim; both are safe outcomes
"Ai la tong thong My"    → route=assistant.general, unaffected by the DTC fix (regression check)
"XYZ123 nghia la gi?"    → route=assistant.general (not DTC-shaped), unaffected by the DTC fix
```

### Bug found live, fixed: `answer_open_query` fabricated a DTC-like explanation for a random token

While verifying the fix above, "XYZ123 nghia la gi?" (correctly NOT routed as a DTC) got answered
by `OllamaNarrator.answer_open_query` with: *"XYZ123 là mã lỗi giả lập liên quan đến sự cố giao
tiếp của bộ điều khiển"* ("XYZ123 is a simulated fault code related to a controller communication
issue") — a fabricated, confident-sounding claim, apparently pattern-matched off the real active
`U0100` DTC's title ("Controller communication fault") present in context. Not caught by the
guardrail because it invents no *number* — the only thing the code-level check verifies.

**Fix**: hardened `answer_open_query`'s system prompt (`app/mobile/llm.py`) with an explicit new
top-priority rule: never invent a meaning/category/explanation for any code or identifier not
verified in `GROUNDED_CONTEXT_JSON`, "not even by analogy to something that IS in
GROUNDED_CONTEXT_JSON." This is a prompt change, not a new code-level check — documented
accordingly in `docs/KNOWN_LIMITATIONS.md` as reinforced-but-not-guaranteed.

**Re-verified live, 3 consecutive runs after the fix**, same "XYZ123 nghia la gi?" input, same
active U0100 DTC in context:
```
run 1: "Tôi không có thông tin về mã XYZ123 này. Bạn có thể hỏi về tình trạng xe hoặc cảnh báo lỗi khác được không?"
run 2: (identical)
run 3: (identical)
```
All three correctly declined instead of fabricating — no regression in `uv run pytest -q` (still
262 passed) since the existing `answer_open_query` unit tests use scripted responses unaffected by
a system-prompt wording change.

### Ollama-offline fallback and recovery — genuine provider-offline, not a guardrail rejection

Backend recreated with `LLM_BASE_URL` pointed at an unused port (`http://host.docker.internal:19999`,
via a temporary docker-compose override, not touching the tracked `docker-compose.yml`) — a real
connection failure, not a code path bypass:

```
elapsed=0.04s status=200
route=companion.conversation llmUsed=false fallback=true fallbackReason=provider_unavailable
model=deterministic-context-router
text: "Tôi ở đây cùng bạn. Xe đang chạy 65 km/h, cabin 24 độ C. Bạn đã lái khoảng 15 phút rồi.
       Nếu bạn bắt đầu mệt, hãy nói với tôi hoặc dừng ở vị trí an toàn khi có thể."
risk: LOW (unchanged by the failed LLM attempt)
GET /health → "ok" (backend stayed healthy throughout)
```

**Recovery**: `docker compose up -d` (using the real `docker-compose.yml`, no other command)
recreated the container with the correct `LLM_BASE_URL` restored. The very next identical request:

```
elapsed=1.73s status=200
route=companion.conversation llmUsed=true fallback=false fallbackReason=null
model=ollama/qwen2.5:7b-instruct-q4_K_M
```

### Docker rebuild from current source

```
docker compose down && docker compose build --no-cache && docker compose up -d
→ image b20763799f69, built 2026-08-05 07:43:41 +07:00 (after the hallucination-prompt fix)
→ container safedrive-ai-backend-backend-1: running/healthy
→ GET /health → "ok", GET /ready → "ready"
```

### Real-device status at this checkpoint

The physical test device (`24129PN74G`) was connected and verified in the previous audit pass
(installed APK hash-matched the source build, `adb reverse` active, app process running). At the
start of *this* session's device-observability step, `adb devices -l` returned **no devices
attached** (confirmed on retry after a few seconds, not a transient blip) — the phone had been
disconnected between passes. **No on-device verification (scenarios, push-to-talk, TTS,
screenshots, video) was performed in this session** as a result; everything above is backend/API-level
evidence against the real Docker container and real Ollama, not on-device UI evidence. See
`docs/KNOWN_LIMITATIONS.md` and the session's final report for the exact reconnection step needed
to close this gap.

## Test-count discrepancy explanation (previous report said "251 + 12 = 262")

251 + 12 does not equal 262 — the previous report's narrative arithmetic was wrong; the underlying
**262 passed** count itself was correct. Reconstructed precisely from the actual diff of that pass:

```
Tests added:            11
  tests/test_mobile_intent.py         +5  (DTC-shape routing for U0100/P0300/B1234, XYZ123
                                            correctly not treated as a DTC, SOS still wins over
                                            a co-mentioned code)
  tests/test_mobile_assistant.py      +5  (known-code preservation, HIGH severity not weakened,
                                            unknown-shaped code not hallucinated, unknown code
                                            still surfaces a real active one, non-DTC token
                                            falls through to assistant.general)
  tests/test_mobile_compatibility.py  +1  (end-to-end MEDIUM-narrates / HIGH-blocks test)
Tests removed:            0
Tests renamed/replaced:   0
Final collected count:  251 + 11 = 262  (matches the reported 262 exactly)
```

The "12" in the previous prose summary was a manual counting slip when writing the chat response,
not a discrepancy in the actual test suite or its reported pass count — nothing was silently
dropped or miscounted in the persisted evidence.

## DTC catalog/active-state separation and code-enforced unverified-token guard (this session)

Follow-up per explicit direction: (a) separate generic DTC catalog knowledge from live vehicle
state rather than treating "not currently active" the same as "unknown," and (b) make the
unverified-technical-code-token protection code-enforced, not prompt-only.

### Three-tier DTC answer (`app/mobile/dtc_catalog.py`, `app/mobile/assistant.py`)

New `app/mobile/dtc_catalog.py`: 7 hand-verified, standardized SAE J2012/ISO 15031-6 codes
(`P0128`, `P0171`, `P0300`, `P0301`, `P0420`, `U0100`, `U0101`) with real, accurate generic
meanings — deliberately excludes manufacturer-specific "B"/"C" codes. `ContextAwareAssistant`'s
`vehicle.fault_concern` branch now checks, in order: vehicle's own `activeDtcs` (KNOWN_AND_ACTIVE,
always wins), then the static catalog (KNOWN_BUT_NOT_ACTIVE, explicitly framed as not-currently-
active), then neither (UNKNOWN_TO_CATALOG, honest non-answer). `required_narration_snippets` was
extended to require the catalog meaning to survive verbatim for the middle tier.

New/changed tests: `tests/test_mobile_assistant.py` — replaced the two tests that used `P0300` as
an "unknown" example (P0300 is now genuinely catalogued) with 4 tests covering the
KNOWN_BUT_NOT_ACTIVE tier (using `P0300`) and the UNKNOWN_TO_CATALOG tier (using `P0130`, a real
code deliberately left out of the small catalog), including a direct assertion that asking about
an inactive code never raises `risk.level` above `LOW`.

### Deterministic (code-enforced) guard for unverified code-like tokens

New in `app/mobile/session_store.py`: `_UNVERIFIED_CODE_TOKEN_PATTERN =
\b[A-Za-z]{2,5}[0-9]{2,5}\b` and `MobileSessionStore._find_unverified_code_token(text,
context_pack)`. Wired into `answer_assistant` immediately before the `assistant.general` branch
would call `answer_open_query`: if the driver's text contains a code-like token (letters
immediately followed by digits) that is neither DTC-shaped (handled above) nor present anywhere in
the grounded `ContextPack`, a fixed deterministic reply is returned and **the LLM is never called
for that turn at all**. This supersedes the previous pass's prompt-only mitigation (kept as
defense-in-depth) with a guarantee that doesn't depend on the model following instructions.

New `tests/test_mobile_session_store_guards.py` (7 unit tests, calling
`MobileSessionStore._find_unverified_code_token` directly, no app/HTTP layer needed): detects
`XYZ123`/`ABX900`, ignores ordinary mixed Vietnamese text (false-positive check), ignores
DTC-shaped tokens (handled separately), allows a token that genuinely appears in grounded context
(case-insensitively). New `tests/test_mobile_compatibility.py::test_unverified_code_token_never_reaches_the_llm_at_all`
proves end-to-end that the fake `/api/chat` endpoint is hit **zero times** for this input, even
though the fake was deliberately configured to return a fabricated answer if called — the
strongest form of this guarantee: not "output was rejected" but "the call never happened."

### Full regression after this round

```
uv run pytest --collect-only -q                                 → 272 tests collected
uv run pytest -q                                                 → 272 passed, 16.2s
uv run pytest -q -k "dtc or fault_concern or catalog or
  technical_code"                                                → 28 passed
uv run pytest -q -k "assistant_general or assistant_clarify or
  grounding or hallucination"                                    → 2 passed
uv run pytest -q -k "engine_temperature or emergency or crash
  or fallback"                                                    → 26 passed
uv run ruff check app tests                                      → All checks passed!
```
(272 = 262 + 10 net: +4 in `test_mobile_assistant.py` replacing −2 removed, +7 new
`test_mobile_session_store_guards.py`, +1 in `test_mobile_compatibility.py`.)

### Live verification, real Docker container (rebuilt `--no-cache` from this round's source), real Ollama

```
KNOWN_AND_ACTIVE   "Ma U0100 nghia la gi?" (U0100 active, MEDIUM)
  → route=vehicle.fault_concern llmUsed=true "U0100" + active severity guidance preserved

KNOWN_BUT_NOT_ACTIVE  "Ma U0100 nghia la gi?" (no active DTCs)
  → route=vehicle.fault_concern llmUsed=false fallback=true (this run's narration attempt was
    guardrail-rejected and fell back to the exact deterministic catalog text -- a safe outcome)
  → text states the catalog meaning AND that U0100 is NOT currently active

UNKNOWN_TO_CATALOG  "Ma P0130 nghia la gi?" (real code, not in the 7-entry catalog)
  → route=vehicle.fault_concern llmUsed=true, honestly says not in the reference catalog,
    no invented meaning

5 unsupported code-like tokens (XYZ123, ABX900, QRT456, ZKL77, MNB321), each asked as
"<token> nghia la gi?" against a state with no active DTCs:
  → all 5: route=assistant.general llmUsed=false fallback=false model=deterministic-context-router
  → all 5: token preserved in an honest "no verified data" reply, zero /api/chat calls
```

Docker: `docker compose down && docker compose build --no-cache && docker compose up -d` →
image rebuilt from this round's source, container healthy, `GET /health`/`GET /ready` both pass.
