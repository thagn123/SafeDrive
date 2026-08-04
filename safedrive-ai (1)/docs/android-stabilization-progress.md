# Android Stabilization Progress

Tracks execution of `docs/android-mvp-plan/12-mobile-completion-before-ai-backend.md` via
`docs/android-mvp-plan/13-claude-mobile-stabilization-prompt.md`. Updated after every workstream.
Never mark a workstream `Done` on compile alone — only on exit criteria + evidence.

## W0 — Baseline, source control, parity evidence

**Status: DONE** (2026-07-27)

### Tasks

| Task | Result |
|---|---|
| W0.1 Git baseline | Root repo had **no Git**. `git init` run at repo root. Staged 217 files (excludes `android/app/build/`, `android/local.properties`, `android/.gradle/`, `graphify-out/cache/`). **Could not create a commit**: `git commit` failed with "Author identity unknown" (no global `user.name`/`user.email` configured in this sandbox, and per the stabilization prompt's explicit instruction, a fake identity must not be invented to force a commit through). Per that same instruction, recorded recoverable baseline evidence instead: `git write-tree` produced tree `0326fff2e6e087f745fd647999ba6ffa04eb87e8`, pinned via a lightweight ref `refs/tags/baseline-w0-tree` so it survives `git gc`. Recoverable with `git checkout baseline-w0-tree -- .` or `git archive baseline-w0-tree` once a real identity is configured, without losing any baseline content. **Action for a human with a real Git identity**: run `git config user.name/user.email` then `git commit-tree baseline-w0-tree -m "baseline" | xargs git update-ref refs/heads/main` (or similar) to turn this into a normal commit — no file content needs to change. |
| W0.2 APK baseline checksum | Debug APK SHA-256 (unchanged from Phase 8): `6d9f1141814a602ba94b54c21dc165ec8e9ae81c663607218f38709e158f5ada` (`android/app/build/outputs/apk/debug/app-debug.apk`, verified with `sha256sum`, matches `TEST_REPORT.md`). |
| W0.3 Re-run build/test/lint | `gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug` → **BUILD SUCCESSFUL in 52s**, 57 tasks (5 executed, 52 UP-TO-DATE — no source changed since Phase 8, so this reconfirms the existing 99-test/0-failure/12-lint-warning baseline rather than re-deriving new numbers). |
| W0.4 Device + instrumented tests | A phone was connected earlier in this session (used to install/verify the APK and diagnose the user's mic/Simulator questions) but **disconnected before this stabilization task began** (`adb devices` empty, confirmed after `adb kill-server`/`start-server`). `:app:connectedDebugAndroidTest` **not run** — marked `DEVICE_PENDING`, not skipped/ignored. Will run as soon as a device reconnects (see W8). |
| W0.5 AI Studio video capture | No AI Studio URL was available in this conversation to record from. Per the stabilization prompt's own fallback instruction ("nếu URL không còn truy cập được, tiếp tục bằng source `src/`"), parity was instead verified by direct source-to-source comparison of `src/presentation/*`, `src/components/*`, `src/context/SafeDriveContext.tsx`, `src/data/mock/mockRepository.ts` against the Android Kotlin/Compose equivalents. This is precise for logic/copy but does not replace a human visually driving both apps — flagged as a W8 manual acceptance item, not silently dropped. |
| W0.6 Parity matrix | `docs/mobile-parity-matrix.md` created. 0 "Unknown" rows. Confirmed all 11 plan-12 P0 gaps against actual source (not taken on faith) plus one net-new backlog item (voice-overlay typed fallback while LISTENING, non-blocking). |
| W0.7 Latency baseline | `docs/mobile-latency-baseline.md` created. App-controlled numbers captured from source (240–440 ms artificial Demo delay, 12s/10s/15s/10s current timeouts). Provider/device numbers (STT ready latency, TTS cold/warm start, real-network round trip) marked `DEVICE_PENDING` with exact commands to run once a device is available — not estimated or fabricated. |
| W0.8 Doc sync | `06-roadmap-20-days.md` and `10-plan-review-and-traceability.md` already carried a "superseded by 12" banner from a prior session. Added the same banner to `03-data-api-contract.md` and `09-checklists-and-decisions.md`. `03`'s actual prose (timeout/error-mapping section) already matched plan 12's decisions — the gap was in the **code**, not the doc; docs now consistently point to `12` as authoritative. |
| W0.9 Contract-delta draft | `docs/contract-delta-draft.md` created: documents `source`, `locale` (promoted to top-level), `clientAttemptOf`, `serverProcessingMs`, `model`, `finishReason`, `safetyMetadata`, and the error envelope shape as deltas to land in W7's OpenAPI freeze, plus explicit sequencing (W1 domain-only → W5 `GatewayError.Configuration` → W7 wire DTOs + OpenAPI together). |

### Exit criteria check

- [x] Recoverable baseline exists (tree object + ref, not a commit — see W0.1 note; content-identical to what a commit would contain).
- [x] Device/API/model recorded where known; unknowns explicitly marked `DEVICE_PENDING` rather than guessed.
- [x] Screen-by-screen evidence exists (parity matrix) and latency baseline exists (app-controlled numbers real, device numbers pending).
- [x] All 11 P0 gaps from plan 12 re-verified against current source (not assumed) — see parity matrix and gap confirmations below.
- [x] No contradicting active contract/timeline docs — `03/06/09/10` all point to `12` as authoritative for the 3 superseding points.

**Gate contribution:** W0 does not itself close a lettered Gate but is a prerequisite for all of them.

### Gaps reconfirmed against live source (not taken on faith from plan 12)

| Gap | File:line | Confirmed |
|---|---|---|
| GAP-02 (text reply never TTS) | `feature/assistant/AssistantViewModel.kt:120-124` | Yes — `requestAssistantReply` only updates `_state`, no TTS call anywhere in the class |
| GAP-03 (voice bypasses conversation) | `voice/AndroidSpeechRecognizerController.kt:195-213` | Yes — `submitTranscript` calls `assistantQueryUseCase` directly; reply only updates `VoiceUiState`/speaks, never touches `AssistantViewModel`/chat |
| GAP-04 (no raw audio) | `voice/AndroidSpeechRecognizerController.kt:144` | Confirmed **absence** is correct (`onBufferReceived` is a no-op) — this is intended, not a bug, matching the transcript-first ADR |
| GAP-05 (TTS manifest/compat) | `AndroidManifest.xml` (full file read) | Yes — no `<queries>` block at all; `AndroidTextToSpeechController.kt:20-23` sets language but never checks `setLanguage()`'s return code |
| GAP-06 (Demo artificial delay) | `data/mock/MockSafeDriveGateway.kt:95-96` | Yes — `delay((240..440).random())` unconditional on every query |
| GAP-07 (session outside turn timeout) | `domain/usecase/AssistantQueryUseCase.kt:12,36` vs `domain/usecase/SessionCoordinator.kt` | Yes — 12s `withTimeoutOrNull` wraps only `queryAssistant`; `sessionCoordinator.currentSessionId()` (line 31) runs *before* the timeout block starts, fully unbounded |
| GAP-08 (session hard-codes DEMO) | `domain/usecase/SessionCoordinator.kt:36` | Yes — `mode = BackendMode.DEMO` literal, ignores actual `gatewayProvider` mode |
| GAP-09 (Remote blank URL → Mock) | `SafeDriveContainer.kt:100` | Yes — `BackendMode.REMOTE -> if (prefs.baseUrl.isBlank()) mockGateway else ...` |
| GAP-10 (Simulator hard to find) | `navigation/AppRoute.kt:14`, `navigation/SafeDriveNavHost.kt:104-124` | Route/nav wiring itself is **correct** (`onOpenSimulator` → `navController.navigate(AppRoute.Simulator.route)`); the gap is discoverability only — no Cockpit shortcut, Settings CTA easy to miss. This matches the user's own live report earlier in this session. |
| GAP-11 (no device acceptance) | N/A | Reconfirmed this session: device was connected, then disconnected; instrumented tests still not run |

### Blockers

None requiring a Product/human decision. Device unavailability is an environment constraint, tracked
as `DEVICE_PENDING`, not a blocker on the remaining W1–W7 code work.

---

## W1 — Conversation store and single assistant turn coordinator

**Status: DONE for text/quick-prompt/retry/cancel** (2026-07-27). Voice still uses the pre-W1 direct
path (`AndroidSpeechRecognizerController.submitTranscript()` → `AssistantQueryUseCase` directly) —
that is W2's job, done immediately next; not deferred indefinitely.

### Files created

- `core/model/AssistantTurnModels.kt` — `AssistantTurnSource`, `InFlightAssistantTurn`, `RetryableAssistantTurn`.
- `domain/repository/ConversationRepository.kt` — interface + `ConversationState`.
- `data/local/InMemoryConversationRepository.kt` — application-scoped impl (in-memory only; see plan 12 §4.2 on why process-death persistence is out of scope).
- `domain/usecase/AssistantTurnCoordinator.kt` — `submit()`/`retry()`/`cancelCurrent()`, global single-flight via `isBusy`, generation guard, `errorMessageFor()` moved here from the ViewModel.
- `app/src/test/.../domain/usecase/AssistantTurnCoordinatorTest.kt` — 8 tests.

### Files changed

- `feature/assistant/AssistantViewModel.kt` — rewritten as a thin delegate: composer text + action-confirmation state only; messages/turn state come from `ConversationRepository` via a collector in `init{}` (same established pattern as the existing `preferencesRepository`/`cockpitSnapshot` collectors in this class — deliberately *not* `combine().stateIn(WhileSubscribed)`, to avoid the documented test-timing pitfall with that operator).
- `feature/assistant/AssistantUiState.kt` — added `AssistantUiAction.CancelTurn`.
- `feature/assistant/AssistantScreen.kt` — added a "Hủy" button next to the thinking indicator (W1.11 requires the UI let the user cancel an in-flight turn, not just block duplicates).
- `SafeDriveContainer.kt` — added `conversationRepository`/`assistantTurnCoordinator` (application-scoped, alongside `emergencyRepository`/`voiceController`).
- `navigation/SafeDriveNavHost.kt` — updated `AssistantViewModel` construction.
- `app/src/test/.../feature/assistant/AssistantViewModelTest.kt` — migrated to the new constructor; added a cancel test and a "ViewModel recreation against the same repository keeps history" test.

### Test commands and results

- `gradlew.bat :app:assembleDebug :app:testDebugUnitTest` → **BUILD SUCCESSFUL**. `AssistantTurnCoordinatorTest`: 8/8 pass. `AssistantViewModelTest`: 10/10 pass. Full suite: **109 tests, 0 failures, 0 errors** (up from 99 baseline: −8 old Assistant tests +10 migrated +8 new Coordinator tests = +18, arithmetically consistent).
- `gradlew.bat :app:lintDebug` → **BUILD SUCCESSFUL**.

### Exit criteria check

- [x] One coordinator for text/quick-prompt/retry (voice joins in W2, immediately next).
- [x] Duplicate/cancel/retry/generation tests pass (`AssistantTurnCoordinatorTest`).
- [x] Existing assistant tests migrated and pass.
- [x] Rotate/recreate Assistant screen does not lose history within the same process (new test, application-scoped repository).

**Gate A (Unified assistant) contribution:** partially satisfied — text/quick-prompt path is unified;
full Gate A requires W2 (voice) to also route through this coordinator. Not claiming Gate A pass yet.

### Known interim state (resolved by W2, not carried further)

Until W2 lands, `AndroidSpeechRecognizerController` still calls `AssistantQueryUseCase` directly and
bypasses `ConversationRepository` entirely (this is the exact GAP-03 behavior documented in W0). This
is a deliberately short-lived intermediate state within the same work session, not a new regression —
proceeding to W2 immediately.

---

## W2 — Voice input routing & W3 — TTS correctness

**Status: DONE** (2026-07-27). Implemented together in one pass (plan 12 explicitly allows W3 to run
partially parallel with W2; splitting them here would have left voice replies silently unspoken for
no reason, since W2.11 requires removing TTS ownership from `VoiceController` and W3 is what replaces
it).

### Architecture change

- `VoiceController` now owns **only** mic/STT lifecycle + UI state, and exposes `events: Flow<VoiceInputEvent>`
  instead of calling the assistant pipeline directly. `speak()`/`stopSpeaking()` removed entirely from
  the interface (W2.11).
- New `TtsController` domain interface (`TtsState`: INITIALIZING/READY/SPEAKING/UNSUPPORTED/MISSING_DATA/ERROR)
  + `AndroidTextToSpeechController` implementation — checks `setLanguage()`'s real return code (W3.3),
  queues only the single latest `speak()` call while initializing (W3.4), fixed `speechRate=pitch=1.0f` (W3.11).
- New `VoiceAssistantCoordinator` (application-scoped): the only consumer of `VoiceController.events`;
  routes to `EmergencyRepository.respond()` when an emergency is active (exact-match only, same
  allowlist as before), otherwise to `AssistantTurnCoordinator.submit(text, VOICE, screen)` — the exact
  same pipeline text/quick-prompt use. This directly fixes GAP-03 (voice bypassing chat) — voice
  transcripts and replies now appear in `ConversationRepository`/the Assistant chat history.
- `AssistantTurnCoordinator` gained `ttsController`/`appPreferences` and calls
  `ttsController.speak()` exactly once per successful reply when TTS is enabled — this directly fixes
  GAP-02 (typed replies never spoke). Failed/cancelled turns never call TTS (W3.13).
- `VoiceOverlay` now combines `VoiceController.state` and `TtsController.state` for *display* only
  (W3.14) — three distinct actions per W2.9: "Hủy nghe" (`voiceController.cancel()`), "Hủy xử lý"
  (`onCancelProcessing` → `AssistantTurnCoordinator.cancelCurrent()`), "Dừng đọc" (`ttsController.stop()`).
- Manifest: added `<queries>` for `android.intent.action.TTS_SERVICE` (W3.1).
- `MainActivity.onStop()` now also stops TTS, not just the recognizer (W3.9).
- `startWakeWord`/`startListening`/`rememberVoiceTrigger` now take a `screen` parameter (cockpit/assistant/emergency) threaded into `VoiceInputEvent` for observability (W2.8) — routing itself only depends on emergency-active state, not on which screen triggered listening.

### Files created

`domain/repository/TtsController.kt`, `domain/usecase/VoiceAssistantCoordinator.kt`,
`core/testing/FakeTtsController.kt`, `app/src/test/.../domain/usecase/VoiceAssistantCoordinatorTest.kt` (9 tests).

### Files changed

`voice/VoiceController.kt`, `voice/AndroidSpeechRecognizerController.kt` (rewritten — no more
`assistantQueryUseCase`/`cockpitSnapshot`/`emergencyRepository`/TTS dependency; pure STT event
emitter), `voice/AndroidTextToSpeechController.kt` (rewritten to implement `TtsController`),
`feature/voice/VoiceOverlay.kt`, `feature/voice/VoiceTrigger.kt`, `core/testing/FakeVoiceController.kt`,
`domain/usecase/AssistantTurnCoordinator.kt`, `SafeDriveContainer.kt`, `SafeDriveApp.kt`,
`navigation/SafeDriveNavHost.kt`, `MainActivity.kt`, `AndroidManifest.xml`,
`app/src/androidTest/.../VoiceOverlayTest.kt` (rewritten for split voice/TTS fakes),
`app/src/test/.../AssistantTurnCoordinatorTest.kt` (+3 TTS tests).

### Test commands and results

- `gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin :app:lintDebug`
  → **BUILD SUCCESSFUL** on all four. Full JVM suite: **117 tests, 0 failures, 0 errors**
  (109 → 117: +8 new `VoiceAssistantCoordinatorTest` tests). 20 instrumented tests now compile
  (18 → 20: `VoiceOverlayTest` gained a "processing/cancel" case and a "speaking is TTS-driven" case,
  replacing the old single-controller SPEAKING test).
- One test-infrastructure nuance discovered and documented for future test authors: when a
  `CoroutineScope` argument is `runTest`'s `backgroundScope` (used because `VoiceAssistantCoordinator`'s
  event subscription is intentionally perpetual and must not trigger `UncompletedCoroutinesError`),
  a *nested* coroutine launched from *within* a `backgroundScope`-run collector (here,
  `AssistantTurnCoordinator`'s own per-turn job, started synchronously from inside
  `VoiceAssistantCoordinator.route()`) was empirically not reliably drained by `advanceUntilIdle()` in
  this Kotlin coroutines-test version. Fix: give the turn coordinator the test's own completable scope
  (`this`) and reserve `backgroundScope` only for the coordinator's genuinely-infinite `events`
  subscription. Documented here rather than silently worked around, per this session's own
  house rule of writing down non-obvious test-timing fixes (matches the pre-existing
  `WhileSubscribed` pitfall note from Phase 3).

### Exit criteria check

- [x] Voice transcript + reply appear in the same conversation as text/quick-prompt (W2.7).
- [x] Emergency exact-match routing still never reaches chat (`VoiceAssistantCoordinatorTest`).
- [x] `"Tôi không ổn"` still not matched as `"Tôi ổn"` (existing `EmergencyVoicePhrasesTest` untouched + new coordinator-level test).
- [x] Text and voice reply both call `TtsController.speak()` exactly once when enabled; 0 times when disabled or on failure.
- [x] TTS toggle now has a real effect on text replies too (previously a no-op preference).
- [ ] **DEVICE_PENDING**: real `SpeechRecognizer`/`TextToSpeech` behavior, `setLanguage()` real-device
  return codes, and a physical device with vi-VN TTS voice data — none of this can be verified without
  a connected device (see W0/`KNOWN_LIMITATIONS.md`). Not claimed as PASS.

**Gate A (Unified assistant): now fully satisfied** — text, quick prompt and voice all funnel through
`AssistantTurnCoordinator`; no second assistant-request path remains anywhere in the app.
**Gate B (Audio UX complete): partially satisfied** — TTS wiring/logic is correct and unit-tested;
the "STT thật chạy trên device" and "device TTS" criteria are DEVICE_PENDING.

---

## W4 — Latency instrumentation and Demo fast path

**Status: DONE** (2026-07-27)

### Changes

- `core/model/Enums.kt`: `SimulatedLatencyProfile` (NONE/MS_100/MS_500/MS_2000/TIMEOUT). `AppPreferences.developerLatencyProfile`, persisted via `DataStorePreferencesRepository`/`FakePreferencesRepository`.
- `data/mock/MockSafeDriveGateway.kt`: removed the unconditional `delay((240..440).random())` (GAP-06 fixed). Now takes a `latencyProfileProvider: () -> SimulatedLatencyProfile` (default always-NONE); `SafeDriveContainer` wires it to `appPreferences.value.developerLatencyProfile`. `TIMEOUT` profile delays 20s — long enough that the *caller's own* timeout fires and produces a genuine `GatewayError.Timeout`, rather than this class fabricating that error.
- New `core/observability/AssistantTurnMetrics.kt` (+ `VoiceCaptureTimings`) and `AssistantTurnMetricsRecorder.kt`: full W4 timing model (`turnStartedAtMs` … `turnCompletedAtMs`) with derived deltas (`micStartToReadyMs`, `networkMs`, `sessionMs`, `responseToTtsStartMs`, `totalTurnMs`, etc.), all nullable so an unmeasured field is `null`, never a fabricated `0`.
- `domain/usecase/AssistantQueryUseCase.kt`: optional `clock`/`onTiming` callback reports `sessionStartedAtMs`/`requestSentAtMs` without changing its return type or breaking any existing call site (both default to no-op).
- `domain/usecase/AssistantTurnCoordinator.kt`: assembles and records `AssistantTurnMetrics` for every completed turn (success and failure); accepts optional `captureTimings: VoiceCaptureTimings?` in `submit()` for voice-originated turns.
- `voice/AndroidSpeechRecognizerController.kt`: `LISTENING` is now only ever rendered after `onReadyForSpeech` actually fires (W4.6) — before that, state stays `WAKE_WORD_DETECTED` ("preparing"); added `finishListening()` calling `SpeechRecognizer.stopListening()` (W4.7, "Kết thúc câu nói" — distinct from `cancel()`, which discards instead of finalizing); added best-effort `EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS`/`..._POSSIBLY_COMPLETE_..." intent extras with an explicit comment that they are optimizations, not guarantees (W4.8); captures real `micRequestedAtMs`/`recognizerReadyAtMs`/`firstPartialAtMs`/`finalTranscriptAtMs` and attaches them to the emitted `VoiceInputEvent`.
- `voice/VoiceController.kt`: `VoiceInputEvent` gained `captureTimings`; interface gained `finishListening()`.
- Settings UI (`feature/settings/`): new "Độ trễ giả lập (Developer Mode)" section (5 radio options) and a last-turn latency summary line, both Developer-Mode-gated; `SettingsViewModel` combines `AssistantTurnMetricsRecorder.lastTurn` into its state.
- W4.9 (TTS init non-blocking): already satisfied by the existing `AndroidTextToSpeechController` design (Android's own `TextToSpeech(context, listener)` constructor is inherently async) — no code change needed, confirmed and documented here rather than silently assumed.
- `docs/mobile-latency-baseline.md` updated in place with an "After W4" section, diffable against the "before" section from W0.

### Test commands and results

- `gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin :app:lintDebug` → **BUILD SUCCESSFUL** on all four. Full JVM suite: **120 tests, 0 failures, 0 errors** (117 → 120: +3 new metrics-recording tests across `AssistantTurnCoordinatorTest`/`VoiceAssistantCoordinatorTest`).
- Fixed 4 pre-existing tests that implicitly depended on the old artificial delay to create an "in-flight" window to assert single-flight/cancel against (`AssistantTurnCoordinatorTest` ×2, `AssistantViewModelTest` ×2) — now use an explicit `slowGateway()` test helper instead of relying on `MockSafeDriveGateway`'s (now-removed) default delay. This is a necessary test update given W4.3's intended behavior change, not a masked regression — documented here so it doesn't look like an unexplained test rewrite.

### Exit criteria check

- [x] `AssistantTurnMetrics`/recorder exist, are injectable (constructor param, default no-op logger) and unit-tested.
- [x] Raw timing only ever rendered in Developer Mode (Settings section gated on `state.developerMode`).
- [x] Demo Mode has no artificial delay by default; verified by the fact that turns now complete synchronously under a non-suspending gateway (tests had to be updated precisely because this is now true).
- [x] Developer Mode can select 0/100/500/2000/timeout simulated profiles.
- [x] LISTENING only rendered once the recognizer signals ready; "Kết thúc câu nói" fallback exists for when best-effort silence-detection extras don't fire.
- [ ] **DEVICE_PENDING**: actual millisecond values for mic-ready/TTS-start/etc. — the instrumentation is real and tested, but real numbers need a device (see `mobile-latency-baseline.md`, "Still DEVICE_PENDING").

**Gate C (Latency and Remote correctness) contribution:** the "Demo đạt latency budget" and "Có timing
breakdown" halves are satisfied; "Remote không fallback Mock" and "Session đúng mode/timeout" are W5's
job, next.

---

## W5 — Remote/session correctness and fail-fast

**Status: DONE** (2026-07-27)

### Changes

- `core/common/GatewayError.kt`: new `Configuration(reasonCode: String)` variant — local/client-side only, never mapped to/from HTTP.
- New `data/remote/ConfigurationErrorGateway.kt`: implements `SafeDriveGateway`, every method fails with `GatewayError.Configuration("REMOTE_BASE_URL_MISSING")`. `SafeDriveContainer.gatewayProvider` now returns this instead of `mockGateway` for Remote+blank BASE_URL (GAP-09 fixed) — Remote Mode can never silently behave like Demo Mode again.
- `domain/usecase/SessionCoordinator.kt` rewritten: `mode` in `StartSessionRequest` now comes from live `AppPreferences.backendMode` (GAP-08 fixed, no more hard-coded `DEMO`); cache key is `(backendMode, baseUrl)` with real `expiresAtMs` checking (stale sessions are never served); on failure, returns the real `GatewayResult.Failure` — **no more `sess_local` fabrication** (GAP-05/W5.5 fixed); one retry, connection errors only (`Offline`/`Timeout`), never for a non-connection error.
- `domain/usecase/AssistantQueryUseCase.kt`: single 10s `withTimeoutOrNull` now wraps session resolution **and** the query together (GAP-07 fixed — no more double-wait up to ~27s); `sessionCoordinator.currentSessionId()`'s `GatewayResult<String>` failure is returned directly as the turn's failure (relies on `GatewayResult.Failure : GatewayResult<Nothing>` being assignable to any `GatewayResult<T>`).
- `domain/usecase/ObserveCockpitUseCase.kt`: now combines `appPreferences` as a third `combine()` source, so switching backend mode/BASE_URL alone — with no vehicle-state change — still issues a fresh `updateVehicleState` call against the newly-selected gateway (GAP/W5.8 fixed); session failures now map to a connection-status-updated snapshot instead of being silently absorbed.
- `core/network/NetworkModule.kt`: connect/read/write timeouts lowered to the locked budget 3s/8s/5s (was 10s/15s/10s).
- `domain/usecase/AssistantTurnCoordinator.kt`: gained `lastHealthStatus: StateFlow<HealthStatus?>` — `submit()` fails fast (no gateway call at all) when the last known health explicitly reported `assistant=false` (W5.10); absence of health info never blocks. Also: mode/URL change now calls `assistantTurnCoordinator.cancelCurrent()` in `SafeDriveContainer`'s init block (W5.7), in addition to the existing session invalidation.
- `feature/settings/SettingsViewModel.kt`: `checkHealth()` wrapped in a 5s timeout (separate, tighter budget than the general network read timeout); success message now shows "Local Mock" for Demo or the parsed host for Remote, plus `assistant=<bool>` capability (W5.9); new `onHealthChecked` callback feeds `SafeDriveContainer.recordHealthStatus()` — Settings is the only place `checkHealth()` is ever called from.
- `domain/usecase/ConfirmActionUseCase.kt`, `feature/simulator/SimulatorViewModel.kt`: updated for `SessionCoordinator.currentSessionId()`'s new `GatewayResult<String>` return type (simulator's fire-and-forget scenario event now silently skips sending if session resolution fails, since its own result was already discarded).
- Exhaustive `GatewayError` `when` blocks updated for the new `Configuration` case in `AssistantTurnCoordinator`, `SettingsViewModel`, `ObserveCockpitUseCase` — caught by the compiler, not missed.
- W5.12 (cancellation reaching Retrofit): verified, not changed — `RemoteSafeDriveGateway.safeCall`'s catch clauses (`SocketTimeoutException`/`SerializationException`/`IOException`) do not catch `CancellationException`, so `AssistantTurnCoordinator.cancelCurrent()`'s `Job.cancel()` already propagates correctly through the suspend chain into Retrofit's coroutine adapter (which cancels the underlying OkHttp `Call`). No code change needed; documented here so this isn't mistaken for an unverified gap.

### Files created

`data/remote/ConfigurationErrorGateway.kt`, `app/src/test/.../data/remote/ConfigurationErrorGatewayTest.kt` (9 tests), `app/src/test/.../domain/usecase/SessionCoordinatorTest.kt` (8 tests).

### Test commands and results

- `gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin :app:lintDebug` → **BUILD SUCCESSFUL** on all four. Full JVM suite: **142 tests, 0 failures, 0 errors** (120 → 142: +9 `ConfigurationErrorGatewayTest`, +8 `SessionCoordinatorTest`, +2 `AssistantTurnCoordinatorTest` capability-gating, +1 `ObserveCockpitUseCaseTest` mode-switch, +2 `SettingsViewModelTest` health-label/capability-cache).
- All pre-existing contract tests (`MockSafeDriveGatewayContractTest`, `RemoteSafeDriveGatewayContractTest`, `RemoteSafeDriveGatewayErrorMappingTest`) still pass unmodified — Mock/Remote parity preserved.

### Exit criteria check

- [x] Không có "Remote giả" — Remote+blank URL fails fast with `Configuration`, never silently becomes Mock (`ConfigurationErrorGatewayTest`).
- [x] Remote failure có thời gian hữu hạn (10s total, down from an unbounded-session + 12s-query combination) and message actionable (`errorMessageFor`/`checkHealth` messages).
- [x] Mock/Remote vẫn pass shared contract tests.
- [x] Session luôn gửi đúng mode hiện tại, không hard-code DEMO (`SessionCoordinatorTest`).
- [x] Không tạo sess_local giả khi Remote thất bại.
- [x] Đổi mode/URL invalidate session **và** cancel turn đang chạy.
- [x] Assistant query không auto-retry; chỉ session/health retry tối đa một lần cho lỗi kết nối.
- [ ] **DEVICE_PENDING**: real network conditions (Wi-Fi/LAN/weak/offline matrix from W8) — MockWebServer-based coverage exists; real-network behavior still needs a device and a reachable backend (neither exists in this sandbox).

**Gate C (Latency and Remote correctness): now fully satisfied** (combined with W4's latency half).

---

## W6 — UI parity, Simulator and khả năng tìm thấy tính năng

**Status: DONE** (2026-07-27)

### Changes

- **Cockpit Simulator shortcut (W6.3, the main fix)**: `CockpitViewModel` now also combines
  `PreferencesRepository.preferences` and exposes `developerMode` on `CockpitUiState.Content`.
  `CockpitHeader` renders a small "Simulator" chip (purple, `Icons.Filled.Build`) next to the
  connection chip whenever `developerMode` is true — inside the **existing** fixed-height header row,
  deliberately not a new row/section, so it can't disturb the tightly-weighted non-scrolling portrait
  layout `CockpitContentTest` already covers. Wired to `navController.navigate(AppRoute.Simulator.route)`.
  This directly answers the user's own earlier report in this session ("bấm Mở Simulator chưa có thay
  đổi") with a much more visible, always-on-Cockpit entry point, in addition to the pre-existing
  Settings button (whose navigation was independently re-verified correct in W0 — the gap was always
  discoverability, never a broken route).
- **Simulator top app bar (W6.4)**: added `Scaffold`/`TopAppBar` with a Back navigation icon and a
  mode label ("Demo Mode"/"Remote Mode", from `SimulatorUiState.backendMode`).
- **Simulator Apply/Reset feedback (W6.6)**: new `SimulatorUiEffect.ShowMessage`, collected into a
  `Snackbar` — "Đã áp dụng: `<speed>` km/h" after Apply, "Đã khôi phục trạng thái mặc định" after Reset.
- **Speed slider semantics (W6.5/W6.7)**: re-verified unchanged and correct — 0–160 km/h range, only
  updates the local draft (`ManualTelemetryForm`) until `applyManual()` is called explicitly.
- **Assistant quick prompts (W6.9)**: `QuickPromptsRow` gained `Modifier.horizontalScroll(...)` — the
  previous plain `Row` had no overflow safety net at all for 4 Vietnamese labels on a 360dp-wide
  screen; this was a real, previously-undetected layout bug, not just a hypothetical one.
- **TTS icon reflects real state (W6.11)**: `AssistantScreen` now takes a `ttsController: TtsController`
  parameter; the header's volume icon/tint/content-description differ for off vs. speaking vs.
  unsupported/missing-data/error — previously it only reflected the boolean `ttsEnabled` setting,
  which could show "on" even when the engine could not actually speak vi-VN at all.
- Settings' Developer Mode CTA position (W6.2) was re-examined and judged already adequate (button
  directly under the toggle); the Cockpit chip was judged the higher-value fix and implemented instead
  of reshuffling Settings further.

### Files changed

`feature/cockpit/CockpitUiState.kt`, `CockpitViewModel.kt`, `CockpitScreen.kt`, `CockpitContent.kt`,
`components/CockpitHeader.kt`, `feature/simulator/SimulatorUiState.kt`, `SimulatorViewModel.kt`,
`SimulatorScreen.kt`, `feature/assistant/AssistantScreen.kt`, `navigation/SafeDriveNavHost.kt`,
`app/src/androidTest/.../AssistantScreenTest.kt` (updated for new `ttsController` param).

### Files created

`app/src/test/.../feature/cockpit/CockpitViewModelTest.kt` (1 test).

### Test commands and results

- `gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin :app:lintDebug`
  → **BUILD SUCCESSFUL** on all four (one fix needed along the way: `TopAppBar` required
  `@OptIn(ExperimentalMaterial3Api::class)` in this Compose BOM version). Full JVM suite:
  **143 tests, 0 failures, 0 errors** (142 → 143).
- `docs/mobile-parity-matrix.md` updated in place — every previously-open GAP row now marked FIXED
  with the specific test that proves it, per-row, not just a blanket "done" claim.

### Exit criteria check

- [x] `mobile-parity-matrix.md` has no remaining GAP rows without a fix + test citation.
- [x] User được hướng dẫn đường mở Simulator: now `Settings → Developer Mode → Mở Simulator` **or**
  the Cockpit header chip, whichever the user finds first.
- [x] Speed slider semantics correct (draft-only until Apply) — unchanged, re-verified.
- [x] Quick prompts can't overflow un-scrollably on a narrow screen (logic-level fix; visual
  confirmation is W8).
- [x] TTS icon is no longer "just a boolean" — reflects `TtsState`.
- [ ] **DEVICE_PENDING**: actual pixel-level no-overlap at 360×800/390×844/412×915/844×390 and font
  scale 1.3 — `CockpitContentTest`/instrumented tests compile but have not run on a device (see W0/W8).

**Gate D (UX parity): now fully satisfied** at the logic/test level — the two remaining Gate D
criteria ("Target layouts pass", i.e. real screen rendering) are explicitly device-dependent and
carried forward to W8 as `DEVICE_PENDING`, not silently assumed.

---

## W7 — Contract freeze and backend handoff package

**Status: DONE** (2026-07-27)

### Changes

- **Domain model** (`core/model/GatewayContracts.kt`): `AssistantQueryRequest` gained top-level
  `source: AssistantTurnSource`, `locale: String`, `clientAttemptOf: String?` (moved `locale` out of
  `AssistantContext` per `contract-delta-draft.md`'s decision). `AssistantQueryResult` gained
  `serverProcessingMs`/`model`/`finishReason` (all nullable/additive).
- **Wire DTOs** (`data/remote/dto/AssistantDtos.kt`) mirror the domain change exactly;
  `AssistantQueryResponseDto` deliberately does **not** declare `safetyMetadata` yet (reserved for a
  future phase — `ignoreUnknownKeys=true` means adding it later is strictly additive, not breaking).
- **`ApiMappers.kt`**, **`AssistantQueryUseCase.kt`** (gained `source`/`clientAttemptOf` parameters),
  **`AssistantTurnCoordinator.kt`** (now actually threads its own `source`/`clientAttemptOf` — a
  parameter that existed since W1 but was unused until this wire-level need existed) all updated.
- **`MockSafeDriveGateway.kt`** now populates `serverProcessingMs`/`model`="safedrive-mock-rules"/`finishReason`="STOP" on every reply, matching the frozen response shape.
- **`docs/android-mvp-plan/03-data-api-contract.md`** assistant/query section updated in place to
  match the frozen shape exactly (was showing the pre-W7 nested `context.locale` shape).

### Artifacts created

- `openapi/safedrive-v1.yaml` — complete OpenAPI 3.0.3 spec: all 9 endpoints, 24 schemas, typed error
  envelope, safety-invariant notes inline (`realEmergencyDispatchEnabled` always false, no raw audio,
  no secrets). **Actually validated offline** with `openapi-spec-validator` (installed via pip in this
  sandbox, no network dependency at validate time) — including full external `$ref` resolution of
  every example file. This is a real, reproducible pass/fail check, not a claimed-but-unverified one.
- `openapi/examples/*.json` (9 files) — health-ok, session-start, state-update,
  assistant-text-query, assistant-voice-query, assistant-response, action-confirm,
  emergency-snapshot, error-envelope. Every field matches the actual current Kotlin DTOs, not an
  aspirational shape.
- `docs/backend-handoff.md` — sequencing, authority table, timeout/retry contract, error envelope,
  idempotency, explicit "what not to build yet."
- `docs/assistant-action-allowlist.md` — all 5 `ActionType` values, confirmation rule, what the
  backend vs. Android each control.
- `docs/latency-budget.md` — the target/release-stop contract (companion to
  `docs/mobile-latency-baseline.md`'s actual measurements).
- `app/src/test/.../data/remote/dto/AssistantDtoSerializationTest.kt` (7 tests): round-trips the new
  fields, decodes the actual OpenAPI example JSON verbatim, and proves both backward compatibility (a
  backend omitting the new optional fields still parses) and forward compatibility (an unknown
  `safetyMetadata` field from a future backend doesn't break decoding today).

### Test commands and results

- `gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin :app:lintDebug`
  → **BUILD SUCCESSFUL** on all four. Full JVM suite: **149 tests, 0 failures, 0 errors** (143 → 149).
- `python -c "from openapi_spec_validator import validate; ..."` → **valid**, run twice (once via
  plain `yaml.safe_load` sanity check, once via the real validator with `$ref` resolution).

### Exit criteria check

- [x] OpenAPI + examples validate (genuinely, offline, reproducibly).
- [x] Android DTO/contract tests pass, including new serialization round-trip tests.
- [x] Backend developer can build a server stub from `openapi/safedrive-v1.yaml` +
  `docs/backend-handoff.md` without reading Compose UI source.
- [x] No remaining contract decision left open: action allowlist, error envelope, idempotency,
  session rules, latency budget all documented and cross-referenced.
- [ ] W7's own exit criteria are fully met; **Gate E as a whole** additionally requires device QA and
  a human backend/Android owner review of this handoff package — both carried into W8, not claimed
  here.

---

## W8 — Device QA, release candidate và backend-ready gate

**Status: PARTIAL — not DONE.** W8's own name is "Device QA," and no device QA happened (no
device/emulator was ever reachable in this sandbox), so this workstream cannot honestly be marked
`DONE`. What *is* done: the non-device-dependent evidence work (final clean build/test/lint run,
OpenAPI re-validation, doc rewrite, explicit Gate A–E evaluation). Every device-dependent exit
criterion is marked `DEVICE_PENDING` below, not silently passed. (2026-07-27)

### What W8 actually did in this sandbox

1. **Re-verified device availability one final time.** `adb` is not even present in this sandbox's
   Android SDK path (`%LOCALAPPDATA%/Android/Sdk/platform-tools/adb.exe` does not exist), confirming
   the W0 finding was not a transient disconnect — no device/emulator has been reachable at any point
   in W0–W8. This is the same conclusion reached at the start of W0 and again at the start of W8;
   recorded a third time here as the final check before closing the workstream.
2. **Added Mock latency-profile coverage** (`data/mock/MockSafeDriveGatewayTest.kt`, +4 tests): asserts
   `NONE` completes with no delay, `MS_500`/`MS_2000` report `latencyMs` on the response within
   tolerance, and `TIMEOUT` never resolves inside the caller's own timeout window (so the *caller's*
   `GatewayError.Timeout` fires — the Mock gateway itself never fabricates that error).
3. **Rewrote all four hand-maintained evidence docs** (`TEST_REPORT.md`, `KNOWN_LIMITATIONS.md`,
   `MOCK_VS_REMOTE_COVERAGE.md`, `DEMO_SCRIPT.md`) to match the post-stabilization architecture and
   numbers, each cross-referencing the others rather than duplicating narrative.
4. **Updated `android/README.md`**: architecture-summary section, W0–W8 status table, "superseded"
   callouts on the three phase sections most affected (3/5/7), refreshed final test-evidence line.
5. **Ran the full clean verification suite one more time**, from a cold `clean`, as the final W8
   evidence run (see table below) — not reusing any earlier pass's numbers.
6. **Re-validated `openapi/safedrive-v1.yaml`** offline one more time via `openapi-spec-validator`
   with full external `$ref` resolution.
7. **Evaluated Gate A–E explicitly** (below) instead of inferring a verdict from individual
   workstream statuses.

### Final verification commands and results (this pass, cold clean, 2026-07-27)

| Command | Result |
|---|---|
| `gradlew.bat --stop` | 1 stale daemon stopped |
| `gradlew.bat clean :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:compileDebugAndroidTestKotlin :app:assembleRelease :app:lintRelease` | **BUILD SUCCESSFUL in 26s**, 117 actionable tasks |
| Unit test XML aggregation (`test-results/testDebugUnitTest/*.xml`) | **tests=153 skipped=0 failures=0 errors=0** |
| `:app:compileDebugAndroidTestKotlin` | compiles clean — 20 Compose UI tests, not executed (no device) |
| `:app:lintDebug` / `:app:lintRelease` | **BUILD SUCCESSFUL**, same version-availability warning set as before, no new warnings introduced |
| `sha256sum` on both APKs | debug `d69ddc1e...b043a1`, release-unsigned `34f7383b...b842ecc` — **correction (found during the post-stabilization remediation pass below): this row previously claimed the debug checksum was "unchanged from W0 baseline," which is false on its face — W0.2 above records the W0 baseline debug checksum as `6d9f1141...`, a different value. W1–W7 legitimately changed dozens of source files, so a changed checksum was the correct expectation; the "unchanged" wording was simply wrong and is struck out here rather than silently corrected, per the remediation pass's own rule against quietly overwriting a wrong claim.** |
| `python` + `openapi_spec_validator.validate(...)` on `openapi/safedrive-v1.yaml` with `base_uri` set to the file path (so external `$ref`s to `openapi/examples/*.json` actually resolve) | **OPENAPI VALID** |
| `adb devices` / SDK platform-tools presence check | **no device, `adb.exe` not even present in the SDK path** — final confirmation, consistent with every prior check this pass |

### Gate evaluation (explicit, per the master prompt's requirement — no gate inferred silently)

| Gate | Criteria | Verdict | Evidence |
|---|---|---|---|
| **A — Unified assistant pipeline** | Text, quick-prompt, and voice all submit through one coordinator; no second assistant-request code path anywhere in the app | **PASS** | `AssistantTurnCoordinator` is the sole caller of `AssistantQueryUseCase`; `VoiceAssistantCoordinator` and `AssistantViewModel` both route into it (W1/W2). Verified by reading every call site of `AssistantQueryUseCase`, not just by the tests. |
| **B — Audio UX complete** | Voice reply reaches chat; TTS speaks for both text and voice; overlay reflects real STT/TTS state | **PASS at logic/test level; DEVICE_PENDING for on-hardware behavior** | `VoiceAssistantCoordinatorTest`, `AssistantTurnCoordinatorTest` (TTS-on-success-only), `VoiceOverlayTest` all pass. Real `SpeechRecognizer`/`TextToSpeech` timing, vi-VN voice data availability, and OEM quirks are unverifiable without a device — never claimed as tested. |
| **C — Latency and Remote correctness** | No artificial Demo delay by default; timing instrumented; Remote fails fast, never masquerades as Mock; session reflects live mode, no fabricated ids | **PASS** | `MockSafeDriveGatewayTest` (incl. new latency-profile tests), `ConfigurationErrorGatewayTest`, `SessionCoordinatorTest` all pass. Real-network conditions (weak/offline Wi-Fi, real backend latency) remain `DEVICE_PENDING` — MockWebServer coverage is real but is not a substitute. |
| **D — UX parity** | Parity matrix fully closed; Simulator discoverable; layouts don't overlap/clip at target sizes | **PASS at logic/test level; DEVICE_PENDING for pixel-level rendering** | `mobile-parity-matrix.md` has zero open GAP rows, each with a test citation. `CockpitViewModelTest`, `CockpitContentTest` pass/compile. Actual on-screen rendering at 360×800/390×844/412×915/844×390 and font scale 1.3, and real rotation/process-death, were never visually confirmed — no device existed to confirm them on. |
| **E — Backend-ready** | Contract frozen and validated; backend handoff package complete; **device QA performed**; **human backend/Android owner has reviewed the handoff** | **FAIL (the two starred criteria cannot be satisfied by this automated pass)** | Contract/handoff half is genuinely done (`openapi/safedrive-v1.yaml` validates, `docs/backend-handoff.md`/`assistant-action-allowlist.md`/`latency-budget.md` exist and are cross-referenced). But no device/emulator existed at any point in W0–W8 to run `:app:connectedDebugAndroidTest` or walk `DEMO_SCRIPT.md` on real hardware, and no human backend or Android owner has reviewed `docs/backend-handoff.md` — both are explicit Gate E requirements per the master prompt, and neither is something an automated pass in this sandbox can produce for itself. |

**Overall Gate result: A/C PASS, B/D PASS-at-logic-level with explicit DEVICE_PENDING items, E FAILS
on its two device/human-review criteria.** Per the master prompt's own rule ("chỉ kết luận
BACKEND_READY nếu Gate E pass đầy đủ... nếu Gate E chưa thể hoàn thành do thiếu thiết bị, kết luận
DEVICE_VALIDATION_PENDING chứ không phải BACKEND_READY"), the final conclusion for this pass is
**`DEVICE_VALIDATION_PENDING`**, not `BACKEND_READY`.

### Exit criteria check

- [x] Full clean build/unit-test/lint/instrumented-compile/release-build all green, re-run from
  `clean` as the final evidence (not reused from an earlier workstream's numbers).
- [x] OpenAPI spec re-validated offline as the final check.
- [x] All four hand-maintained evidence docs (`TEST_REPORT.md`, `KNOWN_LIMITATIONS.md`,
  `MOCK_VS_REMOTE_COVERAGE.md`, `DEMO_SCRIPT.md`) and `android/README.md` rewritten to match current
  reality, not left stale from Phase 8.
- [x] Gate A–E evaluated explicitly, each with its own verdict and evidence — none inferred from
  "all workstreams say DONE."
- [ ] **DEVICE_PENDING** (carried forward, not resolved by this pass): `:app:connectedDebugAndroidTest`
  execution, `DEMO_SCRIPT.md` walkthrough on real hardware, real STT/TTS/latency numbers, human
  backend/Android owner review of `docs/backend-handoff.md`. See `KNOWN_LIMITATIONS.md` for the exact
  commands to run once hardware and a reviewer are available.

---

## Post-stabilization remediation pass — independent re-audit of W0–W8

**Status: PARTIAL — not DONE.** All 9 found defects are fixed with passing tests and verified by
direct source scan (below); device QA and a human backend/Android owner review — both explicit Gate E
requirements — remain `DEVICE_PENDING` and are not something this pass can produce for itself.
(2026-07-27, second pass, same day)

A follow-up prompt required treating the W0–W8 self-report as *unverified* and re-deriving every
claim from source and tests directly. That re-audit found nine real defects — some in code, some in
the W0–W8 documentation itself (the struck-through checksum claim above is one of them) — that a pure
"re-read the docs" pass would have missed. This section documents each: the concrete root cause found
in source, the fix, and the test that proves it, not just a restated intent.

### 1. `requestId`/`clientAttemptOf` mismatch

**Root cause (confirmed by reading, not assumed from the prior report):**
`AssistantTurnCoordinator.runTurn` minted `requestId = idGenerator.next("turn")` and used it for
`InFlightAssistantTurn`, metrics and the TTS `utteranceId` — but `AssistantQueryUseCase.invoke` minted
its *own*, separate `requestId = idGenerator.next("req")` for the actual `AssistantQueryRequest` sent
over the wire. The coordinator never saw the real network id. On failure,
`RetryableAssistantTurn(clientAttemptOf = requestId, ...)` captured the coordinator's internal
`turn_*` id — not the `req_*` id the backend had actually received — so a retry's `clientAttemptOf`
could never correlate with anything the backend had seen. Separately, the health-gated block-before-
sending path in `submit()` fabricated `clientAttemptOf = idGenerator.next("turn")` for a request that
was never sent at all.

**Fix:** `AssistantTurnCoordinator.beginTurnLocked` now mints exactly one `requestId` per attempt
(`idGenerator.next("req")`) and passes it into `AssistantQueryUseCase.invoke(requestId = ...)`, which
uses that exact value for `AssistantQueryRequest.requestId` instead of minting its own — one id, one
attempt, threaded through `InFlightAssistantTurn`, `AssistantTurnMetrics.requestId` and
`ttsController.speak(utteranceId = requestId)`. Retry's `clientAttemptOf` now references that same
real id (`RetryableAssistantTurn.clientAttemptOf`, now nullable). The health-gate path sets
`clientAttemptOf = null` — never a fabricated id for an attempt that was never sent.

**Evidence:** `AssistantTurnCoordinatorTest` — `retry mints a new requestId and its clientAttemptOf is
exactly the previous attempt's real network requestId` (asserts the literal id values across two
attempts) and `a health-blocked turn (no network call ever made) sets clientAttemptOf to null, never a
fabricated id`. Source scan: `grep -rn "assistantQueryUseCase(" android/app/src/main` shows exactly one
call site (`AssistantTurnCoordinator.kt`).

### 2. Non-atomic global single-flight lock

**Root cause:** `submit()`/`retry()`/`cancelCurrent()` each read `conversationRepository.state.value`
then, in a separate step, called `conversationRepository.setInFlightTurn(...)` — a classic
check-then-act race with no lock. Text/quick-prompt submissions run on Compose's Main dispatcher while
`VoiceAssistantCoordinator.route()` (voice) runs on the shared `applicationScope`; two callers on two
dispatchers could both observe "not busy" and both start a turn. `currentGeneration`/`currentJob` were
plain, unguarded `var`s read and written from both the calling thread and the turn's own coroutine.

**Fix:** Added a `private val lock = Any()`; `submit()`, `retry()`, `cancelCurrent()`, and the
generation-check-plus-state-mutation at the end of every turn's coroutine are now each wrapped in
`synchronized(lock) { ... }`, making the busy-check-and-mutate sequence one atomic critical section.
The lock only ever guards non-suspending code — the actual gateway call happens outside it, so no
suspension point exists inside a `synchronized` block (which the Kotlin compiler would reject anyway).

**Evidence:** `AssistantTurnCoordinatorTest.concurrent submits from many real threads at once start
exactly one turn` — spins up 24 real JVM `Thread`s synchronized on a `CyclicBarrier` so they call
`submit()` at effectively the same instant (not just sequential single-threaded coroutine-test calls,
which would have passed even against the old, unsynchronized code), and asserts exactly one is
accepted and exactly one user/assistant message pair lands.

### 3. Session cache key, `contractVersion`, and preferences/gateway snapshot consistency

**Root cause:** `SessionCoordinator`'s cache key was only `(mode, baseUrl)` — `SessionInfo.contractVersion`
was received from every session response but never read or validated anywhere. Separately,
`AssistantQueryUseCase`, `ConfirmActionUseCase`, `ObserveCockpitUseCase` and `SimulatorViewModel` each
resolved a session via `SessionCoordinator` and then *independently* called `gatewayProvider.current()`
a few statements later for their actual follow-up call — two separate reads of the same
`StateFlow<AppPreferences>` that could, in principle, observe different values if Settings changed
between them, sending a session-bound request through a different gateway than the one the session
was started against.

**Fix:** `GatewayProvider` gained `forPreferences(prefs: AppPreferences)` (default delegates to
`current()`, so pre-existing test fakes needed no changes; `SafeDriveContainer`'s real implementation
resolves directly from the passed snapshot). `SessionCoordinator.currentSession()` now captures `prefs`
exactly once, calls `gatewayProvider.forPreferences(prefs)` exactly once, and returns a new
`ResolvedSession(sessionId, gateway)` — every caller (`AssistantQueryUseCase`, `ConfirmActionUseCase`,
`ObserveCockpitUseCase`, `SimulatorViewModel`) now sends its follow-up call through
`resolvedSession.gateway`, never a fresh `gatewayProvider.current()`. The cache key is now
`(mode, baseUrl, contractVersion)`; a session whose `contractVersion` doesn't match the pinned
`EXPECTED_CONTRACT_VERSION = "v1"` fails fast with `GatewayError.Configuration("CONTRACT_VERSION_INCOMPATIBLE")`
and is never cached.

**Evidence:** `SessionCoordinatorTest` — `an incompatible contractVersion fails fast...`, `...is never
cached...`, `currentSession always resolves its gateway via forPreferences, never the ambient current()`
(a recording `GatewayProvider` fake asserts `current()` is called zero times), and `the ResolvedSession's
gateway is the exact instance the session was started against`.

### 4. `ErrorEnvelope` never parsed

**Root cause:** `RemoteSafeDriveGateway.safeCall`'s failure branch called `mapHttpError(response.code())`
directly — the response body was never read on a non-2xx response at all, so any typed
`code`/`message`/`retryable` the backend sent per `openapi/safedrive-v1.yaml`'s `ErrorEnvelope` schema
was silently discarded in favor of a coarse HTTP-status-only guess.

**Fix:** New `ErrorEnvelopeDto` (matches the OpenAPI schema exactly). `mapErrorResponse` now reads
`response.errorBody()`, decodes it with the same lenient `NetworkModule.json` config used for success
bodies, and maps `code` to the corresponding `GatewayError` — HTTP status is now only a fallback for a
missing/malformed body or an unrecognized future `code` value, never crashing on either.

**Evidence:** `RemoteSafeDriveGatewayErrorMappingTest` gained 6 new cases: a valid `VALIDATION`/`CONFLICT`
envelope maps with the envelope's own message; an envelope `code` that disagrees with the HTTP status
(`UNAUTHORIZED` body on a `500`) defers to the envelope; a malformed body, an empty body, and an
unrecognized future `code` all fall back to the HTTP status without throwing.

### 5. Latency instrumentation gaps

**Root cause:** `AssistantQueryUseCase` called `onTiming(sessionStartedAtMs, requestSentAtMs)`
*unconditionally*, immediately after `sessionCoordinator.currentSessionId()` returned — including when
that call failed. A session failure therefore reported a real-looking `requestSentAtMs` for a request
that was never actually sent, and `networkMs` (`requestSentAtMs` → `responseReceivedAtMs`) was
similarly fabricated. `AssistantTurnMetrics` had no field for `serverProcessingMs` at all (the value
existed on `AssistantQueryResult` but was never threaded into the recorded metrics). `ttsRequestedAtMs`
(when `speak()` was called) was used as the audio-start timestamp, conflating "TTS was asked to speak"
with "TTS actually started producing audio" — the two can differ by the engine's own warm-up/queueing
time.

**Fix:** `onTiming` is now only invoked *inside* the `GatewayResult.Success` branch of session
resolution, immediately before the actual `queryAssistant` call — a session failure now leaves
`requestSentAtMs`/`networkMs` genuinely `null`. `AssistantTurnMetrics` gained `serverProcessingMs`
(populated from `AssistantQueryResult.serverProcessingMs` on success) and `ttsStartedAtMs`. `TtsController`
gained an `events: SharedFlow<TtsUtteranceEvent>` fired from the real Android `onStart` callback;
`AssistantTurnCoordinator.awaitTtsStarted` waits (bounded, 5s) for that event keyed on `requestId` and
patches `ttsStartedAtMs` into the already-recorded metrics via a new
`AssistantTurnMetricsRecorder.recordTtsStarted`. `responseToTtsStartMs` now derives from
`ttsStartedAtMs`, never `ttsRequestedAtMs`.

**Evidence:** `AssistantTurnCoordinatorTest` — `session resolution failure records metrics with no
fabricated requestSentAtMs or networkMs`, `a completed turn records the backend-reported
serverProcessingMs`, `the TTS engine's real onStart callback populates ttsStartedAtMs, not just when
speak() was called` (uses `FakeTtsController.emitStarted`, a new test hook that simulates the platform
callback rather than merely the `speak()` call).

### 6. `finishListening()` had no UI call site; a failed event emit could strand PROCESSING

**Root cause:** `VoiceController.finishListening()` existed on the interface and was implemented, but
no composable ever called it — `VoiceOverlay`'s `LISTENING` branch had no button for it, so the
feature was unreachable from the UI despite being fully wired underneath. Separately,
`AndroidSpeechRecognizerController.onResults` called `_events.tryEmit(...)` and discarded the boolean
result; if the emit failed (buffer momentarily full), the state — already set to `PROCESSING` a line
earlier — would never be cleared, since nothing would ever route that (lost) event to
`clearProcessingState()`.

**Fix:** `VoiceOverlay`'s `LISTENING` branch gained a "Kết thúc câu nói" `TextButton` calling
`voiceController::finishListening`, distinct from the close button's "Hủy nghe"
(`voiceController.cancel()`). `onResults` now checks `tryEmit`'s return value and, on `false`, sets
state to `ERROR` with a message instead of leaving `PROCESSING` unrecoverable.

**Evidence:** `VoiceOverlayTest.listeningState_finishListeningButtonCallsFinishListeningNotCancel`
(androidTest, compiles clean — execution needs a device, see Gate B below).

### 7. TTS missing-data/unsupported had no visible UX

**Root cause:** `AssistantHeader`'s TTS icon changed tint/`contentDescription` for
`UNSUPPORTED`/`MISSING_DATA`/`ERROR`, but nothing else was visible — a differently-tinted icon is easy
to miss, and there was no way for the user to actually act on it.

**Fix:** `AssistantHeader` now renders a visible banner (only when the user has TTS enabled and the
engine genuinely cannot speak) plus a "Cài đặt giọng đọc" CTA that launches the platform TTS settings
intent (best-effort — wrapped in a try/catch for OEMs with no such screen).

**Evidence:** `AssistantScreenTest.ttsUnavailableWhileEnabled_showsVisibleBannerAndSettingsCta`
(androidTest, compiles clean).

### 8. Remaining parity gaps

**Root cause:** The AI Studio prototype (`src/presentation/AssistantScreen.tsx`) has 5 quick-suggestion
chips; Android had 4 — the 5th ("Gợi ý điểm dừng nghỉ gần đây" / "Tìm điểm nghỉ gần đây") was missing,
confirmed by direct source comparison, not assumed. Separately, `AssistantViewModel`'s
`START_SOS_COUNTDOWN` confirmation handler still showed "Đếm ngược SOS mô phỏng sẽ khả dụng ở Phase
6" — stale even by the time Phase 6/Emergency actually shipped, since confirming that assistant action
never actually starts the real Emergency countdown (which is only ever triggered by the crash-evidence
rule or the Simulator's crash preset).

**Fix:** Added the 5th quick prompt to `AssistantScreen.kt`'s `quickPrompts` list (routes to
`MockSafeDriveGateway`'s existing "nghỉ" reply branch — no gateway change needed). Replaced the stale
message with one that doesn't promise a future phase or a capability the action doesn't have, and
points at the Simulator's crash preset for the real flow.

**Evidence:** `AssistantScreenTest.fifthQuickPrompt_restStopSuggestion_isPresent` (androidTest, compiles
clean).

### 9. Evidence/documentation inaccuracies

**Found:** the W8 checksum row's false "unchanged from W0 baseline" claim (struck through and
corrected above — the W0 and W8 hashes were always different, since W1–W7 legitimately changed dozens
of files); the AndroidTest count was actually 19 in source at the time `TEST_REPORT.md`/`README.md`/
`KNOWN_LIMITATIONS.md`/`MOCK_VS_REMOTE_COVERAGE.md` all claimed 20; `openapi/safedrive-v1.yaml`,
`docs/backend-handoff.md`, `docs/latency-budget.md`, `docs/assistant-action-allowlist.md` and
`docs/android-mvp-plan/03-data-api-contract.md` all said "Frozen at Gate E," which asserts Gate E has
*passed* — it has not (device QA and human review are still outstanding).

**Fix:** All five "Frozen at Gate E" occurrences reworded to "**Candidate** ... not frozen until Gate
E's device-QA and human-review criteria also pass." `TEST_REPORT.md`/`KNOWN_LIMITATIONS.md`/
`MOCK_VS_REMOTE_COVERAGE.md`/`README.md` updated with the real, freshly-counted numbers from this
pass's own clean build (below) rather than carried forward from the prior pass.

### Final verification commands and results (this remediation pass, cold clean, 2026-07-27)

| Command | Result |
|---|---|
| `gradlew.bat clean :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:compileDebugAndroidTestKotlin :app:assembleRelease :app:lintRelease` | **BUILD SUCCESSFUL in 1m 12s**, 117 actionable tasks |
| `gradlew.bat :app:testDebugUnitTest --rerun-tasks` (forced re-execution, not cache-served) | **BUILD SUCCESSFUL** |
| Unit test XML aggregation | **tests=170 skipped=0 failures=0 errors=0** (153 → 170: +17 new tests — 6 in `AssistantTurnCoordinatorTest`, 5 in `SessionCoordinatorTest`, 6 in `RemoteSafeDriveGatewayErrorMappingTest`) |
| AndroidTest count (`grep -rn "@Test" app/src/androidTest`, actual recount, not assumed) | **22** (was actually 19 before this pass, not the previously-claimed 20 — +3: `finishListening` button, 5th quick prompt, TTS-unavailable banner) |
| `:app:compileDebugAndroidTestKotlin` | compiles clean — 22 Compose UI tests, not executed (no device) |
| `:app:lintDebug` / `:app:lintRelease` | **BUILD SUCCESSFUL**, same pre-existing warning set |
| `sha256sum` on both APKs (fresh, this pass — source genuinely changed, so a new hash is correct and expected, not a bug) | debug `3e930e5ef19dd740812f3b0cc593b4a457ec472c5f0584c587b160cca05962a5`, release-unsigned `6638f0a8b06cfe02ec224b5d79d05d3aff43a0986c9fce2795952a4db7561e24` |
| `python` + `openapi_spec_validator.validate(...)` on `openapi/safedrive-v1.yaml` (re-run after the "candidate" wording edit) | **OPENAPI VALID** |
| Source scans (see below) | all 7 invariants confirmed directly from source, not inferred |

**Source-scan invariants confirmed** (each grepped directly, not assumed):
`assistantQueryUseCase(` has exactly one call site (`AssistantTurnCoordinator.kt`); `finishListening`
has a real composable call site (`VoiceOverlay.kt`); `onBufferReceived` is still the only raw-audio
touchpoint and is a no-op; `BackendMode.REMOTE` never resolves to `mockGateway`; retry's
`clientAttemptOf` is always the real minted `requestId` (or `null` when nothing was sent);
`contractVersion` is checked at both the cache-read and cache-write path in `SessionCoordinator`;
`ErrorEnvelopeDto`/`parseErrorEnvelope`/`mapErrorCode` exist and are wired into `mapErrorResponse`.

### Gate re-evaluation (supersedes the W8 table above for every gate it touches)

| Gate | Verdict | What changed this pass |
|---|---|---|
| **A** | **PASS** (unchanged) | Still exactly one `AssistantQueryUseCase` call site; the requestId-ownership fix strengthens the pipeline's own internal consistency without changing the "unified" verdict. |
| **B** | **PASS at logic/test level; DEVICE_PENDING for on-hardware behavior** (unchanged) | `finishListening` is now UI-reachable and TTS-unavailable is now visible — both closing real gaps — but real-device STT/TTS behavior remains unverifiable in this sandbox. |
| **C** | **PASS** (strengthened) | Session/contractVersion/ErrorEnvelope/latency-instrumentation correctness fixes all land under Gate C; `SessionCoordinatorTest`/`RemoteSafeDriveGatewayErrorMappingTest` now cover cases the W8 pass did not. |
| **D** | **PASS at logic/test level; DEVICE_PENDING for pixel-level rendering** (unchanged) | 5th quick prompt closes the last known parity gap; pixel-level rendering still needs a device. |
| **E** | **FAIL** (unchanged — still correctly not claimed as passing) | Contract/handoff docs corrected from "frozen" to "candidate," which is itself a Gate E correctness fix, but the two device-QA/human-review criteria remain outstanding regardless. |

**Final conclusion: `DEVICE_VALIDATION_PENDING`, not `BACKEND_READY`.** Code- and contract-level
correctness is now genuinely stronger than the W0–W8 pass alone delivered — nine real defects (five in
code, two in test coverage that let them hide, two in the documentation's own claims) were found and
fixed with evidence, not just re-described. What remains is exactly what Gate E's own text requires and
this sandbox cannot produce: device QA and a human backend/Android owner review. AI Backend work has
not been started, per this remediation prompt's explicit instruction.

**Final conclusion: `DEVICE_VALIDATION_PENDING`.** Explicitly **not** `BACKEND_READY` — Gate E is not
fully satisfied, and per the master prompt's explicit rule this is reported honestly rather than
rounded up. AI Backend work has not been started in this task, per the master prompt's explicit
instruction.

## Second independent re-audit — 7 further logic defects found and fixed (2026-07-28)

An independent re-audit of the remediation pass above found that its own build/test/lint evidence was
real (170 JVM tests passing, 22 AndroidTest compiling, OpenAPI valid) but **incomplete**: several logic
defects existed that the pass's own test suite did not exercise, so green CI did not mean bug-free.
Status before this section: **`NOT_BACKEND_READY`** (per the master prompt's own rule — a green build is
not sufficient evidence to claim `DEVICE_VALIDATION_PENDING` while known logic bugs remain unfixed).
Each of the 7 defects below was independently re-verified by reading the actual source (not assumed from
the prior pass's own claims), fixed, and given a genuine regression test that fails against the
pre-fix code. AI Backend work was not started at any point in this pass.

### 1. `clientAttemptOf` could still reference a request never actually sent

**Root cause:** `AssistantTurnCoordinator`'s `Failure` branch unconditionally set
`clientAttemptOf = requestId`, and `cancelCurrent()` unconditionally set `clientAttemptOf =
inFlight.requestId` — in both cases regardless of whether `AssistantQueryUseCase` had actually reached
the point of sending the query over the network. A session-start failure, an incompatible
`contractVersion`, or a cancel that lands while session resolution is still suspended all mean no query
was ever dispatched, yet the old code still told a subsequent retry "the backend saw this exact id."

**Fix:** `AssistantTurnCoordinator.kt` — a new private `InFlightAttempt` holder with a `@Volatile
querySent: Boolean` flag, set `true` only inside `AssistantQueryUseCase`'s `onTiming` callback (which
itself only fires after session resolution succeeds, immediately before the real network call). Both
`cancelCurrent()` and the `Failure` branch now read this flag before deciding `clientAttemptOf =
requestId` vs. `null`.

**Regression tests (`AssistantTurnCoordinatorTest.kt`):** `cancel while startSession is still suspended
never claims a network attempt was sent`, `cancel after the query has actually been sent still preserves
clientAttemptOf lineage for retry`, `an incompatible contractVersion failure never claims a network
attempt was sent` — each uses a `CompletableDeferred`-gated or contract-mismatched gateway to force the
exact race window the bug lived in.

### 2. `SessionCoordinator` cache hit could pair a cached session id with a different gateway instance

**Root cause:** `currentSession()` called `gatewayProvider.forPreferences(prefs)` on *every* call,
including cache hits, and used that freshly-resolved instance to build the returned `ResolvedSession` —
even though the cached `sessionId` was started against whatever gateway instance existed at start time.
If two callers raced on a cold cache, or the provider itself was not perfectly memoized (a real gap in
`SafeDriveContainer.remoteGatewayFor`'s own check-then-set, not guarded by any lock), a cache hit could
return a session id paired with a gateway instance other than the one that actually created it.

**Fix:** `SessionCoordinator.kt` — `CachedSession` now stores the `gateway` instance itself. A cache hit
returns `ResolvedSession(cached.sessionId, cached.gateway)` directly, without calling
`gatewayProvider.forPreferences` again; the provider is only ever consulted on an actual miss, inside the
existing `Mutex`. `SafeDriveContainer.kt`'s `remoteGatewayFor` gained double-checked locking
(`synchronized(remoteGatewayLock)`) around its own read-then-write so it can never construct two
different `RemoteSafeDriveGateway`/Retrofit instances for the same BASE_URL under concurrent callers.

**Regression tests (`SessionCoordinatorTest.kt`):** `even when the gateway provider returns a fresh
instance on every call, a cache hit still returns the exact instance the session was started against`,
`two concurrent currentSession calls on a cold cache start exactly one session and share the same
gateway instance` (real concurrent `async` callers against a gateway with an artificial `delay(50)` to
widen the race window). One pre-existing test's assertion (`forPreferencesCallCount == 2`) was itself
encoding the bug as expected behavior — corrected to assert the gateway is resolved once, on the miss
path only.

### 3. `SpeechRecognizer` could be driven from more than one thread

**Root cause:** `SafeDriveApplication.applicationScope` runs on `Dispatchers.Default`.
`AndroidSpeechRecognizerController`'s internal preferences collector runs on that scope and called
`cancel()` (which touched the live `SpeechRecognizer` instance) directly — while `startListening`/
`startWakeWord`/`finishListening` are called from Compose/ViewModel action handlers, ordinarily the Main
thread. `SpeechRecognizer` must be created and driven from one consistent thread; nothing serialized
these two call paths against each other.

**Fix:** New `MainThreadExecutor` interface (+ `AndroidMainThreadExecutor`, backed by a `Handler` on
`Looper.getMainLooper()`) and `SpeechRecognizerFactory`/`PlatformSpeechRecognizer` interfaces wrapping
the real `SpeechRecognizer` construction and lifecycle calls, all in `AndroidSpeechRecognizerController.kt`.
Every platform-touching operation (`create`, `startListening`, `stopListening`, `cancel`, `destroy`) now
only ever runs inside a block passed to `mainThreadExecutor.execute { ... }`; the public methods
themselves only bump the (now `AtomicLong`) generation counter and update `_state` before handing off.

**Regression tests (new `AndroidSpeechRecognizerControllerTest.kt`, JVM):** using a `FakeMainThreadExecutor`
(queues instead of running immediately) and `FakeSpeechRecognizerFactory`/`FakePlatformSpeechRecognizer`
(pure-Kotlin doubles, no Robolectric needed) — `startListening never touches the platform recognizer
until the executor actually runs the dispatched block`, `finishListening and cancel always go through
the executor`, `toggling wakeWordEnabled off from a real background thread still dispatches cancel
through the executor` (genuine `Dispatchers.Default` thread, not virtual test-dispatcher ordering), `a
stale recognizer callback delivered after cancel does not resurrect state`, `a new startListening
supersedes the previous generation before the old dispatched block runs`.

### 4. TTS `onStart` could race ahead of the coordinator's own collector and be lost

**Root cause:** `AssistantTurnCoordinator` called `ttsController.speak(...)` and only *afterward*
launched a coroutine to collect `ttsController.events.first { ... }`. `events` is a hot, non-replaying
stream; if the engine's `onStart` callback fired synchronously inside `speak()` (or on another thread
faster than the collector coroutine got scheduled), the event was emitted to a stream nobody was
listening to yet and was lost forever — `ttsStartedAtMs` would then always be `null` for that turn, with
no way to distinguish "never subscribed in time" from "callback never fired."

**Fix:** `AssistantTurnCoordinator.kt`'s `awaitTtsStarted` now starts the collector via
`externalScope.async(start = CoroutineStart.UNDISPATCHED) { withTimeoutOrNull(...) { events.first {...} } }`
*before* calling `speak()` (passed in as a `before: () -> Unit` lambda) — `UNDISPATCHED` guarantees the
collector has already suspended waiting inside `first {}` (i.e. is registered) before control returns to
the caller and `speak()` runs, so no emission, however fast, can race ahead of it.

**Regression tests (`AssistantTurnCoordinatorTest.kt`):** `TTS onStart firing synchronously inside
speak() is still captured, not lost to the subscription race` (new `FakeTtsController(autoEmitStartAtMs
= {...})` hook that emits `onStart` from inside `speak()` itself — the exact worst-case race), `a tts
onStart event for a different utteranceId is ignored, the correct one still populates ttsStartedAtMs`,
`TTS disabled never creates a tts-start waiter and never fabricates ttsStartedAtMs`, `if the TTS onStart
callback never arrives, ttsStartedAtMs stays null after the bounded wait times out`.

### 5. No explicit discriminator between "cancelled" and "genuinely failed"

**Root cause:** Both a user cancel and a real gateway/session failure populate the same
`RetryableAssistantTurn`; the only way to tell them apart was the incidental fact that `cancelCurrent()`
happens not to call `setErrorMessage(...)`. Any future reader of `ConversationState` (this pass's own
`VoiceAssistantCoordinator.turnOutcome`, item 6 below, needed exactly this) would have had to rely on
that fragile inference rather than an explicit signal.

**Fix:** `RetryableAssistantTurn` gained `val wasCancelled: Boolean = false`, set `true` only by
`cancelCurrent()`. `VoiceAssistantCoordinator` now branches on this field directly instead of inferring
from `errorMessage == null`.

**Regression tests (`AssistantTurnCoordinatorTest.kt`):** `cancel marks the retryable turn
wasCancelled=true and sets no error message`, `a real gateway failure marks the retryable turn
wasCancelled=false and sets an error message`, plus a consolidated `full turn state transition matrix -
idle to in-flight to success, to failure-then-retry, and to cancelled` covering the entire idle →
in-flight → {success, failure→retry, cancelled} matrix in one place, including the pre-existing
stale-success-after-cancel guard.

### 6. Voice overlay never showed the voice turn's actual reply or error

**Root cause:** `VoiceOverlay`'s `PROCESSING` branch showed only a generic "Đang xử lý yêu cầu..."
spinner. The instant the turn actually completed, `VoiceAssistantCoordinator` called
`voiceController.clearProcessingState()`, flipping the recognizer state back to `IDLE` — which made the
whole overlay disappear immediately, before the user ever saw the reply or error. The final transcript
(already captured in `VoiceUiState.finalTranscript`) was never rendered during `PROCESSING` either.

**Fix:** New `VoiceTurnOutcome` sealed interface (`Success(replyText)` / `Failure(errorMessage)`) and a
`VoiceAssistantCoordinator.turnOutcome: StateFlow<VoiceTurnOutcome?>`, populated once per voice turn
right after it completes (using the `wasCancelled` discriminator from item 5 to show nothing for a user
cancel) and cleared by a new `dismissTurnOutcome()`. `VoiceOverlay` takes this as an additional
(default-valued, so existing call sites keep compiling) parameter, renders a reply/error bubble with an
explicit "Đóng" dismiss button ahead of the recognizer-state branches, and now also shows
`voiceState.finalTranscript` during `PROCESSING`. `SafeDriveApp.kt` wires
`container.voiceAssistantCoordinator.turnOutcome`/`::dismissTurnOutcome` in. `VoiceController` itself
never sees a reply or error — ownership stays split exactly as before (W2.11).

**Regression tests:** `VoiceAssistantCoordinatorTest.kt` — `a successful voice turn publishes its reply
text as a Success turnOutcome`, `a failed voice turn publishes the error message as a Failure
turnOutcome`, `a cancelled voice turn publishes no turnOutcome at all`, `dismissTurnOutcome clears the
currently-shown outcome without touching chat history`, `a text-sourced turn never publishes a
turnOutcome, so a text reply can never leak into the voice overlay`. `VoiceOverlayTest.kt` (androidTest)
— `successful turnOutcome shows the reply text and a dismiss button`, `failure turnOutcome shows the
error message and a dismiss button`, `processingState_showsFinalTranscriptWhenPresent`, `no turnOutcome
and idle mic renders nothing, matching the original idle behavior`.

### 7. `VoiceInputEvent` could be silently dropped despite `tryEmit` returning `true`

**Root cause:** `AndroidSpeechRecognizerController`/`FakeVoiceController` used
`MutableSharedFlow<VoiceInputEvent>(extraBufferCapacity = 4)` for `events`. A replay-0 `SharedFlow`'s
extra buffer only lets an *already-subscribed* collector fall behind without suspending the emitter — it
is not a queue for a collector that has not subscribed yet. A value emitted while no collector is
active (a genuine possibility during app startup, before `VoiceAssistantCoordinator.start()` runs) is
lost, not merely delayed, even though `tryEmit(...)` reports success.

**Fix:** Both classes now back `events` with a `kotlinx.coroutines.channels.Channel<VoiceInputEvent>`
(`capacity = 4`) exposed via `.receiveAsFlow()`. A `Channel` is a genuine point-to-point queue: a value
sent via `trySend` is held until *some* future `receive()`/collect call, regardless of subscription
timing — `trySend(...).isSuccess` is now a real backpressure signal (the bounded buffer is actually
full), not merely "nobody happened to be listening this instant." `VoiceAssistantCoordinator.start()` was
also made idempotent (`collectorStarted` guard) so an accidental double-call can never register two
competing collectors on the same channel.

**Regression tests (`VoiceAssistantCoordinatorTest.kt`):** `a transcript emitted before start() is
called is still processed exactly once, not lost` (emits via the fake *before* `coordinator.start()` is
ever invoked — would have been silently dropped by the old `SharedFlow`-backed fake), `calling start()
twice never registers a second collector, no duplicate turn`.

### Final verification commands and results (this re-audit pass, cold clean, 2026-07-28)

| Command | Result |
|---|---|
| `gradlew.bat clean :app:assembleDebug :app:testDebugUnitTest --rerun-tasks :app:lintDebug :app:compileDebugAndroidTestKotlin :app:assembleRelease :app:lintRelease` | **BUILD SUCCESSFUL in 1m 59s**, 117 actionable tasks |
| Unit test XML aggregation (`app/build/test-results/testDebugUnitTest/*.xml`, summed programmatically, not eyeballed) | **tests=194 failures=0 errors=0 skipped=0**, 24 test classes (170 → 194: +24 new — 12 in `AssistantTurnCoordinatorTest`, 4 in `SessionCoordinatorTest`, 8 in `VoiceAssistantCoordinatorTest`, and a new 5-test `AndroidSpeechRecognizerControllerTest` class) |
| AndroidTest count (`grep -rn "@Test" app/src/androidTest`, actual recount) | **26** (was 22 before this pass — +4, all in `VoiceOverlayTest`: success/failure turnOutcome, final-transcript-during-PROCESSING, idle-with-no-outcome) |
| `:app:compileDebugAndroidTestKotlin` | compiles clean — 26 Compose UI tests, not executed (no device attached in this sandbox) |
| `:app:lintDebug` / `:app:lintRelease` | **BUILD SUCCESSFUL**, 0 errors both, 12 pre-existing dependency-version warnings only (unchanged) |
| `sha256sum` on both APKs (fresh, this pass) | debug `c2bba71a799cf83aa6d79e7767cfe86c9f57f6a67b445b8601aa6813efcbabba`, release-unsigned `efc454592b179cd606881eeb3c77f394c8ab69918824be0770af1d9416a9b1e7` |
| `python -m openapi_spec_validator openapi/safedrive-v1.yaml` | **OPENAPI VALID** |
| Source scans (see below) | all 12 invariants confirmed directly from source |

**Source-scan invariants confirmed** (each grepped directly, not assumed): `assistantQueryUseCase(` still
has exactly one call site; `finishListening` still has a real composable call site; `onBufferReceived`
is still the only raw-audio touchpoint and is still a no-op; `BackendMode.REMOTE` never resolves to
`mockGateway`; `clientAttemptOf` is always `if (querySent/attempt.querySent) requestId else null` (never
unconditional); `contractVersion` is still checked at both cache-read and cache-write; `ErrorEnvelopeDto`/
`parseErrorEnvelope`/`mapErrorCode` still exist and are wired; **new this pass:** every `SpeechRecognizer.`
reference lives inside `AndroidSpeechRecognizerFactory`/error-code mapping, never called directly from a
caller thread; `VoiceInputEvent` delivery is `Channel`-backed in both the real controller and the fake,
not `SharedFlow`; the TTS-start collector (`CoroutineStart.UNDISPATCHED`) is registered before `before()`
(the `speak()` call) runs; `cancelCurrent`'s `clientAttemptOf` decision reads the `querySent` marker, not
an unconditional `requestId`; `SafeDriveContainer.remoteGatewayFor` is guarded by
`synchronized(remoteGatewayLock)`.

### Gate re-evaluation (supersedes both tables above for every gate it touches)

| Gate | Verdict | What changed this pass |
|---|---|---|
| **A** | **PASS** (unchanged) | Still exactly one `AssistantQueryUseCase` call site. |
| **B** | **PASS at logic/test level; DEVICE_PENDING for on-hardware behavior** (strengthened) | `SpeechRecognizer` threading is now provably serialized (in a genuinely testable way, not just "should be fine"); the voice overlay now shows real reply/error content instead of a spinner that vanished on completion; `VoiceInputEvent` delivery no longer has a startup-race loss window. Real-device STT/TTS/Handler-Looper behavior remains unverifiable in this sandbox. |
| **C** | **PASS** (strengthened) | `clientAttemptOf`/`SessionCoordinator` gateway-instance/TTS-race fixes all land under Gate C, each with a regression test that fails against the pre-fix code (verified by writing the test first, confirming a specific pre-fix failure, then fixing it — not just written to already agree with the fixed code). |
| **D** | **PASS at logic/test level; DEVICE_PENDING for pixel-level rendering** (unchanged) | No parity-affecting change this pass. |
| **E** | **FAIL** (unchanged — still correctly not claimed as passing) | Device QA and human backend/Android owner review remain the only outstanding criteria; nothing in this pass can satisfy them from this sandbox. |

**Final conclusion: `DEVICE_VALIDATION_PENDING`.** Per the master prompt's own rule, this status is only
earned once every named code/contract logic defect is fixed with a genuine regression test — that is now
true for all 7 items above, verified by a cold clean build (194/194 JVM tests passing, 0 failures/errors,
26 AndroidTest compiling clean, 0 lint errors, OpenAPI valid, 12 source-scan invariants confirmed).
Explicitly **not** `BACKEND_READY` — Gate E's device-QA and human-review criteria remain outstanding and
cannot be produced from this sandbox. AI Backend work has not been started, per the master prompt's
explicit instruction.

## Third independent re-audit — 6 further logic defects found and fixed (2026-07-28)

**This section supersedes the "Second independent re-audit" section's `DEVICE_VALIDATION_PENDING`
conclusion above.** A further independent re-audit (green build genuinely re-confirmed: 194/194 JVM
tests, 26 AndroidTest compiling, 0 lint errors, OpenAPI valid) still found **6 further logic/race defects
the existing test suite did not catch**, spanning `AndroidSpeechRecognizerController`, `ConversationRepository`/
`AssistantTurnCoordinator`, `VoiceAssistantCoordinator`, `AssistantTurnMetricsRecorder` and `VoiceOverlay`.
Status reverted to `NOT_BACKEND_READY` for this pass per the master prompt's explicit rule, then
re-evaluated below only after every item was fixed with a genuine regression test.

### Root cause, fix and regression test per item

| # | Root cause (confirmed by direct source read, not assumed) | Fix | Regression test |
|---|---|---|---|
| 1 | The **actual production voice trigger** (`feature/voice/VoiceTrigger.kt`'s `rememberVoiceTrigger`) only ever calls `VoiceController.startWakeWord(screen)` — never `startListening()`. But `startWakeWord()` skipped `recognizerFactory.isRecognitionAvailable()` entirely and never reset `pendingCaptureTimings`, while `startListening()` (only ever called by tests, never by production code) had both. Every existing "production-like" test happened to call `startListening()` directly, so this divergence was invisible to the whole suite. | Both public methods now delegate to one shared private `beginListening(screen)` that does permission check → availability check → mints `generation` once → resets `pendingCaptureTimings` with a fresh `micRequestedAtMs` → sets `WAKE_WORD_DETECTED` → dispatches create/start via `MainThreadExecutor`. `AndroidSpeechRecognizerController.kt` | `AndroidSpeechRecognizerControllerTest.kt`: `startWakeWord` checks availability before creating a recognizer (unavailable factory → 0 `create()` calls, state `ERROR`); `startWakeWord` initializes fresh capture timings (read via reflection on the private field — `onResults(Bundle)` cannot carry real data in a plain JVM unit test, see note below); two consecutive `startWakeWord` sessions never reuse the previous session's timing; `startWakeWord` increments generation exactly once; permission-denied never calls availability/create. |
| 2 | `cancel()`/`finishListening()`'s dispatched `MainThreadExecutor` blocks read/wrote a single mutable `recognizer` field with **no generation check at all** — they blindly acted on "whatever `recognizer` currently is" at the moment the block actually ran. `AndroidMainThreadExecutor.execute` runs a block **immediately** when the caller is already on the main thread, but queues it (via `Handler.post`) otherwise — so a `cancel()`/`finishListening()` issued from a background thread (e.g. the preferences collector) could have its block sitting in the queue *behind* a same-thread, immediately-executed newer `startListening()`, letting the stale block tear down/stop the **new** session instead of the one it was meant to target. | Replaced the bare `recognizer` field with `ActiveSession(generation, recognizer)`. `cancel()`/`finishListening()` capture their *target* generation before dispatching, and their blocks check `activeSession?.generation == targetGeneration` before acting — a mismatch (a newer session already took over) is a safe no-op. `shutdown()` is now terminal via an `isShutdown` flag checked in every dispatched `start` block. | 3 new tests using a genuinely reordered `FakeMainThreadExecutor` (extended with `pollPending()` to hold a block and run a *later*-queued one first, reproducing the exact out-of-order execution `AndroidMainThreadExecutor` can cause): stale cancel block run after gen 2 started never destroys gen 2; stale finish block run after gen 2 started never stops gen 2; shutdown queued before a start block is terminal (0 `create()` calls). Plus a real-16-thread concurrent start/cancel test asserting at most one surviving active recognizer. |
| 3 | W1.2's `AssistantTurnState` (idle/in-flight/success/failure/cancelled) did not exist. `ConversationRepository` exposed independent setters (`setInFlightTurn`/`setRetryableTurn`/`setErrorMessage`/`addAssistantMessage`); a turn's terminal transition was published as **2–3 separate `MutableStateFlow.update` calls** (`setInFlightTurn(null)` first, then the reply/error afterward) — a collector could observe `inFlightTurn == null` before the reply/error was actually visible. | Added `AssistantTurnState` sealed interface (`Idle`/`InFlight`/`Success`/`Failure`/`Cancelled`, each carrying `requestId`/`generation`/`source`) to `AssistantTurnModels.kt`. `ConversationRepository` now exposes only atomic reducers — `beginTurn`/`completeSuccess`/`completeFailure`/`completeCancelled` — each doing exactly one `_state.update{}` that publishes `inFlightTurn`, `messages`, `retryableTurn`, `errorMessage` and `turnState` together. `AssistantTurnCoordinator` rewritten to call only these. | `AssistantTurnCoordinatorTest.kt`: a full-history collector proves no observed state ever has `inFlightTurn == null` with `turnState` still `InFlight`, and the exact state that clears `inFlightTurn` on success already contains the appended reply (`turnState.reply == messages.last()`); failure/cancelled terminal states carry the exact `requestId`/`generation`/`source` of the turn that produced them. |
| 4 | `VoiceAssistantCoordinator.route()` inferred a voice turn's outcome by waiting for `conversationRepository.state.first { it.inFlightTurn == null }`, then **re-reading `.value` a second time** and picking `state.messages.lastOrNull { sender == SAFEDRIVE }` — ambient state with no correlation to which turn actually produced it. A stale pre-existing SAFEDRIVE message, or a second turn racing in immediately after, could be misattributed to this voice turn. | `AssistantTurnCoordinator.submit(...)` gained an `onStarted: (requestId, generation) -> Unit` callback (default no-op, so every other call site is unaffected) invoked synchronously, still under lock, with the exact id/generation minted for the attempt. `VoiceAssistantCoordinator` now waits for the specific `AssistantTurnState` terminal value matching that exact `requestId`+`generation`, and reads the reply/error directly off that state object — never off ambient `messages`/`retryableTurn`. | `VoiceAssistantCoordinatorTest.kt`: a pre-seeded, unrelated old SAFEDRIVE message is never used as the new voice turn's outcome; a text turn submitted immediately after a voice turn completes never overwrites the voice turn's own `turnOutcome`; a real-thread test with a genuine 300 ms gateway delay proves correlation holds even with a concurrent (rejected) text submit racing in mid-flight. |
| 5 | `AssistantTurnCoordinator`'s success branch called `metricsRecorder.record(base metrics)` **after** `awaitTtsStarted()`/`speak()`. On a real `Dispatchers.Default` scope, an engine whose `onStart` callback reaches another thread fast enough could let the `recordTtsStarted()` patch coroutine run **before** the base record — finding no matching `lastTurn` yet (still null/previous turn) and silently, permanently dropping the real TTS-start timestamp. The existing tests used a single virtual-time test dispatcher, under which the patch coroutine structurally cannot run before the base record — so they could never reproduce this. Separately, `recordTtsStarted()` patched `_lastTurn` but never logged, so the log line that's supposed to carry `responseToTtsMs` never actually showed it non-null. | Base metrics are now recorded **before** `awaitTtsStarted()`/`speak()` are even called — there is no longer any instant after `speak()` where a matching `lastTurn` doesn't already exist for that `requestId`, closing the race structurally rather than by dispatcher luck. `AssistantTurnMetricsRecorder.recordTtsStarted()` now also emits its own redacted log line (numbers only) when the patch actually applies. | `AssistantTurnCoordinatorTest.kt`: a hand-built `TtsController` whose `speak()` fires the onStart event from a **genuine new `Thread`**, wired into a coordinator on a real `CoroutineScope(Dispatchers.Default)` (not `TestScope`), polled to assert `ttsStartedAtMs` is eventually non-null — the actual production race, not a virtual-time trick that cannot reproduce it. |
| 6 | `VoiceOverlay`'s `when` block checked `speaking` **before** `outcome is VoiceTurnOutcome.Success`/`Failure` — so the real reply/error text was hidden behind a generic "SafeDrive đang trả lời" placeholder for the entire time TTS was reading the reply aloud, the opposite of W6.10's intent. | Reordered so `Success`/`Failure` outcome branches are checked first; the `Success` branch itself now switches copy/action based on `speaking` (shows the real reply either way; a "Dừng đọc" stop button while speaking, a "Đóng" dismiss button once done) instead of being pre-empted by a separate `speaking` branch. "Dừng đọc" only calls `ttsController.stop()` — it never dismisses the outcome, so the reply stays visible after TTS stops until the user explicitly closes it. | `VoiceOverlayTest.kt`: SPEAKING+Success shows the real reply text and a stop button, not the generic placeholder; clicking "Dừng đọc" only increments `stopCallCount` (dismiss count stays 0) and the reply remains displayed afterward; SPEAKING+Failure shows the real error message, not hidden by the speaking copy. |

**Note on item 1's tests**: `android.os.Bundle` cannot carry real data in a plain JVM unit test under
AGP's `isReturnDefaultValues=true` (`putStringArrayList`/`getStringArrayList` are stubbed to no-ops,
confirmed empirically — a populated Bundle always reads back `null` here). This means `onResults(Bundle)`
can never produce a non-blank transcript in this environment, so the "final `VoiceInputEvent` carries the
correct `VoiceCaptureTimings`" claim is verified two ways instead: (a) directly, via reflection on
`AndroidSpeechRecognizerController`'s private `pendingCaptureTimings` field, proving it is freshly reset
per `startWakeWord`/`startListening` call; (b) end-to-end propagation of a `VoiceCaptureTimings` object
into recorded `AssistantTurnMetrics` is already covered by the pre-existing, still-passing
`VoiceAssistantCoordinatorTest`'s "mic-recognizer capture timings ... reach the recorded metrics (W4)"
test, which injects the timings via `FakeVoiceController` and so never depends on `Bundle`. Real
`SpeechRecognizer.onResults(Bundle)` parsing on an actual device remains `DEVICE_PENDING`, unchanged in
status by this pass (this was already true before this pass; not a new gap).

### Verification commands and results (this pass)

| Command | Result |
|---|---|
| `gradlew.bat clean :app:assembleDebug :app:testDebugUnitTest --rerun-tasks :app:lintDebug :app:compileDebugAndroidTestKotlin :app:assembleRelease :app:lintRelease` | **BUILD SUCCESSFUL in 51s**, 117 actionable tasks (first attempt failed compilation on an invalid test override signature; fixed, then failed 3 test assertions written with a wrong expectation for the Bundle stub / natural FIFO ordering — see "errors and fixes" below; final re-run is fully clean) |
| Unit test XML aggregation (`app/build/test-results/testDebugUnitTest/*.xml`, summed via PowerShell `[xml]` parsing, not eyeballed) | **tests=211 failures=0 errors=0 skipped=0**, 24 test classes (194 → 211: +17 new tests across `AndroidSpeechRecognizerControllerTest` (+12), `AssistantTurnCoordinatorTest` (+4), `VoiceAssistantCoordinatorTest` (+3, net after also adding a real-thread test); some earlier draft tests were rewritten rather than kept, so the net delta is 17 not the raw count of tests authored) |
| AndroidTest count (`grep -rn "@Test" app/src/androidTest`, actual recount) | **29** (was 26 before this pass — +3, all in `VoiceOverlayTest`: SPEAKING+Success shows real reply, click-stop-doesn't-dismiss, SPEAKING+Failure not hidden) |
| `:app:compileDebugAndroidTestKotlin` | compiles clean — 29 Compose UI tests, **not executed** (`adb devices` empty, confirmed again this pass) |
| `:app:lintDebug` / `:app:lintRelease` | **BUILD SUCCESSFUL**, 0 errors both; SARIF-parsed rule list confirms all warnings are the same pre-existing `AndroidGradlePluginVersion`/`GradleDependency`/`NewerVersionAvailable` dependency-version notices, none new |
| `sha256sum` on both APKs (fresh, this pass) | debug `62d652b754a3493bd1144b83e78cef5024970dc265d98782d10ad3d1c58b191e`, release-unsigned `945556277ee102eeebda0070bfba059b0f0b0d3709590f7b7b2c14aa1abfb147` |
| `python -m openapi_spec_validator openapi/safedrive-v1.yaml` | **OPENAPI VALID** (spec itself unchanged this pass — no contract/DTO defect was in scope this time) |
| `adb devices` | empty — no device/emulator attached in this sandbox; `connectedDebugAndroidTest` genuinely cannot run, not skipped by choice |

**Errors hit and fixed while producing the above** (kept here per the master prompt's "prove the test
would have failed on the old code, or explain the failure mode precisely" requirement): (a) a first test
draft overrode `Context.checkPermission(permission: String?, ...)` — compile error, the real stub
signature takes non-nullable `String`; fixed the override signature. (b) A first "finishListening queued
for generation 1 does not stop generation 2" test asserted gen 1's recognizer would **not** be stopped —
wrong: under natural FIFO queueing (no reordering), `finishListening()`'s block legitimately runs while
gen 1 is still the active session, so it correctly *should* stop it; the test's own premise was flawed,
not the code. Fixed by adding `FakeMainThreadExecutor.pollPending()` to construct a **genuine** out-of-order
scenario (hold the gen-1 block, let gen-2's start block run first, then run the held gen-1 block) — this
is the actual scenario the bug report described, and only this construction exercises the fix. (c) Two
tests calling `onResults(Bundle)` with `putStringArrayList` timed out (`TimeoutCancellationException`)
waiting for a `VoiceInputEvent` that never arrived — root-caused to the `Bundle` stub limitation described
above, not a code defect; rewritten to inspect `pendingCaptureTimings` via reflection instead.

**Source-scan invariants reconfirmed this pass**: `startWakeWord`/`startListening` both delegate to the
same private `beginListening`; every `cancel()`/`finishListening()`/`startListeningInternal` dispatched
block checks `activeSession`'s generation before touching a recognizer; `AssistantTurnCoordinator` no
longer calls `setInFlightTurn`/`setRetryableTurn`/`setErrorMessage`/`addAssistantMessage` anywhere (only
`beginTurn`/`completeSuccess`/`completeFailure`/`completeCancelled`); `VoiceAssistantCoordinator` no longer
references `messages.lastOrNull`/`ChatSender.SAFEDRIVE` anywhere; `metricsRecorder.record(` (base) appears
before `awaitTtsStarted(`/`ttsController.speak(` in `AssistantTurnCoordinator`'s success branch (line
order confirmed directly).

### Gate re-evaluation

| Gate | Verdict | What changed this pass |
|---|---|---|
| **A** | **PASS** (strengthened) | `AssistantTurnState` is now a real W1.2 state machine with atomic terminal publishes, not an implicit combination of independently-set fields. |
| **B** | **PASS at logic/test level; DEVICE_PENDING for on-hardware behavior** (strengthened) | The actual production wake-word entry path is now validated/timing-correct (previously only the untested `startListening` path was); `MainThreadExecutor` dispatch is now provably safe against genuine out-of-order execution, not just FIFO. |
| **C** | **PASS** (strengthened) | The TTS metrics race is now closed structurally (proven with a real background thread, not virtual time) and voice-turn outcome correlation is now exact (`requestId`+`generation`), not ambient-state inference. |
| **D** | **PASS at logic/test level; DEVICE_PENDING for pixel-level rendering** (strengthened) | Voice overlay now shows the real reply/error even while TTS is speaking, matching W6.10 exactly. |
| **E** | **FAIL** (unchanged — still correctly not claimed as passing) | Device QA and human backend/Android owner review remain the only outstanding criteria. |

**Final conclusion: `DEVICE_VALIDATION_PENDING`.** All 6 defects named in this pass's re-audit are now
fixed with a genuine regression test each (adversarial ordering proven via real threads/`CyclicBarrier`/a
reordering-capable fake executor where the defect was a concurrency race, and via direct/reflection-based
state inspection where the defect was a Bundle-environment-limited or data-correlation bug) verified
against a cold clean build (211/211 JVM tests passing, 0 failures/errors, 29 AndroidTest compiling clean,
0 new lint issues, OpenAPI still valid). Explicitly **not** `BACKEND_READY` — Gate E's device-QA and
human-review criteria remain outstanding and cannot be produced from this sandbox. AI Backend work has
not been started, per the master prompt's explicit instruction.

## Fourth independent re-audit — 5 further architectural blockers found and fixed (2026-07-28)

**This section supersedes the "Third independent re-audit" section's `DEVICE_VALIDATION_PENDING`
conclusion above.** A further independent re-audit — explicitly distrusting the prior pass's own report,
per instruction — found **5 further architectural blockers**, all genuine concurrency/correctness gaps
the existing test suite did not catch because they require either genuine multi-thread contention or a
specific ordering the prior tests never constructed. Status reverted to `NOT_BACKEND_READY` for this pass,
re-evaluated below only after every blocker was fixed **and proven closed by architecture, not merely by a
green test**, per the master prompt's explicit "prove the race condition is eliminated architecturally,
not just make tests green" requirement.

### Blocker 1 — voice could wait for a terminal state that would never arrive

**Root cause (confirmed by direct source read):** `VoiceAssistantCoordinator.route()` correlated a voice
turn's outcome by calling `conversationRepository.state.map { it.turnState }.first { it.matches(requestId,
generation) }` — but `ConversationRepository.state` is a `StateFlow`, which only ever holds the **latest**
value. If the voice turn's terminal state was published and then a *different*, later turn (started and
finished before this coroutine ever got scheduled to begin collecting) overwrote it, the `.first{}`
collector would search the *current* (already different) value forever — `StateFlow` does not replay
superseded history. This is a genuine indefinite hang: `turnOutcome` would never be published and
`voiceController.clearProcessingState()` would never run, leaving PROCESSING stuck.

**Fix (architectural, not a timing patch):** Introduced `AssistantTurnCoordinator.StartedTurn(requestId,
generation, completion: Deferred<AssistantTurnState>)`. `submit()`'s `onStarted` callback now hands back
this handle, with `completion` a `CompletableDeferred` created **synchronously at turn-start time** (or
already-completed, via the `CompletableDeferred(value)` factory, for the health/configuration-blocked
synchronous path) and resolved **exactly once** — either by `beginTurnLocked`'s coroutine reaching a
terminal `GatewayResult`, or by `cancelCurrent()`. `VoiceAssistantCoordinator.route()` now does
`turn.completion.await()` instead of subscribing to `ConversationRepository.state`.

**Why this eliminates the race architecturally, not just in tests:** a `Deferred` is a genuine per-turn
*identity* — `await()` resolves to the exact value `complete()` was called with, **regardless of when
`await()` is called relative to that completion**, and regardless of what any other, unrelated
`StateFlow` shows by the time `await()` runs. There is no "subscribe before/after" window at all: the
value is retained by the `Deferred` object itself, not broadcast-and-forgotten. Cancellation is handled
the same way — `cancelCurrent()` resolves the cancelled turn's own `completion` to
`AssistantTurnState.Cancelled` under the same lock that mints/clears it, so a waiter is released
immediately instead of hanging on a network call that will never complete for it. Exactly-once completion
is enforced by the existing `generation != currentGeneration` guard: whichever of
`cancelCurrent()`/the turn's own coroutine reaches the terminal transition first bumps/reads
`currentGeneration` inside the same `synchronized(lock)` block, so the other path's own `completion.complete()`
call is provably unreachable (guarded behind the same generation check that already existed for
`ConversationRepository` writes). No history/map of past turns is retained — each `completion` is a local
value, live only as long as the awaiting coroutine holds a reference to it.

Files: `AssistantTurnCoordinator.kt` (`StartedTurn`, `currentCompletion`, `beginTurnLocked`,
`cancelCurrent`, the health-blocked branch of `submit()`), `VoiceAssistantCoordinator.kt` (`route()`
rewritten, the `AssistantTurnState.matches()` extension deleted — no longer needed).

Tests (`AssistantTurnCoordinatorTest.kt`): a turn's completion resolves correctly even after
`ConversationRepository.state.turnState` has since moved on to a completely different, later turn (the
exact scenario above, constructed deterministically: turn 1 submitted and completed, turn 2 submitted and
completed — overwriting `turnState` — *then* turn 1's `completion.await()` is called and asserted to
still return turn 1's own `Success`); the same construction for a synchronous health-blocked `Failure`;
cancelling a turn resolves its `completion` to `Cancelled`, releasing the waiter with no Success/Failure;
a cancelled turn's `completion` is resolved exactly once (asserted via `Deferred` reference identity
across two `await()` calls, with a stale gateway resolution allowed to run in between via
`advanceUntilIdle()`).

### Blocker 2 — `VoiceAssistantCoordinator.start()` was not thread-safe

**Root cause:** `collectorStarted` was a plain `Boolean`: `if (collectorStarted) return; collectorStarted
= true`. This read-then-write is not atomic — two OS threads calling `start()` at the same instant could
both observe `false` before either writes `true`, and both would then register an independent collector
on `voiceController.events.onEach(::route).launchIn(externalScope)`.

**Fix:** `collectorStarted` is now `AtomicBoolean`; `start()` does
`if (!collectorStarted.compareAndSet(false, true)) return`.

**Why this eliminates the race architecturally:** `compareAndSet` is a single atomic hardware (CAS)
operation — of any number of concurrent callers, exactly one can ever observe its own CAS succeed (`false`
→ `true`); every other caller's CAS necessarily fails and returns immediately. There is no window between
"read" and "write" for a second caller to slip through, unlike the prior two-statement check.

File: `VoiceAssistantCoordinator.kt`.

Tests (`VoiceAssistantCoordinatorTest.kt`): `start()` called concurrently from 32 real OS threads
(`CyclicBarrier`-synchronized) registers **exactly one** collector — measured directly by wrapping
`voiceController.events` with `.onStart { subscriptionCount.incrementAndGet() }` (a direct subscription
count, deliberately *not* inferred from Channel fan-out semantics, per the explicit instruction not to
rely on "only one collector gets a given Channel value" to paper over a multi-collector bug); a sequential
`start()` ×3 test asserts the same direct count is `1`.

### Blocker 3 — `AndroidSpeechRecognizerController` was not fully main-thread-confined

**Root cause (4 sub-issues, confirmed by direct source read):**
1. `recognizerFactory.isRecognitionAvailable()` was called directly in `beginListening()`, on the
   *caller's* thread — every other platform operation (`create`/`startListening`/`stopListening`/
   `cancel`/`destroy`) was already confined to `mainThreadExecutor`, but this one was not.
2. The per-session capture-timings field (`pendingCaptureTimings`) was written by `beginListening()` on
   the caller's thread but read/written by `RecognitionListener` callbacks on the main thread — a genuine
   unsynchronized cross-thread field, not even `@Volatile`.
3. That same field had no ownership tag tying it to a specific generation/session — a stale callback
   could read/write timings that, by then, belonged to a different, newer session.
4. `beginListening()` published `VoiceUiState(state = WAKE_WORD_DETECTED, ...)` **before** dispatching to
   `mainThreadExecutor` — so if `shutdown()` had already been requested, the dispatched block would
   correctly refuse to create a recognizer, but the UI had *already* been shown "preparing" and would
   never move on to anything else, since nothing else in that call path would ever update `_state` again.

**Fix (architectural):** `ActiveSession` now owns both the recognizer instance **and** its `timings`
(`var timings: PartialCaptureTimings`), and is only ever constructed/read/mutated from inside a
`mainThreadExecutor`-dispatched context — either the block that creates it, or a `RecognitionListener`
callback (which Android always delivers on the same thread the `SpeechRecognizer` was created on — the
main thread here, since creation itself only ever happens inside `mainThreadExecutor`). Every listener
callback that touches `timings` now does so via `activeSession?.takeIf { it.generation == gen }`,
tagging ownership to the exact session, not "whatever the field currently holds". `beginListening()` now
performs the availability check, `ActiveSession` construction, **and** the `WAKE_WORD_DETECTED` state
publish **all inside the same single `mainThreadExecutor.execute {}` block**, gated by the same
`isShutdown`/generation check already used for the create/start operations.

**Why this eliminates the race architecturally, not just in tests:** confinement now applies uniformly —
there is exactly one code path that ever touches `activeSession`/`timings`/`recognizer`, and it is always
reached via `mainThreadExecutor`. Since production's `AndroidMainThreadExecutor` only ever *executes*
(never merely queues-and-forgets) on the main thread — either immediately if already there, or via
`Handler.post` — every read and write is single-thread-confined by construction, needing no lock. The
`isShutdown`-and-generation check now gates the *first* state-changing statement in `beginListening`'s
block (not a later one), so a call issued after `shutdown()` returns without ever touching `_state`,
`activeSession`, or the recognizer — it is structurally impossible for it to leave a "preparing" state
with no path forward, because no such state is ever published in that case. In production this adds no
latency: the dispatched block still runs synchronously/immediately in the overwhelmingly common case
(the caller is already on the main thread — a Compose action handler or the permission-result callback).

Files: `AndroidSpeechRecognizerController.kt` (`ActiveSession` gains `timings`; `beginListening`
restructured; all three `RecognitionListener` callbacks that touch timings updated),
`FakeSpeechRecognizerFactory.kt` (gains `isRecognitionAvailableCallCount`/`isRecognitionAvailableCallingThreads`
so a test can prove *where* this call happens, not just *that* it happens).

Tests (`AndroidSpeechRecognizerControllerTest.kt`): `isRecognitionAvailable()` is only ever called from
inside the executor, never on the caller's thread (a real background thread calls `startWakeWord`; the
call count stays `0` until `executor.runAll()` is invoked from the test thread, and the recorded calling
thread is asserted to not be the caller's thread); a stale generation's `RecognitionListener` callback
arriving after a new session started never modifies the new session's capture timings (verified via
reflection into `activeSession.timings`, since real callbacks can't be reproduced through
`onResults(Bundle)` in this environment — see the existing Bundle-stub note); `startWakeWord` called
after `shutdown()` never creates a recognizer *and* never leaves state stuck at `WAKE_WORD_DETECTED`
(asserts state is unchanged from before the attempt); the existing 16-real-thread concurrent
start/cancel test now additionally asserts every `isRecognitionAvailable()` call was made from the test
thread (i.e. via `executor.runAll()`), never from any of the 16 spawned caller threads. Two pre-existing
capture-timing tests and the generation-count test were updated to call `executor.runAll()` before
reading state/timings, since both are now only populated once the confined block actually executes
(previously read synchronously, before confinement moved them).

### Blocker 4 — TTS metrics patch performed a side effect inside a retried `update{}` lambda

**Root cause:** `AssistantTurnMetricsRecorder.recordTtsStarted()` captured "did this patch apply" into an
outer `var patched` from *inside* the lambda passed to `MutableStateFlow.update {}`. That lambda is
retried by `update`'s own internal `compareAndSet` loop every time it loses a race against a concurrent
writer (e.g. a concurrent `record()` call moving `_lastTurn` on to a different turn) — so `patched` could
end up holding a value from an attempt whose CAS never actually won, or whose value was for a turn that,
by the time the whole `update` call returned, was no longer current. The method would then log
"ttsStartedAtMs patched" naming a value that was never actually the one visible in `lastTurn`.

**Fix (architectural):** Replaced the `update{}` call with a hand-rolled `compareAndSet` retry loop:
read `current`, return early (no-op, no log) if its `requestId` doesn't match, compute `patched`, and only
log — *after* — a direct `_lastTurn.compareAndSet(current, patched)` call returns `true`.

**Why this eliminates the bug architecturally:** the log statement is now physically inside the
`if (compareAndSet(...))` block, reading only the `patched` value that specific successful call just
made visible. There is no retry path that can reach the log statement with a value other than the one
that genuinely just committed — the side effect (logging) is inseparable from the successful transition
itself, not decoupled from it by an intermediate variable that survives failed attempts.

File: `AssistantTurnMetricsRecorder.kt`.

Tests (new file `AssistantTurnMetricsRecorderTest.kt`, 4 tests): correct requestId is patched and logged
exactly once, with the exact expected `responseToTtsMs`; a stale (non-matching) requestId is a no-op and
never logged; a late TTS event for an old turn racing a concurrent `record()` of a new turn on two real
threads never logs "patched" for the old id once the new one is current; a sustained high-contention test
(8 real attacker threads hammering `recordTtsStarted` for an id that is **never** the argument to any
`record()` call, concurrent with 500 real `record()` calls rotating the current turn on the main thread)
asserts zero "patched" log lines ever name that id — a property that must hold under *any* correct
implementation regardless of scheduling, and is exactly the class of bug (a log decoupled from the
actually-committed value) blocker 4 fixes.

### Blocker 5 — audit of atomic turn transitions (no new defect found)

Re-audited `AssistantTurnCoordinator`/`ConversationRepository` end-to-end per the explicit instruction.
Confirmed via direct source read: `beginTurn`/`completeSuccess`/`completeFailure`/`completeCancelled`
remain the *only* mutation methods on `ConversationRepository` (grepped — no `setInFlightTurn`/
`setErrorMessage`/`addAssistantMessage` anywhere in the codebase, main or test); each performs exactly one
`MutableStateFlow.update{}` call (5 total `_state.update` call sites in `InMemoryConversationRepository.kt`:
1 for `addUserMessage`, 4 for the atomic reducers); `submit()`'s `addUserMessage` call and the
in-flight-marking `beginTurn`/synchronous-failure `completeFailure` call remain inside the same
`synchronized(lock)` critical section, so no concurrent `submit()`/`retry()`/`cancelCurrent()` call can
observe or act on the gap between them — the only user-visible intermediate state (user bubble appended,
turn not yet marked in-flight) is an intentional, single-threaded-safe UI sequencing choice, not a race
window. No new defect found; no code change made for this item beyond what blockers 1–4 already touched.

### Latency and no-regression audit

Full-repo scan for `delay(`/`Thread.sleep`/`runBlocking`/polling loops/`messages.lastOrNull`/non-atomic
`collectorStarted` in `android/app/src/main`: the only `delay(` call sites are the pre-existing, legitimate
Emergency countdown tick (`SafeDriveContainer.kt`), the Emergency display refresh tick
(`EmergencyViewModel.kt`), and the Developer-Mode-only, default-zero simulated latency profile
(`MockSafeDriveGateway.kt`) — none touched by this pass, none added. The one new `while (true)` loop
(`AssistantTurnMetricsRecorder.kt`'s `compareAndSet` retry) is a standard non-blocking CAS loop with no
sleep/delay at all, exactly the pattern the master prompt itself requested ("Dùng compareAndSet loop").
`messages.lastOrNull` for voice-outcome inference: zero occurrences anywhere in `src/main` (confirmed by
grep — the mechanism it used to support no longer exists). `collectorStarted` as a non-atomic `Boolean`:
zero occurrences (converted to `AtomicBoolean`). No new latency, delay, or polling was introduced in
production code; `Thread.sleep`-based bounded polling appears only in test code waiting on real
background threads, an established pattern already used elsewhere in this suite (e.g. the pre-existing
24-real-thread `AssistantTurnCoordinatorTest` concurrency test), not something newly introduced here.

### Verification commands and results (this pass)

| Command | Result |
|---|---|
| `gradlew.bat clean :app:assembleDebug :app:testDebugUnitTest --rerun-tasks :app:lintDebug :app:compileDebugAndroidTestKotlin :app:assembleRelease :app:lintRelease` | **BUILD SUCCESSFUL in 27s**, 105 actionable tasks (two earlier attempts failed: one compile error — a test's `MutableStateFlow(HealthStatus(...))` inferred a non-nullable type where `HealthStatus?` was needed; one test failure — `UncompletedCoroutinesError` from launching a long-lived voice-event collector on the test's main scope instead of `backgroundScope`; both fixed, then a fully clean re-run) |
| Unit test XML aggregation (PowerShell `[xml]` parsing of `app/build/test-results/testDebugUnitTest/*.xml`) | **tests=224 failures=0 errors=0 skipped=0**, 25 test classes (211 → 224: +13 — 4 in `AssistantTurnCoordinatorTest` (35→39), 2 in `VoiceAssistantCoordinatorTest` (16→18), 3 in `AndroidSpeechRecognizerControllerTest` (15→18, plus one existing test extended with an extra assertion, not counted as new), 4 in the new `AssistantTurnMetricsRecorderTest`) |
| AndroidTest count (`grep -rn "@Test" app/src/androidTest`) | **29** (unchanged — this pass's 5 blockers are all JVM-testable concurrency/architecture issues; no Compose UI surface changed) |
| `:app:compileDebugAndroidTestKotlin` | compiles clean — 29 Compose UI tests, **not executed** (`adb devices` empty, re-checked this pass) |
| `:app:lintDebug` / `:app:lintRelease` | **BUILD SUCCESSFUL**, 0 errors both; SARIF rule list re-confirmed identical to the prior pass's 12 pre-existing dependency-version notices, none new |
| `sha256sum` on both APKs (fresh, this pass) | debug `7f6d8a2a551fc765dbc41f4c75b919ffc369becdd74920b785a44fdec31dc527`, release-unsigned `cc8467d2b3d7913b642bd6a96baa0ab462eda5942444057324c7de75d607de15` |
| `python -m openapi_spec_validator openapi/safedrive-v1.yaml` | **OPENAPI VALID** (spec unchanged this pass — all 5 blockers are internal architecture, no wire/DTO impact) |
| `adb devices` | empty — no device/emulator attached; `connectedDebugAndroidTest` genuinely cannot run |

### Gate re-evaluation

| Gate | Verdict | What changed this pass |
|---|---|---|
| **A** | **PASS** (strengthened) | Turn completion is now a genuine per-turn `Deferred` identity, immune to `ConversationRepository`'s StateFlow-latest-only limitation. |
| **B** | **PASS at logic/test level; DEVICE_PENDING for on-hardware behavior** (strengthened) | `AndroidSpeechRecognizerController` is now fully main-thread-confined, including the availability check and per-session timing ownership; `shutdown()` can no longer leave the UI stuck in a fake "preparing" state. |
| **C** | **PASS** (strengthened) | Voice turn correlation and TTS metrics patching are now both provably race-free by construction (Deferred identity; CAS-then-log ordering), not merely "passes under the test scheduler used so far". |
| **D** | **PASS at logic/test level; DEVICE_PENDING for pixel-level rendering** (unchanged) | No UI-surface change this pass. |
| **E** | **FAIL** (unchanged — still correctly not claimed as passing) | Device QA and human backend/Android owner review remain the only outstanding criteria. |

**Final conclusion: `DEVICE_VALIDATION_PENDING`.** All 5 blockers named in this pass are fixed with a
change in *architecture*, not just a passing test — each fix is accompanied by an explicit "why this is
now structurally impossible" argument in this document, verified against a cold clean build (224/224 JVM
tests passing, 0 failures/errors, 29 AndroidTest compiling clean, 0 new lint issues, OpenAPI still valid).
Explicitly **not** `BACKEND_READY` — Gate E's device-QA and human-review criteria remain outstanding and
cannot be produced from this sandbox. AI Backend work has not been started, per the master prompt's
explicit instruction, in this or any prior pass.

## Fifth independent re-audit — 2 further architectural blockers plus 1 latency-honesty defect, and 2 test-gap closures (2026-07-28)

A fifth pass, again starting from a re-read of the *current* source (not trusting the fourth pass's
report), fixing 2 more genuine architectural blockers, closing 2 real test-coverage gaps in existing
regression tests (the underlying production code for these two was already correct from the fourth pass —
only the tests failed to actually discriminate correct from buggy behavior), and finding one additional,
previously-unnoticed latency-honesty defect during the mandated re-audit of prior fixes.

### Blocker 1 — turn completion could hang forever if the query pipeline threw instead of returning `Failure`

**Root cause (confirmed from source):** `AssistantTurnCoordinator.beginTurnLocked`'s launched coroutine
called `assistantQueryUseCase(...)` with **no try/catch at all**. `AssistantQueryUseCase.invoke()` itself
has no catch around `sessionCoordinator.currentSession()` or the gateway call, and `RemoteSafeDriveGateway.safeCall`
only catches three specific exception types (`SocketTimeoutException`/`SerializationException`/`IOException`)
*around* the network call — a mapper bug (`it.toDomain()` throwing on a malformed field), any other
unanticipated `RuntimeException` from session resolution, or a bug in the gateway itself would propagate
**uncaught** out of the coroutine. Since this coroutine is launched via `externalScope.launch {}` with no
enclosing try/catch, the exception would surface as an uncaught exception on the application scope —
`currentJob`/`currentAttempt`/`currentCompletion` would never be cleared, `ConversationRepository` would
stay `InFlight` forever, and `StartedTurn.completion` would never resolve — a real, permanent hang for any
waiter (`VoiceAssistantCoordinator.route()`'s `completion.await()` would never return, leaving `PROCESSING`
stuck indefinitely). Separately, `onStarted` (a caller-supplied callback, e.g.
`VoiceAssistantCoordinator.route()`'s `{ turn -> startedTurn = turn }`) was invoked with no try/catch
*before* `currentJob` was assigned in `beginTurnLocked` — a throwing callback would propagate out of
`submit()` itself, leaving the turn marked `InFlight` in `ConversationRepository` with no coroutine ever
launched to resolve it.

**Fix (architectural):** The call to `assistantQueryUseCase(...)` is now wrapped: `CancellationException`
is always rethrown (cooperative cancellation must keep working exactly as before — `cancelCurrent()`
already resolves `completion` itself under `lock`, so the cancelled coroutine has nothing left to do once
it observes cancellation), but any other `Exception` is converted to
`GatewayResult.Failure(GatewayError.Unexpected(e.message))` — a new, purely local `GatewayError` case,
never sent over the wire, added specifically to distinguish "our own pipeline threw" from a confirmed
backend `Server` error. This converted result then flows through the *exact same* `when (result) { ... }`
terminalization branch as a real network failure — same generation check, same `completion.complete()`,
same `ConversationRepository.completeFailure()`, same retry-lineage construction. The `onStarted` callback
is now invoked via a new `invokeOnStartedSafely` helper (catch-log-swallow, rethrowing only
`CancellationException`) called *before* `currentJob` is assigned — so even a throwing callback can never
prevent the turn's own coroutine from launching. Post-terminal side effects (metrics recording, the TTS
`speak()`/`awaitTtsStarted` call) are likewise wrapped: once `completion.complete(state)` has run, nothing
that happens afterward can crash the coroutine or be mistaken for a reason to revisit the already-committed
outcome.

**Why this eliminates the race architecturally:** there is now exactly **one** terminalization path for
Success, Failure and (via `cancelCurrent`) Cancelled — an exception can no longer create a second, silent
"crashed and abandoned" path that skips clearing `currentJob`/`currentAttempt`/`currentCompletion` or
leaves `completion` unresolved. Every accepted turn is now provably guaranteed to reach exactly one
terminal `AssistantTurnState` and resolve its `Deferred` exactly once, regardless of what the query
pipeline does internally.

Files/lines: `AssistantTurnCoordinator.kt` (`beginTurnLocked`'s launched coroutine — the try/catch around
`assistantQueryUseCase`, the try/catch around each branch's post-terminal side effects, the new
`invokeOnStartedSafely` helper, and `awaitTtsStarted`'s inner `launch` also guarded), `GatewayError.kt`
(new `Unexpected` case), `AssistantTurnCoordinator.errorMessageFor`/`SettingsViewModel.errorMessageFor`
(both exhaustive `when`s over `GatewayError`, updated to handle the new case).

Tests (`AssistantTurnCoordinatorTest.kt`, 6 new): the gateway throwing an unexpected exception mid-query
still terminates the turn as `Failure` (not a hang), and `clientAttemptOf` correctly still references the
request since `onTiming` had already fired before the throw (proving the fix doesn't accidentally lose
real retry lineage); session resolution throwing terminates as `Failure` with `clientAttemptOf` null
(query never dispatched); an `onStarted` callback that throws never strands an in-flight turn without a
job — the turn still reaches `Success` normally; an exception thrown by metrics recording, and separately
by the TTS engine's `speak()`, after a `Success` is already committed, never reverses the terminal state or
hangs the turn (both proven by making `AssistantTurnMetricsRecorder`'s injected `logger` and a hand-built
`TtsController.speak()` throw respectively); a stale exception from an already-cancelled turn's coroutine —
forced past cancellation's reach via a real, non-suspend-aware `java.util.concurrent.CountDownLatch` (a
plain suspend-based gate would instead be cancelled *at* the gate, never reaching the throw) — never
re-completes its already-`Cancelled` `Deferred` (proven via reference identity across two `await()` calls)
and never surfaces as an uncaught exception on the scope, verified directly via a `CoroutineExceptionHandler`
that would record it if it did (not merely inferred from unchanged state, which a `SupervisorJob` alone
could not distinguish from "silently swallowed"). Hang-prone `.completion.await()` assertions are wrapped
in a 5-second `withTimeout` purely as a test-only fast-failure guard (per the master prompt's explicit
instruction) — production code has no such timeout added.

### Blocker 2 — appending the user message and starting/rejecting a turn were two separate, non-atomic publishes

**Root cause (confirmed from source):** `AssistantTurnCoordinator.submit()` called
`conversationRepository.addUserMessage(...)` and then, in a second, separate call, either
`beginTurnLocked` (→ `conversationRepository.beginTurn(turn)`) or, on the health-blocked path,
`conversationRepository.completeFailure(...)` — two independent `MutableStateFlow.update{}` calls.
`synchronized(lock)` only ever serializes `AssistantTurnCoordinator`'s own callers (`submit`/`retry`/
`cancelCurrent`) against each other — it says nothing about a `StateFlow` collector running on a different
thread/dispatcher, which really could observe the intermediate state: the new user bubble already
appended, but `inFlightTurn` still `null` and `turnState`/`retryableTurn`/`errorMessage` still reflecting
the *previous* turn. This is exactly the same class of bug independent re-audit item 3 (fourth pass) fixed
for *terminal* transitions, one level further up the pipeline for the *initial* transition.

**Fix (architectural):** `ConversationRepository` gained a `beginTurn(message: ChatMessage, turn: InFlightAssistantTurn)`
overload — used by a fresh `submit()` — that appends the message and starts the turn in one atomic
`MutableStateFlow.update{}`; the original single-argument `beginTurn(turn)` remains for `retry()`, which
must never append a second bubble (W1.12). A new `rejectBeforeInFlight(message, requestId, generation,
source, error, userMessage, retryableTurn)` method appends the message and completes as `Failure` in one
atomic update, for the health-blocked path (a turn that never goes in-flight at all, so there is no
`beginTurn`+`completeFailure` pair to make atomic — this needed its own method). `submit()` now builds the
`ChatMessage` once, up front, inside `synchronized(lock)`, and passes it into whichever single-update path
applies — never publishing it via its own separate update.

**Why this eliminates the race architecturally:** every `ConversationRepository` mutation method
(`beginTurn` ×2, `completeSuccess`, `completeFailure`, `rejectBeforeInFlight`, `completeCancelled`) now
performs exactly one `StateFlow` update, and every one of them leaves `ConversationState` in a single,
internally-consistent snapshot — there is no publish that appends a message without also, in the same
atomic step, moving `inFlightTurn`/`turnState`/`retryableTurn`/`errorMessage` to match. A collector can no
longer observe "message N present, but turn N's state absent."

Files: `ConversationRepository.kt` (interface — new `beginTurn` overload, new `rejectBeforeInFlight`,
KDoc), `InMemoryConversationRepository.kt` (implementations — `addUserMessage` removed entirely, confirmed
via grep to have no remaining callers), `AssistantTurnCoordinator.kt` (`submit`/`retry`/`beginTurnLocked`
updated to build the message once and route it through the correct atomic method).

Tests (`AssistantTurnCoordinatorTest.kt`, 2 new): a full `ConversationState` history is collected (relying
on this suite's `UnconfinedTestDispatcher`, which resumes a suspended collector inline/synchronously on
every `StateFlow` emission — the same technique the fourth pass's terminal-transition test already used)
across a fresh submit following a prior failed/retryable turn, asserting no observed state ever pairs the
new message with the *previous* turn's still-lingering Failure/retryableTurn/errorMessage (the gateway for
the second turn is deliberately made to succeed, so any Failure-shaped state paired with its message can
only be stale turn-1 leftover, never turn 2's own legitimate outcome); a health-blocked submit is proven to
publish the message and the Failure together, never as two separate updates.

### Item 3 — voice-outcome-overwrite regression test was not actually discriminating (no new code defect)

The fourth pass's fix (a per-turn `Deferred` completion) was architecturally correct, but
`VoiceAssistantCoordinatorTest`'s existing "a text turn submitted immediately after a voice turn completes
never overwrites the voice turn's own outcome" test only ever exercised the *boring* interleaving: it
fully drains the voice turn, including its completion-reading consumer coroutine, via `advanceUntilIdle()`
*before* the text turn is even submitted — it could never have caught a bug where the consumer reads
stale/wrong data, because the consumer always finishes before anything else starts.

Added a genuinely discriminating test: the voice coordinator's `externalScope` is a `StandardTestDispatcher`
backed by its own, independently-constructed `TestCoroutineScheduler` (explicitly *not* the zero-argument
`StandardTestDispatcher()`, which was found — empirically, via a same-instance check — to silently reuse
whatever scheduler `Dispatchers.setMain` has installed, which would have defeated the whole point of
pumping the two schedulers independently). The voice turn's own gateway call is gated behind a plain
`CompletableDeferred` (needed because `mainDispatcherRule.dispatcher` is an `UnconfinedTestDispatcher`,
under which a `launch{}` on the turn's own scope runs *inline*, synchronously, on whatever thread is
currently pumping the voice scheduler — without a real suspension point, the whole turn would resolve
synchronously within the very first pump, leaving no window to observe "resolved but not yet read" at
all). This lets the test deterministically construct: the voice turn's `Deferred` resolves (proven by
`ConversationRepository.turnState` becoming `Success`) while its completion-reading consumer coroutine
provably has not run yet (`turnOutcome` still `null`, `clearProcessingStateCallCount` still `0`, since the
consumer's own `launch{}` sits enqueued-but-unrun on the voice scheduler); a second, unrelated text turn
then runs to completion on the turn scheduler, overwriting `ConversationRepository` entirely; only then is
the voice scheduler pumped, letting the consumer finally read `completion` — which, being a per-turn
identity rather than a re-read of `ConversationRepository`, still resolves to the voice turn's own value. A
second new test proves a voice event rejected by single-flight (while a prior voice turn is genuinely in
flight, gated by a real `delay`) clears its own processing state without disturbing the already-accepted
turn's own eventual outcome or double-clearing.

Files: `VoiceAssistantCoordinatorTest.kt` (2 new tests, `StandardTestDispatcher`/`TestCoroutineScheduler`
imports).

### Item 4 — metrics CAS regression test also was not actually discriminating (no new code defect)

The fourth pass's `AssistantTurnMetricsRecorder.recordTtsStarted()` fix (a hand-rolled `compareAndSet`
retry loop, logging only after a successful CAS) was already correct, but its "a stale (non-matching)
requestId is a no-op and is never logged as patched" test would pass identically on the pre-fix
`update{}`-based implementation too, since the target id in that test never matches the current turn at
*any* point — it never exercises the actual historical bug shape (a genuinely *matching* id whose first
attempt loses a race to a concurrent write, forcing a retry that lands on a *different*, now-current turn).

Added a deterministic reproduction via a new, `internal`-visibility, test-only seam
(`onBeforeCompareAndSetForTest: (() -> Unit)?`, always `null`/a no-op in production, never invoked by
`record()`) fired immediately after `recordTtsStarted()` reads its snapshot and before its
`compareAndSet` attempt — letting a test force that specific attempt to lose its race by writing a
conflicting value from inside the hook, instead of hoping real-thread scheduling happens to land in that
exact interleaving. A companion test reproduces the identical forced interleaving against a faithful local
copy of the *old*, buggy `update{}`-based pattern (self-contained in the test file, not touching production
code) and shows it genuinely *does* emit a phantom "patched" log for the stale attempt — concrete proof
that the interleaving the seam forces is a real bug shape a correct implementation must resist, not a
vacuous scenario no implementation could get wrong. A third new test documents and verifies the multi-call
semantics explicitly (last-write-wins for repeated calls against the same still-current id; never touches
a different turn).

Files: `AssistantTurnMetricsRecorder.kt` (new `onBeforeCompareAndSetForTest` seam + KDoc clarifying
multi-call semantics), `AssistantTurnMetricsRecorderTest.kt` (3 new tests).

### Item 5 — re-audit of prior fixes found one additional latency-honesty defect

Re-confirmed from source (not assumed): `StartedTurn.completion`'s per-turn identity and unbounded-growth
freedom (blocker 1, fourth pass) — unchanged, still correct; `VoiceAssistantCoordinator.start()`'s
`AtomicBoolean` guard — unchanged, still correct; `AndroidSpeechRecognizerController`'s main-thread
confinement of every platform call and `ActiveSession`-owned, generation-tagged timing — unchanged,
still correct; base metrics still recorded before `speak()`/`awaitTtsStarted()`; no `messages.lastOrNull`
anywhere (confirmed by grep); `collectorStarted` still an `AtomicBoolean`, never reverted to a plain
`Boolean`; `ConversationRepository`'s only mutation methods remain `beginTurn` ×2, `completeSuccess`,
`completeFailure`, `rejectBeforeInFlight`, `completeCancelled` — no `setInFlightTurn`/`setErrorMessage`/
`addAssistantMessage` anywhere in the codebase (grepped; the only hits are historical KDoc prose describing
the old, already-fixed design, not real methods).

One genuine, previously-unnoticed defect *was* found during this re-audit: `AndroidSpeechRecognizerController.beginListening`
computed `micRequestedAtMs = clock.nowMs()` **inside** the `mainThreadExecutor.execute { ... }` block —
i.e. at the instant the confined block actually *ran*, not at the instant the caller actually requested the
microphone. On a busy real main `Looper` with other pending messages ahead of this dispatch, this would
silently redefine "mic requested" to mean "main executor got around to processing it," hiding real
dispatch/queueing delay and reporting a falsely-fast mic latency — exactly the kind of latency-measurement
dishonesty the master prompt explicitly warned against.

**Fix:** `requestedAtMs = clock.nowMs()` is now captured on the caller's own thread, immediately, as the
first thing `beginListening` does after the permission check — before `mainThreadExecutor.execute` even
enqueues the confined block — and threaded into the block via its closure (an immutable local, never a
shared mutable field read at two different times) for `PartialCaptureTimings(micRequestedAtMs = requestedAtMs)`.

Test (`AndroidSpeechRecognizerControllerTest.kt`, 1 new): calls `startWakeWord`, captures the clock reading
at that instant, advances the fake clock by 250ms (simulating a busy main Looper), *then* drains the
executor — and asserts the recorded `micRequestedAtMs` equals the call-time reading, not the post-advance
one. Verified to actually catch the bug, not just look correct: the fix was temporarily reverted (reading
`clock.nowMs()` back inside the block) and the test suite re-run — exactly this one test failed, as
expected — then the fix was restored and the suite re-confirmed green.

Files/lines: `AndroidSpeechRecognizerController.kt` (`beginListening`).

### Latency and no-regression audit (this pass)

Full-repo scan for `delay(`/`Thread.sleep`/`runBlocking`/polling loops/`messages.lastOrNull`/non-atomic
`collectorStarted` in `android/app/src/main`: the only `delay(` call sites remain the same three
pre-existing, legitimate ones already documented (Emergency countdown tick, Emergency display refresh
tick, Developer-Mode-only default-zero simulated latency) — none touched, none added. The `while (true)`
loops present are: the pre-existing Emergency tick loop, the pre-existing `DataStoreEmergencyRepository`
reducer-convergence loop (a plain, non-suspending, non-delaying fixed-point loop, unrelated to this pass),
the pre-existing test-only `FakeMainThreadExecutor.runAll()` queue-drain loop, the pre-existing Emergency
display tick, and `AssistantTurnMetricsRecorder`'s CAS retry loop (unchanged from the fourth pass, no
sleep/delay). No new `delay`/`Thread.sleep`/`runBlocking` was added in production code this pass.
`messages.lastOrNull`: zero occurrences. `collectorStarted`: still an `AtomicBoolean`. The one new
production timing change this pass (`micRequestedAtMs` now captured on the caller's thread) makes latency
measurement *more* honest, not less — it can only ever report an equal or larger mic-latency number than
the old code, never a smaller/faked one, and adds no actual delay of its own (it's a plain `clock.nowMs()`
read, not a wait).

### Verification commands and results (this pass)

| Command | Result |
|---|---|
| `gradlew.bat clean :app:assembleDebug :app:testDebugUnitTest --rerun-tasks :app:lintDebug :app:compileDebugAndroidTestKotlin :app:assembleRelease :app:lintRelease` | **BUILD SUCCESSFUL in 1m 7s**, 117 actionable tasks, all executed (no earlier failed attempts this pass — every new test compiled and passed on first full run after individual-class verification during development) |
| Unit test XML aggregation (Python script summing every `TEST-*.xml`'s `tests`/`failures`/`errors`/`skipped`) | **tests=238 failures=0 errors=0 skipped=0**, 25 test classes (224 → 238: +14 — 8 in `AssistantTurnCoordinatorTest` (39→47), 2 in `VoiceAssistantCoordinatorTest` (18→20), 1 in `AndroidSpeechRecognizerControllerTest` (18→19), 3 in `AssistantTurnMetricsRecorderTest` (4→7)) |
| AndroidTest count (`grep -rc "@Test" app/src/androidTest --include=*.kt`) | **29** (unchanged — none of this pass's fixes touch a Compose UI surface) |
| `:app:compileDebugAndroidTestKotlin` | compiles clean — 29 Compose UI tests, **not executed** (`adb devices` empty, re-checked this pass) |
| `:app:lintDebug` / `:app:lintRelease` | **BUILD SUCCESSFUL**, 0 errors both; SARIF re-parsed (level/ruleId counted programmatically, not eyeballed) — identical 12 pre-existing dependency-version findings (`NewerVersionAvailable`×8, `AndroidGradlePluginVersion`×2, `GradleDependency`×2) on both variants, nothing new |
| SHA-256 on both APKs (fresh, this pass) | debug `b4b55d927c6c8bd67d9e579632b3aa6e6e74e16f1db83d28f6ccec42317c9a18`, release-unsigned `0944f8a32439d6dae3608cc36771324ee01316eb4c0faf0e5e12dd154d2a9ff2` |
| `python -m openapi_spec_validator openapi/safedrive-v1.yaml` | **OPENAPI VALID** (spec unchanged this pass — the new `GatewayError.Unexpected` case is explicitly local-only) |
| `adb devices` | empty — no device/emulator attached; `connectedDebugAndroidTest` genuinely cannot run |

Verification note on blocker 1's regression tests: several were double-checked to actually fail without the
fix by construction, not just by inspection — e.g. the gateway/session "throws an unexpected exception"
tests rely on `runTest` propagating an uncaught exception from a coroutine launched directly on its own
`TestScope` and failing the test itself, which is exactly what the unwrapped pre-fix code would trigger.
The real-thread "stale exception past cancellation's reach" test and the `micRequestedAtMs` test were both
additionally verified by temporarily reverting their respective production fixes and re-running: in both
cases, exactly the intended new test failed and nothing else did, then the fix was restored and the suite
reconfirmed fully green.

### Gate re-evaluation

| Gate | Verdict | What changed this pass |
|---|---|---|
| **A** | **PASS** (strengthened) | Turn termination is now exception-safe end to end — an unanticipated exception anywhere in the query pipeline can no longer strand a turn `InFlight` forever; a throwing `onStarted` callback can no longer either. |
| **B** | **PASS at logic/test level; DEVICE_PENDING for on-hardware behavior** (strengthened) | `micRequestedAtMs` now honestly reflects the caller's request instant, not the main executor's processing instant. |
| **C** | **PASS** (strengthened) | User-message-plus-turn-transition publishing is now atomic, closing the last known non-atomic `ConversationState` publish path; the voice-outcome-overwrite guarantee and the metrics CAS guarantee are now backed by genuinely discriminating tests, not merely tests that happened to pass. |
| **D** | **PASS at logic/test level; DEVICE_PENDING for pixel-level rendering** (unchanged) | No UI-surface change this pass. |
| **E** | **FAIL** (unchanged — still correctly not claimed as passing) | Device QA and human backend/Android owner review remain the only outstanding criteria. |

**Final conclusion: `DEVICE_VALIDATION_PENDING`.** Both blockers named in this pass are fixed with a change
in *architecture* (a single exception-safe terminalization path; atomic message+turn-transition
publishing), each with an explicit "why this eliminates the race architecturally" argument above; the two
test-coverage gaps (voice-outcome overwrite, metrics CAS race) are closed with genuinely discriminating
tests rather than re-asserting already-correct behavior; the one additional latency-honesty defect found
during re-audit (`micRequestedAtMs`) is fixed and its regression test independently confirmed to fail
against the pre-fix code. All verified against a cold clean build (238/238 JVM tests passing, 0
failures/errors, 29 AndroidTest compiling clean, 0 new lint issues, OpenAPI still valid, fresh checksums).
Explicitly **not** `MOBILE_READY_FOR_BACKEND` — Gate E's device-QA and human-review criteria remain
outstanding and cannot be produced from this sandbox (`adb devices` empty). **AI Backend work has not been
started**, per the master prompt's explicit and repeated instruction, in this or any prior pass.

## Sixth independent re-audit — cancellation-path blocker, one more genuine production bug, and closing test gaps (2026-07-28)

A sixth pass, again starting from a re-read of the *current* source (not trusting the fifth pass's own
report), closing the one architectural gap the fifth pass's exception-safety fix left open — a
`CancellationException` reaching a turn's coroutine does not always mean `cancelCurrent()` already handled
it — fixing one further genuine production bug found via a more faithful test double, and rewriting/adding
regression tests the master prompt identified as non-discriminating or missing.

### Blocker — a `CancellationException` not originating from `cancelCurrent()` could abandon a turn `InFlight` forever

**Root cause (confirmed from source):** `beginTurnLocked`'s launched coroutine wrapped
`assistantQueryUseCase(...)` as `catch (e: CancellationException) { throw e }` — correct as far as *never
converting cancellation into a `Failure`*, but the KDoc's own justification ("cancellation is already fully
handled by `cancelCurrent()` resolving `completion` itself under `lock` before this coroutine ever gets to
run any of this code for that generation") is an assumption that does not hold in general. Three concrete
ways it breaks:

1. The gateway or session throws `CancellationException` directly, for an internal reason unrelated to
   `cancelCurrent()`, while the turn's generation is still current.
2. `externalScope`'s parent Job is cancelled from outside (e.g. application shutdown) — this cancels the
   turn's Job too, but never goes through `cancelCurrent()`, so `currentJob`/`currentAttempt`/
   `currentCompletion` are never cleared and `completion` is never resolved.
3. `externalScope` is already fully cancelled *before* `beginTurnLocked` even calls `launch` — the
   resulting Job never runs its body at all, so the `try/catch` never even executes.

In all three cases, the bare `throw e` propagated the cancellation without first checking whether anyone
had actually terminalized the turn — leaving `ConversationRepository` showing `InFlight` forever and
`StartedTurn.completion` permanently unresolved, hanging any waiter (e.g.
`VoiceAssistantCoordinator.route()`'s `completion.await()`) indefinitely. Separately,
`invokeOnStartedSafely`'s `catch (e: CancellationException) { throw e }` had the identical gap one level
earlier: a throwing `onStarted` callback rethrew *before* `currentJob` was even assigned, so there was no
coroutine yet for any per-turn cleanup to piggyback on.

**Fix (architectural):** `beginTurnLocked` now registers `job.invokeOnCompletion { terminalizeAsCancelledIfAbandoned(...) }`
the instant the turn's coroutine is launched. This handler fires on *every* way that Job can reach a final
state — normal completion, an exception escaping, cancellation mid-flight, or the Job never running its
body at all because the scope was already dead — and calls a new `terminalizeAsCancelledIfAbandoned`
helper that terminalizes the turn as `AssistantTurnState.Cancelled` **if, and only if,** nobody has already
done so: it checks `generation == currentGeneration && currentCompletion === completion` under `lock`
before doing anything. `cancelCurrent()` always bumps `currentGeneration` and clears `currentCompletion`
*before* touching anything else; the coroutine's own normal-path cleanup always clears `currentCompletion`
under the same `lock` before returning — so by the time either of those has genuinely run, the guard is
already false and the safety net is a correct no-op. It only ever does real work in exactly the three
abandonment cases above. For the `onStarted`-throws-`CancellationException` case (before `currentJob`
exists), the same helper is called synchronously, inline, from the `catch` block itself, before rethrowing.

**Why every accepted turn now terminalizes exactly once, architecturally:** there are now exactly two
places that can ever resolve a turn's `completion`/publish its terminal `ConversationState` —
(a) `cancelCurrent()`, and (b) the turn's own coroutine's normal-path cleanup under `lock` — and the new
`invokeOnCompletion` safety net is *structurally* incapable of racing either of them, because it reads the
exact same guard variables (`currentGeneration`, `currentCompletion`) those two paths write, under the
exact same `lock` (`synchronized` monitors are reentrant, so this is safe whether the safety net fires
inline on a thread already holding `lock` or later on a completely different thread). A turn can therefore
never be observed `InFlight` with no code left anywhere that will ever resolve it — the safety net is
*always* still there, for the lifetime of the process, regardless of how the coroutine ends.

**Logging (master prompt item 5):** every previously-silent `catch (e: Exception) { /* swallowed */ }` site
(the `onStarted` callback, post-`Success` metrics/TTS, post-`Failure` metrics, `recordTtsStarted`) now calls
a new `logSwallowedException(context, e, requestId, generation, source)` helper that forwards to a new
constructor-injected `logger: (String) -> Unit = {}` (defaults to a no-op — every existing call site and
test is unaffected). The logged string contains only the exception's *class name*
(`e::class.simpleName`), never `Throwable.message` (which could itself echo transcript/response content
back out) and never the transcript/reply body directly. `CancellationException` is still never logged —
only ever rethrown, per Kotlin coroutine convention. Wired in `SafeDriveContainer` to
`android.util.Log.w("SafeDriveTurn", ...)`.

Files/lines: `AssistantTurnCoordinator.kt` (`beginTurnLocked`'s `onStarted` try/catch and
`job.invokeOnCompletion` registration; new `terminalizeAsCancelledIfAbandoned` and `logSwallowedException`
private functions; every previously-silent catch site; new `logger` constructor parameter),
`SafeDriveContainer.kt` (logger wiring).

Tests (`AssistantTurnCoordinatorTest.kt`, 6 new — all verified to genuinely depend on the fix by temporarily
reverting both the `onStarted`-catch's `terminalizeAsCancelledIfAbandoned` call and the
`job.invokeOnCompletion` registration and re-running the suite: exactly the 5 cancellation-scenario tests
failed, and only those): the gateway throwing `CancellationException` directly while still current
terminalizes as `Cancelled` (not a hang, not `Unexpected`), with `clientAttemptOf` correctly still
referencing the request (`onTiming` had already fired before the throw); session resolution throwing
`CancellationException` before the query is ever sent terminalizes as `Cancelled` with `clientAttemptOf`
null; a distinct parent `Job` being cancelled (simulating application shutdown, never calling
`cancelCurrent()`) after a turn is accepted still resolves its completion instead of abandoning it
`InFlight`; `submit()` on an already-cancelled scope never leaves an accepted turn stuck `InFlight` (this
codebase's chosen contract: accept-and-immediately-terminalize, one of the two the master prompt sanctioned
— documented in `beginTurnLocked`'s KDoc); an `onStarted` callback throwing `CancellationException`
terminalizes the turn as `Cancelled` before rethrowing (cooperative cancellation still propagates to the
caller); an `onStarted` callback throwing a plain `RuntimeException` is now logged, not silently swallowed.
`.completion.await()` calls that could hang on a regression are wrapped in a 5-second `withTimeout` as a
test-only fast-failure guard (per the master prompt's explicit instruction — production code has no such
timeout added).

### Test fix — "stale exception after cancel" did not actually prove the gateway had been entered

**Gap identified (from the master prompt, confirmed by re-reading the test):** the existing test waited for
`repository.state.value.inFlightTurn != null` before calling `cancelCurrent()` — but `InFlightAssistantTurn`
is published *synchronously* inside `submit()`, before the coroutine that actually calls `queryAssistant()`
on a real thread pool even starts running. Waiting for it proves nothing about whether the gateway had
actually been entered; the cancel could in principle race ahead of the query coroutine ever starting.

**Fix:** replaced the single `CountDownLatch` with two — `enteredGateway` (counted down as the very first
line inside the fake gateway's `queryAssistant()` override) and `releaseGateway` (awaited immediately after,
before throwing). The test now asserts `enteredGateway.await(5, SECONDS)` returns `true` — genuine proof the
query coroutine is blocked inside the gateway — before calling `cancelCurrent()`, then releases the second
latch afterward to let the stale exception fire past cancellation's reach. Also strengthened with
additional assertions: no fabricated assistant reply, and `clientAttemptOf` correctly still references the
cancelled turn's own requestId (the query really was dispatched before the cancel).

File: `AssistantTurnCoordinatorTest.kt`.

### Item — a genuine production bug found via a more faithful `FakeVoiceController`

**Root cause (confirmed from source, found during the master prompt's own item-D re-audit request):**
`FakeVoiceController.emitFinalTranscript()` never touched `state` at all — unlike the real
`AndroidSpeechRecognizerController.onResults()`, which transitions to `VoiceState.PROCESSING` *before*
routing the transcript. Because of this gap, `VoiceController.clearProcessingState()`'s actual behavior —
`_state.update { if (it.state == PROCESSING) it.copy(state = IDLE) else it }`, which has **no notion of
whose turn's Processing indication it is clearing** — was never exercisable by any existing test. Making
the fake mirror production immediately exposed a real, latent production bug: if a voice event is rejected
by `AssistantTurnCoordinator`'s global single-flight while an *earlier*, already-accepted voice turn from
the *same* `VoiceAssistantCoordinator` is still genuinely in flight, `route()`'s rejection branch calling
`voiceController.clearProcessingState()` would prematurely flip `PROCESSING` back to `IDLE` — ending the
still-legitimately-running accepted turn's own Processing indication early, even though nothing about that
turn actually changed. This is directly reachable in production: two wake-word-triggered transcripts
arriving close together (e.g. the user speaking twice quickly) would trigger it.

**Fix:** `VoiceAssistantCoordinator` gained a `voiceTurnInFlight: AtomicBoolean` — set `true` the instant a
voice turn is accepted, cleared the instant that same turn's own completion consumer coroutine runs
(`AtomicBoolean` because the consumer that clears it is a different, asynchronously-launched coroutine than
`route()`'s own collector, which is the only writer setting it `true`). The rejection branch now only calls
`clearProcessingState()` if `!voiceTurnInFlight.get()` — i.e. only when the busy-ness came from something
*other* than this coordinator's own accepted voice turn (e.g. a text turn), which would otherwise never get
its own Processing indication cleared.

**Why this is the correct contract (per the master prompt's two sanctioned options):** "an accepted turn
keeps Processing until its own terminal state" — the rejected event never disturbs it; only the accepted
turn's own eventual completion consumer clears it, exactly once.

Files: `FakeVoiceController.kt` (`emitFinalTranscript` now sets `PROCESSING`, mirroring production),
`VoiceAssistantCoordinator.kt` (`voiceTurnInFlight` flag and the gated rejection-branch clear).

Tests (`VoiceAssistantCoordinatorTest.kt`): the pre-existing "rejected while a voice turn is in flight" test
was rewritten to assert the *correct* contract (previously it asserted the old, now-recognized-as-buggy
behavior as if it were correct — `clearProcessingStateCallCount == 2`; now asserts `== 1`, and that
`VoiceState` never leaves `PROCESSING` until the accepted turn's own real completion). A new companion test
proves the *other* half of the contract: a voice event rejected while a *text* turn (not a voice turn) is
what's busy still clears Processing itself, since nothing else ever will.

### Items B & C — exception/cancellation reaching the *full* voice pipeline, not just the Deferred

The fifth pass's fix (per-turn `Deferred` completion) and this pass's cancellation safety net were already
proven correct at the `AssistantTurnCoordinator`/`Deferred` level, but never exercised end to end through
`VoiceAssistantCoordinator.route()`/`turnOutcome`/`clearProcessingState()`. Two new tests
(`VoiceAssistantCoordinatorTest.kt`) close this: a gateway `RuntimeException` reaches the full pipeline as
a `VoiceTurnOutcome.Failure`, never hangs, and clears Processing exactly once; a gateway
`CancellationException` (not via `cancelCurrent()`) reaches the full pipeline with no stuck `PROCESSING`
and, matching the existing user-cancel contract, no `Success`/`Failure` outcome at all — clearing
Processing exactly once.

### Item A — the health-blocked "consumer not yet run" gap is architecturally unforceable, and why

Re-attempted the exact scenario the master prompt specifies (submit while health-blocked → synchronous
`Failure` → do not let the consumer run → flip health → run a text turn to completion, overwriting
`ConversationState` → only then let the consumer run → assert it still gets the health-blocked turn's own
outcome). Confirmed by direct analysis of `AssistantTurnCoordinator.submit()`'s health-blocked branch and
`VoiceAssistantCoordinator.route()`: there is **no suspension point anywhere** between "capability check
fails" and "the Deferred is completed" (the whole branch is synchronous, non-suspending Kotlin code), and
`route()` itself never suspends either — it only ever launches a *child* coroutine to await completion.
Under `UnconfinedTestDispatcher` (this suite's default), or even a dedicated `StandardTestDispatcher` pumped
independently (the technique the async-turn overwrite test above uses successfully), any single pump that
gets `route()` to run far enough to launch its consumer will *also* run that consumer to completion in the
same pass, since the consumer has nothing left to suspend on — `completion` is already resolved. Forcing a
"resolved but not yet read" gap here would require a production-only test hook with no corresponding real
timing hazard, which the master prompt's own "no delay/polling workaround" and "no timing luck" principles
argue against fabricating. The test added instead proves the property that genuinely matters and *is*
testable at this layer: the health-blocked turn's own `Failure` outcome is never overwritten by a later,
different, overwriting text turn, exercised end to end through `VoiceAssistantCoordinator` (not just the
`Deferred`, already covered separately in `AssistantTurnCoordinatorTest`).

### Latency and no-regression audit (this pass)

Full-repo scan for `delay(`/`Thread.sleep`/`runBlocking`/polling loops/`messages.lastOrNull`/non-atomic
`collectorStarted`/a non-null `onBeforeCompareAndSetForTest` in production in `android/app/src/main`: the
only `delay(` call sites remain the same three pre-existing, legitimate ones (Emergency countdown tick,
Emergency display refresh tick, Developer-Mode-only default-zero simulated latency) — none touched, none
added. No new `Thread.sleep`/`runBlocking` in production. `messages.lastOrNull`: zero occurrences.
`collectorStarted`: still an `AtomicBoolean`. `onBeforeCompareAndSetForTest` (the fourth/fifth pass's
metrics test seam): confirmed it is only ever read via `?.invoke()` and never set away from `null` anywhere
in `src/main` — genuinely inert in production. The new `Job.invokeOnCompletion` safety net adds no polling
or delay of its own: it is a single, one-time-registered completion callback, not a loop, and fires
immediately/synchronously as part of coroutines' own completion machinery — never a wait. The new `logger`
calls are plain synchronous function invocations (a no-op by default), adding no latency. Confirmed no
regression in any of the prior five passes' fixes via direct source re-read (see per-item notes above and
in `KNOWN_LIMITATIONS.md`).

### Verification commands and results (this pass)

| Command | Result |
|---|---|
| `gradlew.bat clean :app:assembleDebug :app:testDebugUnitTest --rerun-tasks :app:lintDebug :app:compileDebugAndroidTestKotlin :app:assembleRelease :app:lintRelease` | **BUILD SUCCESSFUL in 35s**, 117 actionable tasks, all executed |
| Unit test XML aggregation (Python script summing every `TEST-*.xml`'s `tests`/`failures`/`errors`/`skipped`) | **tests=248 failures=0 errors=0 skipped=0**, 25 test classes (238 → 248: +10 — 6 in `AssistantTurnCoordinatorTest` (47→53), 4 in `VoiceAssistantCoordinatorTest` (20→24)) |
| AndroidTest count (`grep -rc "@Test" app/src/androidTest --include=*.kt`) | **29** (unchanged — no Compose UI surface touched) |
| `:app:compileDebugAndroidTestKotlin` | compiles clean — 29 Compose UI tests, **not executed** (`adb devices` empty, re-checked this pass) |
| `:app:lintDebug` / `:app:lintRelease` | **BUILD SUCCESSFUL**, 0 errors both; SARIF re-parsed programmatically — identical 12 pre-existing dependency-version findings, nothing new |
| SHA-256 on both APKs (fresh, this pass) | debug `61542fa41edbc63198ef592e801dda62f9d9233b32152dc1a0e14e4150349f6d`, release-unsigned `cf732c0cfe50899ad4539257a5324c6b47a97cdb7ed63d1abb1e015c27102086` |
| `python -m openapi_spec_validator openapi/safedrive-v1.yaml` | **OPENAPI VALID** (spec unchanged this pass — no wire/DTO impact) |
| `adb devices` | empty — no device/emulator attached; `connectedDebugAndroidTest` genuinely cannot run |

Verification note: the 6 new cancellation-scenario tests in `AssistantTurnCoordinatorTest.kt` were
double-checked to actually depend on the fix, not merely pass coincidentally — the `job.invokeOnCompletion`
registration and the `onStarted`-catch's `terminalizeAsCancelledIfAbandoned` call were both temporarily
commented out and the suite re-run: exactly the 5 tests that exercise those specific paths failed (the 6th,
proving the `RuntimeException`-from-`onStarted` case is now logged, does not depend on the safety net and
correctly still passed) — then both were restored and the suite reconfirmed fully green.

### Gate re-evaluation

| Gate | Verdict | What changed this pass |
|---|---|---|
| **A** | **PASS** (strengthened) | Every accepted turn now terminalizes exactly once regardless of *why* or *how* its coroutine ends — cancellation no longer needs to have originated from `cancelCurrent()` to be handled correctly. |
| **B** | **PASS at logic/test level; DEVICE_PENDING for on-hardware behavior** (unchanged) | No mic/recognizer timing change this pass. |
| **C** | **PASS** (strengthened) | A real Processing-state-ownership bug in the voice pipeline is fixed; exception/cancellation safety is now proven end to end through the full voice pipeline (route/turnOutcome/clearProcessingState), not just at the `AssistantTurnCoordinator`/`Deferred` level. |
| **D** | **PASS at logic/test level; DEVICE_PENDING for pixel-level rendering** (unchanged) | No UI-surface change this pass. |
| **E** | **FAIL** (unchanged — still correctly not claimed as passing) | Device QA and human backend/Android owner review remain the only outstanding criteria. |

**Final conclusion: `DEVICE_VALIDATION_PENDING`.** The cancellation-path blocker named in this pass is
fixed with a change in *architecture* (a uniform `Job.invokeOnCompletion` safety net that cannot race
`cancelCurrent()` or the coroutine's own normal-path cleanup, by construction), with an explicit "why every
accepted turn now terminalizes exactly once" argument above; the genuinely-discovered Processing-ownership
bug is fixed with its own regression tests; the required test-coverage gaps (stale-exception proof, voice
pipeline exception/cancellation end-to-end, item A's documented limitation) are closed. All verified
against a cold clean build (248/248 JVM tests passing, 0 failures/errors, 29 AndroidTest compiling clean, 0
new lint issues, OpenAPI still valid, fresh checksums), and the new cancellation tests independently
confirmed to fail when the fix is reverted. Explicitly **not** `MOBILE_READY_FOR_BACKEND` — Gate E's
device-QA and human-review criteria remain outstanding and cannot be produced from this sandbox (`adb
devices` empty). **AI Backend work has not been started**, per the master prompt's explicit and repeated
instruction, in this or any prior pass.

## Seventh independent re-audit — an ABA race in voice Processing/outcome ownership, and closing the last honestly-documented test gap (2026-07-28)

Starting state for this pass: `NOT_BACKEND_READY`, AI Backend not started. The sixth pass's cancellation
safety net, atomic `ConversationRepository` reducers, CAS-based metrics, per-turn voice `Deferred` and
`micRequestedAtMs` capture were re-confirmed correct by direct source read and are **not** revisited below
except where this pass's fix touches them structurally. This pass focuses solely on the blocker the master
prompt named: an ABA race in `VoiceAssistantCoordinator`'s Processing/outcome ownership, and the remaining
test-coverage gaps around it.

### Root cause: `AtomicBoolean voiceTurnInFlight` has no turn identity

The sixth pass fixed a real bug (a voice event rejected by single-flight could prematurely clear an
already-accepted voice turn's own Processing indication) with a single `AtomicBoolean voiceTurnInFlight`:
set `true` the instant a turn is accepted, cleared `false` the instant that turn's own completion consumer
runs. This tracks only **whether** some voice turn is in flight — never **which** one. Direct source read
of `VoiceAssistantCoordinator.route()` confirmed the exact failure sequence the master prompt described:

1. Voice turn A is accepted (`voiceTurnInFlight` → `true`).
2. A completes at the `AssistantTurnCoordinator` level — global single-flight frees up — but *this
   coordinator's own* completion-consumer coroutine for A (`externalScope.launch { turn.completion.await()
   ... }`) has not yet been scheduled to run.
3. Voice turn B is accepted while A's consumer is still pending (`voiceTurnInFlight` stays/becomes `true`,
   indistinguishable from A) and legitimately claims the Processing indicator and the outcome slot.
4. A's now-late consumer finally runs, reads `voiceTurnInFlight == true` (which it cannot tell apart from
   "A is still what's in flight"), and incorrectly: publishes A's stale outcome over B's, clears B's
   legitimate Processing indication early, and leaves `voiceTurnInFlight` in a state that no longer
   corresponds to any turn actually in flight (B's own consumer, running later, would then also decrement it
   — a second false-negative on any further rejected event's clear decision).

This is a textbook ABA race: the flag transitions `true(A) → true(B) → false(by A's late consumer)`,
losing all information about *which* turn the `true` actually refers to at each point.

### Fix: a per-turn identity token compared under one lock

`VoiceAssistantCoordinator` gained:

- `private class VoiceTurnOwner(val requestId: String, val generation: Long)` — a per-accepted-turn
  identity token. `requestId`/`generation` are carried for diagnostic value only; every ownership comparison
  uses reference identity (`===`), never `equals`/`hashCode`.
- `private var activeOwner: VoiceTurnOwner?` — the turn that currently owns the Processing indicator and
  `turnOutcome`, replacing `voiceTurnInFlight`.
- `private val voiceOwnershipLock = Any()` — guards every read/write of `activeOwner` and every
  check-then-act transition built on it, as one atomic critical section. Never held across a suspension
  point (every block inside it is a synchronous state read/mutation only — comparing/reassigning
  `activeOwner`, setting `_turnOutcome.value`, calling `VoiceController.clearProcessingState()`), and never
  acquired while already holding `AssistantTurnCoordinator`'s own internal lock (acquisition order is always:
  call into `AssistantTurnCoordinator` fully, *then* acquire this lock once it has returned) — so this cannot
  lock-order-invert against it.

`route()` now:

- On acceptance: unconditionally sets `activeOwner = owner` for the newly-accepted turn (this *is* the ABA
  race's resolution point — a previous turn's completion consumer's own later check will simply find
  `activeOwner` no longer references it) and clears `_turnOutcome.value`, both inside one lock acquisition.
- On rejection (single-flight busy, or an emergency-phrase event arriving mid-turn — the same rule now
  applies there too, closing an adjacent gap found during this audit): only calls `clearProcessingState()`
  if `activeOwner == null`.
- In the completion consumer: computes the outcome from the resolved terminal state *outside* the lock (pure
  computation, no side effect), then, inside one lock acquisition, checks `activeOwner === owner`; only if
  true does it publish the outcome, clear `activeOwner`, and call `clearProcessingState()` — all three as one
  indivisible unit, so no new turn can be accepted in the gap between the check and the act.

A new optional constructor parameter, `completionScope: CoroutineScope = externalScope`, separates "which
dispatcher runs the voice-event collector" from "which dispatcher runs a turn's own completion-awaiting
consumer coroutine." Production wiring in `SafeDriveContainer` does not pass it, so production behavior is
byte-for-byte unchanged (both default to the same `applicationScope`). It exists purely to make the ABA race
deterministically forceable for the *synchronously-resolved* health-blocked path in tests (see below).

### Why a late, superseded turn's completion can never affect the current owner

Every operation that reads or could act on `activeOwner` — accepting a new turn, rejecting one, a turn's own
completion consumer's publish/clear — executes inside `synchronized(voiceOwnershipLock)`. A completion
consumer's check (`activeOwner === owner`) and a newer turn's claim (`activeOwner = newOwner`) can therefore
never interleave: whichever acquires the lock first either fully claims ownership (before any late consumer
can check) or fully completes its stale check-and-no-op (before any new turn can claim). There is no
read-then-act window between "check" and "act" for a third party to land in, because both are inside the
same critical section. This holds regardless of *which* terminal state the stale turn resolved to (`Success`,
`Failure`, or `Cancelled`) and regardless of *how* its Deferred came to resolve (an in-flight network call
completing, `cancelCurrent()`, or the synchronous health-blocked path) — the guard is on identity, not on the
terminal value or the resolution mechanism.

### Test regression and proof the tests genuinely discriminate

Five new tests added to `VoiceAssistantCoordinatorTest.kt` (`domain.usecase`), all reproducing the exact ABA
sequence above with real enqueue-order control on independently-pumped `TestCoroutineScheduler`s — no
`Thread.sleep`/timing luck:

- **ABA race Success** — turn A accepted and genuinely in flight (a `CompletableDeferred`-gated gateway);
  turn B's transcript is queued into the channel *while the collector is idle*, enqueuing "process B" on the
  shared scheduler; then A's gateway call is resolved, which — as a side effect — enqueues A's own consumer's
  resume *after* B's already-queued task. Draining the queue therefore runs, in order, B's acceptance (claims
  ownership) and then A's now-late consumer (finds it is no longer owner, becomes a no-op). Asserts: B stays
  genuinely in flight, Processing stays `PROCESSING`, `clearProcessingStateCallCount` stays `0` until B's own
  completion, `turnOutcome` stays `null` until B's own completion, and the final published outcome is
  provably B's own reply (captured from `ConversationRepository` at resolution time), never A's.
- **ABA race Failure** — identical structure, A resolves to `GatewayResult.Failure` instead of `Success`.
- **ABA race Cancelled** — identical enqueue-order technique, triggered by `cancelCurrent()` instead of a
  resolved gateway call (A's gateway call is gated on a `CompletableDeferred` that is never completed;
  `cancelCurrent()` cancels the coroutine directly).
- **ABA race health-blocked** — the case the sixth pass's `TEST_REPORT.md` had declared unforceable (see
  below): uses the new `completionScope` parameter, pumped on its own independent `TestCoroutineScheduler`,
  separate from the one driving `route()` itself. Turn A (health-blocked, resolved synchronously inside
  `submit()`) has its completion consumer sit enqueued-but-unread on `completionScope` while turn B is
  accepted and claims ownership on the collector's own scheduler; only once both schedulers are drained does
  A's consumer run and correctly become a stale no-op.
- **Stress (supplementary)** — 20 rapid iterations of two sequential voice turns on a real `Dispatchers.Default`
  thread pool, polling for state transitions; asserts no cross-contamination across any iteration.

**Revert-and-rerun proof**: temporarily reverted `VoiceAssistantCoordinator.route()` to the exact sixth-pass
`AtomicBoolean voiceTurnInFlight` logic (marked `// TEMP-REVERT-FOR-VERIFICATION`) and re-ran the suite:
**4 of the 5 new tests failed** — Success, Failure, Cancelled and health-blocked, exactly the four
deterministic ones. The supplementary real-thread stress test did not happen to fail on this particular run
(it is explicitly probabilistic, disclosed as such, and is not relied on as primary proof — the four
deterministic tests are). Restored the fix and reconfirmed all 29 tests in the class green.

Two pre-existing tests continue to pass unchanged under the new implementation, confirming no regression on
the (non-ABA-race) scenarios the sixth pass already covered: "a voice event rejected by single-flight while a
prior voice turn is genuinely in flight never clears the accepted turn's own Processing state early," and
"...while a text turn (not a voice turn) is what's busy still clears Processing itself."

### Superseding the sixth pass's "unforceable gap" conclusion

The sixth pass's `TEST_REPORT.md` stated the "Deferred resolved but consumer not yet run" gap could not be
forced for the health-blocked path, since neither `submit()`'s health-blocked branch nor `route()` itself
ever suspends — correct under a *single-scope* test design, where any pump that gets `route()` to run far
enough to accept a turn also fully drains whatever it just launched. This pass closes that gap without a
production-only test hook, per the master prompt's explicit instruction not to declare it untestable: the new
optional `completionScope` parameter (default-equal to `externalScope`, zero production behavior change)
lets a test pump "accepting a turn" and "reading that turn's own completion" on two independently-controlled
schedulers. The new health-blocked ABA test above is the direct result. The *prior* health-blocked test (a
health-blocked turn's `Failure` outcome surviving a later, overwriting *text* turn) is unaffected and remains
in the suite unchanged — it is a different, still-valid property (survival once one's own consumer has
already run) from the ownership race this pass closes (a second *voice* turn claiming ownership before the
first's consumer runs).

### Re-audit for regressions (this pass)

Direct source re-read, not assumed from prior reports:

- `ConversationRepository.beginTurn`/`rejectBeforeInFlight`: untouched this pass, still atomic (single
  `StateFlow` update per transition).
- `retry()`: untouched, still does not append a duplicate user message.
- `AssistantTurnCoordinator`'s per-turn `CompletableDeferred`: untouched structurally; the one change
  (`currentJob` assignment guard, below) does not affect completion/resolution semantics.
- `VoiceAssistantCoordinator.start()`: untouched, still `compareAndSet`-guarded, creates exactly one
  subscription.
- `AndroidSpeechRecognizerController`: untouched this pass — main-executor confinement, stale-generation
  callback isolation and `micRequestedAtMs` capture-at-request-time all unchanged.
- `AssistantTurnMetricsRecorder`'s CAS patch-then-log ordering: untouched this pass.
- `onBeforeCompareAndSetForTest`: confirmed still only ever read via `?.invoke()`, never set away from `null`
  anywhere in `src/main` — genuinely inert in production (re-checked via grep this pass, see below).
- **New finding this pass**: `AssistantTurnCoordinator.beginTurnLocked`'s `currentJob = job` assignment (the
  line immediately after `externalScope.launch { ... }`) was unconditional. If `externalScope` ever dispatched
  eagerly enough for the turn's coroutine to run to completion — including its own normal-path
  `synchronized(lock) { ...; currentCompletion = null }` cleanup — *before* control returned to that
  assignment, the unconditional write would clobber an already-correct `null` with a reference to an
  already-dead `Job`, leaving `currentJob` stale until the next accepted turn happened to overwrite it. Fixed
  by guarding the assignment: `synchronized(lock) { if (currentCompletion === completion) currentJob = job }`
  — a no-op exactly when the coroutine already ran its own cleanup, unchanged behavior on every other path.
  **Honestly disclosed**: revert-and-rerun of this specific guard produced **zero** test failures across the
  full suite — the only other `currentJob` read site (`cancelCurrent()`) is already gated by
  `ConversationRepository.state.value.inFlightTurn`, the correct source of truth, so this closes a real but
  currently-latent ordering hazard rather than one any test (old or new) observes today. Reported as such,
  not claimed as proven by a failing test.
- `FakeVoiceController.emitFinalTranscript` now also mirrors production's `trySend`-failure fallback (falls
  back to `VoiceState.ERROR` with the same message the real controller uses, instead of leaving a phantom
  `PROCESSING` state when the bounded event queue is genuinely full) — found while re-reading
  `AndroidSpeechRecognizerController.onResults()` line-by-line per the master prompt's instruction to make the
  fake accurately reflect production's contract.

### Latency and no-regression audit (this pass)

Full-repo scan for `Thread.sleep`/`runBlocking`/`delay(` in the two touched production files
(`AssistantTurnCoordinator.kt`, `VoiceAssistantCoordinator.kt`) and `FakeVoiceController.kt`: zero matches.
The new `voiceOwnershipLock` critical sections are plain synchronous state reads/writes (comparing/assigning
a reference, setting a `StateFlow` value, calling a synchronous `clearProcessingState()`) — no suspension, no
loop, no wait. The `currentJob` assignment guard adds one `synchronized` block around a single reference
comparison and assignment — no additional latency. `completionScope` defaulting to `externalScope` means
production code paths are unchanged in every respect other than the added guard logic itself.

### Verification commands and results (this pass)

| Command | Result |
|---|---|
| `gradlew.bat clean :app:assembleDebug :app:testDebugUnitTest --rerun-tasks :app:lintDebug :app:compileDebugAndroidTestKotlin :app:assembleRelease :app:lintRelease` | **BUILD SUCCESSFUL in 4m 1s**, 117 actionable tasks, all executed |
| Unit test XML aggregation (every `TEST-*.xml`'s `tests`/`failures`/`errors`/`skipped` summed) | **tests=253 failures=0 errors=0 skipped=0**, 25 test classes (248 → 253: +5, all in `VoiceAssistantCoordinatorTest`, 24→29) |
| AndroidTest count (`grep -rc "@Test" app/src/androidTest --include=*.kt`) | **29** (unchanged — no Compose UI surface touched) |
| `:app:compileDebugAndroidTestKotlin` | compiles clean — 29 Compose UI tests, **not executed** (`adb devices` empty, re-checked this pass) |
| `:app:lintDebug` / `:app:lintRelease` | **BUILD SUCCESSFUL**, 0 errors both; SARIF re-parsed — identical 12 pre-existing dependency-version findings (`NewerVersionAvailable`×8, `AndroidGradlePluginVersion`×2, `GradleDependency`×2), nothing new |
| SHA-256 on both APKs (fresh, this pass) | debug `732b7cdf1293d7b4415f37ed41c51825bf34d32d9a8bc0ada86e13acd4106d4a`, release-unsigned `9d3a38ed0ff1d6390e92937f3e755943f44aac82e528dbd6152e10fda3aa0bf2` |
| `python -m openapi_spec_validator openapi/safedrive-v1.yaml` | **OPENAPI VALID** (spec unchanged this pass — no wire/DTO impact; `docs/backend-handoff.md` not touched) |
| `adb devices` | empty — no device/emulator attached; `connectedDebugAndroidTest` genuinely cannot run |

Revert-and-rerun verification note: reverting `VoiceAssistantCoordinator`'s ownership logic to the sixth
pass's `AtomicBoolean` produced `29 tests completed, 4 failed` — exactly the four deterministic new tests
(Success/Failure/Cancelled/health-blocked ABA scenarios), confirmed via the JUnit XML failure list. The fix
was restored and the suite reconfirmed fully green with no output (success). A second, independent
revert-and-rerun of the `currentJob` guard alone produced zero failures across the entire 253-test suite —
disclosed above as a latent, not-currently-observed hazard rather than a proven regression.

### Gate re-evaluation

| Gate | Verdict | What changed this pass |
|---|---|---|
| **A** | **PASS** (unchanged from sixth pass) | No change to the cancellation safety net's own logic this pass; the `currentJob` guard is an unrelated, orthogonal latent-hazard fix. |
| **B** | **PASS at logic/test level; DEVICE_PENDING for on-hardware behavior** (unchanged) | No mic/recognizer timing change this pass. |
| **C** | **PASS** (strengthened) | The ABA race in voice Processing/outcome ownership is closed with a per-turn identity token under one lock, proven via revert-and-rerun; the sixth pass's "unforceable gap" conclusion for the health-blocked path is superseded with a genuine, deterministic proof. |
| **D** | **PASS at logic/test level; DEVICE_PENDING for pixel-level rendering** (unchanged) | No UI-surface change this pass. |
| **E** | **FAIL** (unchanged — still correctly not claimed as passing) | Device QA and human backend/Android owner review remain the only outstanding criteria. |

**Final conclusion: `DEVICE_VALIDATION_PENDING`.** The ABA race named by the master prompt is closed with a
per-turn identity token compared under one lock — structurally incapable of letting a stale, superseded
turn's late completion affect the current owner, by construction, with an explicit "why" argument above; the
required deterministic tests (Success/Failure/Cancelled/health-blocked variants) are added and independently
confirmed via revert-and-rerun to fail against the old `AtomicBoolean` implementation; the sixth pass's
"unforceable" conclusion for the health-blocked ownership gap is honestly superseded, not left standing. All
verified against a cold clean build (253/253 JVM tests passing, 0 failures/errors, 29 AndroidTest compiling
clean, 0 new lint issues, OpenAPI still valid, fresh checksums). Explicitly **not**
`MOBILE_READY_FOR_BACKEND` — Gate E's device-QA and human-review criteria remain outstanding and cannot be
produced from this sandbox (`adb devices` empty). **AI Backend work has not been started**, per the master
prompt's explicit and repeated instruction, in this or any prior pass.

## Eighth independent re-audit — the pre-claim race, and generation-correlating Processing ownership (2026-07-29)

Starting state for this pass: `NOT_BACKEND_READY`, AI Backend not started. The seventh pass's
`VoiceTurnOwner`/`voiceOwnershipLock` ABA-race fix, the cancellation safety net, atomic
`ConversationRepository` reducers, CAS-based metrics, per-turn voice `Deferred` and `micRequestedAtMs`
capture were re-confirmed correct by direct source read and are **not** revisited below except where this
pass's fix touches them structurally. This pass focuses solely on the P1 blocker the master prompt named: a
race that exists *before* a voice turn ever claims ownership, plus the remaining test-coverage gaps around
it.

### Root cause: ownership alone cannot tell "which recognizer session is actually visible right now"

The seventh pass's fix is correct for what it targets: once a turn is superseded (`activeOwner` reassigned
to a newer turn), a stale completion consumer's `activeOwner === owner` check reliably fails and it becomes
a no-op. But direct re-read of `AndroidSpeechRecognizerController.onResults()` (and the mirroring
`FakeVoiceController.emitFinalTranscript`) confirmed a narrower, *earlier* window the master prompt
described exactly right:

1. Voice turn A has completed at the `AssistantTurnCoordinator` level (global single-flight is free).
2. A's own completion consumer in `VoiceAssistantCoordinator` has not run yet.
3. A **newer** recognizer session, B, produces a final transcript: `onResults()` unconditionally sets
   `VoiceUiState.state = PROCESSING` (tagged with B's own generation) and enqueues a `VoiceInputEvent` for
   B — entirely independent of whether `route()` has gotten anywhere near deciding to accept or reject it.
4. The voice-event collector has not run `route(B)` yet, so `activeOwner` still points to A.
5. A's completion consumer runs now. It checks `activeOwner === ownerA` — **true**, because nothing has
   superseded A yet at the assistant-turn level; the seventh pass's ownership check has nothing to object
   to here, and legitimately proceeds.
6. Its `clearProcessingState()` call (no generation awareness before this pass) unconditionally flips
   whatever is currently shown from `PROCESSING` to `IDLE` — but what is *currently shown* is already B's
   generation, not A's. B's legitimate Processing indication is wiped out before B's own turn even exists.
7. `route(B)` subsequently accepts B and assigns it as owner, but nothing re-shows `PROCESSING` (that is
   the recognizer's job, and it already ran once for this transcript) — B is left genuinely in flight with
   the voice overlay showing `IDLE`.

This is real and distinct from the seventh pass's ABA race: it is not about two turns racing at the
ownership level at all — A never stops being `activeOwner` until its own consumer voluntarily relinquishes
it. It is about the **recognizer's own visible state moving ahead of the assistant-turn ownership
bookkeeping**, since `onResults()` publishes unconditionally and `route()` only decides accept/reject
*after* that publish has already happened.

### Fix: thread the recognizer's own generation through ownership, independent of who "owns" the turn

- `VoiceInputEvent` gained `generation: Long` — the exact generation its recognizer session published
  `PROCESSING` under. `AndroidSpeechRecognizerController.onResults()` now explicitly stamps
  `generation = gen` on both the `PROCESSING` state update and the enqueued event (previously relied on
  `.copy()` implicitly preserving it — now explicit, minted together from one source of truth).
- `VoiceController.clearProcessingState()` → `clearProcessingState(expectedGeneration: Long)`: transitions
  `PROCESSING` → `IDLE` only when `VoiceUiState.generation` still exactly equals `expectedGeneration`.
- A new `VoiceController.reassignProcessingOwner(generation: Long)`: re-attributes a currently-shown
  `PROCESSING` to a different generation without touching the state itself — a no-op unless genuinely
  `PROCESSING`. Both methods implemented identically in `AndroidSpeechRecognizerController` and
  `FakeVoiceController`.
- `VoiceAssistantCoordinator.VoiceTurnOwner` gained `voiceGeneration` — the recognizer generation the
  accepted turn was claimed from (renamed the pre-existing field to `turnGeneration` for clarity, since it
  is `AssistantTurnCoordinator`'s own unrelated per-turn counter). A turn's own completion consumer now
  calls `clearProcessingState(owner.voiceGeneration)` — naming its own generation explicitly, so it becomes
  a no-op the instant a newer session has already overwritten the visible generation, *even while still,
  technically, `activeOwner`* (closing exactly the window above, which the ownership check alone could not).
- Rejected events and emergency-phrase commands are resolved by a new `resolveUnclaimedEvent(eventGeneration)`,
  under `voiceOwnershipLock`:
  - **No turn owns Processing** (`activeOwner == null`): clears directly, using the rejected event's own
    generation — nothing else will ever resolve it otherwise (e.g. a *text* turn is what's busy).
  - **Some other, still-genuinely-in-flight voice turn owns Processing**: the older turn's own `onResults()`
    published its `PROCESSING`/generation *before* this newer, now-rejected event's session even started —
    but that newer session's own `onResults()` has since overwritten the *visible* generation to its own
    value. Left alone, the older turn's own eventual clear call would name its own generation, which no
    longer matches — silently stranding this rejected generation's `PROCESSING` forever (and, as a second-
    order effect, also stranding the visible indication long after the older turn genuinely finishes, since
    nothing else would ever have a reason to touch it again). **Transferring** the visible generation back
    to the still-owning turn (`reassignProcessingOwner`) instead of clearing outright keeps the indication
    accurate (real work is still happening) and restores that turn's own later clear call to correctly
    match again.
- `FakeVoiceController.emitFinalTranscript` gained a `generation: Long` parameter (auto-incrementing by
  default via an internal counter starting at 1, so ordinary tests get a fresh distinct value per call
  without caring; explicit for tests that need to force a specific two-session ordering) and stamps it onto
  `state` and the enqueued event together, exactly like the real controller. The existing `trySend`-failure
  → `ERROR` fallback (sixth pass) is preserved unchanged.

### Why an older, still-current-owner completion cannot clear a newer recognizer generation

Ownership (`activeOwner`) and visibility (`VoiceUiState.generation`) are now two independent facts that
must both agree before any UI-mutating action proceeds. A turn's own clear call names its own
`voiceGeneration`; `clearProcessingState` only acts when that generation is still the one visibly showing.
Since a newer recognizer session always stamps its own generation onto `VoiceUiState` the instant it
produces a transcript — strictly before `route()` can do anything with the resulting event — by the time an
older turn's completion consumer runs (whenever that happens: before or after the newer turn is formally
accepted), either the visible generation is still the older turn's own (nothing newer has happened yet — the
clear proceeds correctly), or it has already moved on (the clear is a no-op, correctly). There is no
intermediate state in which the older turn's clear could partially succeed or corrupt a newer generation's
indication, because the check is a single, atomic equality comparison inside one lock, and nothing ever
mutates `VoiceUiState.generation` without going through the recognizer's own `onResults()`,
`clearProcessingState`, or `reassignProcessingOwner` — all of which respect this invariant.

### Deterministic pre-claim test sequence

`VoiceAssistantCoordinatorTest.kt` gained 10 new tests, all using two independently-pumped
`TestCoroutineScheduler`s (`voiceDispatcher` drives `route()`/the collector; `completionDispatcher` drives
each turn's own completion-awaiting consumer via the existing `completionScope` seam) — no
`Thread.sleep`/timing luck for any of the deterministic ones:

1. **Success** (the mandatory scenario): route and accept A (gated gateway); resolve A's gateway call,
   freeing single-flight, *without* pumping the collector; emit B's transcript (generation 200) — asserted
   `PROCESSING`/200 immediately; pump `completionDispatcher` alone, running A's consumer *before*
   `route(B)` — A legitimately publishes its own outcome (still `activeOwner`) but its
   `clearProcessingState(100)` call is a no-op (visible generation is already 200); assert `PROCESSING`/200
   survives untouched. Pump `voiceDispatcher` — B claims ownership, A's transient outcome is cleared. Keep
   B's own gateway call suspended — assert B genuinely in flight and `PROCESSING`/200. Complete B, pump
   `completionDispatcher` — assert only B's outcome is ever published, `PROCESSING` → `IDLE` exactly once,
   `clearProcessingStateCallCount == 2` (A's no-op + B's real transition).
2. A minimal, direct proof (no `route()`/turn machinery at all): a bare `FakeVoiceController`, emit
   generation 1, emit generation 2, `clearProcessingState(1)` (no-op, still `PROCESSING`/2),
   `clearProcessingState(2)` (clears).
3–4. The same Success sequence repeated with A resolving to `Failure` and to `Cancelled` (via
   `cancelCurrent()`) instead.
5. The same sequence for a **synchronously-resolved** (health-blocked) A — requiring the new,
   independently-pumped `completionScope` (separate from the collector's own `voiceDispatcher`) to force
   "A's consumer sits enqueued-but-unread while B is accepted," since the health-blocked path has no
   `await`/`delay` suspension point of its own to hang that gap on.
6. B rejected while A is genuinely in flight (not the pre-claim race — a real, ongoing single-flight busy
   state): asserts the visible generation is **transferred** to A's (100), not cleared, with
   `reassignProcessingOwnerCallCount == 1` and `clearProcessingStateCallCount == 0`; then A completes for
   real and its own clear now correctly matches.
7. B rejected while a *text* turn is busy (no voice owner to transfer to): asserts the **clear** path,
   using B's own generation, with `reassignProcessingOwnerCallCount == 0`.
8–9. The same transfer-vs-clear distinction proven for the emergency-phrase branch, with and without a
   genuinely-in-flight voice owner.
10. `trySend` failure (bounded queue genuinely full, `autoStart = false`) still produces `ERROR`; an old
   generation's `clearProcessingState`/`reassignProcessingOwner` calls afterward cannot resurrect or alter
   it (both are no-ops once the state is no longer `PROCESSING`, regardless of generation).

`AndroidSpeechRecognizerControllerTest.kt` gained 2 minimal tests proving `clearProcessingState`/
`reassignProcessingOwner` are safe no-ops on the *real* controller while genuinely idle — the positive
generation-match case cannot be exercised there in this environment (see the Bundle limitation, unchanged
from prior passes) and is instead fully proven at the `VoiceAssistantCoordinator` + `FakeVoiceController`
level, confirmed identical by direct source comparison.

### Revert-and-rerun evidence

Reverted exactly two things, marked `// TEMP-REVERT-FOR-VERIFICATION`: `FakeVoiceController
.clearProcessingState` dropped its generation check (unconditional clear whenever `PROCESSING`, matching
the seventh pass), and `VoiceAssistantCoordinator.resolveUnclaimedEvent` reverted to the seventh pass's
ownership-only shape (`if (activeOwner == null) clearProcessingState(...) else /* nothing */` — no
transfer). Re-ran `VoiceAssistantCoordinatorTest`: **39 tests completed, 7 failed** — exactly the 7 new
tests that depend on generation correlation (both pre-claim-race Success/Failure/Cancelled/health-blocked
variants, the direct old-generation-can't-clear-new-generation proof, and both transfer-vs-clear tests). The
3 new tests that exercise the unchanged "no owner exists" fast path (reject-while-text-busy,
emergency-no-owner, `trySend`-failure) correctly did **not** fail — precise, not blanket, discrimination,
proving the tests exercise exactly the mechanism they claim to. Restored both reverts, confirmed no
`TEMP-REVERT-FOR-VERIFICATION` marker remains anywhere in `src/main`/`src/test`, and reconfirmed the full
suite green.

### Rejected-event and emergency semantics (explicit contract)

| Scenario | `activeOwner` | Action taken |
|---|---|---|
| Rejected — a text turn is busy, no voice turn accepted | `null` | Clear, using the rejected event's own generation |
| Rejected — an accepted voice turn is still genuinely in flight | non-null | **Transfer** the visible generation back to the owner's `voiceGeneration` — never clear outright |
| Emergency phrase — no voice turn accepted | `null` | Clear, using the emergency event's own generation |
| Emergency phrase — an accepted voice turn is still genuinely in flight | non-null | Transfer back to the owner, same as the rejection case |
| A turn's own completion, still current owner | n/a (owner is itself) | Publish outcome, clear using its *own* `voiceGeneration` (no-op if a newer session has since overwritten the visible generation) |
| A turn's own completion, no longer owner (superseded) | n/a | Stale no-op — the seventh pass's ABA-race guard, unchanged |

### Re-audit for regressions (this pass)

Direct source re-read: `AssistantTurnCoordinator.kt` was **not touched** this pass (cancellation safety net,
`currentJob` guard, atomic reducers all structurally unchanged, re-confirmed by diff-free re-read).
`AndroidSpeechRecognizerController`'s pre-existing per-callback generation guards
(`if (gen != generation.get()) return`), main-thread confinement, and `micRequestedAtMs` capture-at-request
logic are all unchanged — only `clearProcessingState`/`reassignProcessingOwner`'s bodies and `onResults()`'s
explicit generation stamp were added. `AssistantTurnMetricsRecorder`, `ConversationRepository`, TTS metrics
ordering: untouched. `onBeforeCompareAndSetForTest`: re-confirmed still only ever read via `?.invoke()`,
never set away from `null` in `src/main`. No transcript/reply/`Throwable.message` is logged anywhere new
this pass — `VoiceInputEvent.generation`/`VoiceUiState.generation` are plain monotonic counters with no
relationship to user data. No new wire DTO or OpenAPI change — `VoiceInputEvent` is a purely internal
Android domain type, never serialized, never touching `docs/backend-handoff.md`.

### Latency and no-regression audit (this pass)

Full scan of every file touched this pass (`VoiceController.kt`, `AndroidSpeechRecognizerController.kt`,
`VoiceAssistantCoordinator.kt`, `FakeVoiceController.kt`) for `Thread.sleep`/`runBlocking`/`delay(`: zero
matches. Every new/changed code path is a synchronous state comparison/mutation (a `Long` equality check, a
`StateFlow.update`/`.value` write) inside an already-existing `synchronized` block or a plain function call
— no new suspension point, no loop, no wait anywhere.

### Verification commands and results (this pass)

| Command | Result |
|---|---|
| `$env:JAVA_HOME='C:\Program Files\Eclipse Adoptium\jdk-17.0.19.10-hotspot'; gradlew.bat clean :app:assembleDebug :app:testDebugUnitTest --rerun-tasks :app:lintDebug :app:compileDebugAndroidTestKotlin :app:assembleRelease :app:lintRelease` | **BUILD SUCCESSFUL in 41s**, 117 actionable tasks, all executed |
| Unit test XML aggregation (every `TEST-*.xml`'s `tests`/`failures`/`errors`/`skipped` summed) | **tests=265 failures=0 errors=0 skipped=0**, 25 test classes (253 → 265: +12 — 10 in `VoiceAssistantCoordinatorTest` (29→39), 2 in `AndroidSpeechRecognizerControllerTest` (19→21)) |
| AndroidTest count (`grep -rc "@Test" app/src/androidTest --include=*.kt`) | **29** (unchanged — no Compose UI surface touched) |
| `:app:compileDebugAndroidTestKotlin` | compiles clean — 29 Compose UI tests, **not executed** (`adb devices` empty, re-checked this pass) |
| `:app:lintDebug` / `:app:lintRelease` | **BUILD SUCCESSFUL**, 0 errors both; SARIF re-parsed — identical 12 pre-existing dependency-version findings (`NewerVersionAvailable`×8, `AndroidGradlePluginVersion`×2, `GradleDependency`×2), nothing new |
| SHA-256 on both APKs (fresh, this pass) | debug `043d309eef8c4e11f153841e37131db27aa4ad23c8909aabbb1b19552422f84d`, release-unsigned `7cb2a61ede948da84867d30e080242ebaf41562be3fce51fbe3a552df8442e89` |
| `python -m openapi_spec_validator openapi/safedrive-v1.yaml` | **OPENAPI VALID** (spec unchanged this pass — no wire/DTO impact; `docs/backend-handoff.md` not touched) |
| `adb devices` | empty — no device/emulator attached; `connectedDebugAndroidTest` genuinely cannot run |

**Flake disclosure**: the pre-existing (seventh-pass), explicitly-probabilistic real-thread stress test
("stress - rapid voice turn pairs on real threads never leak stale state across turns" — unmodified this
pass) failed once during gate verification (`replyText` showed A's reply instead of B's, at the last of 20
iterations) on a run that included the full 265-test suite plus lint plus both APK assemblies back to back.
Re-ran the same test class in isolation 4 more times immediately after: all 4 passed cleanly. Given (a) this
test is untouched by this pass's changes, (b) it is real-`Dispatchers.Default`-thread-based with
`Thread.sleep` polling by design (already documented as supplementary, never primary proof, in the seventh
pass's own report), and (c) every deterministic test — including all 10 new ones — passed 100% of the time
across every run including the one where this flaked, this is assessed as pre-existing real-thread
scheduling variance in the stress test's own construction, not a reproduction of the bug this pass fixes
(which is proven, deterministically and repeatably, by the tests in the section above). The final full-gate
run reported in the table above is completely clean.

### Gate re-evaluation

| Gate | Verdict | What changed this pass |
|---|---|---|
| **A** | **PASS** (unchanged) | No change to the cancellation safety net this pass. |
| **B** | **PASS at logic/test level; DEVICE_PENDING for on-hardware behavior** (unchanged) | No mic/recognizer timing change this pass. |
| **C** | **PASS** (strengthened) | The pre-claim race in voice Processing/outcome ownership — a narrower, earlier window the seventh pass's ownership check alone could not close — is fixed via recognizer-generation correlation, proven via revert-and-rerun with precise (not blanket) test discrimination. |
| **D** | **PASS at logic/test level; DEVICE_PENDING for pixel-level rendering** (unchanged) | No UI-surface change this pass. |
| **E** | **FAIL** (unchanged — still correctly not claimed as passing) | Device QA and human backend/Android owner review remain the only outstanding criteria. |

**Final conclusion: `DEVICE_VALIDATION_PENDING`.** The pre-claim race named by the master prompt is closed
by threading the recognizer's own generation through ownership (`VoiceInputEvent.generation` →
`VoiceTurnOwner.voiceGeneration` → generation-keyed `clearProcessingState`/`reassignProcessingOwner`),
architecturally independent of (and layered on top of) the seventh pass's turn-ownership check — with an
explicit "why an older, still-current-owner completion cannot clear a newer generation" argument above; the
required deterministic tests (pre-claim Success/Failure/Cancelled/health-blocked, a direct
old-generation-can't-clear-new-generation proof, transfer-vs-clear for both rejection and emergency paths,
and the `trySend`-failure/old-generation case) are added and independently confirmed via revert-and-rerun to
fail precisely on the mechanism they target. All verified against a cold clean build (265/265 JVM tests
passing, 0 failures/errors, 29 AndroidTest compiling clean, 0 new lint issues, OpenAPI still valid, fresh
checksums) — with one honestly-disclosed, isolated flake in an unrelated, pre-existing, already-documented
probabilistic real-thread test, reproduced-clean on immediate rerun. Explicitly **not**
`MOBILE_READY_FOR_BACKEND` — Gate E's device-QA and human-review criteria remain outstanding and cannot be
produced from this sandbox (`adb devices` empty). **AI Backend work has not been started**, per the master
prompt's explicit and repeated instruction, in this or any prior pass.
## Ninth independent re-audit — P1-1/P1-2 concurrency blockers in the eighth pass's own fix, and removing a flaky committed test (2026-07-29)

Starting state for this pass: `NOT_BACKEND_READY`. The eighth pass's generation-correlation fix (`VoiceInputEvent
.generation` → `VoiceTurnOwner.voiceGeneration` → generation-keyed `clearProcessingState`/
`reassignProcessingOwner`) was re-confirmed correct for the *pre-claim* race it targeted. An independent
source audit found two further concurrency blockers inside that same fix, plus flagged the eighth pass's own
disclosed stress-test flake as unacceptable for a "fully green" gate claim. This pass closes all three.

### P1-1 root cause: `reassignProcessingOwner` compared nothing on the "move away from" side

`resolveUnclaimedEvent(eventGeneration)` correctly decided *whether* to transfer (only when some other voice
turn genuinely still owns Processing) but the transfer itself,
`voiceController.reassignProcessingOwner(owner.voiceGeneration)`, named only the *destination* generation —
an unconditional overwrite once the "some owner exists" branch was taken. Failing schedule, confirmed by
direct source read of both `resolveUnclaimedEvent` and the (then single-argument) `reassignProcessingOwner`:

1. Voice turn A owns generation 100 and remains genuinely in flight.
2. Event B (generation 200) is enqueued, not yet routed.
3. A newer event C (generation 300) publishes its own `PROCESSING`/generation before B's routing runs —
   nothing gates a recognizer session's own publish on the *previous* event's routing state; `route()`
   processes queued events strictly one at a time, in enqueue order, but recognizer publishes are
   independent of that.
4. `route(B)` finally runs: A is still busy, B is rejected. `resolveUnclaimedEvent(200)` sees `activeOwner`
   is A (non-null) and calls `reassignProcessingOwner(100)` — **unconditionally**, with no way to notice the
   visible generation is already 300 (C's), not 200 (B's own).
5. The visible generation is silently stamped to 100, destroying C's legitimate, currently-visible
   `PROCESSING` before C's own transcript is even routed.
6. If A subsequently completes before C is ever routed, A's own `clearProcessingState(100)` call would
   **match** the now-wrongly-100 visible generation and clear it to `IDLE` — even though C is about to be
   accepted and is genuinely still going to be in flight.

`voiceOwnershipLock` does not close this: it correctly serializes ownership *decisions* (who currently owns
Processing at the assistant-turn level), but the actual recognizer-generation comparison inside
`reassignProcessingOwner` never existed at all before this pass — there was nothing for any lock to protect.

**Fix**: `VoiceController.reassignProcessingOwner(generation: Long)` →
`reassignProcessingOwner(expectedCurrentGeneration: Long, newOwnerGeneration: Long)` — a genuine
compare-and-set: only transitions when `state == PROCESSING && generation == expectedCurrentGeneration`,
otherwise a strict no-op. `resolveUnclaimedEvent` now passes `expectedCurrentGeneration = eventGeneration`
(the rejected/emergency event's *own* generation — B's 200, never A's 100) and
`newOwnerGeneration = owner.voiceGeneration` (A's 100). Implemented identically in
`AndroidSpeechRecognizerController` and `FakeVoiceController`.

**Why an older, rejected event's transfer attempt can no longer clobber a newer, unrelated session**: the
call now names *two* generations explicitly — the one it believes is currently visible (its own — since
that's the only generation this specific rejection could ever have legitimately observed) and the one it
wants to move to. If a third session has since published, the visible generation no longer matches what the
rejected event believes, and the compare-and-set silently declines — exactly the same "name the exact thing
you're entitled to touch" discipline the eighth pass already established for `clearProcessingState`, now
applied to the other mutating method too.

### P1-2 root cause: the generation check and its resulting mutation were never atomic across threads

Every `RecognitionListener` callback (`onReadyForSpeech`/`onError`/`onPartialResults`/`onResults`) does
`if (gen != generation.get()) return` as a guard, then — only if it passed — mutates `_state`/`activeSession`/
`_events`. `cancel()`, `shutdown()`, and a newer `beginListening()` all bump `generation` on the *caller's*
own thread, by design, so none of them ever has to wait on `mainThreadExecutor` just to invalidate a
generation (only the platform teardown/creation itself is confined there). Since `RecognitionListener`
callbacks are always delivered on the thread the recognizer was created on (the main thread in production),
and `cancel()` can be called from a genuinely different thread (the preferences collector, confirmed running
on `Dispatchers.Default` per this class's own existing KDoc/tests), the following was a real, not merely
theoretical, race:

1. `onResults(gen=1)` on the main thread evaluates `gen != generation.get()` — `1 == 1`, passes.
2. Before it mutates anything, a concurrent `cancel()` on a background thread reads `generation.get()`
   (still 1), bumps it to 2, and publishes `IDLE`.
3. The main thread's `onResults` call resumes exactly where it left off — nothing re-checks anything — and
   proceeds to set `state = PROCESSING`, `generation = 1` (stale), and enqueue a `VoiceInputEvent` for a
   session that was just cancelled.

This directly contradicted the class's own long-standing claim ("`[generation]` guards every recognizer
callback so a stale session ... can never resurrect state or emit a transcript") — the guard existed, but
checking it and acting on it were two separate, unsynchronized steps.

**Fix**: a new private `callbackLock = Any()`. Every callback's full body (check + mutation) now runs inside
`synchronized(callbackLock)`. `cancel()`'s generation-read-bump-and-publish, `beginListening()`'s dispatched
check-then-act (restructured as `if`/`else` instead of an early return, to avoid relying on non-local-return-
through-inlining semantics across the non-inline `mainThreadExecutor.execute { }` boundary), and
`shutdown()` (which now *also* bumps `generation`, closing a related gap: previously a callback for the
still-current generation could resurrect state even after shutdown, since shutdown never invalidated
`generation` at all) all acquire the same lock. `isShutdown` lost its `@Volatile` — both its one write and
one read now happen inside `callbackLock`, which alone provides the necessary cross-thread visibility.
Never held across a suspension point (nothing here is a `suspend` function) and never held while the
platform recognizer itself is being driven except inside `beginListening()`'s own already-`mainThreadExecutor`
-confined block (which was already strictly serialized with every other platform call regardless).

**Why a callback that already passed its check cannot be undone by a concurrent bump**: with a shared lock,
"check `generation`, then mutate accordingly" is one atomic unit. Whichever side — the callback or the
generation-bumping caller — acquires the lock first completes its entire check-then-act before the other
side's own read can even happen. There is no intermediate state to observe from outside: either the callback's
view of `generation` was still valid for its *entire* locked section (a legitimate outcome — the transcript
genuinely arrived a moment before cancellation), or the bump had already fully landed before the callback's
own check ever ran (so the check correctly fails). The previously-possible third case — check passes, bump
happens, callback continues anyway — is now structurally impossible.

### Deterministic test sequences

`AndroidSpeechRecognizerControllerTest.kt` gained 5 tests (21 → 26). The two structural proofs for P1-2 use a
blocking `AppClock` test double — an "arm exactly once" `AtomicBoolean` guard ensures only the intended
callback invocation pauses, since `beginListening()`/`startListening()` also call `clock.nowMs()`
incidentally for `requestedAtMs` and would otherwise be paused by mistake:

1. **Cancel racing a stale callback**: start a session, arm the clock, launch a real thread calling
   `onPartialResults` (passes its generation check, then blocks *inside* `callbackLock` at the armed clock
   call). Confirm (via a latch) it is genuinely paused there. Launch a second real thread calling `cancel()`.
   Assert `cancel()`'s completion latch is **still not fired after a bounded 300ms wait** — a structural
   guarantee on the fix (the JVM monitor makes this true regardless of how long the test waits, not a timing
   guess), and would be **immediately true** on the pre-fix implementation (nothing there for `cancel()` to
   wait on). Release the clock's pause; confirm `cancel()` now completes, and the final state is `IDLE`.
2. **A newer start racing a stale callback**: same pause technique, but the concurrent actor is
   `startListening("assistant")` (mints generation 2 instantly, queues its dispatched block) followed by
   running `executor.runAll()` on a separate thread (since that is what actually needs `callbackLock` to
   process generation 2's block). Same bounded-wait structural assertion; confirms `factory.createCallCount`
   stays at 1 (gen 2 has not run) until the paused callback releases the lock, then reaches 2 with the
   correct final `WAKE_WORD_DETECTED`/generation 2 state.
3. **Shutdown-generation-bump**: `shutdown()` then a stale `onReadyForSpeech` — asserts state stays
   `WAKE_WORD_DETECTED`, never resurrects `LISTENING`. Sequential, no threads needed, but genuinely
   discriminates old vs. new (old `shutdown()` never bumped `generation` at all).
4–5. Basic sequential regression coverage: a stale `onError` after a *fully completed* `cancel()`, and a
   stale `onResults` (blank-transcript branch — Bundle limitation, see below) after a *fully completed*
   newer start, both confirming the pre-existing (pre-dating this pass) generation guard still correctly
   rejects once the bump has already landed. These do not by themselves prove the new interleaved-atomicity
   guarantee (tests 1–2 above are what prove that) — a purely sequential "bump fully first, then call back"
   scenario already worked before this pass too, since the bump itself was always instantaneous/unguarded.

`VoiceAssistantCoordinatorTest.kt` gained 4 tests (39 → 43) for P1-1:

1. A direct, minimal `FakeVoiceController` proof: emit generation 200, `reassignProcessingOwner(200, 100)`
   succeeds (100 now visible); emit generation 300 (supersedes); `reassignProcessingOwner(200, 999)` — a
   stale caller still expecting 200 — is a safe no-op, 300 stays visible.
2. A full-pipeline A/B/C test: A (100) accepted and in flight; B (200) and C (300) both emitted, enqueued,
   unrouted (using an independently-pumped `voiceDispatcher`, since `backgroundScope`'s shared Unconfined
   dispatcher would otherwise route each event inline, synchronously, as part of its own `emitFinalTranscript`
   call — leaving no window to enqueue both without either being routed). Drain the queue: B rejected then C
   rejected, in enqueue order. **Note on assertion design**: B and C both ultimately want to transfer to the
   *same* still-in-flight owner (A/100), so the final converged generation value is identical (100) whether
   B's stale attempt was correctly rejected or blindly applied — checking only the end state would not
   discriminate a regression. `FakeVoiceController` gained `reassignProcessingOwnerCalls` (a recorded list of
   every `(expectedCurrentGeneration, newOwnerGeneration)` pair, test-observability only, no behavior change)
   so the test can assert the *exact* argument sequence: `containsExactly(200L to 100L, 300L to 100L).inOrder()`
   — proving B's own call named exactly its own generation, never anything else. A then completes for real;
   its clear now correctly matches.
3. The same invariant, same argument-sequence assertion, for a rejected emergency-phrase B ahead of a newer C.
4. A fully deterministic replacement for the eighth pass's real-thread stress test (P1-3, see below).

### Revert-and-rerun evidence

**P1-1**: reverted `FakeVoiceController.reassignProcessingOwner` to ignore `expectedCurrentGeneration`
(unconditional, matching the pre-fix shape), marked `// TEMP-REVERT-FOR-VERIFICATION`. Re-ran
`VoiceAssistantCoordinatorTest`: **43 tests completed, 1 failed** — exactly the direct `FakeVoiceController`
CAS proof (test 1 above). The two full-pipeline tests (2–3 above) correctly did **not** fail on this revert:
since they assert on `reassignProcessingOwnerCalls`' *arguments* (which the coordinator always passes
correctly regardless of what `FakeVoiceController` does internally with them) rather than solely on final
converged state, they exercise the coordinator's wiring, a distinct concern from the fake's own CAS logic —
by design, not an oversight (see the assertion-design note above). Restored the fix, reconfirmed 43/43 green.

**P1-2**: reverted `callbackLock` to `private val callbackLock: Any get() = Any()` — a property getter
returning a fresh, unshared monitor on every access, so every `synchronized(callbackLock)` call synchronizes
on a *different* object and none of them actually contend, without touching any surrounding call site. Marked
`// TEMP-REVERT-FOR-VERIFICATION`. Re-ran `AndroidSpeechRecognizerControllerTest`: **26 tests completed, 2
failed** — exactly the two genuinely-forced-interleaving tests (1–2 above). The shutdown-generation-bump test
and both basic sequential tests correctly did **not** fail — precise, not blanket, discrimination: they do
not depend on genuine cross-thread lock contention, only on generation values that are correct with or
without the lock in a single-threaded execution. Restored the fix, reconfirmed 26/26 green. Confirmed via
`grep -r "TEMP-REVERT-FOR-VERIFICATION"` across `src/main`/`src/test`: no marker remains anywhere.

### P1-3: diagnosing and replacing the flaky committed test

The eighth pass's real-thread stress test (`VoiceAssistantCoordinatorTest`'s "stress - rapid voice turn pairs
on real threads never leak stale state across turns") had failed once out of 5 runs during that pass's own
gate verification. Rather than accept "reruns clean" as sufficient (per this pass's explicit instruction),
the actual mechanism was diagnosed: the test's final wait loop per iteration polled
`while (scenario.coordinator.turnOutcome.value == null) Thread.sleep(5)` — waiting for *any* non-null
outcome, not specifically B's. Turn A's own completion consumer can legitimately publish A's own outcome
first, if it happens to run before B's `route()` claims ownership and resets `turnOutcome` to `null` — this
is correct, already-documented, intentional behavior (see the eighth-pass pre-claim tests, which explicitly
assert "A's own outcome was legitimately published here — this is not itself a bug"). If the real-thread
scheduler happened to let A's consumer run late enough to be sampled by this loop, the test would see a
non-null (but wrong, A's) outcome, exit the loop immediately, and fail the subsequent
`.contains("B vòng $iteration")` assertion — a race in the test's *own* synchronization, not in production
code. This precisely explains the originally-observed symptom ("showing A's reply text instead of B's").

**Fix applied to the existing test**: the wait loop now polls for the *specific* expected condition
(`(turnOutcome.value as? Success)?.replyText?.contains("B vòng $iteration") == true`) instead of merely
non-null, so it correctly polls straight through any transient A-outcome to B's real one, or times out and
fails with a clear diagnostic if something is genuinely wrong. This is a strengthening of the test's own
assertion, not a weakened one — not a timeout increase, not a retry, not `@Ignore`.

**Deterministic replacement (the primary proof from now on)**: a new test,
"deterministic stress equivalent - 20 consecutive voice turn pairs with forced ordering never leak stale
state," reuses the eighth pass's two-independently-pumped-`TestCoroutineScheduler` technique across 20 A/B
pairs on one long-lived coordinator (so state cannot silently accumulate across iterations), alternating
which side — A's completion consumer or B's `route()` claim — runs first on each iteration, forcing *both*
orderings explicitly with zero timing dependency, instead of hoping a real thread pool happens to hit both
by chance. The corrected real-thread test is retained only as supplementary, independent coverage — matching
the file's existing convention for that category of test.

Both the corrected real-thread test and its deterministic replacement passed on every run this pass,
including the mandatory 20-consecutive-run check below.

### Rejected-event and emergency semantics (carried forward from the eighth pass, now generation-CAS-checked on both ends)

| Scenario | `activeOwner` | Action taken |
|---|---|---|
| Rejected — a text turn is busy, no voice turn accepted | `null` | Clear, using the rejected event's own generation |
| Rejected — an accepted voice turn is still genuinely in flight | non-null | **Transfer** the visible generation back to the owner's `voiceGeneration` — only if the visible generation still matches the rejected event's own (P1-1's compare-and-set); never clear outright |
| Emergency phrase — no voice turn accepted | `null` | Clear, using the emergency event's own generation |
| Emergency phrase — an accepted voice turn is still genuinely in flight | non-null | Transfer back to the owner, same as the rejection case, same compare-and-set |
| A turn's own completion, still current owner | n/a (owner is itself) | Publish outcome, clear using its *own* `voiceGeneration` (no-op if a newer session has since overwritten the visible generation) |
| A turn's own completion, no longer owner (superseded) | n/a | Stale no-op — the seventh pass's ABA-race guard, unchanged |

### Re-audit for regressions (this pass)

Direct source re-read: `AssistantTurnCoordinator.kt` **not touched** this pass. The eighth pass's generation
threading (`VoiceInputEvent.generation`, `VoiceTurnOwner.voiceGeneration`, `onResults()`'s explicit
`generation = gen` stamp) is structurally unchanged — only `reassignProcessingOwner`'s signature/body and
the addition of `callbackLock` around the recognizer callbacks/`cancel`/`shutdown`/`beginListening` were
touched. `finishListening()` was deliberately **not** wrapped in `callbackLock`: it never mutates
`_state`/`_events`/`generation`, only conditionally invokes a platform call gated by `activeSession`'s own
main-thread-confined generation tag (an already-existing, sufficient guard) — adding the lock there would
have been unnecessary defensive code for a scenario that cannot occur. Grepped for every production call
site of `clearProcessingState`/`reassignProcessingOwner`: only `VoiceAssistantCoordinator` calls either,
confirming the "never called from UI code" invariant still holds. Grepped for every `VoiceController`
implementer: only `AndroidSpeechRecognizerController` and `FakeVoiceController` (both updated); the two
`object : VoiceController by baseVoiceController` test delegates elsewhere require no change (Kotlin
interface delegation forwards the new two-argument signature automatically). No transcript/reply/
`Throwable.message` logged anywhere new this pass. No new wire DTO or OpenAPI change.

### Latency and no-regression audit (this pass)

Full scan of every file touched this pass for `Thread.sleep`/`runBlocking`/`delay(` in `src/main`: zero
matches. `callbackLock` adds a JVM monitor acquisition (uncontended in the overwhelming common case — a
single recognizer session, no concurrent preferences-toggle) around code that was already doing the exact
same work; `reassignProcessingOwner`'s new compare-and-set is one additional `Long` equality check. No new
suspension point, no loop, no wait anywhere in production code.

### Verification commands and results (this pass)

| Command | Result |
|---|---|
| `$env:JAVA_HOME='C:\Program Files\Eclipse Adoptium\jdk-17.0.19.10-hotspot'; gradlew.bat clean :app:assembleDebug :app:testDebugUnitTest --rerun-tasks :app:lintDebug :app:compileDebugAndroidTestKotlin :app:assembleRelease :app:lintRelease` | **BUILD SUCCESSFUL in 1m 6s**, 117 actionable tasks, all executed |
| Unit test XML aggregation | **tests=274 failures=0 errors=0 skipped=0**, 25 test classes (265 → 274: +9 — 4 in `VoiceAssistantCoordinatorTest` (39→43), 5 in `AndroidSpeechRecognizerControllerTest` (21→26)) |
| Full JVM suite, run a **second** time | **BUILD SUCCESSFUL in 29s**, identical 274/274, 0/0/0 |
| `VoiceAssistantCoordinatorTest` + `AndroidSpeechRecognizerControllerTest`, run **20 consecutive times** | **20/20 clean** — 43 + 26 tests, 0 failures/errors every run |
| AndroidTest count | **29** (unchanged — no Compose UI surface touched) |
| `:app:lintDebug` / `:app:lintRelease` | **0 errors** both variants; 12 pre-existing findings, identical rule-id set, nothing new |
| SHA-256 on both APKs (fresh, this pass) | debug `fe836b8abf6e83cc4928609672d64c1cbb864053dd241a20c48fbacc9fe514d8`, release-unsigned `a255a116da724719fd20068e048de831a6616d8bfc810fdb8321ac24ec0cb7ab` |
| `python -m openapi_spec_validator openapi/safedrive-v1.yaml` | **OK** (spec unchanged this pass) |
| `adb devices` | empty — no device/emulator attached |

**Flake disclosure — two items**:

1. The test P1-3 specifically targets (see above): diagnosed, fixed, and given a deterministic primary
   replacement. Both passed on every run this pass.
2. An unrelated, pre-existing, **untouched this pass** test, `AssistantTurnCoordinatorTest`'s "concurrent
   submits from many real threads at once start exactly one turn" (24 real threads via `CyclicBarrier`),
   failed once during one full-suite run (`expected: 1 but was: 2`). This class was not modified this pass
   and shares no code with any voice/recognizer path touched here. Reran it in isolation 7 additional times:
   all 7 clean. Assessed as ordinary real-thread scheduling variance on this machine — disclosed transparently
   rather than silently ignored, but explicitly out of this pass's P1-1/P1-2/P1-3 scope; not modified, left
   for a future pass to decide whether it also warrants a deterministic replacement.

Every deterministic test — including all 15 new ones this pass — passed 100% of the time across every run.

### Gate re-evaluation

| Gate | Verdict | What changed this pass |
|---|---|---|
| **A** | **PASS** (unchanged) | No change to the cancellation safety net this pass. |
| **B** | **PASS at logic/test level; DEVICE_PENDING for on-hardware behavior** (unchanged) | No mic/recognizer timing change this pass. |
| **C** | **PASS** (strengthened further) | Two further P1 concurrency blockers in the eighth pass's own fix are closed (`reassignProcessingOwner`'s missing compare-and-set on its "from" side, and the recognizer callbacks' non-atomic check-then-act), plus the eighth pass's own flaky test is diagnosed, fixed, and given a deterministic primary replacement. |
| **D** | **PASS at logic/test level; DEVICE_PENDING for pixel-level rendering** (unchanged) | No UI-surface change this pass. |
| **E** | **FAIL** (unchanged — still correctly not claimed as passing) | Device QA and human backend/Android owner review remain the only outstanding criteria. |

**Final conclusion: `DEVICE_VALIDATION_PENDING`.** Both P1 blockers named by the master prompt are closed:
`reassignProcessingOwner` is now a genuine two-sided compare-and-set, and every recognizer callback's
generation check is now atomic with its own resulting mutation under `callbackLock`, both verified via
revert-and-rerun with precise (not blanket) test discrimination. The eighth pass's own committed flaky test
is diagnosed (a bug in the test's own polling condition, not production code), fixed, and superseded by a
fully deterministic primary proof, run 20 consecutive times with zero failures. All verified against a cold
clean build (274/274 JVM tests passing, run twice, 0 failures/errors, 29 AndroidTest compiling clean, 0 new
lint issues, OpenAPI still valid, fresh checksums) — with one honestly-disclosed, isolated, investigated,
non-blocking flake in an unrelated, pre-existing, untouched-this-pass real-thread test (7/7 clean reruns).
Explicitly **not** `MOBILE_READY_FOR_BACKEND` — Gate E's device-QA and human-review criteria remain
outstanding and cannot be produced from this sandbox (`adb devices` empty). **AI Backend work has not been
started**, per the master prompt's explicit and repeated instruction, in this or any prior pass.
## Tenth independent re-audit — completing generation linearization, and eliminating a nondeterministic committed test (2026-07-29)

Starting state for this pass: `NOT_BACKEND_READY`. The ninth pass's `callbackLock` was re-confirmed correct
for what it protected. An independent audit found the ninth pass's own fix left one generation-mutating path
unguarded, plus identified that the committed concurrent-submit test's "expected 1, got 2" failure was caused
by the test's own setup, not proven to be a production defect. This pass closes both.

### Blocker 1 root cause: `beginListening()`'s own generation mint ran outside `callbackLock`

The ninth pass wrapped every `RecognitionListener` callback's check-then-act, `cancel()`'s bump-and-publish,
and `beginListening()`'s *dispatched* check-then-act in `callbackLock`. It did **not** wrap
`beginListening()`'s own `val gen = generation.incrementAndGet()` — that mint still ran on the caller's
thread, before ever touching the lock. Direct re-read confirmed this was a genuine, not merely theoretical,
gap:

1. An old `RecognitionListener` callback acquires `callbackLock`.
2. It checks `gen == generation.get()` — passes, since nothing has bumped `generation` yet.
3. A concurrent `startListening()` on another thread calls `generation.incrementAndGet()` — this does **not**
   need `callbackLock`, so it proceeds immediately, invisible to the callback still holding the lock.
4. The old callback, unaware, continues mutating `_state`/enqueuing a `VoiceInputEvent` using its own
   now-stale generation.
5. The newer start's dispatched block runs later, once the callback releases the lock, and correctly
   re-validates *its own* generation — but that check was never able to protect the *old* callback's
   already-in-progress mutation, since the two were never actually serialized against each other.

This directly contradicted the claimed model ("every generation bump is serialized against callback
validation and mutation") — `cancel()`'s and `shutdown()`'s bumps were correctly inside the lock; only
`beginListening()`'s mint was not.

**Fix**: `val gen = generation.incrementAndGet()` → `val gen = synchronized(callbackLock) { generation.incrementAndGet() }`.
No other line changed — the dispatched block's own re-check was already correct and remains unchanged.

**Why this closes the gap**: the mint and any callback's locked check-then-act now compete for the exact same
monitor. Whichever acquires it first completes entirely before the other can even begin. There are now
exactly two possible orderings, both correct: (a) the callback acquires the lock first, completes its full
check-then-act using a generation value that is *guaranteed* not to change until it releases the lock (since
nothing else can bump `generation` while it holds the lock) — legitimate, since the transcript/event genuinely
arrived before the newer session existed; or (b) the mint acquires the lock first, bumps `generation`,
releases — and the callback's own subsequent check (whenever it gets the lock) correctly observes the new
value and bails. The previously-reachable third case — check passes, concurrent mint slips in unguarded,
callback continues regardless — is now structurally unreachable.

### Deterministic forced-interleaving evidence (blocker 1)

`AndroidSpeechRecognizerControllerTest.kt`: the ninth pass's "newer start" test was rewritten (not merely
extended) because its own structure could no longer even execute correctly against the fix — it called
`controller.startListening("assistant")` synchronously on the test's main thread, which, once the mint itself
requires `callbackLock`, would have deadlocked the test itself (blocking on a lock never released before the
test's own later `proceed.countDown()` call, which was unreachable from inside the blocked call). Rewritten as:

1. Start generation 1; arm a blocking `AppClock`; launch a real thread invoking `onPartialResults`, which
   passes its own generation check and then blocks *inside* `callbackLock` at the armed clock call. A latch
   confirms it is genuinely parked there.
2. Invoke `startListening("assistant")` from a **second** real thread (required, since the mint itself now
   blocks synchronously on the fixed implementation).
3. Assert that thread's own completion latch is **not** fired after a bounded 300ms wait — structural (the
   JVM monitor makes this true regardless of how long the test waits), not a timing guess. Also assert nothing
   has even been queued on the executor yet (`pendingCount == 0`) — the mint itself, not just the dispatch,
   never happened.
4. Release the paused callback; assert it finishes, and *only then* does the second thread's `startListening`
   call return, mint generation 2, and (once the executor is run) dispatch correctly.
5. **Reverse order**, as its own separate test: generation 2 wins first, fully, sequentially (mint, dispatch,
   executor run all complete) — then every generation-1 callback (`onReadyForSpeech`/`onPartialResults`/
   `onResults`) is confirmed to be a stale no-op, leaving generation 2's state completely untouched.
   `onResults`'s specific "emits no event" claim remains provable only by code inspection here (the identical
   lock, identical guard, guards the enqueue too) rather than an independent runtime assertion — the
   pre-existing Bundle-stubbing limitation (unchanged since the eighth pass) still prevents a non-blank
   transcript from ever reaching that branch in this environment.

### Revert-and-rerun evidence (blocker 1)

Reverted `val gen = synchronized(callbackLock) { generation.incrementAndGet() }` back to
`val gen = generation.incrementAndGet()`, marked `// TEMP-REVERT-FOR-VERIFICATION`. Re-ran
`AndroidSpeechRecognizerControllerTest`: **27 tests completed, 1 failed** — exactly the new forced-mint-
interleaving test. The reverse-order test correctly did **not** fail (that direction never depended on the
mint being locked — generation 2 wins fully first regardless of where its mint is synchronized). Restored the
fix, reconfirmed 27/27 green. Confirmed via grep: no `TEMP-REVERT-FOR-VERIFICATION` marker remains anywhere.

### Blocker 2: the concurrent-submit test's own nondeterminism

`AssistantTurnCoordinatorTest`'s "concurrent submits from many real threads at once start exactly one turn"
(24 threads via `CyclicBarrier`) uses the real, ungated `MockSafeDriveGateway`, which can answer fast enough
that the first accepted turn completes and frees single-flight again **before all 24 threads have even
reached their own `submit()` call** — a later thread's `submit()` is then *legitimately* accepted as a second,
genuinely new turn, producing `acceptedCount == 2` without that proving anything about
`AssistantTurnCoordinator.submit()`'s single-flight guard under genuine contention. This, not a production
defect, was confirmed to be the cause of the test's earlier intermittent `expected 1, got 2`.

**Fix (test-only — `AssistantTurnCoordinator.submit()` itself was not touched)**: the gateway used by this
test is now wrapped so its `queryAssistant()` call awaits a `CompletableDeferred` gate before delegating to
the real mock. The rewritten test:

1. Starts all 24 threads via the same `CyclicBarrier`, each calling `submit()`; the one that succeeds
   captures its `StartedTurn` (via `submit()`'s `onStarted` callback) into an `AtomicReference`.
2. Joins every thread with a 10s timeout, then **asserts every thread has actually terminated**
   (`threads.all { !it.isAlive }`) — a silently-continuing timed join would hide a genuine hang instead of
   failing on it.
3. Asserts exactly one `submit()` call returned `true`, and that its `StartedTurn` was captured.
4. Asserts exactly one in-flight turn and exactly one user-sender message — while the accepted turn's own
   gateway call is still genuinely gated/suspended, proving the single-flight guard held for the entire
   window all 24 threads were contending, not merely "eventually settled to 1 by coincidence."
5. Only then releases the gate.
6. Awaits the accepted turn's own terminal state via its per-turn `Deferred` (`turn.completion.await()`,
   wrapped in `runBlocking { withTimeout(5_000) { ... } }`) — a genuine, deterministic await, never a
   `Thread.sleep` polling loop.
7. Asserts the terminal state is `Success`, and exactly one user message and one assistant reply exist.

No production code in `AssistantTurnCoordinator.kt` was changed — the rewritten test, run repeatedly (see
below), never surfaced any evidence of an actual single-flight defect; every one of the 24 threads'
`submit()` outcomes was consistent with the guard working correctly once the test itself stopped racing its
own gateway response against its own thread-startup.

### Files changed this pass

- `android/app/src/main/java/vn/edu/haui/hvs/safedrive/voice/AndroidSpeechRecognizerController.kt` —
  `beginListening()`'s mint moved inside `callbackLock`; `callbackLock`'s KDoc extended.
- `android/app/src/test/java/vn/edu/haui/hvs/safedrive/voice/AndroidSpeechRecognizerControllerTest.kt` — the
  "newer start" test rewritten (renamed to reflect what it now proves); a new reverse-order test added.
- `android/app/src/test/java/vn/edu/haui/hvs/safedrive/domain/usecase/AssistantTurnCoordinatorTest.kt` — the
  concurrent-submit test rewritten with a gated gateway, thread-termination assertion, and a deterministic
  per-turn await.
- No production change to `AssistantTurnCoordinator.kt`, `VoiceAssistantCoordinator.kt`,
  `FakeVoiceController.kt`, or `VoiceController.kt` this pass — the ninth pass's P1-1 fix is untouched and
  unaffected.

### Latency and no-regression audit (this pass)

Full scan for `Thread.sleep`/`runBlocking`/`delay(` in `src/main`: zero matches (unchanged). The rewritten
concurrent-submit test's `runBlocking { withTimeout(5_000) { turn.completion.await() } }` is test-only code,
replacing a `Thread.sleep`-polling loop with a genuine, non-polling blocking await — if anything, this
removes latency-adjacent code from the test suite rather than adding any to production. `beginListening()`'s
mint now briefly holds `callbackLock` (uncontended in the overwhelming common case) instead of using a bare
atomic increment — negligible, same class of cost as every other `synchronized` block already in this file.

### Verification commands and results (this pass)

| Command | Result |
|---|---|
| `$env:JAVA_HOME='C:\Program Files\Eclipse Adoptium\jdk-17.0.19.10-hotspot'; gradlew.bat clean :app:assembleDebug :app:testDebugUnitTest --rerun-tasks :app:lintDebug :app:compileDebugAndroidTestKotlin :app:assembleRelease :app:lintRelease` | **BUILD SUCCESSFUL in 33s**, 117 actionable tasks, all executed |
| Unit test XML aggregation | **tests=275 failures=0 errors=0 skipped=0**, 25 test classes (274 → 275: +1, `AndroidSpeechRecognizerControllerTest` 26→27; `AssistantTurnCoordinatorTest` rewritten at unchanged count 53; `VoiceAssistantCoordinatorTest` unchanged at 43) |
| Full JVM suite, run a **second** time | **BUILD SUCCESSFUL in 19s**, identical 275/275, 0/0/0 |
| `AndroidSpeechRecognizerControllerTest` + `VoiceAssistantCoordinatorTest`, run **20 consecutive times** | **20/20 clean** — 27 + 43 tests, 0 failures/errors every run |
| `AssistantTurnCoordinatorTest` (rewritten concurrent-submit test), run **50 consecutive times** | **50/50 clean** — 53 tests, 0 failures/errors every run |
| AndroidTest count | **29** (unchanged — no Compose UI surface touched) |
| `:app:lintDebug` / `:app:lintRelease` | **0 errors** both variants; 12 pre-existing findings, identical rule-id set, nothing new |
| SHA-256 on both APKs (fresh, this pass) | debug `bf91396b0715c3867b65f9a32732ee711432d3ee892125e090e76e27b47218b5`, release-unsigned `4b8b4e413554792d887b44c56ead6b97f3c81e542fa9543dd626a0339cdeeb7c` |
| `python -m openapi_spec_validator openapi/safedrive-v1.yaml` | **OK** (spec unchanged this pass) |
| `adb devices` | empty — no device/emulator attached |

No flakes of any kind this pass — every run of every affected test, across both full-gate runs and every
repeated-run batch (20× and 50×), was 100% clean. Unlike the ninth pass, there is no flake disclosure section
here: none occurred.

### Gate re-evaluation

| Gate | Verdict | What changed this pass |
|---|---|---|
| **A** | **PASS** (unchanged) | No change to `AssistantTurnCoordinator.kt`'s cancellation safety net; the concurrent-submit test rewrite is test-only. |
| **B** | **PASS at logic/test level; DEVICE_PENDING for on-hardware behavior** (unchanged) | No mic/recognizer timing change this pass. |
| **C** | **PASS** (completed) | The last unguarded generation-mutating path (`beginListening()`'s own mint) now participates in `callbackLock`, closing the gap the ninth pass's own fix left open — verified via forced-interleaving revert-and-rerun. |
| **D** | **PASS at logic/test level; DEVICE_PENDING for pixel-level rendering** (unchanged) | No UI-surface change this pass. |
| **E** | **FAIL** (unchanged — still correctly not claimed as passing) | Device QA and human backend/Android owner review remain the only outstanding criteria. |

**Final conclusion: `DEVICE_VALIDATION_PENDING`.** Both blockers named by the master prompt are closed:
`beginListening()`'s generation mint now participates in the same `callbackLock` linearization protocol as
every other generation-mutating operation, verified by a genuinely-forced-interleaving test and revert-and-
rerun; the concurrent-submit test's nondeterminism (a gap in the test's own setup, not a production defect)
is closed by gating the accepted turn's gateway call, joining and verifying every worker thread's actual
termination, and awaiting completion deterministically rather than polling — run 50 consecutive times, all
clean. See the verification-commands table below for the full gate re-run this pass. Explicitly **not**
`MOBILE_READY_FOR_BACKEND` — Gate E's device-QA and human-review criteria remain outstanding and cannot be
produced from this sandbox (`adb devices` empty). **AI Backend work has not been started**, per the master
prompt's explicit and repeated instruction, in this or any prior pass.
