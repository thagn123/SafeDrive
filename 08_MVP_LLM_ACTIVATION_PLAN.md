# 08 - MVP Real-LLM Activation Plan

**Date:** 2026-08-03
**Trigger:** User pasted a mentor-report draft (for tonight's mentor meeting) and asked for a plan to
reach "1 MVP hoạt động được với LLM thật" (a working MVP with a real LLM), with an explicit instruction
to standardize on `/api/v1` immediately so Android and backend never drift apart.
**Supersedes:** `SAFEDRIVE_MVP_LLM_PLAN.md` for architecture decisions (see §5) — that document proposed
an LLM design (conversation memory, LLM-driven intent classification, Gemini/OpenAI providers) that was
never built. What actually got built is smaller, safer, and already working; this plan is grounded in the
real code, not the older proposal.

---

## 1. Why this document exists

The pasted mentor-report is a good report to *give* a mentor, but several of its "chưa xây" (not built
yet) claims are stale — they don't match the code in this workspace today. Before planning new work, this
section corrects the record so the plan targets real gaps, not already-solved problems. Every claim below
was checked directly against source, a live test run, and a live local Ollama call on 2026-08-03 — not
assumed from documentation.

| Report's claim | Actual current state | Evidence |
| --- | --- | --- |
| API contract not unified; Android expects `/api/v1/...`, repo has plain paths | **False today.** Already unified. | `app/api/router.py` mounts `/api` → `app/api/v1/router.py` mounts `/v1` → every mobile route (`sessions/start`, `state/update`, `assistant/query`, `events`, `actions/confirm`, `emergency/*`) lives at `/api/v1/...`. Android's `SafeDriveApi.kt` calls the exact same paths. |
| "Chưa tích hợp LLM thật... reasoning chính vẫn là rule/template" | **Partially false.** A real local LLM is wired in and tested, but deliberately narrow-scope (see §2). | `app/mobile/llm.py` (`OllamaNarrator`), `app/mobile/emergency_reasoner.py` (`EmergencyLLMReasoner`), both constructed in `app/main.py::_publish_services` when `LLM_PROVIDER=ollama`, both called from `app/mobile/session_store.py`. Test coverage: `tests/test_mobile_llm.py`, `tests/test_mobile_emergency_reasoner.py`. Live check 2026-08-03: local Ollama at `127.0.0.1:11434` is running with `qwen2.5:7b-instruct-q4_K_M` and `qwen2.5:3b-instruct-q4_K_M` already pulled. |
| Android app: "chỉ có kiến trúc, playbook... chưa có APK hoàn chỉnh" | **False.** A real, building, mostly-tested Android app exists. | `data/remote/SafeDriveApi.kt` implements all 9 backend routes; `GatewayProvider`/`SessionCoordinator` switch Demo↔Remote; Settings/Cockpit/Assistant/Simulator/Emergency screens exist; voice pipeline (wake word + command capture) implemented this project. Debug APK builds and installs (verified this session on a physical device). |
| (Not mentioned in report) Backend reachability | Backend process on port 8002 is **currently down** — it was a background process from an earlier session that died. Not a design gap, just needs restarting. | `curl 127.0.0.1:8002/health` → connection refused, this session. |
| (Not mentioned in report) Deployment target | **No `Dockerfile` exists** in the backend repo. The report's `docker build` step in its own plan would fail today. | `Glob` for `Dockerfile`/`docker*` under the backend repo → no matches. |
| (Not mentioned in report) Android Automotive OS | The app is currently a **plain Android phone app**, not an Android Automotive OS (AAOS) build — no `android.hardware.type.automotive` feature, no `androidx.car.app`. It was tested on a physical Xiaomi phone via adb. | `AndroidManifest.xml` grep for automotive/car-app → no matches. |

**Bottom line:** the two things you named — a real LLM and a unified `/api/v1` contract — already exist
and already agree with each other. There is nothing to "chuẩn hóa ngay" on the contract; it's done. The
real remaining work is narrower than the report implies: mostly *proving it end-to-end on your device* and
making a couple of explicit scope decisions, not building new plumbing.

---

## 2. What "real LLM" currently means here (read this before promising more to a mentor)

This matters because it's easy to overstate. Today, the LLM:

- **Does** rewrite the final wording of a reply for exactly three routes: `assistant.general` (anything
  that doesn't match a known keyword), `assistant.clarify`, and `companion.conversation`
  (`app/mobile/session_store.py:_NARRATABLE_ROUTES`). Only when risk is not `HIGH`/`CRITICAL` and there's
  no active emergency candidate (`_can_narrate`).
- **Does** produce an optional, non-authoritative "second opinion" narrative when a crash/no-response
  emergency opens or escalates (`EmergencyLLMReasoner`) — it can only add an explanation string, it can
  never change the state machine transition itself, which stays 100% deterministic
  (`_refresh_emergency` / `_advance_emergency_if_due` in `session_store.py`).
- **Never** classifies intent, never picks a risk level, never decides whether HVAC/DTC/fatigue/SOS
  wording is used, and never runs on the vehicle-control fast path. `IntentResolver` (`app/mobile/intent.py`)
  is a plain keyword router with zero LLM calls, by design — the safety-critical majority of user
  interactions (HVAC commands, status/DTC/fatigue queries, SOS) are template text today and will remain
  so unless you explicitly decide otherwise (§5).
- **Fails closed**: if Ollama times out, errors, or the rewritten text fails validation (contains CJK
  garbage, drops a number, exceeds length, etc.), the deterministic `response` is returned unchanged
  (`answer_assistant`'s `if narrated is None: return response`).

So "MVP hoạt động được với LLM thật" is achievable and honest to claim — but be precise with a mentor:
*"the safety-critical majority of the app is deterministic by design; the LLM handles wording for
open-ended/small-talk turns and gives a secondary opinion during emergencies."* That is a defensible,
even impressive architecture choice (as `SAFE_DRIVE_STATUS.md`'s own audits repeatedly stress-tested) —
don't let it get flattened into "we have an LLM chatbot," which overclaims and invites a harder question.

---

## 3. Immediate action plan (no new architecture decisions required)

These get you to a live, demoable, real-LLM MVP using what's already built:

1. **Restart the backend with the LLM enabled and confirm it end-to-end.**
   ```powershell
   $env:ENVIRONMENT = "development"
   $env:ACTIVE_PROFILE = "PRODUCTION_NO_DMS"
   $env:SAFEDRIVE_API_KEY = "local-android-debug-key"
   $env:LLM_PROVIDER = "ollama"
   $env:LLM_MODEL = "qwen2.5:7b-instruct-q4_K_M"
   $env:LLM_BASE_URL = "http://127.0.0.1:11434"
   $env:LLM_TIMEOUT_SECONDS = "20"
   .venv\Scripts\python.exe -m uvicorn app.main:app --host 0.0.0.0 --port 8002
   ```
   Confirm `GET /health` and that a `companion.conversation`/general query's response `model` field reads
   `ollama/qwen2.5:7b-instruct-q4_K_M` (proof the narrator actually ran), not
   `deterministic-context-router`.

2. **Run the existing live smoke-test runbook against the real device**, not just unit tests —
   `safedrive-ai (1)/android/REMOTE_MODE_SMOKE_TEST.md` already scripts the exact 5 scenarios the
   mentor-report calls out (HVAC control, hot-cabin comfort, fatigue intervention, DTC explanation,
   crash/no-response SOS simulation), plus one you should add: a genuinely open-ended question (e.g.
   "Bạn có thể nói chuyện với tôi một chút không?" or an out-of-keyword-list question) to see the LLM's
   own phrasing on-screen, not a template. This is the actual missing proof today — not missing code.

3. **Re-run both test suites** after any change and record the real numbers (they've drifted from what
   `SAFE_DRIVE_STATUS.md` last recorded — this session's fresh run: backend `176 passed, 0 failed`;
   Android was last recorded at `284 passed`, re-run `gradlew.bat testDebugUnitTest --rerun-tasks` to
   refresh before the mentor meeting).

4. **`/api/v1` contract:** nothing to change. Optional low-cost insurance: add one contract-drift test
   that fails CI if the path list in `safedrive-ai (1)/openapi/safedrive-v1.yaml` and the path list in
   Android's `SafeDriveApi.kt` / the backend's actual FastAPI routes ever diverge again — cheap given how
   much of this project's own history (`SAFE_DRIVE_STATUS.md`'s Phase 1 entries) was spent catching exactly
   this kind of drift by hand.

None of the above requires a design decision from you — it's restart, verify, record.

---

## 4. One thing worth deciding before a live demo (not urgent, not blocking)

`app/mobile/intent.py`'s keyword router still has known rough edges tracked in `SAFE_DRIVE_STATUS.md`
(e.g. no "turn off AC"/fan/media/door support at all — any such request falls through to the generic
clarify prompt, which will sound like a mishearing rather than "not supported yet"). This is safety-neutral
(it never produces unsafe text, only an unhelpful one) but could look awkward live if a mentor tries it.
Not fixing it now; flagging so it's a deliberate choice, not a surprise.

---

## 5. Explicit decision point: how far should the LLM's role grow?

`SAFEDRIVE_MVP_LLM_PLAN.md` (the older doc this supersedes) proposed a considerably bigger LLM role:
multi-turn conversation memory, an LLM-based intent classifier for ambiguous phrasing, a pluggable
Gemini/OpenAI provider, and a structured-JSON output contract. **None of that was built**, and this plan
does not recommend rushing into it before a demo, for a concrete reason: intent classification sits on the
safety-critical path (it currently decides whether the reply is HVAC/DTC/fatigue/SOS text), and putting an
LLM in front of that — even "advisory only" — is a bigger, riskier change than reading the report's
paragraph length suggests. The current design's conservative split (deterministic router decides *what*,
LLM only ever touches *wording* for the 3 safety-cleared routes) is exactly what let every past adversarial
audit in `SAFE_DRIVE_STATUS.md` pass. Recommend keeping that split for this MVP.

If you *do* want the bigger LLM role (real conversation memory so follow-up questions like "Nó có nguy
hiểm không?" work; LLM help on ambiguous phrasing that misses every keyword today), that's legitimate,
valuable follow-on work — just say so explicitly and it becomes its own scoped plan, not something folded
into "activate the LLM we already have."

> **Decision (2026-08-03):** user chose to expand LLM scope into intent classification for the ambiguous
> fallback specifically. Implemented the same day — see §9 for what was built, how it stays safety-bounded,
> and live verification against the real local model. Conversation memory and cloud providers remain out
> of scope (§6); only the classification piece was authorized and built.

---

## 6. Out of scope for this MVP pass (unchanged from prior status, still true)

- Multi-turn conversation memory.
- Cloud LLM providers (Gemini, OpenAI) — still Ollama-only.
- ~~LLM-based intent classification~~ — **built 2026-08-03** in a deliberately narrow, safety-bounded
  form; see §9. Still out of scope: letting the LLM classify into action-bearing/safety routes
  (climate/emergency) or touch anything on the fast path.
- HVAC off/fan-speed/media/door controls.
- Real VHAL / vehicle HAL integration — all vehicle state is still mocked via the Simulator screen and
  `MockVehicleDataSource`-equivalent sources.
- Real emergency dispatch — permanently simulation-only (`realEmergencyDispatchEnabled` forced `false`
  at 6+ schema/logic sites on both sides); this is a product invariant, not a temporary MVP limitation.
- Packaging as an Android Automotive OS app, unless the mentor confirms the hackathon requires it.

---

## 7. Questions for tonight's mentor meeting (only they can answer these)

Trimmed from the pasted report — the `/api/v1` question is removed since it's resolved; everything below
is a genuine external unknown, not something more coding can resolve:

1. **Deployment target:** does the Virtual Development Platform need the backend as a Docker image, a
   VM process, or something else? (Concretely blocking: **no `Dockerfile` exists yet** — this needs
   writing regardless of the answer, so it can start now if useful.)
2. **Network path:** how does the Android/AAOS emulator on the platform reach the backend — `10.0.2.2`,
   `host.docker.internal`, an internal service DNS name, or a public staging URL?
3. **Platform target — the important one given §1's finding:** does submission require true Android
   Automotive OS (head-unit emulator, VHAL read/write), or is a regular Android phone app acceptable as
   the "Digital Cockpit" client? Today's app is the latter. If AAOS is mandatory, that's a real,
   non-trivial gap to plan for separately, not part of this LLM-activation plan.
4. **VHAL/DTC minimum bar:** is a VHAL/DTC simulator sufficient for grading, or must the app read/write a
   real (or platform-provided) VHAL?
5. **Submission deliverables:** source repo link vs. platform workspace, APK, Docker image, deployment
   URL, demo video, slide/report — and any size limits.
6. **Deadline and demo rubric specifics.**

---

## 8. Verification checklist before you call this "done"

- [x] Backend restarted with `LLM_PROVIDER=ollama`; `/health` reachable; a narrated response's `model`
      field shows `ollama/...`, proving the LLM actually ran (not silently falling back).
- [x] `pytest -q` in the backend passes (184 after §9's work — up from 176; re-confirm on further changes).
- [ ] `gradlew.bat testDebugUnitTest --rerun-tasks` passes on Android — unaffected by §9 (backend-only
      change), not re-run this pass; re-run before a live demo regardless.
- [ ] All 5 scenarios in `REMOTE_MODE_SMOKE_TEST.md` pass live **on the Android device** — still the one
      thing not done in this pass (§9 verified the backend directly over HTTP, not through the app UI;
      this device has adb `input tap`/`pm grant` blocked by MIUI, so on-device UI driving needs you).
- [x] Result recorded in `SAFE_DRIVE_STATUS.md`.

---

## 9. Execution log — intent-classification reclassification, built and verified (2026-08-03)

Per your decision in §5, added an advisory LLM reclassification step for text the deterministic router
could not match at all. Design goal: let the LLM help exactly where the router today gives up
(`IntentResolution.needs_clarification`), while making it structurally impossible for that step to
invent wording, invent an action, or touch the safety/action-critical fast paths.

### What was built

- **`app/mobile/llm.py`** — new `OllamaIntentClassifier` + a closed
  `AMBIGUOUS_INTENT_LABELS` tuple (`safety.driver_fatigue`, `comfort.too_hot`, `vehicle.fault_concern`,
  `assistant.vehicle_status`, `companion.conversation`, `assistant.clarify`). Deliberately excludes any
  action-bearing or emergency label — `climate.*` and `safety.emergency_request` are never reachable from
  this classifier, because those are already resolved deterministically upstream (an explicit HVAC target
  or the "sos/cứu hộ/cấp cứu" keywords are checked before this step could ever run). The classifier's
  prompt asks for **one label, nothing else**; any output that isn't an exact match against the closed set
  — including a timeout, a non-2xx response, or a sentence containing a label as a substring — is rejected
  and the caller keeps the original deterministic reply.
- **`app/mobile/assistant.py`** — added `ContextAwareAssistant.build_reply(resolution, snapshot, safety,
  request_id)`, a public wrapper around the existing private template dispatcher. This is the only thing a
  successful reclassification can call — it produces exactly the same grounded text/actions the
  deterministic path would have produced for that label on its own; nothing new is generated.
- **`app/mobile/session_store.py`** — `MobileSessionStore` gains a `classifier` constructor param and
  `_can_classify(resolution, safety)`, gated identically to `_can_narrate` (never during an active
  emergency or HIGH/CRITICAL risk) plus `resolution.needs_clarification` (true only for the router's own
  `assistant.general`/final-`assistant.clarify` give-up paths). `_maybe_reclassify` runs the LLM call
  **outside the session lock** (same reasoning as the narrator: local inference must never block
  telemetry), then briefly re-acquires the lock only to rebind `session.issued_actions` for the
  reclassified route's actions — and discards the whole reclassification if the state version moved on
  while the model was thinking, rather than bind actions against a stale state.
- **`app/main.py`** — constructs `OllamaIntentClassifier` alongside the existing narrator/reasoner when
  `LLM_PROVIDER=ollama`, using the **same timeout as the narrator** (not a tighter one — see the "why" below).
- **Tests:** 6 new unit tests (`tests/test_mobile_llm.py`: valid label, stray punctuation/whitespace
  tolerance, rejects an out-of-set label, rejects a sentence merely containing a label, timeout, structured
  prompt content) + 2 new full-app integration tests (`tests/test_mobile_compatibility.py`: an off-keyword
  message gets reclassified into `assistant.vehicle_status` and rendered via the real deterministic
  template; reclassification never runs during an active crash/no-response emergency, proven by asserting
  the classifier's system prompt was never sent even though the narrator/reasoner's own calls still fire).

### A real bug this caught before it shipped

First live test against the real model returned the *unchanged* deterministic text with no reclassification
— tracing it down: the classifier's timeout had been set to `min(llm_timeout_seconds, 5.0)` (tighter than
the narrator's, to avoid stacking latency across two sequential LLM calls per request). A cold/idle local
Ollama process took **~8.5s just to load `qwen2.5:7b-instruct-q4_K_M` back into memory** (confirmed by a
direct probe of `/api/chat` — `load_duration` was 8.79s of an 8.79s total, `eval_duration` only 111ms) before
it could even start generating the 1-word label, so the classify call timed out and silently no-op'd — correct
fail-closed *behavior*, wrong tuning. Fixed by giving the classifier the same timeout budget as the narrator;
the label generation itself is short (`num_predict=12`) once the model is warm, so this doesn't meaningfully
change worst-case behavior, it just stops a cold start from defeating the feature entirely.

### Live verification against the real local model (not mocked), same session

- Off-keyword message **"Bạn có thích nhạc không"** (do you like music) against a fresh, low-risk state:
  reclassified `assistant.general` → **`companion.conversation`**, then separately narrated (that route is
  in `_NARRATABLE_ROUTES`) into genuinely model-generated Vietnamese text: *"Tôi không thích nhạc. Nếu bạn
  cảm thấy mệt, hãy nói với tôi hoặc dừng ở vị trí an toàn khi có thể."* — not a template string. Round trip
  (classify + narrate, both warm): **~11s**. Worth knowing before a live demo: two sequential local-model
  calls on a 7B model is not fast; if this needs to feel snappier live, the smaller already-pulled
  `qwen2.5:3b-instruct-q4_K_M` is a lower-risk lever than touching the safety-side logic.
- Same off-keyword message during an active crash/no-response emergency: **no reclassification message was
  sent** (verified by intercepting the HTTP layer in the integration test); production behavior mirrors this
  by construction (`_can_classify` gate).
- An explicit HVAC command (`"Đặt điều hòa 23 độ C"`) against the same live backend: **`latencyMs: 0`,
  `model: "deterministic-context-router"`**, ~63ms wall time including the HTTP round trip — confirms the
  fast/safety path is completely untouched by any of this.
- Full backend suite: **184 passed, 0 failed** (was 176 before this feature; `ruff check app tests` clean).
- Android: not touched, not re-run — this feature is backend-only.

### Still not done from §3's checklist

The on-device Android smoke test (`REMOTE_MODE_SMOKE_TEST.md`'s 5 scenarios, driven from the actual app UI)
remains the one item this pass didn't complete — this environment can verify the backend directly over
HTTP but can't tap through the Android UI on your MIUI device (adb `input tap`/`pm grant` are blocked there,
a limitation hit earlier in this project too). That's the next concrete step before calling the MVP demo-ready.
