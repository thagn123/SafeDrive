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
  OllamaNarrator (app/mobile/llm.py)        ← ONLY for a small allow-listed set of routes
        │  (never HIGH/CRITICAL, never DTC/fatigue/HVAC/status)
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
excludes every route with a grounded fact or confirmable action (DTC, fatigue, HVAC, status) and
every HIGH/CRITICAL risk level from narration entirely, so those replies are always the exact
deterministic text, synchronously, before any model call could even start.

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

## Where the LLM is used

- Rewording an already-decided, already-safe reply into more natural Vietnamese for
  low-stakes/no-fact routes (`companion.conversation`, `assistant.general`, `assistant.clarify`).
- Advisory intent reclassification when the deterministic keyword router genuinely cannot decide
  (still only ever selects an existing template, never invents wording).
- An advisory second opinion on emergency candidate/escalation — never gates the state machine.

## Where the LLM is deliberately not used

- Risk-level calculation, DTC severity, engine-temperature thresholds.
- Emergency/SOS state transitions (fixed timers: 5s verify → 15s await response → 10s countdown).
- Any HIGH/CRITICAL reply.
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
