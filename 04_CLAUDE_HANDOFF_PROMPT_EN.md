# Claude Handoff Prompt - English

Copy the following prompt into Claude when implementation needs to continue:

```text
You are working in C:\Users\Admin\Downloads\SafeDrive.

Before changing any code, read these files completely in this order:
1. 00_CLAUDE_READ_ME_FIRST.md
2. 00_SAFEDRIVE_MASTER_CONTEXT.md
3. 02_INTEGRATION_PLAN.md
4. 03_TARGET_CONTRACT_SUMMARY.md
5. 05_PHASE_CHECKLIST.md

Product intent to preserve:

SafeDrive AI Companion is a complete Digital Cockpit assistant, not only a chatbot and not only an emergency feature. In normal driving it must understand natural user language, know the precise current vehicle state, and complete normal Voice-Controlled Assistant tasks: HVAC, fan speed, media, volume, doors, infotainment, vehicle status, DTC explanation, and trip support. It must use current context when a request is related to the vehicle or the driver. It must not respond as a simple keyword-to-command system.

The system also includes a Safety Guardian that continuously evaluates verified context while the normal assistant is running. When there are signs of fatigue, distraction, severe DTC, abnormal stop, crash, occupant no-response, or another configured safety risk, deterministic safety policy must verify the latest context, calculate risk, limit allowed actions, and guide the user toward the safest next step.

The strongest product scenario is a crash/no-response rescue workflow: when policy identifies a critical simulated event, SafeDrive starts a simulated SOS countdown and builds a concise rescue brief. The brief includes last known location, a short vehicle-status summary, risk level, evidence, timestamps/freshness, and simulation status. It may be displayed in the cockpit or sent to a mock assistance API. It must never call a real emergency service.

Required safety constraints:
- `realEmergencyDispatchEnabled` must always be false.
- Never claim a real rescue call was made.
- Never diagnose injuries, illness, or unconsciousness.
- Never send raw video, raw audio, or raw CAN streams to an LLM.
- Safety-critical escalation is determined by deterministic policy and the Safety Risk Engine, never by an LLM alone.
- If necessary context is missing or stale, state that clearly and ask for confirmation when appropriate.

Repository ownership:
- backend AI/safedrive-ai-backend: canonical signal ingestion, state, freshness, risk policy, assistant context, action guardrails, and rescue-brief simulation.
- safedrive-ai (1): web/Android cockpit UI, voice/text experience, simulator controls, risk/SOS screens.

Integration strategy:
1. Do not rewrite the cockpit app first.
2. Treat `safedrive-ai (1)/openapi/safedrive-v1.yaml` as the app-facing target contract.
3. Add a compatibility layer in the backend that implements the mobile/cockpit endpoints while keeping current signal-first endpoints.
4. Add contract tests before adding broader features.
5. Complete the implementation one phase at a time and report what passed, what failed, and the next safe step.

Required compatibility endpoints:
- GET /health
- POST /api/v1/sessions/start
- POST /api/v1/state/update
- GET /api/v1/state?sessionId=...
- POST /api/v1/assistant/query
- POST /api/v1/events
- POST /api/v1/actions/confirm
- GET /api/v1/emergency/{id}
- POST /api/v1/emergency/{id}/respond

Acceptance criteria:
- Android and web DTOs can parse all responses.
- State update preserves source/freshness and maps compatible fields into canonical signals or compatible session state.
- Assistant responses are grounded in selected current context, with evidence or uncertainty where relevant.
- Direct HVAC/vehicle-status commands remain fast and do not require an LLM.
- Crash/no-response creates an emergency snapshot and a simulated rescue brief.
- Tests pass before the next phase begins.

Do not commit secrets. Do not alter the client contract unless the backend compatibility approach has been evaluated and documented first.
```
