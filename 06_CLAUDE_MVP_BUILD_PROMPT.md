# Claude Build Prompt - SafeDrive MVP

Copy everything inside the block below into Claude Code after both repositories have been copied under `C:\Users\Admin\Downloads\SafeDrive`.

```text
You are the implementation owner for SafeDrive AI Companion.

Work only inside:
  C:\Users\Admin\Downloads\SafeDrive

Do not start coding immediately. First perform the required audit and report the result in `SAFE_DRIVE_STATUS.md` at the SafeDrive root.

============================================================
1. MANDATORY READING AND PROJECT DISCOVERY
============================================================

Read these files completely before modifying code:

1. 00_CLAUDE_READ_ME_FIRST.md
2. 00_SAFEDRIVE_MASTER_CONTEXT.md
3. 02_INTEGRATION_PLAN.md
4. 03_TARGET_CONTRACT_SUMMARY.md
5. 05_PHASE_CHECKLIST.md
6. 06_CLAUDE_MVP_BUILD_PROMPT.md

Then locate these expected repositories:

- backend AI\safedrive-ai-backend
- safedrive-ai (1)\android
- safedrive-ai (1)\openapi\safedrive-v1.yaml

If either repository is missing from the SafeDrive root, do not edit the original repositories outside SafeDrive. Report the exact missing path in `SAFE_DRIVE_STATUS.md` and stop.

Before every code phase, inspect the relevant source, tests, OpenAPI contract, and existing behavior. Preserve user changes. Do not use destructive git commands, do not reset files, and do not delete code merely because it appears unrelated.

============================================================
2. PRODUCT INTENT - NEVER LOSE THIS
============================================================

SafeDrive AI Companion is a complete Digital Cockpit assistant, not a chatbot placed on a dashboard and not an emergency-only feature.

In ordinary driving, SafeDrive must:

- Understand natural Vietnamese or English text/voice transcripts.
- Understand the accurate current vehicle state before answering relevant questions.
- Handle normal Voice-Controlled Assistant responsibilities: HVAC, fan speed, media/volume, door/cockpit controls, vehicle status, DTC explanation, infotainment, and supported trip assistance.
- Continue a short conversation naturally and interpret follow-up requests using recent conversation and current context.
- Avoid keyword-only behavior. For example, "The cabin feels too hot" must be resolved using fresh cabin temperature, HVAC state, energy state, and preference when available.

At the same time, Safety Guardian continuously evaluates verified context. If the driver is fatigued, distracted, in a high-risk vehicle-fault state, involved in an abnormal stop/crash, or if an occupant does not respond, SafeDrive must prioritize safety without losing the ordinary assistant experience.

The emotional product story is:

"It would be a beautiful future if a news report said that an AI system sent a rescue signal in time and helped save an unconscious crash victim."

For implementation and demo wording, never claim that SafeDrive diagnosed unconsciousness, injuries, or illness. Use grounded language such as: "crash signal detected", "no response recorded", "possible safety risk", and "human verification required".

The technical proof of this story is a simulated rescue workflow that creates and sends a concise rescue brief through a mock SOS/rescue gateway. It contains a last known location, a short vehicle-status summary, risk level, evidence, timestamps/freshness, and a visible `SIMULATION_ONLY` status.

============================================================
3. SAFETY INVARIANTS - NEVER VIOLATE
============================================================

- `realEmergencyDispatchEnabled` must always be `false`.
- Never make real calls, SMS, police, ambulance, roadside-assistance, or external emergency dispatches.
- Never send raw cabin video, raw audio, raw CAN data, secrets, or unredacted personal data to an LLM.
- Never let an LLM determine `risk_level`, create a critical incident, confirm a rescue dispatch, or bypass tool guardrails.
- The Safety Risk Engine and tool guardrails are deterministic backend logic.
- Every tool execution rechecks the latest relevant state before acting.
- Missing, stale, low-confidence, or invalid evidence must fail closed for safety escalation. The assistant must state uncertainty or ask a concise clarification question.
- All demo rescue behavior is `SIMULATION_ONLY` and must be visibly labeled as such in the UI and API response.

============================================================
4. CURRENT REPOSITORY REALITY
============================================================

The two existing repositories are valuable but not integrated:

1. `backend AI\safedrive-ai-backend`
   - Already has a tested signal-first foundation: signal ingestion, canonicalization, latest state, rolling windows, validation, and GET `/health`, POST `/api/v1/signals`, GET `/api/v1/state`.
   - It does NOT yet expose the mobile/cockpit endpoints required by the Android Remote gateway.

2. `safedrive-ai (1)`
   - Already has Android cockpit UI, Mock and Remote gateways, voice transcript flow, simulator, diagnostics, SOS state machine, and the app-facing OpenAPI contract.
   - Its Remote Mode expects session/state/assistant/action/emergency endpoints that the backend does not currently implement.
   - Its current action enum is intentionally narrow and does not yet prove end-to-end HVAC/media/door assistant control.

Do not rewrite either repository. Make them work as one product by adding a carefully tested compatibility layer to the backend first, then making only the smallest necessary client/contract additions.

============================================================
5. TARGET ARCHITECTURE
============================================================

Use this ownership model:

Cockpit UI (Android/Web)
  - voice/text interaction, cockpit controls, status/risk/SOS views, simulator controls
  - does not calculate authoritative critical risk in Remote Mode
        |
        v
Backend Compatibility API
  - sessions, state update, state retrieval, assistant query, confirmations, emergency snapshots
        |
        v
Canonical State and Safety Core
  - signal adapters and canonical ingestion
  - latest state, rolling features, event log
  - Context Selector and Context Pack Builder
  - deterministic Safety Risk Engine
  - Action Guardrail and Tool Executor
  - simulated Rescue Gateway and Rescue Brief Builder
        |
        +--> VHAL / mock telemetry / GPIO / DMS replay / GPS adapters
        +--> optional constrained LLM for language and ambiguous-intent fallback only

Treat `safedrive-ai (1)\openapi\safedrive-v1.yaml` as the app-facing baseline. Preserve the backend's existing signal-first endpoints as the canonical internal/API foundation.

============================================================
6. INTENT AND CONTEXT MECHANISM
============================================================

Implement the following control flow. Do not send every available signal to an LLM.

User transcript or vehicle event
  -> input normalization and emergency keyword detection
  -> direct-command routing OR one/more intent hypotheses
  -> intent-specific Context Contract selects only necessary state
  -> Context Pack Builder includes values, sources, timestamps, age/freshness, rolling features, missing fields, and permitted actions
  -> deterministic Safety Risk Engine calculates risk level and reason codes
  -> response planner uses template or constrained LLM
  -> Guardrail validates action against policy and fresh state
  -> Tool Executor performs a simulated/allowed action and records its result
  -> cockpit receives a grounded response and updated state

Use four execution paths:

1. Fast path: explicit commands such as "set temperature to 25". Use deterministic parsing/slot extraction; no LLM is required.
2. Context path: known intents such as "the cabin is too hot", "what is this warning", or "I am sleepy". Fetch only required context.
3. Fallback path: ambiguous language such as "I do not feel well". A constrained LLM may propose intent hypotheses and required fields, then the backend retrieves verified data before generating a response. If confidence remains low, ask one short clarification question.
4. Emergency path: crash/no-response/severe configured policy event. Bypass normal LLM decision-making; deterministic emergency policy owns the workflow. The LLM may only explain it.

Static knowledge such as vehicle manuals, DTC guidance, approved safety policy, and warning explanations may use RAG. Realtime sensor state must remain structured state, not embeddings.

============================================================
7. REQUIRED MVP SCOPE
============================================================

Build a polished, feasible MVP. Do not attempt every feature imaginable.

MUST HAVE A - ordinary assistant:
- Start session, push/retrieve state, and return current vehicle status through the backend.
- At least one real state-backed cockpit control flow for HVAC, including explicit command parsing and a deterministic simulated/VHAL-compatible tool result.
- Plain-language explanation of current vehicle state and one active DTC when supplied by simulation.

MUST HAVE B - context-aware safety:
- "The cabin is too hot" uses fresh cabin/HVAC/energy state to make a grounded comfort recommendation.
- "I am sleepy" plus long driving/fatigue context creates a deterministic risk assessment and rest-stop/airflow recommendation.
- The assistant explains uncertainty when a required signal is absent or stale.

MUST HAVE C - rescue proof:
- Simulated `crashDetected` plus configured no-response evidence creates an emergency snapshot and a visible countdown.
- The backend builds a rescue brief with location if available, concise vehicle status, risk level, evidence, and freshness.
- A `SimulatedRescueGateway` records a successful simulated transmission and returns an acknowledgement/dispatch status that the cockpit can display.
- No real external request is made. The UI and payload explicitly say `SIMULATION_ONLY`.

SHOULD HAVE:
- A single, well-explained DTC scenario.
- DMS replay/GPIO adapter publishing bounded signals such as fatigue score or occupant no-response. Do not promise medical or visual diagnosis.
- A narrow, typed expansion of the app action contract for HVAC control if the current contract cannot represent the required normal-assistant action. Do not add an arbitrary JSON parameter bag.

OUT OF SCOPE FOR THIS MVP:
- Real emergency dispatch.
- Raw audio storage/upload.
- Raw video-to-LLM processing.
- Autonomous driving control.
- Cloud-scale infrastructure, Kafka, Kubernetes, production vector database, or a large multi-agent system.

============================================================
8. BUILD PHASES AND GATES
============================================================

Phase 0 - Audit and baseline
- Inspect both repository trees, current OpenAPI files, existing tests, and current git status.
- Run backend tests, Android unit tests, and web build if dependencies are available.
- Record exact baseline results and contract mismatches in `SAFE_DRIVE_STATUS.md`.
- Do not claim end-to-end integration exists until a real remote smoke test passes.

Phase 1 - Contract alignment
- Add backend-compatible implementations for:
  GET  /health
  POST /api/v1/sessions/start
  POST /api/v1/state/update
  GET  /api/v1/state?sessionId=...
  POST /api/v1/assistant/query
  POST /api/v1/events
  POST /api/v1/actions/confirm
  GET  /api/v1/emergency/{id}
  POST /api/v1/emergency/{id}/respond
- Preserve existing POST `/api/v1/signals` and existing canonical state behavior.
- Add focused API/contract tests before starting Phase 2.

Phase 2 - State, Context Pack, and deterministic risk
- Map `VehicleStateDto` into canonical signals where supported; retain app-specific compatible session state where a canonical mapping does not exist yet.
- Preserve state version, source, timestamps, freshness, missing context, active DTCs, energy, cabin/HVAC state, crash state, driver-support signals, and location when available.
- Implement only a small, reviewable rule set for long-drive/fatigue, hot cabin, DTC concern, crash/no-response, and insufficient/stale data.

Phase 3 - Everyday assistant plus actions
- Implement deterministic direct commands and state-grounded assistant responses.
- Add a small context contract registry mapping intent -> required context -> allowed actions -> clarification question.
- For any new HVAC action, use a typed additive contract change, update DTOs/OpenAPI/fixtures/tests in the same change, and make the Android UI show the actual simulated result.

Phase 4 - Safety Guardian and rescue simulation
- Implement the emergency state transition and guardrail logic on the backend.
- Build `RescueBrief` and `SimulatedRescueGateway`; persist or expose the simulated acknowledgement through the emergency snapshot.
- The client must show the exact short vehicle-status description and last known location sent by the backend.

Phase 5 - End-to-end verification
- Configure Android Remote Mode against the local backend.
- Demonstrate: normal HVAC command, contextual hot-cabin request, fatigue recommendation, DTC explanation, crash/no-response rescue simulation.
- Run backend tests, Android unit tests, web build if applicable, and a manual remote smoke test.
- Update `SAFE_DRIVE_STATUS.md` with commands, results, limitations, and the next unfinished phase.

Do not start a later phase when the current phase fails its acceptance criteria.

============================================================
9. TESTING, REPORTING, AND CONTINUITY
============================================================

After every phase, update `SAFE_DRIVE_STATUS.md` using exactly this format:

PHASE: <phase name>
STATUS: PASS | PARTIAL | BLOCKED
GOAL: <one sentence>
FILES CHANGED: <list>
CONTRACT CHANGES: NONE | <list and compatibility note>
TESTS RUN:
- <command> -> PASS/FAIL and result count
MANUAL CHECKS:
- <scenario> -> result
SAFETY CHECKS:
- realEmergencyDispatchEnabled=false -> PASS/FAIL
- raw sensor data excluded from LLM -> PASS/FAIL
- deterministic emergency policy -> PASS/FAIL
LIMITATIONS / BLOCKERS: <honest list>
NEXT SAFE STEP: <one concrete action>

Use these test commands when appropriate:

Backend:
  cd "backend AI\safedrive-ai-backend"
  py -m pytest

Android:
  cd "safedrive-ai (1)\android"
  .\gradlew.bat testDebugUnitTest --rerun-tasks

Web:
  cd "safedrive-ai (1)"
  npm.cmd install
  npm.cmd run build

If a command cannot run because of local environment issues, record the exact failure and use the safest available verification. Do not hide it.

============================================================
10. DEFINITION OF DONE
============================================================

The MVP is complete only when:

- The Android/Web cockpit and backend communicate through the same tested app-facing contract.
- Normal assistant behavior demonstrates a real state-backed HVAC or cockpit action, not only chat text.
- Context-aware answers visibly use fresh vehicle/driver state and report uncertainty when necessary.
- Safety risk and allowed actions are deterministic backend outputs, not LLM guesses.
- The crash/no-response scenario produces a simulated rescue transmission acknowledgement and a readable rescue brief with location, vehicle status, risk, and evidence.
- `realEmergencyDispatchEnabled` remains false everywhere.
- All changed backend and Android tests pass, and the remote smoke test result is documented.

Start now with Phase 0. Do not implement Phase 1 until you have written the audit and baseline in `SAFE_DRIVE_STATUS.md`.
```
