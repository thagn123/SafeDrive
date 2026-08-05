# Known Limitations

- **SOS is simulation-only.** `realEmergencyDispatchEnabled` is hardcoded `False` end-to-end; no real
  call, SMS, or rescue dispatch is possible in this build.
- **Maps/POI is not implemented.** The narrator only ever says "hãy dừng tại vị trí an toàn gần
  nhất" — never a distance or place name, because no map/POI provider is wired in. Confirmed by code
  (no `PlacesProvider`-shaped dependency exists) and by test coverage.
- **No state/emergency WebSocket.** `/api/v1/ws/cockpit` does not exist. State/emergency delivery is
  still client-driven polling. This was a deliberate scope decision, not an oversight — see
  `docs/API_V1_CONTRACT.md`. (A *different* WebSocket, `/api/v1/ws/assistant`, was added for the
  chat channel specifically — see below.)
- **Assistant-chat guardrails are whole-string, not streamed.** `/api/v1/ws/assistant` fixes the
  client-side timeout being shorter than real local-LLM latency (heartbeat frames instead of a fixed
  wall-clock guess), but the narrator's grounding/language/length checks still run on the *complete*
  generated text before anything is sent to the client — there is no token-by-token display of raw,
  ungated model output. Making those checks run incrementally on partial text would be a materially
  larger, riskier change and was deliberately out of scope for this pass.
- **`assistant.general`'s off-topic redirect is prompt-engineered, not code-enforced.**
  `OllamaNarrator.answer_open_query` is instructed to decline (briefly, honestly, in-scope) any
  question unrelated to driving/vehicle/safety rather than actually answering it — but the
  guardrail can only verify the *output* (language, length, no invented numbers); it cannot verify
  the model actually *declined* rather than answered. A model that ignores the instruction and
  answers general trivia anyway would still pass the guardrail as long as it invents no numbers.
  Mitigation is prompt quality and spot-checking, not a code-level check.
- **`required_narration_snippets` is a verbatim-substring check, not a meaning-preservation
  guarantee.** It closes one specific gap (a DTC code like `U0100` is invisible to the
  number-preservation regex; safety-directive clauses like the fatigue rest-stop instruction had no
  preservation check at all) for `vehicle.fault_concern`, `safety.driver_fatigue`/
  `safety.user_discomfort_check`, and abnormal `assistant.vehicle_status` replies. It does not
  guarantee the narrated text around those snippets is otherwise faithful — only that specific,
  named safety-relevant substrings survive unchanged.
- **The WebSocket transport is unit/integration-tested (backend `tests/test_assistant_ws.py`,
  Android `AssistantSocketClientTest`/`RemoteSafeDriveGatewayErrorMappingTest`), not yet
  device-verified against a real Ollama cold-start on the physical test device** — the specific
  failure this was built to fix (repeated on-device timeouts during manual acceptance testing) has
  not yet been re-run end-to-end on-device with the new code. See `docs/TEST_EVIDENCE.md` for what
  has been verified (backend WS round-trip via `TestClient`/`websockets`) versus what remains
  (on-device retry of the exact scenarios that originally failed).
- **Wake phrase ("Mai ơi") is experimental.** Logcat evidence from earlier this project shows the
  ambient listener running healthily for 10+ minutes with correct self-recovery, and it was
  reconfirmed running (SODA sessions cycling normally) during this pass. However, no human spoke the
  phrase during this specific verification run, so end-to-end wake-word success has not been
  freshly re-confirmed today. **Push-to-talk is the guaranteed demo path**; do not depend on the wake
  phrase for the judged demo.
- **On-device scenario evidence is backend-verified, not tap-verified.** The test device
  (`24129PN74G`, HyperOS/MIUI) blocks ADB input injection
  (`input tap`/`input text` → `SecurityException`, requires `INJECT_EVENTS`). This is a device
  policy restriction, not an app bug, and was already documented earlier in this project. Real,
  unprompted Android→backend traffic (session start, continuous state pushes) was captured from
  device logs; the specific scenario payloads (engine 110/116°C, DTC conflict, rest recommendation,
  crash/SOS) were verified against the exact same running backend code via direct requests rather
  than via on-device taps. **Next step to close this gap**: run the same scenarios by hand on the
  device (or an emulator/device without this restriction) following `docs/COMPETITION_DEMO.md`.
- **MIUI silently restores old app data on reinstall.** During this pass, uninstalling and
  reinstalling the APK did not fully reset `BASE_URL` to the new default — MIUI's own backup/restore
  behavior repopulated a previously-saved value from before this session. This is a device/OEM
  behavior, not an app bug; a truly clean install (or `pm clear`, which this device also blocks) on
  a non-MIUI device would show the intended fresh default.
- **Docker deployment**: built and run successfully on this machine (image builds, container passes
  its healthcheck, and a request through the container reached the real host Ollama and returned a
  grounded reply) — see `docs/TEST_EVIDENCE.md`. Not verified on any other machine/OS.
- **CarSky/VDP platform deployment: not attempted.** No platform credentials or access were
  available in this environment. Nothing about CarSky was claimed or executed.
- **Demo Mode's engine-temperature policy is a local fallback only** (`MockPolicyEvaluator.kt`,
  explicitly commented as such); the backend's `SafetyRiskEngine` (`app/mobile/safety.py`) is
  authoritative whenever Remote Mode is actually connected. The two are now numerically aligned
  (105°C/115°C) so Demo Mode never contradicts a real backend response for the same reading.
- **RESOLVED — a DTC question phrased outside the keyword list no longer bypasses
  `vehicle.fault_concern`'s guardrail, and DTC catalog knowledge is now separated from active
  vehicle state.** Previously "Ma U0100 nghia la gi?" fell to `assistant.general` since it matched
  no `vehicle.fault_concern` keyword. Fixed with a deterministic DTC-shape regex
  (`DTC_CODE_PATTERN = [PBCU][0-9A-F]{4}`, `app/mobile/intent.py`) checked independent of keywords,
  plus a three-tier answer in `ContextAwareAssistant`: `KNOWN_AND_ACTIVE` (in the vehicle's live
  `activeDtcs` — always the authoritative source when it applies), `KNOWN_BUT_NOT_ACTIVE` (not
  currently active, but in the small static reference catalog `app/mobile/dtc_catalog.py` —
  explicitly framed as not-currently-active, never implies a live fault), `UNKNOWN_TO_CATALOG`
  (covered by neither — honestly says so, never invents a meaning). A non-DTC-shaped token
  (`XYZ123`) is correctly left off this path entirely. See `docs/TEST_EVIDENCE.md` for live
  verification of all three tiers.
- **RESOLVED (code-enforced, not just prompt-engineered) — `answer_open_query` can no longer be
  asked to fabricate a meaning for an unverified technical-code-shaped token.** Live-model audit
  testing surfaced a real hallucination: asked about the non-DTC-shaped token "XYZ123", a real 7B
  model fabricated a plausible-sounding "simulated controller communication fault" explanation for
  it — not caught by the guardrail's number-only check since it invents no number. A first pass
  hardened the system prompt (kept as defense-in-depth), but per explicit direction that prompt
  wording alone is not sufficient, `MobileSessionStore._find_unverified_code_token` now
  deterministically intercepts any letters-immediately-followed-by-digits token (e.g. `XYZ123`,
  `ABX900`) not present in the grounded context **before any LLM call is attempted** — the model is
  never invoked for that turn at all, guaranteeing zero hallucination risk for this pattern
  regardless of model version. Verified live against 5 varied tokens (all zero `/api/chat` calls)
  and by a dedicated unit test suite (`tests/test_mobile_session_store_guards.py`) checking for
  false positives against ordinary mixed text. This narrow pattern cannot catch every conceivable
  way a model could hallucinate about something the driver mentions in free text — only this
  specific, previously-observed failure shape.
