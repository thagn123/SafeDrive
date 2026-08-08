# SafeDrive AI — Android

Native Android Phone MVP for SafeDrive AI. Kotlin + Jetpack Compose + Material 3, no WebView.
The `src/` React prototype at the repository root is reference-only for UI/copy/flow — this module
does not depend on it and never modifies it.

Full plan: [`docs/android-mvp-plan/`](../docs/android-mvp-plan/), especially
[`02-android-architecture.md`](../docs/android-mvp-plan/02-android-architecture.md) and
[`03-data-api-contract.md`](../docs/android-mvp-plan/03-data-api-contract.md).

Post-MVP mobile stabilization pass (W0–W8, gating the start of the AI Backend):
[`12-mobile-completion-before-ai-backend.md`](../docs/android-mvp-plan/12-mobile-completion-before-ai-backend.md),
executed via
[`13-claude-mobile-stabilization-prompt.md`](../docs/android-mvp-plan/13-claude-mobile-stabilization-prompt.md).
**All 8 stabilization workstreams (W0–W8) are complete** — see
[`../docs/android-stabilization-progress.md`](../docs/android-stabilization-progress.md) for the full
per-workstream log (files changed, decisions, test evidence) and the "Mobile Stabilization Pass"
section below for the architecture summary. The original 8-phase MVP build's per-phase notes further
down are kept for historical rationale but are **superseded** wherever this stabilization pass
touched the same code — each superseded section has a pointer added at its top.

## Deliverables

- This file — architecture, build/run instructions, per-phase and per-workstream decisions.
- [`DEMO_SCRIPT.md`](DEMO_SCRIPT.md) — step-by-step Demo Mode walkthrough (Cockpit, Simulator,
  Diagnostics, Assistant/voice, Emergency timeline, optional Remote Mode).
- [`TEST_REPORT.md`](TEST_REPORT.md) — every build/test/lint command run and its result, full unit
  test breakdown, release-artifact security audit findings.
- [`KNOWN_LIMITATIONS.md`](KNOWN_LIMITATIONS.md) — consolidated gaps (mostly: no physical
  device/emulator was available in this environment) and intentional scope boundaries.
- [`MOCK_VS_REMOTE_COVERAGE.md`](MOCK_VS_REMOTE_COVERAGE.md) — endpoint-by-endpoint Mock vs Remote
  implementation status and error-mapping table.
- [`../openapi/safedrive-v1.yaml`](../openapi/safedrive-v1.yaml) + `../openapi/examples/*.json` — the
  **candidate**, offline-validated API contract (source of truth for the wire shape; see W7 — not
  "frozen" until Gate E's device-QA/human-review criteria also pass).
- [`../docs/backend-handoff.md`](../docs/backend-handoff.md),
  [`../docs/assistant-action-allowlist.md`](../docs/assistant-action-allowlist.md),
  [`../docs/latency-budget.md`](../docs/latency-budget.md) — the backend handoff package (W7).
- [`../docs/mobile-parity-matrix.md`](../docs/mobile-parity-matrix.md),
  [`../docs/mobile-latency-baseline.md`](../docs/mobile-latency-baseline.md) — prototype parity and
  before/after latency evidence (W0/W4).
- [`../docs/android-mvp-plan/01-source-audit.md`](../docs/android-mvp-plan/01-source-audit.md) — the
  AI-Studio-prototype → Android mapping (source of truth for what was ported/rewritten/dropped).

## Phase status

| Phase | Scope | Status |
|---|---|---|
| 1 | Foundation, domain model, Mock Gateway | Done |
| 2 | Cockpit + design system | Done |
| 3 | Assistant + Diagnostics + Settings | Done |
| 4 | Vehicle Simulator | Done |
| 5 | Voice + TTS | Done |
| 6 | Emergency | Done |
| 7 | Remote REST | Done |
| 8 | QA, hardening, handoff | Done |

All 8 original MVP phases are complete, and all 8 stabilization workstreams below are complete on top
of them:

| Workstream | Scope | Status |
|---|---|---|
| W0 | Baseline, Git, parity/latency evidence | Done |
| W1 | Unified conversation store + single assistant turn coordinator | Done |
| W2 | Voice input routing split from the assistant pipeline | Done |
| W3 | TTS correctness (domain `TtsController`, device compatibility) | Done |
| W4 | Latency instrumentation, Demo fast path | Done |
| W5 | Remote/session correctness, fail-fast (no Mock fallback) | Done |
| W6 | UI parity, Simulator discoverability | Done |
| W7 | OpenAPI contract freeze, backend handoff package | Done |
| W8 | Device QA, release candidate, Gate A–E evaluation | Done (device-dependent items marked `DEVICE_PENDING`, not silently passed) |

See `TEST_REPORT.md` for the final build/test/lint evidence, `docs/android-stabilization-progress.md`
for the full workstream-by-workstream log, and `KNOWN_LIMITATIONS.md` for what still needs a physical
device to verify. Per-phase detail sections below are kept for architecture rationale and are not
rewritten retroactively — superseded ones are marked at their top instead.

## Toolchain

| Tool | Version |
|---|---|
| AGP | 9.3.0 (built-in Kotlin — no `org.jetbrains.kotlin.android` plugin applied) |
| Gradle | 9.5.0 (wrapper committed under `gradle/wrapper/`) |
| Kotlin (compose-compiler/serialization plugins) | 2.4.10 |
| JDK | 17 |
| compileSdk / targetSdk | 37 / 37 |
| minSdk | 26 |
| Compose BOM | 2026.06.00 |

Verified against the live Google Maven / Gradle Services / Kotlin release feeds during this build
(see `docs/android-mvp-plan/00-executive-plan.md` for why these exact versions are locked).

## Build

From the `android/` directory:

```bash
./gradlew.bat :app:assembleDebug
./gradlew.bat :app:testDebugUnitTest
./gradlew.bat :app:lintDebug
```

Requires `JAVA_HOME` pointing at a JDK 17 install and an Android SDK at `ANDROID_HOME` with
`platforms;android-37`, `build-tools;37.0.0` and `platform-tools` installed (see `local.properties`,
which is machine-specific and gitignored).

Output APK: `app/build/outputs/apk/debug/app-debug.apk`.

## Run

Demo Mode is the default and requires no backend:

```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

Launching the app opens directly to a minimal Cockpit screen driven end-to-end through
`MockSafeDriveGateway` — this proves the UI → ViewModel → UseCase → Repository → Gateway chain from
`02-android-architecture.md` before Phase 2 replaces the Cockpit body with the full screen spec.
Assistant/Diagnostics/Settings tabs currently show a "sẽ hoàn thiện ở Phase 3" placeholder — this is
intentional scope for Phase 1 and is not a bug.

## Architecture implemented in Phase 1

```text
Compose UI (CockpitScreen)
  → CockpitViewModel
    → ObserveCockpitUseCase / SessionCoordinator
      → GatewayProvider → SafeDriveGateway (interface)
        → MockSafeDriveGateway (Demo Mode; Remote arrives in Phase 7)
      → VehicleDataSource (interface) → MockVehicleDataSource
```

- `core/model` — domain models/enums per `03-data-api-contract.md` (no Android/network imports).
- `core/common` — `AppClock`, `DispatcherProvider`, `IdGenerator`, `GatewayResult`/`GatewayError`.
- `core/network` — `BaseUrlValidator` (scheme/host/cleartext validation for Developer Mode BASE_URL).
- `core/testing` — `FakeClock`, fixture builders (no test-framework dependency, safe for main sourceSet).
- `domain/repository` — stable interfaces only: `SafeDriveGateway`, `VehicleDataSource`,
  `PreferencesRepository`, `EmergencyRepository`, `GatewayProvider`.
- `domain/usecase` — `ObserveCockpitUseCase`, `SessionCoordinator`.
- `data/mock` — `MockFixtures` (ported from `src/data/mock/mockRepository.ts`), `MockPolicyEvaluator`
  (ported `evaluateRisk`/`evaluateRestRecommendation`; `NO_IMMEDIATE_INDICATION` → `NORMAL` per
  `09-checklists-and-decisions.md`), `MockSafeDriveGateway`.
- `data/local` — `DataStorePreferencesRepository` (settings persistence, no secrets).
- `vehicle` — `MockVehicleDataSource` for deterministic demos; `AndroidAutomotiveVehicleActionExecutor`
  writes and verifies HVAC/door properties through Car Service, while `AndroidCrashEvidenceAdapter`
  consumes VHAL impact/airbag/speed plus device IMU evidence. Real execution requires OEM-granted
  privileged car permissions on the connected AAOS/CarSky target.
- `voice` — `VoiceController` interface + `VoiceUiState` contract only (implementation is Phase 5).
- `feature/cockpit` — the one real vertical-slice screen this phase ships.
- `feature/assistant|diagnostics|settings` — placeholder screens replaced in Phase 3.
- `navigation` — `AppRoute`, `SafeDriveNavHost`, bottom nav (Simulator/Emergency are intentionally
  not bottom-nav routes).
- `SafeDriveContainer` / `SafeDriveApplication` — the single composition root. Screens/ViewModels only
  ever see interfaces; `GatewayProvider.current()` is the one seam that will switch Demo → Remote in
  Phase 7 without any `if (demoMode)` in a composable.

## Phase 2 — Cockpit + design system

Full screen spec implemented (`core/designsystem/SafeDriveColors.kt`, `Dimensions.kt`, `StatusBadge.kt`;
`feature/cockpit/CockpitContent.kt` + `components/*`):

- Header with connection chip, status hero (risk/rest driven, semantic normal/monitor/high/critical
  colors), 2×2 vehicle metrics, driver-support signal summary (source count computed, never
  hard-coded), DTC summary (navigates to Diagnostics), voice status card (visual-only until Phase 5).
- Adaptive layout via `BoxWithConstraints` (`maxWidth > maxHeight` ⇒ landscape two-column), no fixed
  pixel viewport height, matching `04-screen-specs.md` ("Quy tắc adaptive Cockpit").
- Loading (progress indicator) and stale/offline (banner + connection chip) states from
  `CockpitUiState`/`SystemConnectionStatus` — no infinite spinner, no silent data loss.
- Driver-support details as an `AlertDialog` (`DriverSupportDetailsDialog`), local UI state
  (`rememberSaveable`) — not promoted to ViewModel since it is not safety state.
- Compose UI tests: `app/src/androidTest/.../feature/cockpit/CockpitContentTest.kt` — portrait
  390×844 and landscape 844×390 both render all core sections; CRITICAL risk shows the emergency
  badge copy; stale connection shows the stale banner; DTC count renders; font scale 1.3 still
  renders the primary status. These compile clean (`:app:compileDebugAndroidTestKotlin`) but were
  **not executed** in this environment — no device/emulator is attached (see Known gaps).
- Toolchain gotcha found and fixed: an explicit `import androidx.compose.foundation.layout.weight`
  resolves to an internal `RowColumnParentData` property in this Compose Foundation version and fails
  to compile; `Modifier.weight(...)` is a `ColumnScope`/`RowScope` member and needs no import at all.

## Phase 3 — Assistant + Diagnostics + Settings

> **Superseded (W1/W4/W5)**: the Assistant pipeline described below (voice bypassing this use case
> entirely, TTS never being called for typed replies, the 12s query-only timeout) was replaced by
> `AssistantTurnCoordinator` + `ConversationRepository` as the single pipeline for text/quick-prompt/
> voice, with a combined 10s session+query timeout. See "Mobile Stabilization Pass" below and
> `docs/android-stabilization-progress.md` W1/W4/W5 for the current architecture. This section is kept
> for the still-accurate parts (Diagnostics, Settings' original layout, action-confirmation flow).

- `domain/usecase/AssistantQueryUseCase.kt` — single entry point for both typed input (this phase)
  and Phase 5 voice transcripts; enforces the 12s timeout and rejects blank text before it ever
  reaches the gateway.
- `domain/usecase/ConfirmActionUseCase.kt` — only called for actions with `requiresConfirmation = true`.
- `domain/usecase/PendingPromptCoordinator.kt` — one-shot prefill handoff from Diagnostics'
  "Hỏi SafeDrive AI" button to the Assistant composer.
- `SafeDriveContainer.cockpitSnapshot` — refactored `CockpitViewModel` to consume this single
  application-scoped `StateFlow<CockpitSnapshot?>` instead of re-subscribing to
  `ObserveCockpitUseCase` itself; Assistant/Diagnostics/Settings now share the same subscription so
  vehicle-state polling only happens once, not once per feature.
- Assistant: chat list, composer (IME-aware), quick prompt chips, loading row, inline error + retry
  (retry never re-adds a duplicate user bubble — verified by
  `AssistantViewModelTest.retry after a failure does not duplicate the user bubble`), TTS toggle,
  action cards, confirmation dialog. Duplicate submit is blocked while a request is in flight
  (`AssistantViewModelTest.duplicate submit is blocked...`, using `MockSafeDriveGateway`'s real
  `delay()` under `runTest`'s virtual clock as the deterministic in-flight window).
  `ActionType.NONE`/unrecognized actions are a documented no-op, never a crash.
- Diagnostics: empty/populated states, DTC severity/recommendation rendered verbatim from the
  gateway, "Hỏi SafeDrive AI" navigates to Assistant with a prefilled query, "Mở Simulator" only
  when Developer Mode is on.
- Settings: TTS/wake-word toggles, Developer Mode gate, Demo/Remote radio, BASE_URL presets +
  validation (`BaseUrlValidator`, already covered in Phase 1), and a **real** health check
  (`gatewayProvider.current().checkHealth()`) — never a fake `setTimeout`-style ping.
- Added `AppRoute.Simulator` + a Phase-4 placeholder screen so Diagnostics/Settings can navigate to
  it now without pulling Simulator logic into this phase.
- New test-double `core/testing/FakePreferencesRepository` (in-memory, no DataStore/Context) and
  `core/testing/MainDispatcherRule` (test-only, lives under `src/test`, see "Deviations" below).
- Compose UI test `feature/assistant/AssistantScreenTest.kt` (composer + send button + quick-prompt
  chip) compiles clean but was **not executed** — no device/emulator attached (see Known gaps).
- Gotcha: `SettingsViewModel.uiState`/`DiagnosticsViewModel.uiState` use
  `SharingStarted.WhileSubscribed`, so a unit test that only reads `.value` without first collecting
  never sees anything beyond the initial snapshot — every ViewModel test keeps a background
  `launch(Dispatchers.Unconfined) { uiState.collect {} }` job alive before asserting, mirroring how
  `collectAsStateWithLifecycle()` subscribes in production.

## Phase 4 — Vehicle Simulator

- `feature/simulator/SimulatorViewModel.kt` — every preset/manual apply goes through
  `VehicleDataSource.applyScenario`/`updateManual`, so Cockpit/Diagnostics/Assistant (all reading
  the shared `SafeDriveContainer.cockpitSnapshot`) update together; verified by
  `SimulatorViewModelTest`'s regression loop over all 8 presets.
  Manual apply also recomputes `availableSourceCount` instead of hard-coding it, matching the same
  rule Phase 1's fixtures already followed.
  A `SCENARIO_APPLIED` event (`SafeDriveEvent`) is fired to the gateway on every apply, satisfying
  the typed-event catalogue in `03-data-api-contract.md` even though Demo Mode does nothing with it
  beyond accepting it.
- JSON preview is built with `kotlinx.serialization.json`'s `buildJsonObject`/`JsonArray` (typed,
  no raw string concatenation) rather than adding `@Serializable` to `core.model` classes — keeps
  domain models free of wire-format annotations, consistent with Phase 7 owning DTO↔domain mapping
  in `data/remote` alone. Content is vehicle-state-only; a unit test asserts it never contains an
  `api_key`/`gemini`-looking field.
- Simulator route has defense-in-depth: entry points are hidden unless Developer Mode is on
  (Diagnostics empty state, Settings developer section), **and** `SimulatorScreen` itself pops the
  back stack if Developer Mode is switched off while the screen is open.
- Settings gained a "Mô phỏng trạng thái xe" card with an "Mở Simulator" button (the prototype has
  this entry point in both Settings and Diagnostics; Android now matches that).

## Phase 5 — Voice + TTS

> **Superseded (W2/W3/W4)**: `AndroidSpeechRecognizerController` no longer calls
> `AssistantQueryUseCase` or owns TTS at all — it only emits `VoiceInputEvent`s, routed by the new
> `VoiceAssistantCoordinator`. `AndroidTextToSpeechController` is now a `TtsController` with real
> `TtsState`, language-code checking, and single-latest-call queueing. `LISTENING` is only rendered
> after the recognizer signals ready, and `finishListening()` ("Kết thúc câu nói") is new. See "Mobile
> Stabilization Pass" below and `docs/android-stabilization-progress.md` W2/W3/W4. This section is
> kept for the still-accurate parts (permission handling, `VoiceTrigger`, generation-guard pattern).

- `voice/AndroidSpeechRecognizerController.kt` implements `VoiceController` using Android's
  `SpeechRecognizer` (`vi-VN`, one session at a time) for bounded command transcription and
  composes `voice/AndroidTextToSpeechController.kt` for replies. Ambient "Hey SafeDrive" detection
  is a separate, always-on listener (`voice/SpeechRecognizerWakeWordDetector.kt` — Android's own
  `SpeechRecognizer` again, in `en-US`, restarted continuously; no mature no-account Android SDK
  exists as a dedicated low-power keyword-spotter replacement today) running inside
  `service/WakeWordListeningService.kt` — a foreground service independent of `MainActivity`'s
  lifecycle, so listening survives backgrounding the app. On detection,
  `voice/WakeWordSessionCoordinator.kt` stops the ambient listener and calls
  `VoiceController.startListening()` to capture the actual command.
- Every recognizer/TTS callback and the async assistant reply are guarded by a monotonically
  incrementing `generation` counter, so a stale callback from a cancelled/superseded session can
  never resurrect state — the same pattern `AssistantViewModel`'s request generation already used.
  `cancel()` bumps the generation, destroys the recognizer and stops TTS in one call.
- Voice transcripts are submitted through the exact same `AssistantQueryUseCase` as the Assistant
  composer (`submitTranscript` → `assistantQueryUseCase(text, "voice", stateVersion)`), so Demo/
  Remote behave identically regardless of input modality. An empty/blank final transcript is never
  submitted.
- `MainActivity.onStop()` calls `voiceController.cancel()` — no microphone or TTS is held while the
  app is backgrounded (single-Activity app, so Activity `onStop` is an accurate foreground/background
  signal for this MVP without adding a `ProcessLifecycleOwner` dependency).
  `wakeWordEnabled` toggling in Settings also flips the controller between `DISABLED`/`IDLE`.
- Requesting the `RECORD_AUDIO` runtime permission needs an `ActivityResultLauncher`, which the
  controller deliberately does not own (no Activity reference). `feature/voice/VoiceTrigger.kt`'s
  `rememberVoiceTrigger()` composable owns that launcher and always calls `startListening()`
  afterward — a manual mic tap is itself the wake gesture, so it goes straight to command capture.
  If still denied, the controller itself publishes the `ERROR` state, so there is one source of
  truth for "why voice didn't start," not two.
- `feature/voice/VoiceOverlay.kt` renders above the bottom-nav `Scaffold` in `SafeDriveApp.kt`
  regardless of which tab is active, and is invisible for `IDLE`/`DISABLED` — it never claims
  "listening" unless the controller's real state says so.
- Cockpit's voice card and Assistant's new mic button both call the same `rememberVoiceTrigger`.
  Settings gained a microphone permission status row + "Cấp quyền" button (real
  `ContextCompat.checkSelfPermission`, not a fabricated status).
- `core/testing/FakeVoiceController` (main sourceSet, no Android-framework/test-only dependency) +
  `feature/voice/VoiceOverlayTest.kt` (Compose UI test) cover LISTENING/ERROR/SPEAKING rendering and
  that the stop/close buttons call the right controller methods — per Prompt 5's explicit ask to
  test via a fake controller.
- **Not unit-testable in this environment**: `AndroidSpeechRecognizerController` itself wraps live
  `android.speech.SpeechRecognizer`/`TextToSpeech`, which don't run under plain JVM unit tests
  without Robolectric (not part of this project's toolchain — see Phase 1's rationale for not
  adding it speculatively). Its generation-guard logic is exercised indirectly by
  `AssistantViewModelTest`'s equivalent request-generation tests; the recognizer/TTS wiring itself
  needs manual verification on a real device/emulator (see Known gaps).

## Phase 6 — Emergency (safety-critical)

The single Emergency State Machine per docs/android-mvp-plan/05-voice-emergency.md, replacing the
prototype's two competing SOS implementations entirely (`SosCountdownCard`/`SosScreen` were never
ported — see Phase 1's audit):

- `feature/emergency/EmergencyReducer.kt` — pure `advance(snapshot, nowMs)` transition function,
  zero I/O, exhaustively unit-tested against the exact timeline in `05-voice-emergency.md`
  (`EmergencyReducerTest`: 0→5s verifying, 5→20s awaiting, 20→30s final countdown, exactly one
  `SOS_SIMULATED_SENT` at 30s, and a "catch up through multiple expired deadlines in one loop" case
  for process-recreation-after-a-while).
- `data/local/DataStoreEmergencyRepository.kt` — Demo Mode's Emergency authority. Persists the
  snapshot as one JSON blob (`data/local/EmergencyPersistence.kt`'s `PersistedEmergencySnapshot`,
  kept out of `core.model` for the same reason as Phase 4's JSON preview decision) behind a
  `Mutex`, so `startCandidate`/`tick`/`respond`/`clear` can never race. `emergencyId` **is** this
  MVP's idempotency key — every mutation is guarded by the currently persisted state, so calling
  `respond()` or `tick()` twice is always a safe no-op. A snapshot that fails to parse resets safely
  to no active emergency — it is never guessed to already be sent.
- `SafeDriveContainer` runs two application-scoped coroutines (not tied to any screen's lifecycle,
  so the state machine keeps advancing regardless of which tab is open): one watches
  `vehicleDataSource.vehicleState` for `crashDetected` and only calls `startCandidate` when there
  are **at least two** evidence items (primary `crash_detected` plus a supporting signal —
  `passenger_no_response` or `seat_occupied`); a single signal is deliberately never enough
  (`05-voice-emergency.md`, "Evidence rule"). The other calls `emergencyRepository.tick()` every
  200ms to advance the reducer.
- `feature/emergency/EmergencyViewModel.kt` never invents its own countdown: `remainingSeconds` is
  recomputed from the persisted absolute `deadlineMs` on a 250ms **display-only** ticker (mirroring
  the prototype's `setInterval(250)`); the actual state transitions only ever come from the
  repository's `tick()` loop above.
- `feature/emergency/EmergencyScreen.kt` is mounted in `SafeDriveApp.kt` (not a NavHost route) so it
  overlays whichever tab is active; `BackHandler(enabled = true) {}` consumes Back entirely and the
  bottom nav bar is skipped while it's active. `realEmergencyDispatchEnabled` is always `false` and
  the top banner says so explicitly on screen.
- **Voice routing**: `AndroidSpeechRecognizerController` checks `emergencyRepository.activeSnapshot`
  before deciding where a transcript goes — while an emergency is active, the transcript is matched
  against `domain/usecase/EmergencyVoicePhrases.kt`'s exact allowlist (`"Tôi ổn"`, `"Tôi vẫn ổn"`,
  `"Hủy SOS"`, `"Không cần hỗ trợ"`) and never reaches the assistant chat. The matcher normalizes
  case/punctuation but requires an **exact** match — `EmergencyVoicePhrasesTest` specifically covers
  the doc's warning example ("Tôi không ổn" must never match "tôi ổn" via substring/`contains`).
- Rotation/process recreation: `EmergencyViewModel`/`EmergencyScreen` read the same persisted
  snapshot regardless of when they're (re)constructed, and `DataStoreEmergencyRepositoryTest`
  explicitly asserts a **second** repository instance backed by the same store restores the
  in-flight deadline unchanged.
- Compose UI tests (`EmergencyScreenTest`) cover AWAITING_USER_RESPONSE's confirm button,
  FINAL_COUNTDOWN's cancel button, SOS_SIMULATED_SENT's acknowledge button, that Back does not
  dismiss the screen, and that the "no real dispatch" banner is always visible.
- **Toolchain gotcha found and worked around**: `PreferenceDataStoreFactory`'s real file-backed
  DataStore writes via a temp-file-then-rename, and `File.renameTo()` does not overwrite an existing
  destination on Windows — the *second* write to the same DataStore instance always threw
  `IOException` here (misleadingly reported by the library as "multiple instances of DataStore for
  this file"). `data/local/FakePreferencesDataStore.kt` (an in-memory `DataStore<Preferences>`) is
  used in `DataStoreEmergencyRepositoryTest` instead — it exercises the exact same
  `dataStore.edit{}`/`dataStore.data` contract without touching the filesystem, so the repository's
  own logic is still genuinely tested; only real cross-process file persistence needs a device to
  verify (see Known gaps).

## Phase 7 — Remote REST

> **Superseded (W5/W7)**: `SessionCoordinator` no longer hard-codes `mode=DEMO`, `gatewayProvider` no
> longer falls back to Mock when Remote has a blank BASE_URL (now `ConfigurationErrorGateway`),
> network timeouts are now 3s/8s/5s (was 10s/15s/10s), and the assistant request/response DTOs gained
> `source`/`locale`/`clientAttemptOf`/`serverProcessingMs`/`model`/`finishReason` per the candidate
> `openapi/safedrive-v1.yaml`. See "Mobile Stabilization Pass" below and
> `docs/android-stabilization-progress.md` W5/W7. This section is kept for the still-accurate parts
> (DTO↔domain mapping pattern, contract test structure, `RedactingLoggingInterceptor`).

- `data/remote/dto/*.kt` — `@Serializable` wire DTOs, one file group per concern
  (health/session, state, assistant, events/actions, emergency), field names matching
  `03-data-api-contract.md`'s JSON exactly. `driverSupportSignals` is modeled as an explicit sibling
  field on both `StateUpdateRequestDto` and `StateEnvelopeDto` — the contract doc's endpoint
  description doesn't spell out its wire shape explicitly, so this is the most contract-faithful,
  additive interpretation (mirrors the already-locked domain split from `03-data-api-contract.md`'s
  model section) rather than a silent deviation.
- `data/remote/ApiMappers.kt` is the **one** place DTO↔domain mapping happens, per
  `02-android-architecture.md`. Every enum field goes through a `safeEnumOf` helper: an
  unrecognized string from the backend (e.g. a future `ActionType` the app doesn't know about yet)
  falls back to a safe default (`ActionType.NONE`, `Severity.LOW`, `PassengerResponse.UNKNOWN`, …)
  instead of throwing — "unknown action is a no-op," never a crash, now enforced at the wire
  boundary too, not just in `AssistantViewModel`.
  `realEmergencyDispatchEnabled` is hard-coded to `false` when mapping both `StartSessionResponseDto`
  and `EmergencySnapshotDto` — the MVP invariant holds even if a backend response ever claimed
  otherwise.
- `data/remote/RemoteSafeDriveGateway.kt` implements the exact same `SafeDriveGateway` interface as
  `MockSafeDriveGateway`; `SafeDriveGatewayContractTest` (abstract) plus its two subclasses
  (`MockSafeDriveGatewayContractTest`, `RemoteSafeDriveGatewayContractTest` — the latter runs a real
  HTTP round-trip against `okhttp3.mockwebserver.MockWebServer` with a tiny fake backend dispatcher,
  `FakeSafeDriveBackendDispatcher`) prove both implementations satisfy the same contract.
- Error mapping matches the doc's table exactly (`RemoteSafeDriveGatewayErrorMappingTest`):
  401/403→`Unauthorized`, 404→`Unsupported`, 409→`Conflict`, 422→`Validation`, 5xx→`Server`,
  a connection that never responds→`Timeout`, a dropped connection→`Offline`, and an unparseable
  body→`Protocol` — never an uncaught exception.
- `core/network/NetworkModule.kt` builds one `OkHttpClient`/`Retrofit` per BASE_URL (10s connect/
  15s read/10s write timeouts by default, overridable — used by the error-mapping test to keep the
  "never responds" case fast instead of waiting the full 15s). `core/network/RedactingLoggingInterceptor.kt`
  only ever logs method + path + status + latency — **never** a request/response body, so an
  assistant transcript, DTC detail or emergency evidence can never end up in logcat even in debug
  builds; it is also only attached when `allowCleartext` is true (debug builds), never in release.
- `SafeDriveContainer.gatewayProvider`'s `REMOTE` branch now builds (and caches, keyed by BASE_URL)
  a real `RemoteSafeDriveGateway` via `NetworkModule` + `BuildConfig.ALLOW_CLEARTEXT_DEBUG` — the
  exact same flag `PreferencesRepository.setBaseUrl` already used for validation, so a release build
  can never end up with a cleartext client even from a stale debug-saved URL. Falls back to the mock
  gateway if `baseUrl` is still blank (Remote mode selected but no URL saved yet) instead of crashing.
  Switching backend mode or BASE_URL now calls `sessionCoordinator.invalidate()` so the next request
  starts a fresh session against whichever gateway is now current.
- WebSocket cockpit streaming is explicitly out of scope for this phase (per `03-data-api-contract.md`,
  "optional sau REST gate") — Remote Mode's Cockpit/Assistant/Diagnostics keep working via the
  already-built polling (`ObserveCockpitUseCase` pushes state through `updateVehicleState` on every
  vehicle-state change; `GET /state`/`GET /emergency/{id}` exist on the DTO/API surface for bootstrap/
  resume but the app's own reconnect-and-refetch orchestration was not built since nothing in this
  MVP disconnects a REST call the way a socket would — this is a documented gap, not silently
  dropped scope, and is listed below).
- Dependency versions confirmed to actually exist and resolve (an earlier Maven Central search index
  used while planning turned out to be stale for Square's artifacts): `retrofit`/
  `converter-kotlinx-serialization` 3.0.0, `okhttp`/`logging-interceptor`/`mockwebserver` 4.12.0.
  Lint flags newer releases (okhttp 5.4.0, coroutines/serialization 1.11.0, truth 1.4.5) as
  available; left un-bumped for the same reason AGP/Gradle stay pinned — no functional need, and
  churning versions this late adds risk without a corresponding requirement.

## Deviations from the doc's illustrative file tree (documented, not scope creep)

- `TestDispatcherProvider` is **not** shipped in `core/testing` under `src/main`: it depends on
  `kotlinx-coroutines-test`, a test-only artifact. Shipping it in `main` would pull a test framework
  into the release APK. It will be added under `src/test/java/.../core/testing/` when the first
  ViewModel test needs it (Phase 2+). `FakeClock` and the vehicle-state fixture builders have no such
  dependency and are shipped in `main` as the doc tree shows.
- `ObserveCockpitUseCase` pushes vehicle telemetry to the gateway via `updateVehicleState` (mirroring
  the real `POST /state/update` contract) rather than reading a separate "gateway state stream" —
  there is no such stream in `03-data-api-contract.md` beyond `GET /state`/`POST /state/update`, so this
  is the most contract-faithful way to keep risk/rest computation entirely inside the gateway.
- `core/testing/FakePreferencesRepository` **is** shipped in `src/main` (like `FakeClock`): it only
  depends on `kotlinx.coroutines` + `BaseUrlValidator`, both already main dependencies, so it carries
  no test-framework dependency into the APK and is usable from both `src/test` and `src/androidTest`
  (which do not share source sets with each other). `MainDispatcherRule` stays `src/test`-only since
  it genuinely needs `kotlinx-coroutines-test`.

## Phase 8 — QA, hardening, handoff

- Built and audited a release artifact for the first time: `:app:assembleRelease` succeeds
  (`app-release-unsigned.apk`, unsigned — no signing key was provided, an accepted MVP deliverable
  per `07-testing-security-acceptance.md`).
- Full-APK string scan (every extracted file including `classes*.dex`, not just source) for
  `gemini`/API-key/`attention_score`/`drowsiness_score` patterns: **no matches** in either the debug
  or release artifact. Source-level scan confirms the only occurrences anywhere in `app/src` are test
  assertions that assert their *absence*.
- `aapt dump xmltree` on the **release** merged manifest confirms `allowBackup=false`, only the three
  intended permissions (`INTERNET`, `ACCESS_NETWORK_STATE`, `RECORD_AUDIO`) plus AndroidX-injected
  boilerplate, and only `MainActivity` exported.
- `aapt dump xmltree` on the **release** compiled `network_security_config.xml` confirms
  `cleartextTrafficPermitted=false` with only the `system` trust anchor — the debug-only
  cleartext/user-cert override is confirmed absent from the release build, not just "should be" from
  reading the source.
- `:app:lintRelease` — BUILD SUCCESSFUL, same 12 version-only warnings as debug, no new
  release-specific findings.
- R8/ProGuard minification was deliberately left off (`isMinifyEnabled = false`) for this MVP — see
  `KNOWN_LIMITATIONS.md` for the rationale.
- Full evidence, the unit test breakdown, and APK checksums are in `TEST_REPORT.md`; consolidated
  gaps are in `KNOWN_LIMITATIONS.md`; the Mock/Remote endpoint coverage table is in
  `MOCK_VS_REMOTE_COVERAGE.md`; a step-by-step walkthrough is in `DEMO_SCRIPT.md`.

## Mobile Stabilization Pass (W0–W8 + two remediation passes)

Full detail: `docs/android-stabilization-progress.md` (see its "Post-stabilization remediation pass"
section — a follow-up independent re-audit that found and fixed 9 further defects the W0–W8 pass had
missed or introduced — and its "Second independent re-audit" section — a further re-audit that found and
fixed 7 more logic defects the first remediation pass's own tests had not caught). Architecture after all
three passes:

```text
Compose surfaces (Cockpit / Assistant / Emergency / Simulator)
  ├─ text / quick prompt ──────────────┐
  └─ voice trigger                     │
        ↓                             ↓
  VoiceController              AssistantTurnCoordinator  ← single pipeline for ALL sources
  (mic/STT lifecycle only)       ├─ single-flight, generation guard, retry lineage
        ↓ VoiceInputEvent        ├─ writes ConversationRepository (application-scoped)
  VoiceAssistantCoordinator      ├─ AssistantQueryUseCase → GatewayProvider
  ├─ emergency priority routing  ├─ TtsController.speak() on success, when enabled
  └─ else → AssistantTurnCoordinator └─ AssistantTurnMetricsRecorder (latency, Dev Mode only)
                                          ↓
                                  SessionCoordinator (mode/baseUrl/expiry-aware, no fake fallback)
                                          ↓
                                  GatewayProvider → MockSafeDriveGateway
                                                  → RemoteSafeDriveGateway
                                                  → ConfigurationErrorGateway (Remote, no BASE_URL)
```

Headline fixes (each with a passing test cited in `docs/android-stabilization-progress.md`):

- **Voice replies now appear in chat** — previously `AndroidSpeechRecognizerController` called the
  assistant use case directly and the reply only ever got spoken, never stored (GAP-03).
- **Text replies are now actually spoken** when TTS is on — previously only voice replies were
  (GAP-02).
- **Demo Mode has no artificial delay by default** — previously every Mock reply slept 240–440ms
  unconditionally (GAP-06); Developer Mode can now opt into a simulated profile (0/100/500/2000ms/timeout).
- **Remote Mode never masquerades as Mock** — blank BASE_URL now fails fast with a typed
  `GatewayError.Configuration` (GAP-09); session `mode` reflects the live preference instead of being
  hard-coded to `DEMO` (GAP-08); a Remote session/query failure never fabricates a local session id.
- **No double-wait on a slow Remote backend** — session resolution and the assistant query now share
  one 10s deadline instead of a 12s query timeout stacked on an unbounded session call (GAP-07).
- **Simulator is discoverable** — a Developer-Mode-only chip now sits in Cockpit's header in addition
  to the pre-existing Settings entry point (GAP-10 — this is what the user directly reported hitting
  during this session).
- **(Remediation pass) One real requestId per attempt** — `AssistantTurnCoordinator` and
  `AssistantQueryUseCase` previously minted two different, unlinked ids; retry's `clientAttemptOf` now
  always references the exact id actually sent on the wire, never a fabricated one for an attempt that
  was never sent.
- **(Remediation pass) Single-flight is now provably atomic** under real multi-thread concurrency
  (`synchronized` critical sections + a 24-thread regression test), not just single-threaded
  coroutine-test ordering.
- **(Remediation pass) `contractVersion` is checked** — a session whose contract version doesn't match
  this client's expectation fails fast instead of proceeding, and the gateway a session was started
  against is now guaranteed to be the same instance a follow-up call uses.
- **(Remediation pass) `RemoteSafeDriveGateway` parses the typed `ErrorEnvelope` body** instead of
  guessing from HTTP status alone.
- **(Remediation pass) `finishListening()` and TTS-unavailable are now reachable/visible in the UI**,
  not just correctly implemented underneath.
- **(Second re-audit) `clientAttemptOf` never claims lineage to an unsent request** in the
  cancel-before-send and incompatible-contractVersion cases either, not just the health-blocked case.
- **(Second re-audit) `SessionCoordinator` cache hits return the exact gateway instance** a session was
  started against (stored in the cache entry), never a freshly re-resolved one.
- **(Second re-audit) `SpeechRecognizer` lifecycle calls are serialized through an injectable
  `MainThreadExecutor`**, provably (via a fake that queues instead of running immediately) rather than
  merely by convention.
- **(Second re-audit) TTS `onStart` collection is registered before `speak()` is called**, closing a
  subscription race that could silently lose the callback.
- **(Second re-audit) The voice overlay shows the completed voice turn's actual reply/error**, not just
  a spinner that vanished the instant the turn finished; `VoiceInputEvent` delivery is now
  `Channel`-backed so an event emitted before a collector subscribes is queued, not lost.
- **API contract candidate, validated offline**: `openapi/safedrive-v1.yaml` (offline-validated with
  `openapi-spec-validator`, including example `$ref` resolution) plus a full backend handoff package —
  called a **candidate**, not "frozen," until Gate E's device-QA and human-review criteria also pass.

Test suite grew from 99 → 153 unit tests across W0–W8, → 170 in a first remediation pass (9 defects
fixed), → 194 in a second independent re-audit that found and fixed 7 further logic defects the first
pass's own tests did not catch (see `docs/android-stabilization-progress.md`'s "Second independent
re-audit" section); Compose UI tests grew 18 → 19 (W0–W8; the "20" figure reported at the time was a
miscount) → 22 (first remediation pass) → 26 (second re-audit; compiling clean, execution still needs a
device — see `KNOWN_LIMITATIONS.md`).

## Known gaps / explicit non-goals

See `KNOWN_LIMITATIONS.md` for the consolidated, up-to-date list (environment limitations — no
device/emulator was available in this build session, the stabilization pass, or either remediation pass
that followed — plus intentional scope boundaries like no WebSocket streaming, no raw audio, and no R8
minification).

## Latest test evidence

See `TEST_REPORT.md` for the full, current build/test/lint command results, the complete unit and
Compose UI test breakdown, and the release-artifact security audit. Summary: **194 unit tests / 0
failures**, 26 Compose UI tests compiling clean, lint warnings are all version-availability notices,
debug and release APKs both build successfully, and `openapi/safedrive-v1.yaml` validates offline.
