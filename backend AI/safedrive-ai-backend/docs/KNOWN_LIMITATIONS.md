# Known Limitations

- **SOS is simulation-only.** `realEmergencyDispatchEnabled` is hardcoded `False` end-to-end; no real
  call, SMS, or rescue dispatch is possible in this build.
- **Maps/POI is not implemented.** The narrator only ever says "hãy dừng tại vị trí an toàn gần
  nhất" — never a distance or place name, because no map/POI provider is wired in. Confirmed by code
  (no `PlacesProvider`-shaped dependency exists) and by test coverage.
- **No WebSocket.** `/api/v1/ws/cockpit` does not exist. State/emergency delivery is client-driven
  polling. This was a deliberate scope decision for this pass, not an oversight — see
  `docs/API_V1_CONTRACT.md`.
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
