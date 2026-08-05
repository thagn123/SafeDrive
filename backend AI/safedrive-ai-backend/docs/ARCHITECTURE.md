# Architecture

```
Android (Kotlin/Compose)
  text or voice input
        │  RemoteSafeDriveGateway.queryAssistant()
        │  → WebSocket /api/v1/ws/assistant (heartbeats while narrating)
        │  → other calls: Retrofit HTTP, /api/v1/*
        ▼
Backend (FastAPI)
  app/api/routes/assistant_ws.py         ← thin transport adapter, no business logic
        │  (session gate via MobileSessionStore.validate_session, heartbeat every ~2s)
        ▼
  MobileSessionStore.answer_assistant     ← identical call REST used, unchanged
        │
        ▼
  ContextAwareAssistant + IntentResolver   ← deterministic route selection
        │
        ▼
  SafetyRiskEngine (app/mobile/safety.py)  ← risk level, no LLM, no tool execution
        │
        ▼
  MobileContextBuilder → ContextPack        ← bounded, freshness-tagged vehicle facts
        │
        ▼
  OllamaNarrator (app/mobile/llm.py)        ← rewords/answers when risk is LOW/MEDIUM
        │  (never HIGH/CRITICAL, never safety.emergency_request)
        ▼
  grounding/guardrail (number/language/length checks, reject → deterministic fallback)
        │
        ▼
  AssistantQueryResponse (risk, actions, llmUsed, fallback, fallbackReason)
        │
        ▼
Android renders + speaks (TTS only when speak=true)
```

**Safety decides when to warn. The LLM decides only how to explain it naturally.** Concretely:
`SafetyRiskEngine.evaluate()` never imports or calls the LLM; `MobileSessionStore._can_narrate()`
excludes every HIGH/CRITICAL risk level and any active emergency candidate from narration
entirely, so those replies are always the exact deterministic text, synchronously, before any
model call could even start. `safety.emergency_request` (the SOS-simulation offer/countdown) is
additionally excluded regardless of risk level — the one route-level exclusion left, since it's
the single most safety-critical reply in the system. Every other route — including ones with a
grounded vehicle fact or a confirmable action (DTC, fatigue, HVAC, status) — is narratable once
risk is LOW/MEDIUM: the deterministic system still decides and binds any action *before* the LLM
ever runs (see `MobileSessionStore.answer_assistant`), and the narrator's guardrail
(`OllamaNarrator._validate_and_normalize`, including a `required_verbatim_snippets` check for DTC
codes and safety-directive clauses the plain number check can't see) preserves those facts
verbatim — a wording rewrite carries no safety risk beyond what that guardrail already enforces.

## Assistant chat transport: WebSocket, not a new safety surface

`app/api/routes/assistant_ws.py` (`/api/v1/ws/assistant`) is a thin adapter around the exact same
`MobileSessionStore.answer_assistant(...)` the REST route calls — no new routing, risk, narration,
or guardrail logic. It exists to fix a real, measured problem: a fixed client-side HTTP read
timeout is shorter than genuine local-LLM latency (cold-start Ollama load measured ~14-16s in
manual device testing; a second LLM call for ambiguous-phrasing intent reclassification measured
~9.6-10.1s). Over the socket, the backend sends a `{"type":"heartbeat"}` frame every ~2s while a
real narration call is in flight, so the Android client can keep waiting as long as the connection
is demonstrably still alive, instead of failing at a blind fixed wall-clock guess. The client still
applies its own generous outer cap as a backstop against a genuinely hung connection — see
`docs/latency-budget.md`.

**What this does not change**: the narrator's grounding/guardrail checks
(`OllamaNarrator.rewrite_grounded_reply`) still run on the *complete* generated text before
anything is sent to the client — there is no raw token-by-token streaming of ungated LLM output.
Running those checks incrementally on partial text would be a materially larger, riskier change
and is out of scope here. `OllamaIntentClassifier`'s routing judgment (used to catch
borderline-concerning ambiguous phrasing before treating it as pure chat) is also untouched — the
heartbeat mechanism only removes the artificial timeout ceiling around it, it doesn't change what
it decides.

## DTC-code recognition is deterministic, not LLM-based

`IntentResolver` (`app/mobile/intent.py`) matches a standard 5-character OBD-II DTC shape
(`DTC_CODE_PATTERN = [PBCU][0-9A-F]{4}`, e.g. `U0100`, `P0300`, `B1234`) directly against the
driver's raw text via regex — independent of, and checked before, the existing natural-language
keyword list (`bao loi`, `ma loi`, `dtc`, ...). This means a message like "Ma U0100 nghia la gi?"
routes to `vehicle.fault_concern` even though it matches no keyword. `ContextAwareAssistant`
(`app/mobile/assistant.py`) then answers in three tiers, each with a distinct, deliberately honest
framing — never conflating "the vehicle is reporting this right now" with "this is generally what
the code means":

1. **`KNOWN_AND_ACTIVE`** — the code is present in the vehicle's own current `activeDtcs`. Live
   state always wins over the static catalog below, even if both happen to cover the code: live
   state is the stronger signal for "what's happening right now." Uses the active record's own
   trusted `title`/`severity` exactly as every other `vehicle.fault_concern` reply does —
   client-supplied `recommendation` free text is never spoken.
2. **`KNOWN_BUT_NOT_ACTIVE`** — not currently active, but `app/mobile/dtc_catalog.py` (a small,
   curated, hand-verified set of standardized SAE J2012 / ISO 15031-6 powertrain "P" and network
   "U" code meanings — deliberately excludes "B"/"C" body/chassis codes, whose meaning is largely
   manufacturer-specific) has a generic meaning for it. States that meaning **and** explicitly says
   the code is *not* currently active — never implies a live fault just because it was asked about.
3. **`UNKNOWN_TO_CATALOG`** — the token is DTC-shaped but covered by neither the active state nor
   the static catalog. Says so honestly, recommends official diagnostic documentation, never
   invents a meaning. Also surfaces any other DTC the vehicle genuinely does have active.

A token that doesn't match the DTC shape (e.g. `XYZ123`) is deliberately left off this path
entirely — see the next section for what happens to it.

## Deterministic guard against fabricated meanings for unverified technical-code tokens

Live-model audit testing found a real hallucination: asked "XYZ123 nghia la gi?" (a made-up,
non-DTC-shaped token), a real 7B model — even under an explicit system-prompt instruction not to —
fabricated a plausible-sounding "simulated fault code" explanation for it. Prompt wording alone was
judged insufficient after that finding. `MobileSessionStore._find_unverified_code_token`
(`app/mobile/session_store.py`) now deterministically intercepts this class of message **before**
`OllamaNarrator.answer_open_query` is ever called: a narrow, letters-immediately-followed-by-digits
pattern (`\b[A-Za-z]{2,5}[0-9]{2,5}\b`, deliberately excluding DTC-shaped tokens, which are already
handled by the tiered path above) checked against the driver's raw text; if the matched token
doesn't appear anywhere in the grounded `ContextPack`, the LLM is never invoked for that turn at
all — a fixed, honest "no verified data about this code" reply is returned instead. This guarantees
zero hallucination risk for this specific pattern regardless of what any future model version might
generate, rather than relying on the model reliably following instructions.

## Where the LLM is used

- Rewording an already-decided, already-safe reply into more natural Vietnamese, for any route
  once risk is LOW/MEDIUM — chit-chat (`companion.conversation`), fatigue guidance, HVAC
  confirmations, vehicle status, and DTC diagnostics (`OllamaNarrator.rewrite_grounded_reply`,
  `app/mobile/llm.py`). The reply's facts, numbers, and any action are fixed by the deterministic
  system first; the model can only change the wording, and a guardrail (number-grounding plus, for
  DTC/fatigue/status, `ContextAwareAssistant.required_narration_snippets`) rejects anything that
  drifts and falls back to the deterministic text.
- `assistant.general` — the true catch-all when nothing matched any known category — gets a
  genuinely different treatment (`OllamaNarrator.answer_open_query`): the model reads the user's
  actual message and either answers it from `ContextPack` facts if it's vehicle/trip/driving-
  related, or gives a brief, honest in-scope redirect otherwise (SafeDrive is a driving-safety
  assistant, not a general chatbot). `assistant.clarify` (genuine ambiguity among fatigue/cabin/
  vehicle-concern) stays on the reword-only path above.
- Advisory intent reclassification, scoped to `assistant.clarify` only (genuine ambiguity among
  fatigue/cabin/vehicle-concern keywords) — still only ever selects an existing template, never
  invents wording. Deliberately excludes `assistant.general`'s true catch-all: audit evidence
  showed the classifier, forced to pick from a closed label set, sometimes commits to an
  unrelated-but-plausible-looking label (e.g. `assistant.vehicle_status`) for genuinely off-topic
  text instead of admitting nothing fits, which would silently defeat `answer_open_query` above.
- An advisory second opinion on emergency candidate/escalation — never gates the state machine.

## Where the LLM is deliberately not used

- Risk-level calculation, DTC severity, engine-temperature thresholds.
- Emergency/SOS state transitions (fixed timers: 5s verify → 15s await response → 10s countdown).
- Any HIGH/CRITICAL reply, for any route.
- `safety.emergency_request` specifically — the SOS-simulation offer/countdown wording stays fully
  deterministic no matter what the risk level is, the one route-level exclusion left.
- Choosing or executing a vehicle action (`SET_HVAC_TEMPERATURE` is the only action with a real
  side effect, applied by deterministic code, never by the model).

## Components not built in this pass

- Real Maps/POI provider (narrator says "vị trí an toàn gần nhất", never a fabricated distance).
- WebSocket push for vehicle state/emergency (`/api/v1/ws/cockpit`, a *different*, still-unbuilt
  endpoint from the assistant-chat one above) — the 4s cockpit state heartbeat and emergency
  polling both stay on HTTP.
- Token-by-token streaming of narrated LLM replies (see previous section — guardrails are
  whole-string by design).
- Camera/DMS production pipeline, multi-agent orchestration, RAG — out of scope by design for this
  MVP; see `docs/KNOWN_LIMITATIONS.md`.
