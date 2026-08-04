# Mobile Parity Matrix — Android vs AI Studio Prototype (`src/`)

Generated in W0 of `docs/android-mvp-plan/12-mobile-completion-before-ai-backend.md`. No AI Studio
URL was reachable in this session, so parity was verified by direct source comparison
(`src/presentation/*`, `src/components/*`, `src/context/SafeDriveContext.tsx`,
`src/data/mock/mockRepository.ts`) against the Android Kotlin/Compose source, not by video capture.
This is more precise than a screen recording for logic/copy parity but does not substitute for a
human visually driving both apps side by side — that remains a W8 manual acceptance item.

Legend: **OK** = verified equivalent. **GAP** = confirmed real difference (tracked in plan 12).
**DECISION** = intentional, documented divergence, not a bug. **FIXED (Wn)** = was a GAP in this
table, resolved by the workstream noted, re-verified against the actual post-fix source (not just
compiled) — see `docs/android-stabilization-progress.md` for command/test evidence.

| Surface | Prototype behavior | Android behavior (before this stabilization) | Decision | Acceptance |
|---|---|---|---|---|
| Assistant — pipeline | `submitVoiceQuery(text)` calls the **same** `sendChatMessage()` used by typed input (`SafeDriveContext.tsx:470-475`); every reply goes through one function that appends to `chatMessages` and calls `speakText()` | Text/quick-prompt/voice all call `AssistantTurnCoordinator.submit()` — one pipeline, one `ConversationRepository` | **FIXED (W1/W2)** — was GAP-03 | `AssistantTurnCoordinatorTest`/`VoiceAssistantCoordinatorTest` cover single-flight, retry, emergency-priority routing |
| Assistant — TTS | `sendChatMessage()` unconditionally calls `speakText(replyText)` after every reply (text or voice), `speakText` itself checks `settings.ttsEnabled` | `AssistantTurnCoordinator` calls `TtsController.speak()` once per successful reply when `ttsEnabled`, for text and voice alike | **FIXED (W3)** — was GAP-02 | `AssistantTurnCoordinatorTest` "spoken exactly once"/"never spoken when disabled"/"never spoken on failure" |
| Assistant — latency | Fixture messages use fixed `latencyMs: 120` / `186` (`mockRepository.ts:413,427`); live replies use `Math.floor(Math.random()*200)+240` (`SafeDriveContext.tsx:383`) — i.e. the **prototype itself also simulates latency**, this is not Android-only | `MockSafeDriveGateway.queryAssistant()` has `delay((240..440).random())` — same order of magnitude as prototype | **DECISION** — prototype also delays; Android's real gap is that this delay is **not configurable/removable** in Demo Mode and has no p50/p95 instrumentation | W4: Demo Mode default has no artificial delay; Developer Mode can select a simulated profile matching/exceeding the prototype's range for comparison demos |
| Assistant — quick prompts | 5 fixed quick-prompt chips, horizontally scrollable, calls `handleQuickPrompt` → `handleSend` → same pipeline | `AssistantScreen`/`AssistantViewModel` quick prompts wired to `sendNewMessage` (same pipeline already) | **OK** | No action |
| Assistant — composer mic button | `triggerWakeWord` button in composer footer | Same affordance via `onTriggerVoice` in `AssistantScreen` | **OK** | No action |
| Voice overlay — states | `IDLE/DISABLED` render nothing; `WAKE_WORD_DETECTED/LISTENING/PROCESSING/SPEAKING/ERROR` all rendered with distinct copy | Android `VoiceState` enum has the same 7 states, `VoiceOverlay.kt` renders per-state | **OK** | No action |
| Voice overlay — quick speech chips + typed fallback while LISTENING | Prototype shows 3 quick-speech buttons and a typed-text fallback field while `LISTENING` | Android `VoiceOverlay` does not currently expose a typed fallback during LISTENING | **DECISION** (deferred, not a release-stop) — out of the P0 gap list in plan 12; typed input is always reachable via Assistant tab | Track as backlog item in `KNOWN_LIMITATIONS.md`, not a Gate blocker |
| Voice overlay — stop vs cancel | Prototype's "Dừng đọc / Khôi phục" (stop-speaking) is a distinct action from "Hủy bỏ" (cancel), matching the 3-way distinction plan 12 requires | `VoiceOverlay` now combines `VoiceController.state` (mic) + `TtsController.state` (speaking) for display only; "Hủy nghe"/"Hủy xử lý"/"Dừng đọc" are three distinct actions | **FIXED (W2/W3)** — was an ownership gap | `VoiceOverlayTest` "speakingState is driven by TtsController not VoiceController" |
| Simulator — presets | `SCENARIO_PRESETS` (8 presets) rendered as `ScenarioPresetCard` grid | `MockFixtures` ports the same 8 presets, `ScenarioPresetCard` grid | **OK** | No action |
| Simulator — speed slider | `<input type="range" min=0 max=160>` bound to local draft state; global state only updates on "Áp dụng trạng thái" | Android `SimulatorScreen` slider range 0–160, draft-only until Apply (per `SimulatorViewModel`) | **OK** (component itself is correct) | W6 confirms discoverability, not the slider logic |
| Simulator — discoverability | Prototype's Simulator is a permanent tab in AI Studio's own nav (no gating) | Reachable via `Settings → Developer Mode → "Mở Simulator"` **and** a "Simulator" chip in Cockpit's header whenever Developer Mode is on | **FIXED (W6)** — was GAP-10, matching the user's own live report ("bấm Mở Simulator chưa có thay đổi") | `CockpitViewModelTest` (developerMode wiring); Simulator screen also gained a top app bar + Back + mode label (W6.4) and Apply/Reset snackbar feedback (W6.6) — visual confirmation is still a W8 device item |
| Simulator — manual telemetry fields | Speed/cabin temp/engine temp sliders + driving-minutes + 5 signal toggles + DTC selector + crash/unresponsive toggles | Same fields present in Android `SimulatorViewModel`/`SimulatorScreen` (verified in original MVP build) | **OK** | No action |
| Cockpit | Status hero, 2×2 metrics, driver-signal summary, DTC summary, voice status row; responsive two-column landscape | Same components (`CockpitContent.kt` + subcomponents); header now also renders the W6.3 Simulator chip inside its existing fixed-height row (no new vertical space, chosen deliberately to avoid disturbing the tightly-weighted non-scrolling portrait layout `CockpitContentTest` already covers) | **OK** (re-verify visually in W8 device pass) | New chip's actual on-screen fit at 360×800/font-scale 1.3 is still a W8 device item — logic-level test (`CockpitViewModelTest`) passes |
| Diagnostics | DTC list with severity badges, "Hỏi SafeDrive AI" prefill into Assistant | Same behavior (`DiagnosticsViewModel`/`PendingPromptCoordinator`) | **OK** | No action |
| Settings | TTS/wake-word toggles, mic permission row, Developer Mode gate, BASE_URL, health check | Same sections present; Developer Mode gate confirmed correct in `SettingsScreen.kt`; gained a "Độ trễ giả lập" section (W4.4) and richer health-check message (W5.9) | **OK** | Existing CTA position (button directly under the Developer Mode toggle) judged already adequate; primary discoverability fix was the Cockpit chip, not a Settings reshuffle |
| Emergency | 5s verifying → 15s awaiting-response → 10s final countdown → sent; exact-match voice cancel; back button disabled | Same state machine ported in Phase 6 (`EmergencyReducer`), exact-match phrases (`EmergencyVoicePhrases`), survives rotation/process recreation via DataStore | **OK** (functional logic); voice cancel routing depends on W2's `VoiceAssistantCoordinator` emergency-priority routing, which must not regress | W2 test: emergency exact-match still never reaches chat |

## Summary

- 0 surfaces are "Unknown" — every row has a Decision.
- All GAP rows from the W0 pass are now **FIXED**: Assistant pipeline unification (W1/W2), TTS wiring
  (W3), voice/TTS ownership split (W2/W3), Simulator discoverability (W6) — matching plan 12's
  GAP-03, GAP-02, GAP-10 exactly, each with a passing test cited in this table.
- One net-new backlog item found that plan 12 did not explicitly call out: the prototype's voice
  overlay offers a typed-text fallback while `LISTENING`; Android's overlay does not. Logged as a
  non-blocking backlog item, not a Gate criterion (typed input is always available via the Assistant
  tab as an equivalent path).
- What remains open is exclusively **visual/device confirmation** (W8), not logic: no-overlap at the
  four target screen sizes, font-scale 1.3 clearance, and the new Cockpit chip's on-screen placement.
  None of this can be verified without a physical device or emulator in this sandbox.
