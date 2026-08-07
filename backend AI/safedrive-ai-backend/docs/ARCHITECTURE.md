# Architecture

```
Android (Kotlin/Compose)
  text or voice input
        │  Retrofit, /api/v1/*
        ▼
Backend (FastAPI)
  MobileSessionStore.answer_assistant
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
- WebSocket push (`/api/v1/ws/cockpit`) — polling is used instead.
- Camera/DMS production pipeline, multi-agent orchestration, RAG — out of scope by design for this
  MVP; see `docs/KNOWN_LIMITATIONS.md`.
