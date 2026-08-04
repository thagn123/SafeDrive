# SafeDrive MVP LLM Integration Plan

> **Superseded 2026-08-03 by `08_MVP_LLM_ACTIVATION_PLAN.md`.** This document's architecture (multi-turn
> conversation memory, LLM-based intent classification, Gemini/OpenAI providers, `LLMOrchestrator`) was
> never implemented. What was actually built instead — a narrower `OllamaNarrator`/`EmergencyLLMReasoner`
> that only rewrites wording for 3 safety-cleared routes, never classifies intent — is documented and
> verified against live code in `08_MVP_LLM_ACTIVATION_PLAN.md`. Kept here for historical reference only;
> do not treat the design below as current or as a to-do list without re-reading that file first.

## Executive Summary

This document defines the architectural blueprint, safety guardrails, implementation plan, and test strategy for integrating **Constrained Large Language Model (LLM)** capabilities into the SafeDrive Digital Cockpit AI Companion. 

The SafeDrive architecture combines **deterministic safety enforcement** with **LLM language understanding and multi-turn reasoning**. While deterministic logic controls critical safety risks, HVAC guardrails, and SOS simulations, an LLM handles natural Vietnamese/English query intent classification, ambiguous phrasing ("Tôi không khỏe", "Xe có sao không?"), vehicle fault/manual RAG explanations, and multi-turn conversation memory.

---

## 1. Safety Invariants & Architectural Principles

1. **Safety Risk Engine Ownership:** The LLM **NEVER** computes `risk_level`, triggers critical incidents, confirms rescue dispatches, or overrides tool guardrails. All critical risk evaluations and emergency escalations remain 100% deterministic (`app/mobile/safety.py`).
2. **Emergency Simulation Only:** `realEmergencyDispatchEnabled` is forcibly fixed to `false` at all schema, serialization, and logic layers. The LLM can only explain an ongoing emergency; it cannot alter emergency state machine logic.
3. **No Raw Sensor Data to LLM:** Raw audio streams, camera video feeds, raw CAN frames, or API keys are **NEVER** sent to the LLM. Only pre-processed, compact, structured `ContextPack` JSON payloads (with timestamp freshness, state, rolling metrics, and evidence) are passed.
4. **No Medical Diagnosis:** System prompts strictly prohibit diagnosing illness or injuries. The LLM must use grounded language such as *"tín hiệu bất thường"*, *"cần xác minh tình trạng"*, or *"nguy cơ mệt mỏi"*.
5. **Fail-Closed Graceful Degradation:** If the LLM provider times out (latency > 1500 ms), returns an error, or produces unparseable JSON, the assistant gracefully falls back to the deterministic keyword router (`IntentResolver`).

---

## 2. Target Control Flow & Execution Paths

```
User Query / Transcript + Mobile Session State
                 │
                 ▼
     [ Input Normalizer & Direct Command Detector ]
                 │
      ┌──────────┴────────────────────────┐
      │                                   │
 (Direct Command)             (Ambiguous / Conversational / Follow-up)
      │                                   │
      ▼                                   ▼
 [Fast Path: Rules]               [Context Selector & Pack Builder]
 (e.g. "Đặt 23°C")                       │
                                          ▼
                               [Deterministic Safety Engine]
                                (Risk Level, Reasons, Actions)
                                          │
                                          ▼
                                [LLM Orchestrator Engine]
                                ├── Provider 1: Local Ollama (Qwen 3B)
                                ├── Provider 2: Gemini API / OpenAI API
                                └── Fallback: Deterministic Router
                                          │
                                          ▼
                             [Output Guardrail & Schema Validator]
                                          │
                                          ▼
                             [Action Guardrail Re-check]
                                          │
                                          ▼
                               [Cockpit Response Return]
```

### Path Breakdown
- **Path 1 (Fast Path - Deterministic):** Direct HVAC imperatives (e.g., `"Đặt điều hòa 23 độ"`). Handled in ~1-2 ms via regex/rules. No LLM latency added.
- **Path 2 (Emergency Path - Deterministic):** Active crash/no-response state. Safety Guardian generates emergency message directly. LLM call bypassed.
- **Path 3 (Context & LLM Fallback Path):** Ambiguous inputs (`"Tôi không khỏe"`, `"Xe có vấn đề gì không?"`, follow-up questions `"Nó có nguy hiểm không?"`). LLM parses intent, references multi-turn history, generates grounded explanation, and selects appropriate proposed actions.

---

## 3. Core Technical Components

### Component 1: Multi-Turn Conversation Memory
- **Location:** `app/mobile/session_store.py` (`MobileSession` model).
- **Structure:** Ring buffer storing up to $N = 10$ recent turns (`ChatMessage` objects: query text, response text, timestamp, route, context summary).
- **Usage:** Injected into LLM context window to resolve relative pronouns (*"nó"*, *"cái đó"*), ellipsis (*"thế còn cái kia?"*), and multi-turn comfort adjustments.

### Component 2: Context Pack Serializer
- **Location:** `app/mobile/context.py`
- **Output:** Compact JSON representation for system prompt injection (~150-250 tokens):
```json
{
  "vehicle_state": {
    "speed_kmh": 65.0,
    "cabin_temp_c": 29.5,
    "hvac_target_c": 24.0,
    "energy_pct": 45,
    "driving_duration_min": 140
  },
  "safety_evaluation": {
    "risk_level": "WARNING",
    "reason_codes": ["FATIGUE_LONG_DRIVE", "HOT_CABIN"],
    "emergency_candidate": false
  },
  "active_dtc": [
    {"code": "P0118", "severity": "MEDIUM", "description": "Engine Coolant Temp High"}
  ],
  "allowed_actions": ["SET_HVAC_TEMPERATURE"],
  "conversation_history": [
    {"user": "Xe báo lỗi gì vậy?", "assistant": "Xe đang có mã lỗi P0118 liên quan đến nhiệt độ nước làm mát."}
  ]
}
```

### Component 3: LLM Provider Abstraction & Providers
- **Location:** `app/mobile/llm/`
- **Interface:** `BaseLLMProvider` with `async generate(prompt: str, context: ContextPack) -> LLMOutput`.
- **Implementations:**
  1. `OllamaProvider`: Connects to local Ollama instance (e.g. `qwen2.5:3b-instruct` or `phi3:mini`).
  2. `GeminiProvider`: Connects to Google Gemini API (e.g. `gemini-1.5-flash` or `gemini-2.0-flash`).
  3. `MockLLMProvider`: Instant deterministic responses for testing & offline CI environments.
- **Orchestrator:** `LLMOrchestrator` manages timeout race (default 1.5s), retry logic, and fallback to `IntentResolver`.

### Component 4: System Prompt & Structured Output
- **System Prompt Design:**
  - Standardized Persona: SafeDrive Digital Cockpit AI Companion.
  - Language: Primary Vietnamese, fallback English matching user input language.
  - Output Contract: Must produce JSON matching schema:
```json
{
  "intent_category": "COMFORT" | "SAFETY_FATIGUE" | "DTC_EXPLANATION" | "GENERAL_CLARIFY",
  "explanation_text": "Ground explanation based strictly on context pack.",
  "proposed_action": {
    "type": "SET_HVAC_TEMPERATURE",
    "target_temperature_c": 23.5
  } | null,
  "confidence": 0.95
}
```

---

## 4. Implementation Phasing & Work Breakdown

### Phase 1: Conversation Memory & Context Serializer Refactoring
- Add `conversation_history: list[ChatMessage]` to `MobileSession`.
- Update `MobileSessionStore.add_query_turn()` to maintain sliding window of turns.
- Implement `ContextPackSerializer.to_llm_json()` with token-efficient formatting.

### Phase 2: LLM Provider Layer & Config System
- Create package `app/mobile/llm/`.
- Implement `BaseLLMProvider`, `OllamaProvider`, `GeminiProvider`, `MockLLMProvider`.
- Add environment variables:
  - `LLM_PROVIDER`: `mock` | `ollama` | `gemini` | `disabled` (default: `disabled` for pure deterministic backward compatibility).
  - `OLLAMA_BASE_URL`: `http://localhost:11434`
  - `GEMINI_API_KEY`: API key string.
  - `LLM_TIMEOUT_MS`: `1500`

### Phase 3: Intent Classification & Ambiguous Router Integration
- Enhance `IntentResolver` to call `LLMOrchestrator` when deterministic regex rules yield `ambiguous` or `general` intent.
- Implement schema validator to ensure LLM returned valid JSON and valid temperature ranges (16-30°C).

### Phase 4: Assistant Orchestration & Guardrail Enforcement
- Integrate `LLMOrchestrator` into `ContextAwareAssistant.answer()`.
- Pass LLM response through `ActionGuardrail` to enforce target bounds, session state checks, and safety rules before building `AssistantQueryResponse`.

### Phase 5: Verification & Comprehensive Unit/Integration Testing
- Add backend unit test suite `tests/test_llm_integration.py`:
  - Mock LLM tests for ambiguous inputs ("Tôi không khỏe", "Nó có nguy hiểm không?").
  - LLM timeout / failure fallback to deterministic router tests.
  - Multi-turn conversation continuity test.
  - Guardrail rejection test when LLM outputs illegal action.
- Update `SAFE_DRIVE_STATUS.md` with test metrics and latency benchmarking.

---

## 5. Verification & Acceptance Criteria

1. **Backward Compatibility:** All existing 159 backend unit tests continue to PASS with 100% success rate when `LLM_PROVIDER=disabled` or `mock`.
2. **Ambiguous Resolution:** Phrasing such as `"Tôi không khỏe"` or `"Nó có nguy hiểm không?"` correctly references recent state / conversation history instead of outputting generic static fallback text.
3. **Latency Bound:** End-to-end assistant latency remains $< 1500 \text{ ms}$ for cloud LLM and $< 500 \text{ ms}$ for local LLM / mock.
4. **Safety Invariance:** `realEmergencyDispatchEnabled` remains `false` across all LLM payloads; action target temperatures outside $16-30^\circ\text{C}$ are rejected by guardrails.

---
*Created for SafeDrive Digital Cockpit AI Companion MVP Integration.*
