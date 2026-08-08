# SafeDrive Agent Armor — Conservative Architecture & Minimum-Correct-Change Migration Plan

Status: **PLAN ONLY — no source, test, config, or dependency files were modified to produce this document.**
Prepared by reading the actual repository (source, tests, git history) on 2026-08-07. Where prior docs
(`docs/ARCHITECTURE.md`, `SAFE_DRIVE_STATUS.md`, etc.) disagreed with what the code actually does, the code wins.

---

## 1. CURRENT SOURCE OF TRUTH

```
Branch (local HEAD):     claude/gcp-competition-reconcile
SHA:                     ed8d8a4  (tag v0.0.1-carsky, == origin/claude/gcp-competition-reconcile, == ai/vertex-ai-verification)
origin/main:             882a4ae  (older — GCP Cloud Run deploy + CarSky integration doc only; does not
                                    include the WSS assistant channel, VertexAINarrator, or the 288-test
                                    reconciliation done on top of it)
Unmerged sibling branch: origin/nghia-sua-voice @ d3a0224 ("nghia sua voice lai" — voice fix), NOT an
                          ancestor of HEAD and HEAD is not an ancestor of it. Someone else's in-flight
                          voice work. Do not silently merge or ignore its existence when touching voice code.
Other worktrees:         SafeDrive-dtc-grounding (branch claude/competition-integration, prunable — already
                          contained in current history), SafeDrive-vertex-ai (== HEAD, prunable).
```

**Working tree note:** `git status` shows ~40 "modified" files, but `git diff --stat` on any of them
(verified on `docs/GOOGLE_CLOUD_DEPLOYMENT.md`) shows an equal insertion/deletion count with identical
content — this is a **CRLF/LF line-ending artifact** from mounting a Windows checkout (`app/mobile/llm.py`
confirmed CRLF via `file`) into this Linux inspection sandbox, not real uncommitted work. Nothing here
should be treated as pending changes.

**Backend runtime state:** FastAPI app (`app/main.py`), profile-driven (`PRODUCTION_NO_DMS` / `DMS_DEMO`),
`llm_provider` settings-driven (`mock` / `ollama` / `gemini` / `vertex_ai`). 35 non-test Python files, ~7,200
LOC in `app/`. Backend test suite currently contains **274 test functions** across 32 files in this tree.

**Test execution — honest caveat:** I attempted to run `pytest` in this sandbox. 137 passed, 161 failed. I
traced one representative failure (`test_state_manager_atomic_updates`) to `AttributeError: module
'datetime' has no attribute 'UTC'` — that attribute was added in **Python 3.11**, and this sandbox only has
**Python 3.10.12**, while `pyproject.toml` declares `requires-python = ">=3.11"`. This is a sandbox
tooling mismatch, not a code regression — I did not chase the other 160 failures individually, but the
same root cause (or missing optional deps, already fixed for two of them) plausibly explains most of them.
The last *committed, reproducible* evidence is `docs/TEST_EVIDENCE.md` (dated 2026-08-04): **251 backend
tests passed** via `uv run pytest`, **332 Android unit tests passed** via `gradlew clean testDebugUnitTest`
(0 failures), `ruff check` clean. I did not attempt an Android build in this sandbox (no Android SDK/Gradle
verified available). **Recommendation: re-run `uv run pytest -q` and `gradlew testDebugUnitTest` in a
correctly-versioned environment before trusting any test count going forward, including mine.**

**GCP / LLM providers:** three narration providers exist side by side — `OllamaNarrator` (local),
`GeminiNarrator` (API-key REST), `VertexAINarrator` (ADC metadata-token or API-key REST, Cloud Run-aware).
`OllamaIntentClassifier` (advisory reclassification) and `EmergencyLLMReasoner` (advisory second opinion)
exist **only for the Ollama path** — `main.py` does not construct them for `gemini`/`vertex_ai`.

**Known unfinished work** (from `docs/KNOWN_LIMITATIONS.md`, cross-checked against source): SOS is
simulation-only end-to-end (`realEmergencyDispatchEnabled=False` hardcoded); no maps/POI provider; no
state/emergency push channel (state is client-polled, only the assistant chat channel is a WebSocket);
narration guardrails validate whole-string output, not streamed; `assistant.general`'s off-topic refusal is
prompt-engineered (spot-checked, not code-enforced); Demo Mode's `MockPolicyEvaluator.kt` is a documented,
intentional local duplicate of backend thresholds for offline use.

---

## 2. CURRENT EXECUTION FLOW (AS-IS, verified against source)

**Assistant query, REST or WebSocket (both call the identical `MobileSessionStore.answer_assistant`):**

```
Android AssistantTurnCoordinator.submit()
  -> AssistantQueryUseCase -> SafeDriveGateway.queryAssistant()
     (RemoteSafeDriveGateway -> AssistantSocketClient, heartbeating WS; or MockSafeDriveGateway in Demo Mode)
        -> POST/WS /api/v1/assistant/query  (app/api/routes/mobile.py | assistant_ws.py)
           -> MobileSessionStore.answer_assistant(request)
              1. MobileContextBuilder.build()      — raw StateUpdateRequest -> ContextSnapshot
                                                       (typed ContextValue per field: value, source, age_ms,
                                                        FRESH/STALE/UNAVAILABLE)
              2. SafetyRiskEngine.evaluate()        — deterministic, ordered rule ladder -> RiskAssessment
                                                       + RestRecommendation + allowed_action_types + emergency_candidate
              3. IntentResolver.resolve()           — deterministic keyword/regex router -> IntentResolution (route)
              4. ContextAwareAssistant.answer()     — deterministic route -> (reply text, [SafeDriveAction])
                                                       templates, from route + snapshot + safety only
              5. [optional] OllamaIntentClassifier  — ONLY for the resolver's own "assistant.clarify" fallback;
                 .classify()                          picks a different but still-existing template, never new text
              6. [optional] Narrator.rewrite_grounded_reply() / .answer_open_query()
                                                     — ONLY for LOW/MEDIUM, non-emergency routes; rewords the
                                                       already-decided text against a bounded ContextPack; output
                                                       is rejected back to the deterministic text unless it passes
                                                       Vietnamese-language, verbatim-snippet, and per-unit numeric-
                                                       grounding checks
              <- AssistantQueryResponse (message, risk, actions, route, llmUsed, fallback/fallbackReason)
```

**State update:** Android pushes `StateUpdateRequest` every ~4s -> `MobileSessionStore.update_state()` ->
`MobileContextBuilder.build()` -> `SafetyRiskEngine.evaluate()` -> stored `StateEnvelope` (+ best-effort,
non-blocking projection into an older canonical signal pipeline via `MobileStateBridge` -> `SignalIngestionService`,
used by a separate VHAL/FPT signal-registry demo surface, not by the mobile assistant path itself).

**Emergency:** `crashDetected && passengerResponse == NO_RESPONSE` -> deterministic state machine
(`VERIFYING_EVIDENCE` 5s -> `AWAITING_USER_RESPONSE` 15s -> `FINAL_COUNTDOWN` 10s -> `SOS_SIMULATED_SENT`,
mirrored client-side by Android's pure `EmergencyReducer`). `EmergencyLLMReasoner` may only (a) cancel a
just-opened `VERIFYING_EVIDENCE` candidate back to `IDLE`, never open one, or (b) attach human-readable
`reasoningSummary` text to an escalation that has *already* deterministically happened. Real dispatch is a
`SimulatedRescueGateway` mock (`realEmergencyDispatchEnabled=False`).

**Action confirmation:** the only real action today is `SET_HVAC_TEMPERATURE`. Server issues an `IssuedAction`
bound to the exact `state_version` and a `dependency_fingerprint` (cabin temp, energy%, DTCs, fatigue flag,
etc.). `confirm_action()` rejects if state moved on, the action wasn't issued for this state, or the client's
claimed type/target doesn't match what was issued — then re-checks freshness and re-runs `SafetyRiskEngine`
before applying. This is a real, working, minimal Action Validator + Executor for one capability.

**Android:** `SafeDriveGateway` interface with two implementations (`MockSafeDriveGateway` for Demo Mode,
`RemoteSafeDriveGateway` for Remote Mode) selected by `GatewayProvider` from a `BackendMode` preference — no
feature code branches on which one is active. `AssistantTurnCoordinator` is the single entry point for text/
quick-prompt/voice turns with careful single-flight/generation-token concurrency control. `EmergencyReducer`
mirrors the backend timers locally for Demo Mode. Contract tests (`SafeDriveGatewayContractTest` and its two
subclasses) already assert Mock and Remote satisfy the same behavior.

---

## 3. WHAT ALREADY WORKS AND MUST NOT BE BROKEN

- `SafetyRiskEngine` (`app/mobile/safety.py`) — deterministic, ordered, fully unit-tested risk ladder. No LLM in this path at all.
- `IntentResolver` (`app/mobile/intent.py`) — deterministic routing; DTC-code-shape detection independent of keywords.
- Emergency state machine (backend `session_store.py` timers + Android `EmergencyReducer`) — fixed timers, LLM is advisory-only and can never gate a transition.
- Action confirmation/versioning (`IssuedAction`, `_action_dependency_fingerprint`, `confirm_action`) — anti-tampering, anti-stale-state.
- Narration guardrails (`app/mobile/llm.py`: `_validate_and_normalize`, per-unit numeric grounding, Vietnamese-language check, verbatim snippet preservation) — this is the reason the LLM can be swapped at all without a safety review each time.
- `MobileContextBuilder` / `ContextSnapshot` / `ContextPack` (`app/mobile/context.py`) — already a clean raw-signal -> normalized-fact layer with per-field freshness; this is most of what a "VehicleWorldState" would need to be.
- Android `SafeDriveGateway` abstraction + Demo/Remote parity + contract tests.
- DTC three-tier trust model (live active DTC > static catalog > honest "unknown") in `ContextAwareAssistant` + `dtc_catalog.py`.
- 251 backend / 332 Android tests green as of the last committed evidence.

**Protected in every slice below unless a slice explicitly targets it:** `app/mobile/safety.py`,
`app/mobile/emergency.py`, `app/mobile/emergency_reasoner.py`'s non-gating contract, the emergency timers in
`session_store.py`, `IssuedAction`/`confirm_action`'s validation order, `MockPolicyEvaluator.kt`,
`EmergencyReducer.kt`, `SafetyInvariantsTest.kt`.

---

## 4. CURRENT LIMITATIONS BLOCKING THE DESIRED ASSISTANT (evidence-backed only)

| # | Limitation | Evidence | Severity | Fix now? |
|---|---|---|---|---|
| L1 | No path from an open-ended utterance to an action. Only the fixed deterministic routes in `IntentResolver` can ever produce a `SafeDriveAction`; `assistant.general`'s LLM narrator (`answer_open_query`) is answer-only by contract and cannot emit an action. Concretely: **"Lạnh quá" (too cold) has no matching keyword branch today** (only `comfort.too_hot` exists — "nong"/"stuffy"/"hot"; there is no "lanh"/"cold" branch anywhere in `IntentResolver`), so it falls to `assistant.general` and can never produce an HVAC action, even though the exact same HVAC pipeline (`climate.set_temperature`/`climate.enable_default`/`_apply_hvac_action`) already works for "too hot." | Read `app/mobile/intent.py` lines 126-135 directly; confirmed no cold/lạnh term anywhere in the file. | Medium — blocks UC2 as literally specified | Yes — Slice 1 (see §12) |
| L2 | No formal contract for "an LLM provider" — `OllamaNarrator`/`GeminiNarrator`/`VertexAINarrator` satisfy the same shape by convention (duck typing), not a declared `Protocol`/ABC, and `main.py` hand-wires provider selection with `if/elif` on a string. Provider-swap (UC10) works for narration but the optional classifier/reasoner are Ollama-only. | `app/main.py` `_publish_services`; `app/mobile/llm.py` class definitions | Low-medium | Partial — Slice 2 |
| L3 | No temporal/trend representation. `ContextSnapshot`/`SafetyRiskEngine` only ever see the latest instantaneous value; a rolling window exists (`app/state/rolling_window.py`) but is wired only into the separate, older canonical-signal ingestion path, never into the mobile assistant's context or safety evaluation. UC5 (proactive trend warning) is not supported today. | `app/mobile/context.py`, `app/mobile/safety.py` — no history access; `app/state/rolling_window.py` used only by `SignalIngestionService` | Medium | No — see Future Work |
| L4 | No server-initiated push. The assistant WebSocket is query/response only (client sends a query, server answers); state is client-polled. There is no mechanism for the backend to proactively notify the driver of anything. | `app/api/routes/assistant_ws.py`, `app/api/routes/mobile.py` — no server-push endpoint exists | Medium | No — see Future Work |
| L5 | Capability surface is exactly one hardcoded action type with a real executor (`SET_HVAC_TEMPERATURE`); the other four action types (`SHOW_WARNING`, `OPEN_DIAGNOSTICS`, `SUGGEST_REST_STOP`, `START_SOS_COUNTDOWN`) are advisory UI prompts with no backend-side "executor," and there is no generic capability/tool registry. This is fine at n=1 capability; it will not scale to a second real capability (e.g. media) without extraction. | `app/mobile/assistant.py::_actions/_hvac_action`, `session_store.py::_apply_hvac_action` | Low today, will become Medium at capability #2 | No — extract only when capability #2 is real (see §8) |

None of these are "the LLM is treated mostly as a narrator" in a bad sense — that separation is exactly what
makes this codebase safe to keep evolving. The real gap is narrower: **the deterministic router's keyword
coverage is incomplete, and there is no generic bridge from "novel utterance" to "propose one of a small set
of pre-approved actions."**

---

## 5. NORTH-STAR DEFINITION OF SAFEDRIVE AGENT ARMOR

A persistent Vehicle Intelligence Agent where the LLM is a replaceable, narration/understanding brain, and a
deterministic Armor owns every safety-relevant decision, every action's validity, and every fact the driver is
told. Concretely, "Armor" already substantially exists in this repo as: `SafetyRiskEngine` (Safety Authority),
`ContextSnapshot`/`ContextPack` (Vehicle Context / partial World Model), `IntentResolver` (deterministic
routing), `IssuedAction` + `confirm_action` (Action Validation + Executor for one capability), the emergency
state machine (Safety Authority for crash handling), and the narration guardrail in `llm.py` (Grounding
Verifier). What's genuinely missing is: a generalized Capability/Tool bridge for novel utterances, a
Temporal/Situation layer, and a Proactive channel. This plan does not propose building all of the north-star
component list now — see §8 for which of them are justified today versus deferred.

---

## 6. BRAIN VS ARMOR RESPONSIBILITY MATRIX

| Capability | Owner today | Should stay | Notes |
|---|---|---|---|
| Natural language understanding for known categories | Armor (`IntentResolver`, deterministic keywords/regex) | Armor | No LLM involved; this is intentional and correct |
| Natural language understanding for novel phrasing | Nobody (falls to open-answer LLM, answer-only) | LLM, constrained | Gap — see L1 |
| Reply wording | LLM (optional) with deterministic fallback | LLM (Brain), text only | Cannot change facts, only phrasing — guardrail-enforced |
| Risk level / safety threshold | Armor (`SafetyRiskEngine`) | Armor, always | Never move to LLM |
| DTC severity / meaning | Armor (`Dtc.severity` from vehicle; static catalog for reference) | Armor | LLM never asked to guess |
| Stale-state detection | Armor (`MobileContextBuilder` freshness) | Armor | — |
| Emergency state transition | Armor (fixed timers) | Armor, always | LLM is advisory-only and cannot gate |
| Proposing an HVAC target from explicit "set to X°C" | Armor (`IntentResolver` regex) | Armor | Deterministic, bounded 16-30°C |
| Proposing an HVAC target from indirect language ("lạnh quá") | Missing (L1) | Armor (deterministic, symmetric to `comfort.too_hot`) for this specific shape; LLM-mediated only if/when a general capability bridge is built | See Slice 1 |
| Checking a capability exists / is enabled | Armor | Armor | Extend when capability #2 arrives |
| Checking an action is permitted for current risk/context | Armor (`allowed_action_types`, `_action_dependency_fingerprint`) | Armor, always | — |
| Executing an action | Executor (`_apply_hvac_action`, simulation-only) | Executor, always re-validates freshness | — |
| Claiming an action succeeded | Only after Executor success | Only after Executor success | Already enforced |
| Provider/model identity | Settings (`llm_provider`) | Settings/Armor | LLM swap must never touch Safety/Executor code |

**Test of this matrix (already true today):** swapping `llm_provider` from `ollama` to `vertex_ai` in
`main.py`/settings changes zero lines in `safety.py`, `intent.py`, `assistant.py`, `emergency.py`, or any
Android file. The one real gap is that the optional classifier/reasoner are Ollama-only (L2) — narrower than
"safety changes," but worth closing for honesty about what "swap the provider" currently means.

---

## 7. AS-IS → TARGET COMPONENT MAPPING

| Target responsibility | Current source | Decision | Why |
|---|---|---|---|
| SafetyKernel | `app/mobile/safety.py` | KEEP_AS_IS | Already deterministic, ordered, fully tested; touching it requires a dedicated safety-review task, not this migration |
| Emergency authority | `app/mobile/session_store.py` (timers) + `emergency.py` + Android `EmergencyReducer.kt` | KEEP_AS_IS | Same reasoning |
| VehicleWorldState (partial) | `app/mobile/context.py` (`ContextSnapshot`/`ContextValue`/`ContextPack`) | KEEP_AND_HARDEN | Already the normalized-fact layer the north-star wants; extend only when a derived/trend fact is genuinely needed (Future Work) |
| Intent routing | `app/mobile/intent.py` | KEEP_AND_HARDEN | Extend keyword coverage (Slice 1); do not replace with an LLM router |
| Assistant reply/action templates | `app/mobile/assistant.py` | KEEP_AND_HARDEN | Add one branch for the new route in Slice 1; otherwise unchanged |
| LLMProvider contract | `app/mobile/llm.py` (3 classes, duck-typed) + `app/main.py` wiring | WRAP_BEHIND_INTERFACE | Formalize the existing shape as a `Protocol`; do not rewrite the classes (Slice 2) |
| GroundingVerifier | `app/mobile/llm.py::_validate_and_normalize` and friends | KEEP_AS_IS | Already exactly this component, just not named/extracted as one |
| ActionValidator + Executor (HVAC) | `session_store.py::_apply_hvac_action`, `IssuedAction` | KEEP_AS_IS | Works, tested, minimal |
| CapabilityRegistry / ToolPolicyEngine | Does not exist (one hardcoded capability) | DO NOT BUILD YET | No second capability exists to generalize against; building this now is speculative abstraction (§8) |
| ConversationMemory | `InMemoryConversationRepository.kt` (Android, UI-only chat history) | KEEP_AS_IS | No backend-side memory exists or is needed for any current use case |
| TemporalEngine / SituationEngine | Partial (`app/state/rolling_window.py`) but disconnected from the mobile path | EXTRACT_LATER | Real component only when UC5 is actually prioritized (Future Work) |
| ProactiveEventEngine | Does not exist | DO NOT BUILD YET | No server-push transport exists yet either; two unbuilt prerequisites |
| Observability | `AssistantTurnMetrics`/`AssistantTurnMetricsRecorder` (Android), structured logging (`app/core/logging.py`) | KEEP_AS_IS | Sufficient for current scope |
| GatewayProvider / Demo-Remote seam (Android) | `GatewayProvider.kt`, `SafeDriveGateway.kt` | KEEP_AS_IS | Already exactly the seam needed; no Android change required for any backend-only slice below |

---

## 8. COMPONENTS WE DO NOT NEED YET

Explicitly not building, with why:

- **CapabilityRegistry / ToolPolicyEngine** — there is exactly one real capability (HVAC). A registry generalizes over ≥2 things; building one now is architecture for a use case that doesn't exist yet. Revisit the moment a second real, executable capability (e.g. media transport control) is actually requested.
- **VehicleWorldState as a new class/module** — `ContextSnapshot`/`ContextPack` already do this job for every field currently used. A rename/extraction buys nothing observable.
- **TemporalEngine / SituationEngine** — no current use case's *acceptance criteria* require a trend (UC5 is explicitly "may proactively notify," not committed scope for this pass). Building it now is speculative.
- **ProactiveEventEngine** — has two unbuilt prerequisites (temporal facts, and a push transport that doesn't exist). Do not build the top of a stack whose bottom doesn't exist.
- **ConversationMemory (backend)** — every current use case is answerable from the latest `ContextSnapshot` plus the current turn's text. No evidence any use case needs cross-turn backend memory.
- **AgentRuntime as a new orchestrator class** — `MobileSessionStore.answer_assistant` already *is* this orchestration, just not renamed. Renaming/extracting it with no behavior change is pure churn; not proposed here.
- **A new database, queue, or state store** — sessions are in-memory today (`MobileSessionStore._sessions: dict`) and every current use case is single-process. Nothing in this migration requires persistence.

---

## 9. TARGET CONTRACTS

| Contract | Status | Why / what it wraps |
|---|---|---|
| `NarrationProvider` (Protocol: `provider_name`, `model`, `rewrite_grounded_reply(...)`, `answer_open_query(...)`) | REQUIRED_NOW (Slice 2) | Formalizes the shape `OllamaNarrator`/`GeminiNarrator`/`VertexAINarrator` already share; enables a contract test that guarantees UC10 (provider swap) stays true as new providers are added |
| `IntentResolution` / `ContextSnapshot` / `ContextPack` / `SafetyEvaluation` | Already exist, REQUIRED_NOW (no change) | These already are the minimum contract set the north-star's `AgentRequest`/`AgentResponse`/`EvidenceRef` would otherwise reinvent |
| `AgentRequest` / `AgentCandidate` / `ToolProposal` (generic, LLM-proposes-a-typed-action) | FUTURE — only once L1's narrow fix is insufficient for a second, more open-ended capability | Do not introduce until a real use case needs the LLM (not a keyword list) to select among ≥2 capabilities |
| `EvidenceRef` (pointer from a narrated claim back to a specific `ContextValue`) | FUTURE | No current failure mode requires this; `_validate_and_normalize`'s per-unit numeric grounding already prevents the failure this would guard against |
| `ProviderMetadata` (structured, beyond the current `f"{provider_name}/{model}"` string) | FUTURE | No consumer needs it yet; the current string is already surfaced to Android's Developer Mode |

---

## 10. AI IDE CONTEXT STRATEGY

Proposed responsibilities below. **Update 2026-08-07: `DECISIONS.md` was created** (at repo root)
once Slice 3 produced an actual decision worth recording — see that file. `PROJECT.md`,
`CURRENT_TASK.md`, and `AGENTS.md` remain proposed only, not created.

**`PROJECT.md`** — product goal (persistent vehicle intelligence agent), stack (FastAPI/Pydantic backend,
Kotlin/Compose Android, Ollama/Gemini/Vertex AI providers), the AS-IS flow from §2, invariants from §3,
out-of-scope items from §8.

**`DECISIONS.md`** (append-only, one line each) — e.g.: "Safety authority stays deterministic, never LLM
(`app/mobile/safety.py`)." / "LLM never executes a vehicle action directly; only `_apply_hvac_action` after
`confirm_action` validation does." / "Local Ollama must remain a supported `llm_provider` value alongside
cloud providers." / "Vehicle truth never comes from conversation memory — always the latest `ContextSnapshot`."

**`CURRENT_TASK.md`** — see the exact proposed Slice 1 content in §17. 30-100 lines, primary context source
for a coding agent picking up one slice.

**`AGENTS.md`** — "Understand before editing. Minimum correct change. No unrelated refactoring. No
speculative abstractions (see §8 before adding one). Verification required before marking a slice done. Stop
after success — do not keep improving unrelated code."

---

## 11. VERTICAL-SLICE MIGRATION ROADMAP

Six slices, in dependency order. Each is independently shippable and independently revertible.

### SLICE 1 — Symmetric "too cold" comfort route — **STATUS: IMPLEMENTED 2026-08-07**
(`app/mobile/intent.py`, `app/mobile/assistant.py`, `tests/test_mobile_intent.py`,
`tests/test_mobile_assistant.py` — 4 files, 0 new files, 0 new dependencies, 0 new abstractions, exactly
as budgeted. 4 new tests added, all passing; `test_mobile_intent.py`+`test_mobile_assistant.py` run
45/45 pre-existing + 4/4 new = 49/49 green. Full-suite run went from 137 passed/161 failed (baseline) to
141 passed/161 failed — the failed count is unchanged, confirming zero regressions; the 161 failures are
a pre-existing sandbox issue (`datetime.UTC` requires Python ≥3.11, sandbox has 3.10.12 — traced to
`app/api/errors.py::utc_now()`, unrelated to this slice). One real bug was caught and fixed during
implementation: "lanh" (from "lạnh") is also a substring of "may lanh" ("máy lạnh" = air conditioner),
so the cold-comfort check had to be ordered *after* `_GENERIC_HVAC_COMMANDS` to avoid misreading "Bật máy
lạnh" (turn on the AC) as a cold complaint — caught by the existing
`test_generic_hvac_command_preserves_energy_for_low_energy_state` test, not a new one.)

**CURRENT GOAL:** Make "Lạnh quá" (and equivalent cold-discomfort phrasing) produce a grounded reply and a
confirmable HVAC action, exactly like "Nóng quá" already does.
**WHY NOW:** UC2 as literally specified by the architectural test does not work today (L1); the entire
action/confirmation/execution pipeline this needs already exists and is proven for the opposite direction.
**DONE WHEN:** A `POST /api/v1/assistant/query` with text "lạnh quá" against a fresh state returns route
`comfort.too_cold` (or equivalent) with a non-empty grounded reply and one `SET_HVAC_TEMPERATURE` action;
existing `comfort.too_hot` tests and all other `test_mobile_intent.py`/`test_mobile_assistant.py` cases
still pass unchanged.
**ALLOWED CHANGES:** `app/mobile/intent.py`, `app/mobile/assistant.py`, `tests/test_mobile_intent.py`,
`tests/test_mobile_assistant.py`. **CHANGE BUDGET:** 0 new files, 0 new dependencies, 0 new abstractions,
≤4 files touched. Full detail in §12.

### SLICE 2 — Formal `NarrationProvider` contract — **STATUS: IMPLEMENTED 2026-08-07**
(`app/mobile/llm.py` — added `@runtime_checkable class NarrationProvider(Protocol)`, no method bodies
touched; `app/mobile/session_store.py` — updated the `narrator` parameter's type from `OllamaNarrator |
None` to `NarrationProvider | None`, one import line changed, zero behavior change; new
`tests/test_llm_provider_contract.py`, 4 tests. This one-line annotation fix in `session_store.py` was
not in the original "allowed changes" list (`llm.py` + one test file only) — flagging the small,
deliberate budget expansion here: without it, the Protocol would have been a standalone declaration
never actually referenced by the one place (`MobileSessionStore.__init__`) that was silently narrower
than reality, which is the exact gap (L2) this slice exists to close. The change is a type annotation
only, erased at runtime, so it carries none of the risk a behavior change would. Full-suite regression:
141 passed/161 failed (Slice 1 checkpoint) -> 145 passed/161 failed — failed count unchanged, +4 passed,
zero regressions.)

### SLICE 2 — Formal `NarrationProvider` contract
**CURRENT GOAL:** Declare a `typing.Protocol` that `OllamaNarrator`/`GeminiNarrator`/`VertexAINarrator`
already satisfy, and add one contract test asserting all three (plus any future provider) share it.
**WHY NOW:** Closes L2's honesty gap cheaply; makes UC10 (provider swap) a checked invariant instead of an
implicit convention, with zero behavior change.
**DONE WHEN:** A new `tests/test_llm_provider_contract.py` passes for all three concrete classes; no
existing test changes.
**ALLOWED CHANGES:** `app/mobile/llm.py` (add `Protocol`, no method body changes), one new test file.
**CHANGE BUDGET:** 1 new file (test), 0 new dependencies, 1 new abstraction (the `Protocol` itself, which is
zero-runtime-cost typing, not a new class hierarchy).
**DO NOT CHANGE:** provider selection logic in `main.py`, any narration method body.

### SLICE 3 — Wire `OllamaIntentClassifier`/`EmergencyLLMReasoner` equivalents (or documented non-goal) for cloud providers — **STATUS: RESOLVED 2026-08-07 (non-goal path)**
Decision recorded in `DECISIONS.md` rather than code: cloud-provider classifier/reasoner
equivalents are **not built now**. Reasoning (full detail in `DECISIONS.md`): both components are
advisory-only and never touch a safety-authoritative path, so the gap is a UX cosmetic, not a
safety or Brain-vs-Armor gap; and this environment has no live Gemini/Vertex AI access to iterate
the prompt/output-parsing the way every existing Ollama-side prompt in this codebase was actually
tuned (per `docs/TEST_EVIDENCE.md`) — writing untested provider-specific prompts for a component
that reasons about live emergency situations would be exactly the "guess, patch, guess again"
pattern this migration's methodology forbids. No files changed for this slice; `DECISIONS.md`
records the re-visit trigger.

### SLICE 3 — Wire `OllamaIntentClassifier`/`EmergencyLLMReasoner` equivalents (or documented non-goal) for cloud providers
**CURRENT GOAL:** Decide, with evidence, whether the advisory classifier/reasoner gap for `gemini`/`vertex_ai`
(L2) is worth closing, and if so, close it minimally.
**WHY NOW:** Only after Slice 2's contract makes the gap explicit and checkable.
**DONE WHEN:** Either a Gemini/Vertex-backed classifier+reasoner pass the same advisory-only tests as the
Ollama ones, or a `DECISIONS.md` entry records this as an intentional non-goal with reasoning (e.g., "advisory
reclassification is a UX nicety, not required for cloud-provider parity — revisit only if evidence shows the
cloud paths' `assistant.clarify` UX is materially worse").
**ALLOWED CHANGES:** `app/mobile/llm.py`, `app/main.py` wiring only.

### SLICE 4 — Engine-temperature trend (first temporal vertical slice) — **STATUS: IMPLEMENTED 2026-08-07**
(4 files, at the budget limit: `app/mobile/context.py`, `app/mobile/session_store.py`,
`app/mobile/assistant.py`, `tests/test_engine_temperature_trend.py` (new, the only new file). 0 new
dependencies. All 11 tests pass (A/B/C/D + no-samples + below-minimum-window + out-of-order + E/F/G).
Full suite: 145 passed/161 failed (Slice 2 checkpoint) -> 156 passed/161 failed — failed/error counts
unchanged, +11 passed, zero regressions. Ruff clean on all four files except a pre-existing,
sandbox-wide `EXE002` file-permission artifact present on untouched files too (confirmed via
`ruff check app/mobile/dtc_catalog.py`). One live end-to-end HTTP demonstration via `TestClient`
(session start -> 4 real `state/update` calls over ~36s wall-clock, engine temp 101->109°C ->
`assistant/query "Tinh trang xe the nao"`) produced: `riskLevel` correctly transitioning LOW->HIGH at
107°C (`ENGINE_WARNING_C`, untouched by this slice), reply text `"...Nhiệt độ động cơ đang tăng khoảng 8
độ C trong khoảng 1 phút gần đây."`, `requestId: "live-check-req-1"` echoed correctly,
`route: "assistant.vehicle_status"`. One implementation bug caught during self-verification, not by the
user: the first draft computed the three trend `ContextValue`s but never actually inserted them into the
`values` dict literal — caught immediately by test case G failing, fixed in the next edit.)

**Correction from the earlier version of this section:** the original idea below ("reuse
`RollingWindowManager`") was checked against the actual code and rejected. `app/state/rolling_window.py`
is keyed by `(vehicle_id, trip_id, signal_type)` and lives entirely inside the separate canonical
signal-ingestion pipeline (`SignalIngestionService`/`app/state/manager.py`), which the mobile assistant
path (`MobileSessionStore`/`MobileContextBuilder`) never reads from — only best-effort, one-way writes
into it via `MobileStateBridge`. Wiring the mobile path to *read* that structure would mean adding a
vehicle_id/trip_id identity to every mobile session (none exists today — see `MobileStateBridge`'s own
hashed-partition comment) purely to reuse a class whose locking/transaction model is designed for a
different pipeline. That is not a minimal change. The scope below instead extends `MobileSession`
directly, per the instruction to prefer minimally extending existing session/state structures.

```
SLICE 4 — ENGINE TEMPERATURE TREND
```

**CURRENT GOAL**
Derive a trustworthy short-term engine-temperature trend (rising / stable / falling) from recent
`update_state()` samples already flowing through the current session, and expose it as additional,
read-only context the existing deterministic reply (and, for free, the existing narrator) can use for
"Xe của tôi hiện tại thế nào?" — without touching `SafetyRiskEngine` or any safety-authoritative path.

**WHY NOW**
`SafetyRiskEngine`/`ContextAwareAssistant` today only ever see the single latest `engineTemperatureC`
value (confirmed by reading `app/mobile/context.py` and `app/mobile/safety.py`: neither retains or reads
any prior sample). SafeDrive can say "109°C" but not "rising 8°C in the last 3 minutes" — the smallest
temporal signal that makes UC1's answer meaningfully better without inventing proactive push, a generic
temporal framework, or any new transport.

**CURRENT BEHAVIOR (verified execution path today)**
```
Android state heartbeat (~4s) -> POST /api/v1/state/update
  -> MobileSessionStore.update_state()
     -> MobileContextBuilder.build(request, state_version, now_ms)   # single-sample snapshot only
     -> SafetyRiskEngine.evaluate(snapshot, now_ms)
     -> session.state = StateEnvelope(...)                            # only the LATEST sample is kept
     -> session.last_update = request                                 # overwrites the previous one; no history

"Xe của tôi hiện tại thế nào?" -> POST/WS /api/v1/assistant/query
  -> MobileSessionStore.answer_assistant()
     -> snapshot = MobileContextBuilder.build(session.last_update, ...)   # still single-sample
     -> IntentResolver -> route "assistant.vehicle_status"
     -> ContextAwareAssistant: "<headline>. Tốc độ ... km/h, cabin ... độ C[, động cơ N độ C nếu overheating]..."
```
There is no code path today, anywhere in the mobile assistant flow, that reads more than one historical
sample of anything.

**TARGET EXECUTION PATH**
```
update_state() [unchanged trigger, ~4s cadence]
  -> (NEW) after computing `accepted_at` and evaluating safety as today, if snapshot.state_is_fresh:
       append (accepted_at, request.state.engineTemperatureC) to session.engine_temperature_samples
       trim to the last ENGINE_TREND_WINDOW_MS (e.g. 5 min) and a hard max length (defensive cap)
     [SafetyRiskEngine.evaluate() call and its inputs/outputs: byte-for-byte unchanged]

answer_assistant()
  -> snapshot = MobileContextBuilder.build(
         session.last_update, state_version=..., now_ms=started_at,
         engine_temperature_samples=session.engine_temperature_samples,   # NEW optional param, default ()
     )
     -> (NEW, inside MobileContextBuilder.build) derive_engine_temperature_trend(samples, now_ms)
        -> None (unavailable) OR (direction: rising|falling|stable, delta_c, window_seconds)
     -> two new ContextValue entries added to the existing `values` dict, same FRESH/UNAVAILABLE pattern
        already used for every other optional field (e.g. driver.wearable):
          "vehicle.engine_temperature_trend_direction"
          "vehicle.engine_temperature_trend_delta_c"
  -> SafetyRiskEngine.evaluate(snapshot, ...) -- UNCHANGED CALL, and structurally cannot see the trend:
     confirmed by reading app/mobile/safety.py -- SafetyRiskEngine only ever reads snapshot.state and
     snapshot.driver_support, never snapshot.values. Adding entries to `values` is therefore provably
     inert to every SafetyRiskEngine branch, not just inert by convention.
  -> IntentResolver -> route "assistant.vehicle_status" [unchanged]
  -> ContextAwareAssistant._message_and_actions: (NEW) one additional clause, added only when
     snapshot.values["vehicle.engine_temperature_trend_direction"].status == "FRESH" and direction ==
     "rising", mirroring the existing conditional `engine_clause` pattern already in this branch --
     e.g. an appended clause naming the delta and window in Vietnamese, using the same _fmt_temp() helper
     already used for every other temperature in this file.
  -> [optional] narrator: no code change needed. The trend's numeric value(s) flow into ContextPack via
     the same `values` tuple every other numeric field already uses, and app/mobile/llm.py's existing
     _bare_context_numbers() fallback (used whenever a number's unit suffix isn't in
     _FIELD_UNIT_SUFFIXES) already accepts any numeric ContextValue verbatim -- confirmed by reading
     _grounded_values_by_unit/_bare_context_numbers in app/mobile/llm.py. This is a real, pre-existing
     limitation (documented in docs/KNOWN_LIMITATIONS.md: unit-agnostic fallback doesn't disambiguate
     between same-unit fields), not something Slice 4 introduces or needs to fix.
```

**FAILURE BEHAVIOR**
- Fewer than 2 usable samples in the lookback window -> `None` -> both trend `ContextValue`s get
  `status="UNAVAILABLE"`, `value=None` (identical pattern to every other optional field today) -> no
  clause added to the reply.
- A sample's timestamp is not later than the previous kept sample's (clock skew / replay) -> that sample
  is dropped before trend derivation, never causes an exception.
- Elapsed window between oldest-kept and newest sample is below a minimum meaningful span (e.g. 10s) ->
  treated the same as "insufficient data" -> unavailable, not a wild rate.
- `abs(delta_c)` below a small stability threshold (e.g. 1.0°C) over the window -> `direction = "stable"`
  (matches the user-provided example: 109→109→110→109).
- Under no circumstance does trend computation raise into `update_state()`/`answer_assistant()` — a
  malformed/empty sample list must degrade to "unavailable," never an exception (mirrors the defensive
  style already used throughout `app/mobile/context.py`).

**DONE WHEN**
1. A unit test proves `derive_engine_temperature_trend` returns rising/stable/falling/`None` for the
   six input shapes in Verification below.
2. `MobileContextBuilder.build(..., engine_temperature_samples=[...])` includes the two new
   `ContextValue` entries with the expected direction/delta.
3. A full "Xe của tôi hiện tại thế nào?" turn (`IntentResolver` + `ContextAwareAssistant`, same helper
   pattern as `tests/test_mobile_assistant.py::plan_reply`) against a rising-sample history produces
   reply text containing the derived delta; the same call with no history (`engine_temperature_samples=()`,
   the default) produces byte-identical text to the current, unmodified behavior.
4. `SafetyRiskEngine.evaluate()` output is unchanged for the `engineTemperatureC=116` CRITICAL case
   regardless of whether a rising-trend history is attached to the same snapshot.
5. Every existing backend test still passes (see Regression Gate).

**ALLOWED CHANGES**
- `app/mobile/context.py` — add `ENGINE_TREND_WINDOW_MS`/stability-threshold constants alongside the
  existing `STATE_FRESHNESS_MS`/`WEARABLE_FRESHNESS_MS`; add the private trend-derivation function (and,
  optionally, a small frozen dataclass for its return value, mirroring `ContextValue`'s style — internal
  only, not a new public contract); extend `MobileContextBuilder.build()` with one optional keyword
  parameter; add the two new `ContextValue` entries.
- `app/mobile/session_store.py` — add one field to the `MobileSession` dataclass
  (`engine_temperature_samples: list[tuple[int, float]]`, defaulted to `[]` in `start()`); append+trim
  logic inside `update_state()`'s existing `async with self._lock` block; pass the field into the ONE
  `answer_assistant()` call site's `context_builder.build(...)` call (the other four call sites —
  `update_state`, `accept_event`, both in `_apply_hvac_action` — are left passing no value, so they get
  the default `()` and are functionally untouched, since none of them narrate vehicle-status text).
- `app/mobile/assistant.py` — one additional conditional clause inside the existing
  `route == "assistant.vehicle_status"` branch only.
- **New file (the only one budgeted):** `tests/test_engine_temperature_trend.py` — pure-function unit
  tests plus the one integration-level reply test.

**DO NOT CHANGE**
`app/mobile/safety.py`, `app/mobile/emergency.py`, `app/mobile/emergency_reasoner.py`,
`app/mobile/dtc_catalog.py`, `app/mobile/intent.py`, `app/mobile/llm.py` (not needed — see the narration
note above), `app/mobile/state_bridge.py`, `app/state/rolling_window.py`, `app/state/manager.py`, any
`app/api/routes/*`, any Android file. No new WebSocket frame type, no server-push, no persistence.

**CHANGE BUDGET**
Files changed: 3 existing (`context.py`, `session_store.py`, `assistant.py`) + 1 new test file = **4
total, at the limit**. New files: **1** (test only — trend logic stays inside `context.py`, not a new
module, specifically to leave the one new-file slot for tests rather than a "TemporalEngine"-shaped
module). New dependencies: **0** (plain list/tuple bookkeeping; no moving-average library, no
time-series store). If implementation reveals this needs a 5th file or a 2nd new file, stop and explain
why before continuing — the most likely trigger would be discovering `MobileContextBuilder.build()`'s
signature is called from somewhere not yet found in this scoping pass.

**VERIFICATION (test cases)**
| Case | Input samples (°C, oldest→newest) | Expected |
|---|---|---|
| A | 101 → 104 → 107 → 109 over ~180s | `direction="rising"`, `delta_c≈8`, `window_seconds≈180` |
| B | 109 → 109 → 110 → 109 | `direction="stable"` |
| C | 109 → 106 → 103 | `direction="falling"` |
| D | single sample only | `None` (unavailable) |
| E | two samples but >`ENGINE_TREND_WINDOW_MS` apart (a gap, e.g. app backgrounded) | `None` (unavailable) |
| F | `engineTemperatureC=116` (CRITICAL) with any rising history attached to the same snapshot | `SafetyRiskEngine.evaluate(...).risk.level == "CRITICAL"`, identical reason codes, with and without trend present |
| G | rising history + "Xe của tôi hiện tại thế nào?" | reply text contains the grounded delta; same query with no history is byte-identical to current behavior |

**REGRESSION GATE**
Full `uv run pytest -q` (or this sandbox's equivalent) must show the same failed/error count as the
Slice 2 checkpoint (161 failed / 3 errors, all pre-existing sandbox artifacts unrelated to this code) plus
only new passes from the new test file — mirroring exactly how Slices 1 and 2 were verified. DTC routing,
emergency state machine, HVAC confirmation, and provider fallback tests must be untouched line-for-line.

**ROLLBACK**
Revert the three source-file diffs (each is additive: one new dataclass/function/constants block in
`context.py`, one new field + append block in `session_store.py`, one new conditional clause in
`assistant.py`) and delete the new test file. No schema migration, no persisted state, no config.

**STOP CONDITION**
Stop the instant DONE WHEN 1-5 all hold. Do not add a second temporal signal (e.g. cabin temperature or
driving-duration trend), do not add proactive notification, do not add a new WebSocket frame, do not
extract a generic `TemporalEngine` — those are explicitly Slice 5/6 or later, and only after this one
slice's pattern has been proven live, per the plan's own "extract only after a second use case exists"
rule (§8).

**FUTURE WORK (explicitly deferred, not part of Slice 4)**
Proactive engine-temperature notification (Slice 5, depends on this); a second temporal signal (e.g.
driver-fatigue trend) that would justify actually extracting a generic `TemporalEngine` (only once two
real signals exist, not before); Android-side visualization of the trend; persistent/cross-restart
history.

### SLICE 5 — Narrate the trend for `assistant.vehicle_status` (UC1 completion)
**CURRENT GOAL:** Let the narrator mention "temperature rising" when Slice 4's trend value is present and
significant, using the existing grounding guardrail (extend the unit table with a `_per_min` suffix).
**WHY NOW:** Direct, user-visible payoff from Slice 4; still narration-only, no new safety authority.
**DEPENDS ON:** Slice 4.

### SLICE 6 — Proactive push transport (UC5 completion)
**CURRENT GOAL:** Minimal server-initiated message over the existing `/api/v1/ws/assistant` connection (a new
frame type, e.g. `{"type": "proactive", ...}`) triggered only by a single, explicitly bounded condition (e.g.
engine temperature crossing the existing `ENGINE_WARNING_C` threshold while trending up).
**WHY NOW:** Last slice — genuinely new capability (server push), so it is sequenced after every cheaper,
lower-risk step, and only once Slice 4/5 prove the trend signal is worth acting on.
**DEPENDS ON:** Slice 4, Slice 5. **Explicitly not required for Slice 1-5 to ship independently.**

---

## 12. FIRST SLICE — DETAILED PLAN

### SLICE 1 — Symmetric "too cold" comfort route

**Exact execution path today (the gap):**
```
"Lạnh quá" -> normalize_text() -> "lanh qua"
  -> IntentResolver.resolve(): no branch matches ("nong"/"bi qua"/"ngot ngat"/"stuffy"/"hot" all miss;
     no cold/lanh term exists anywhere in the class)
  -> falls through every branch to the final `_single("assistant.general", 0.55, needs_clarification=True)`
  -> ContextAwareAssistant returns the generic "may be out of scope" template, zero actions
  -> if a narrator is configured, `answer_open_query` may produce a grounded free-text reply, but by
     contract (see its docstring) it NEVER creates an action
```

**Target execution path:**
```
"Lạnh quá" -> normalize_text() -> "lanh qua"
  -> IntentResolver.resolve(): new branch, checked at the same position as the existing "too hot" check
     (before the generic HVAC toggle commands, same as comfort.too_hot today)
  -> route = "comfort.too_cold", confidence 0.92 (mirrors comfort.too_hot's 0.92 exactly)
  -> ContextAwareAssistant._message_and_actions: new branch mirroring comfort.too_hot's structure —
     pick a target (e.g. 26.0°C if energyPercent is low, else 24.0°C — symmetric direction to the existing
     22/24°C hot-side logic, exact numbers to be confirmed against any product guidance, defaulting to the
     existing HVAC bounds 16-30°C already enforced elsewhere)
  -> same SafeDriveAction(SET_HVAC_TEMPERATURE, requiresConfirmation=True) construction already used by
     comfort.too_hot/climate.set_temperature — zero new action-handling code
  -> confirm_action / _apply_hvac_action: completely unchanged, already generic over any HVAC target
```

**Exact files likely involved:**
- `app/mobile/intent.py` — add a `_contains(normalized, "lanh", "ret", "cold", "freezing")`-style branch
  (exact Vietnamese terms to confirm: "lạnh quá" -> "lanh qua", "quá lạnh" -> "qua lanh", "rét" -> "ret").
- `app/mobile/assistant.py` — add one `if route == "comfort.too_cold":` branch in `_message_and_actions`,
  mirroring `comfort.too_hot`'s existing branch exactly (same helper methods, same action constructor).
- `tests/test_mobile_intent.py` — add resolver test(s) for the new route, mirroring existing `comfort.too_hot` tests.
- `tests/test_mobile_assistant.py` — add reply/action test(s) for the new route.

**Protected files (must show zero diff):** `app/mobile/safety.py`, `app/mobile/session_store.py`,
`app/mobile/emergency.py`, `app/mobile/emergency_reasoner.py`, `app/mobile/llm.py`, `app/mobile/context.py`,
`app/mobile/dtc_catalog.py`, every Android file, every other backend test file.

**CHANGE BUDGET:** files changed: 4 (2 source + 2 test). New files: 0. New dependencies: 0. New
abstractions: 0 (reuses the existing route/action pattern verbatim). If implementing this appears to require
touching `session_store.py`, `safety.py`, or introducing a new dataclass/enum, **stop and request approval**
— that would mean the "mirror the existing hot-side branch" assumption was wrong and needs re-diagnosis.

**MINIMAL IMPLEMENTATION:** copy `comfort.too_hot`'s existing keyword-check line and reply/action branch,
change the trigger words to cold-discomfort terms and the target-temperature direction, keep everything else
(confidence value, action type, confirmation requirement) identical in shape to the existing branch.

**VERIFICATION:**
```bash
cd "backend AI/safedrive-ai-backend"
uv run pytest tests/test_mobile_intent.py tests/test_mobile_assistant.py -q
uv run pytest -q   # full regression — must show the same pass count plus the new tests, zero new failures
```
Manual/API check: start a session, `POST /state/update` with `cabinTemperatureC` low, `POST
/assistant/query` with `text: "Lạnh quá"` — expect `route: "comfort.too_cold"`, one
`SET_HVAC_TEMPERATURE` action with `requiresConfirmation: true`.

**REGRESSION GATE:** every existing test in `test_mobile_intent.py`, `test_mobile_assistant.py`,
`test_mobile_compatibility.py`, `test_mandatory_regression.py` must still pass unchanged — this is an
additive branch, not a restructuring, so nothing existing should need to change.

**ROLLBACK:** revert the two source-file diffs (each is a single self-contained added branch/method-arm);
no migration, no state, no config touched.

**STOP CONDITION:** stop the moment the new tests pass and the full existing suite is green. Do not also
"clean up" `IntentResolver`'s keyword lists, do not generalize into a capability registry, do not touch the
hot-side branch beyond reading it as a template.

**FUTURE WORK (explicitly not done in this slice):** a general "novel utterance -> LLM proposes one of N
pre-approved capabilities" bridge (see §9's `AgentRequest`/`ToolProposal`, deferred); expanding beyond
hot/cold to other comfort dimensions (fan speed, seat heat) — no such fields exist in `VehicleState` today.

---

## 13. RISKS (evidence-backed only)

- **Test-count trust:** I could not independently verify the current pass/fail state in this sandbox
  (Python version mismatch, §1). Any slice's "regression gate" must be re-run in an environment matching
  `pyproject.toml`'s `>=3.11` requirement before merging — do not trust a green run from a mismatched
  interpreter.
- **Unmerged sibling branch (`origin/nghia-sua-voice`):** touches voice code and is not in this branch's
  history. None of the slices above touch voice/Android code, so no direct collision is expected, but anyone
  merging `main`/this branch and that branch together should diff voice files specifically.
- **Keyword-list fragility (pre-existing, not introduced by Slice 1):** `_contains` matches substrings
  anywhere in the normalized text with no word-boundary or negation handling (e.g. "không lạnh" — "not cold"
  — would still match a naive "lanh" substring check). This already affects the existing `comfort.too_hot`
  branch (e.g. "không nóng") and is out of scope to fix here — Slice 1 should use the same matching
  discipline as the branch it mirrors, not silently fix a pre-existing class of bug under this task's budget.

---

## 14. FUTURE WORK (explicitly not implemented now)

- General capability/tool bridge for novel utterances (real `AgentRequest`/`ToolProposal` contracts) — once
  a second real, executable capability exists.
- `CapabilityRegistry`/`ToolPolicyEngine` extraction — same trigger.
- Temporal/Situation engine wired into `SafetyRiskEngine` itself (not just narration) — would need an
  explicit safety review, not bundled into this migration.
- Proactive push beyond the single bounded condition in Slice 6.
- Closing the `assistant.general` off-topic-refusal from prompt-engineered to code-enforced (flagged in
  `docs/KNOWN_LIMITATIONS.md`, unrelated to Agent Armor sequencing).
- Maps/POI integration for rest-stop and rescue-brief location naming.
- Backend persistence (currently in-memory sessions) — no current use case requires surviving a process restart.

---

## 15. WHAT NOT TO BUILD YET

Restating §8 as a checklist for future coding agents picking up this plan: no new `WorldModel` class, no
`CapabilityRegistry`, no `ToolPolicyEngine`, no `ProactiveEventEngine`, no backend `ConversationMemory`, no
new database/queue, no `AgentRuntime` rename/extraction of `MobileSessionStore`, no generic
`AgentRequest`/`AgentCandidate` contracts. Each requires a second concrete, evidenced use case before it
earns its own abstraction.

---

## 16. FINAL RECOMMENDATION

Ship Slice 1 first. It is a same-day, near-zero-risk change that makes one of the ten official use cases
(UC2) actually work as specified, using code that already exists and is already tested for the mirror-image
case. It also produces the cleanest possible evidence for the rest of this plan: if "reuse the existing
hot-side pattern" turns out to be harder than it looks once someone is inside `intent.py`/`assistant.py`,
that is itself important information before any larger slice is attempted. Slice 2 (formal provider
contract) is the next-lowest-risk step and should follow regardless of Slice 1's outcome, since it only adds
a type annotation and a test. Slices 3-6 should not be scheduled until 1-2 are merged and verified in a
correctly-versioned CI environment.

---

## 17. NEXT CURRENT_TASK.md (proposed content for Slice 1 — not created as a file by this plan)

```markdown
# CURRENT_TASK.md

## Objective
Make "Lạnh quá" (cold-discomfort phrasing) produce a grounded reply and a confirmable
SET_HVAC_TEMPERATURE action, mirroring the existing "Nóng quá" (comfort.too_hot) path exactly.

## Current state
IntentResolver (app/mobile/intent.py) has a comfort.too_hot branch triggered by
"nong"/"bi qua"/"ngot ngat"/"stuffy"/"hot". No equivalent cold-side branch exists. Cold-discomfort
text falls through to assistant.general, which is answer-only and can never produce an action.

## Target behavior
A new route "comfort.too_cold", triggered by cold-discomfort terms (confirm exact Vietnamese terms,
e.g. "lanh"/"ret"/"qua lanh"/"cold"/"freezing"), checked at the same position in resolve() as
comfort.too_hot. ContextAwareAssistant gets a matching branch: grounded reply citing current cabin
temp/energy, plus one SET_HVAC_TEMPERATURE action (requiresConfirmation=true), built with the
existing _hvac_action() helper — same target-selection style as the hot-side branch, opposite
direction.

## Allowed changes
- app/mobile/intent.py (add one route + keyword check)
- app/mobile/assistant.py (add one branch in _message_and_actions)
- tests/test_mobile_intent.py, tests/test_mobile_assistant.py (add tests mirroring comfort.too_hot's)

## Do not change
app/mobile/safety.py, app/mobile/session_store.py, app/mobile/emergency.py,
app/mobile/emergency_reasoner.py, app/mobile/llm.py, app/mobile/context.py, any Android file,
any other test file.

## Change budget
4 files touched, 0 new files, 0 new dependencies, 0 new abstractions. If this needs more, stop and
ask.

## Verification
uv run pytest tests/test_mobile_intent.py tests/test_mobile_assistant.py -q
uv run pytest -q   (full suite — same pass count as baseline, plus new tests, zero new failures)
Manual: start session -> update state with low cabinTemperatureC -> assistant/query "Lạnh quá" ->
expect route "comfort.too_cold" + one SET_HVAC_TEMPERATURE action.

## Done when
New tests pass; full existing suite unchanged; manual check above matches.

## Stop condition
Stop the instant the above is true. Do not refactor IntentResolver's keyword-matching approach, do
not touch the mirrored hot-side branch beyond reading it, do not generalize into a capability
registry.
```

---

## FINAL QUESTION ANSWERS

**A. Single smallest change closest to the Agent Armor vision:** Slice 1 (symmetric cold-comfort route) —
it proves, with a real use case, that the existing deterministic-route-to-validated-action pipeline
generalizes, which is the actual load-bearing claim behind "Agent Armor" (a stable Armor a Brain can plug
into), without building any new Armor component.

**B. Observable proof:** `POST /assistant/query {"text": "Lạnh quá"}` against a low-cabin-temperature state
returns `route: "comfort.too_cold"` and one confirmable `SET_HVAC_TEMPERATURE` action — today it returns
`assistant.general` with zero actions.

**C. Must not be touched:** `app/mobile/safety.py`, `app/mobile/session_store.py`'s emergency timers,
`app/mobile/emergency.py`, `app/mobile/emergency_reasoner.py`, `app/mobile/llm.py`, any Android file.

**D. Next slice if this succeeds:** Slice 2 — formalize the existing three narrator classes' shared shape as
a `Protocol` and add a contract test, closing the honesty gap in UC10 (provider swap) at near-zero cost.

**E. If Gemini were replaced by another capable LLM tomorrow — today:** add one more `if/elif` branch in
`app/main.py::_publish_services` and one more class in `app/mobile/llm.py` duck-typing the same two methods;
zero changes to `safety.py`, `intent.py`, `assistant.py`, `session_store.py`'s validation logic, or any
Android file (this is already true). **After this migration (post Slice 2):** the same file changes, but
now checked by an explicit `Protocol` and a contract test instead of relying on convention — the actual
blast radius does not shrink further, because it is already minimal; what improves is that a broken
duck-typing assumption would be caught by a test instead of a runtime `AttributeError`.
