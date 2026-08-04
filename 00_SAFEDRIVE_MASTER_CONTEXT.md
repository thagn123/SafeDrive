# SafeDrive AI Companion - Master Product Context

This is the source of truth for the SafeDrive product. Every developer or AI agent must read this document before changing APIs, UI flows, prompts, data models, or demo scenarios.

## 1. Product vision

**SafeDrive AI Companion is a complete Digital Cockpit assistant that understands the driver, the vehicle, and the driving situation.** It must be useful in ordinary driving, context-aware when the driver speaks naturally, and protective when there is a credible safety risk.

The product story is:

> It would be a beautiful future if a news report said that an AI system sent a rescue signal in time and helped save an unconscious crash victim.

This story is the emotional proof of SafeDrive's value. However, SafeDrive is not only an emergency feature. It is first an everyday cockpit assistant. Its ability to prepare an accurate rescue message shows why an in-vehicle AI must understand context, not merely obey isolated commands.

## 2. The problem

Modern vehicles expose many disconnected signals: speed, energy level, cabin temperature, HVAC state, warning lights, DTCs, seat occupancy, driver fatigue indicators, crash events, and GPS position. The driver is often left to interpret all of these signals and decide what to do.

Existing voice assistants usually work as command tools. They can execute "set the temperature to 25 degrees", but they often fail when the driver says:

- "I feel uncomfortable."
- "The car does not feel right."
- "I am getting sleepy."
- "Is it safe to continue driving?"
- "Can you stay with me for a while?"

Those statements are ambiguous. Their real meaning depends on the current vehicle state, cabin conditions, trip history, active faults, the driver's available safety signals, and recent events. A stressed or tired driver should not have to assemble that context manually.

**SafeDrive turns fragmented cockpit data into a grounded explanation and a safe next step.**

## 3. What the assistant must do in normal driving

SafeDrive is not a chatbot bolted onto a dashboard. In ordinary driving it must perform the responsibilities of a capable Voice-Controlled Assistant:

- Understand natural voice or text, including direct commands, follow-up requests, and conversational language.
- Control supported cockpit functions such as HVAC, fan speed, media, volume, doors, infotainment, and VHAL-backed features available on the competition platform.
- Explain the current vehicle condition in plain language, including known vehicle state, active warnings, and DTCs.
- Support trip-related requests such as rest-stop or charging suggestions, weather-aware comfort suggestions, and navigation help when corresponding data sources are available.
- Keep a short conversation history so the next question is interpreted in context.

Even in normal mode, SafeDrive must reason from the latest relevant state. For example, when the driver says "It is too hot in here", the system should look at cabin temperature, current HVAC settings, energy level, and user preference if available. It should propose an action that fits the actual condition, rather than executing a keyword mapping.

## 4. Safety Guardian: always watching, never separate from the assistant

Safety Guardian is a continuous safety layer, not a separate app that opens only after an accident. The ordinary assistant remains usable while the system evaluates fresh state and important events in the background.

When verified context suggests driver fatigue, distraction, a severe vehicle fault, abnormal stop, crash, occupant no-response, or another configured alert condition, SafeDrive must:

1. Fetch and validate the freshest relevant signals.
2. Evaluate risk through deterministic rules and confidence thresholds.
3. Explain the situation in concise, calm language.
4. Recommend or ask confirmation for allowed safety actions.
5. Start a simulated SOS workflow only when policy conditions are met.

Examples:

- The driver says "I am sleepy" after four hours of driving in a hot cabin. SafeDrive recommends a rest stop and offers to increase airflow. It never suggests that changing HVAC makes driving while sleepy safe.
- The driver asks "What is wrong with the car?" while a severe DTC is active. SafeDrive explains the known fault and gives a safety recommendation based on the vehicle's current state.
- A crash signal is followed by no response from occupants. A deterministic emergency policy starts a **simulated** SOS countdown and prepares a rescue brief. The LLM may explain this decision but does not decide whether it occurs.

## 5. Rescue workflow: the core differentiator

During an emergency, a rescue operator needs a compact, accurate, actionable briefing, not a transcript or raw sensor stream. SafeDrive must create a structured rescue brief using verified recent state:

```json
{
  "dispatchMode": "SIMULATION_ONLY",
  "eventType": "CRASH_AND_NO_RESPONSE",
  "vehicleId": "veh_demo_01",
  "timestampMs": 1720000000000,
  "lastKnownLocation": {
    "latitude": 21.0285,
    "longitude": 105.8542,
    "source": "GPS_OR_SIMULATOR",
    "ageMs": 1200
  },
  "vehicleStatusSummary": "Crash signal detected. Vehicle is stopped. An occupant has not responded to the in-cabin check.",
  "riskLevel": "CRITICAL",
  "evidence": ["crash_detected", "vehicle_stopped", "occupant_no_response"],
  "realEmergencyDispatchEnabled": false
}
```

For the hackathon, this payload is shown in a simulated SOS panel, sent to a mock roadside-assistance API, or saved to an event log. It must always visibly state `SIMULATION_ONLY`.

SafeDrive does not diagnose injuries or medical conditions. It can report a crash signal, abnormal posture or movement signal, or lack of response. It must use language such as "possible safety risk" and "requires human verification".

## 6. How SafeDrive understands user intent

SafeDrive must never pretend that one vague sentence is enough to know the driver's intent. It uses **Intent Hypothesis and Context Grounding**:

```text
User speech or text
  -> normalize input and detect direct safety terms
  -> generate one or more intent hypotheses
  -> select only the context relevant to those hypotheses
  -> assess freshness, evidence, and safety risk
  -> resolve the intent or ask one short clarification question
  -> plan a permitted response or action
  -> validate through guardrails
  -> execute a safe tool or present a recommendation
```

For "I do not feel well", SafeDrive may consider fatigue, cabin discomfort, vehicle-fault concern, and emergency concern. It retrieves only relevant state such as driving duration, fatigue indicators, cabin temperature, speed, DTC severity, and recent crash events. It then gives a grounded response or asks a focused question, for example: "Are you feeling tired, uncomfortable in the cabin, or concerned about the vehicle?"

Every context value must carry quality information: source, timestamp, age/freshness, confidence, and whether necessary data is missing. The assistant must acknowledge uncertainty rather than inventing facts.

## 7. Data and edge architecture

SafeDrive accepts any supported input through adapters, so the MVP can use simulated sources now and platform/device sources later:

- VHAL and vehicle state: speed, energy, HVAC, doors, seats, warnings, and active DTCs.
- GPIO, simulator, and event sources: crash, hard brake, abnormal stop, door/seatbelt state, and hackathon signals.
- Driver and cabin sources: user-reported fatigue, DMS replay-derived indicators, occupancy, response, posture, or motion signals.
- Location and environment: GPS, map, weather, charging/rest-stop information.
- Conversation history and optional user preferences.

Raw high-rate sources must not be sent to an LLM. The edge data layer keeps latest state in memory, bounded rolling features, and important events, then builds a compact Context Pack. A camera pipeline may publish `drowsiness_score`, `eye_closure_window`, or `occupant_no_response`; the language model must not receive raw cabin video.

The architecture is hybrid and realistic for an edge cockpit:

- **Fast path:** explicit commands use lightweight NLU/rules and deterministic tools. No LLM is required.
- **Context path:** known intents retrieve only required state and use a template or small LLM for grounded explanation.
- **Fallback path:** ambiguous language can use an LLM to propose/resolve intent hypotheses and select required context.
- **Emergency path:** deterministic policy decides risk and SOS escalation. The LLM only explains the outcome.

Qwen 3B or another small local model can be used for constrained reasoning and natural Vietnamese/English responses. A stronger provider may be an optional fallback if platform resources and privacy rules allow it. Model secrets must never be stored in the mobile client.

## 8. Non-negotiable safety boundaries

- `realEmergencyDispatchEnabled` is always `false`.
- No real call to an ambulance, police, or roadside-assistance organization is made by the MVP.
- No medical diagnosis or claim that a person is unconscious.
- No raw video, raw audio, or raw CAN stream is given to an LLM.
- Deterministic policy, allowed actions, fresh-state checks, and confirmation rules control all safety-related actions.
- A tool executor rechecks latest state before acting; an older LLM plan cannot override a newer safety condition.
- When essential evidence is missing, the assistant must say so and request confirmation when appropriate.

## 9. Implementable MVP

The MVP proves the architecture through five polished end-to-end scenarios. Do not build every possible automotive feature.

1. **Everyday assistant and precise vehicle context:** HVAC/media/vehicle-status interactions and plain-language current-state explanation.
2. **Context-aware comfort:** "The cabin is too hot" becomes a state-grounded HVAC recommendation using temperature, HVAC state, energy, and preference when available.
3. **Driver fatigue intervention:** a fatigue statement or simulated fatigue context triggers a risk explanation and rest-stop/airflow recommendation.
4. **Vehicle fault understanding:** explain an active DTC and give a safe next step based only on known data.
5. **Crash/no-response rescue simulation:** simulated crash and absent response create an SOS countdown and rescue brief containing location, vehicle status, risk, freshness, and evidence.

The MVP may use mock VHAL, GPIO/event signals, DMS replay signals, and simulator data. The adapter design must make it straightforward to replace mocks with FPT platform sources later.

## 10. Target integration architecture

```text
Cockpit UI (web / Android Automotive)
  - voice or text interaction
  - vehicle status, assistant, risk, and SOS panels
  - local simulator controls
             |
             | app-facing API contract
             v
SafeDrive Backend Compatibility Layer
  - sessions, state update, assistant query, actions, emergency snapshots
             |
             v
Canonical State and Safety Core
  - signal ingestion and adapters
  - latest state, rolling features, event log
  - context selector and Context Pack builder
  - deterministic Safety Risk Engine
  - guarded tool executor and rescue-brief builder
             |
             +--> VHAL / mock telemetry / GPIO / DMS replay / GPS adapters
             +--> optional constrained LLM for language and fallback intent routing
```

## 11. Integration decision

Two repositories must become one coherent product without a rewrite:

- `backend AI/safedrive-ai-backend` is the canonical signal/state/safety/rescue backend.
- `safedrive-ai (1)` is the web/Android cockpit experience, voice interaction, SOS screen, and simulator.

The target app-facing contract is `safedrive-ai (1)/openapi/safedrive-v1.yaml`. Add a compatibility API in the backend matching that contract while retaining existing signal-first endpoints. In remote mode, the cockpit UI must not independently calculate critical risk.

## 12. Definition of success

SafeDrive is ready for a credible hackathon demonstration when:

- The cockpit starts a session, sends state, retrieves current state, and asks the assistant through a single backend contract.
- Ordinary assistant commands and context-aware responses are visibly different and grounded in live or simulated state.
- The risk engine, not the LLM, determines fatigue/DTC/crash/no-response risk and allowed actions.
- A simulated rescue workflow produces a concise, fresh rescue brief with location, vehicle status, risk, and evidence.
- Focused backend and app-contract tests cover each core scenario.
- The UI makes the simulation boundary clear and never claims that a real emergency service was contacted.
