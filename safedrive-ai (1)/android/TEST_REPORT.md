# SafeDrive AI Android — Test Report

Regenerated at the end of a **tenth independent re-audit** on top of the post-stabilization remediation
pass (`docs/android-mvp-plan/12-mobile-completion-before-ai-backend.md` W0–W8, a first remediation pass
fixing 9 defects, a second pass fixing 7 further logic defects, a third pass fixing 6 more logic/race
defects, a fourth pass fixing 5 architectural concurrency blockers, a fifth pass fixing 2 more architectural
blockers plus a latency-honesty defect, a sixth pass closing a cancellation-path gap and a voice
Processing-ownership bug, a seventh pass closing an ABA race in that same ownership mechanism, an eighth
pass closing a P1 pre-claim race by threading recognizer generation through ownership, a ninth pass closing
two further P1 concurrency blockers in that eighth-pass fix (`reassignProcessingOwner`'s missing
compare-and-set, and `callbackLock` serializing recognizer-callback check-then-act against `cancel()`/
`shutdown()`/newer-start generation bumps), and now this **tenth pass** closing the one remaining gap in
that same `callbackLock` protocol, plus eliminating a nondeterministic committed test:

- **Blocker 1**: `beginListening()`'s own generation *mint* (`generation.incrementAndGet()`) still ran
  **outside** `callbackLock` — only its later-dispatched check-then-act was protected. A `RecognitionListener`
  callback already holding the lock (having passed its own check) had no protection against a concurrent
  newer `startListening()` minting a new generation in the gap, since that mint never needed the lock at all.
  The mint now also runs inside `callbackLock`, closing the last unguarded generation-mutating path.
- **Blocker 2**: `AssistantTurnCoordinatorTest`'s "concurrent submits from many real threads at once start
  exactly one turn" (24 threads via `CyclicBarrier`) used the real, ungated mock gateway, which could answer
  fast enough that the first accepted turn completed and freed single-flight again before all 24 threads had
  even attempted their own `submit()` — a later thread's submit was then *legitimately* re-accepted as a
  second, genuinely new turn, producing an intermittent `expected 1, got 2`. This was a gap in the test's own
  setup, not a proven production defect. Rewritten to gate the accepted turn's gateway call, assert every
  worker thread's actual termination, and await completion deterministically (never `Thread.sleep` polling).
  `AssistantTurnCoordinator.submit()` itself was not changed.

See `docs/android-stabilization-progress.md`'s "Tenth independent re-audit" section for full root-cause/
fix/evidence detail, including revert-and-rerun proof that the new test genuinely fails without the blocker-1
fix. **Supersedes every number below from any prior pass** — nothing here is carried forward without being
re-verified this pass.

Toolchain unchanged: AGP 9.3.0, Gradle 9.5.0, JDK 17, Kotlin 2.4.10, compileSdk/targetSdk 37, minSdk 26.

## Commands run and results (this pass, clean)

| Command | Result |
|---|---|
| `$env:JAVA_HOME='C:\Program Files\Eclipse Adoptium\jdk-17.0.19.10-hotspot'; gradlew.bat clean :app:assembleDebug :app:testDebugUnitTest --rerun-tasks :app:lintDebug :app:compileDebugAndroidTestKotlin :app:assembleRelease :app:lintRelease` | **BUILD SUCCESSFUL in 33s**, 117 actionable tasks |
| Unit test XML aggregation (`app/build/test-results/testDebugUnitTest/*.xml`, summed across every `TEST-*.xml`'s `tests`/`failures`/`errors`/`skipped` attributes) | **tests=275 failures=0 errors=0 skipped=0**, 25 classes |
| Full JVM suite run a **second** time (`gradlew.bat :app:testDebugUnitTest --rerun-tasks`) | **BUILD SUCCESSFUL in 19s**, identical **275/275, 0/0/0** |
| `python -m openapi_spec_validator openapi/safedrive-v1.yaml` (repo root) | **OK** — spec itself unchanged this pass; internal Android fix only |
| `adb devices` | Empty — no device/emulator attached in this sandbox |
| `AndroidSpeechRecognizerControllerTest` + `VoiceAssistantCoordinatorTest` run **20 consecutive times** | **20/20 runs clean** — 27 + 43 tests, 0 failures/errors every run |
| `AssistantTurnCoordinatorTest` (rewritten concurrent-submit test) run **50 consecutive times** | **50/50 runs clean** — 53 tests, 0 failures/errors every run |

No flakes of any kind observed this pass — every run of every affected test class, across the full gate (run
twice) and every repeated-run batch, was 100% clean.

Instrumented tests (`:app:connectedDebugAndroidTest`) were **not executed** — no Android device/emulator
was attached at any point in this sandbox. All 29 Compose UI tests compile cleanly against real
Compose/Espresso/AndroidX Test APIs (unchanged this pass — none of this pass's fixes touch a Compose UI
surface) and must be run on a real device/emulator before this is a complete sign-off — see
`KNOWN_LIMITATIONS.md`.

## Unit test breakdown (275 tests, 0 failures, 0 errors, 25 classes)

| Test class | Tests | Covers |
|---|---:|---|
| `core.SafetyInvariantsTest` | 2 | `realEmergencyDispatchEnabled` always false (BuildConfig + domain default) |
| `core.network.BaseUrlValidatorTest` | 6 | BASE_URL scheme/host/cleartext validation |
| `data.local.DataStoreEmergencyRepositoryTest` | 9 | Emergency persistence, idempotency, corrupt-snapshot recovery, process-recreation restore |
| `data.mock.MockFixturesTest` | 7 | All 8 scenario presets, computed source counts |
| `data.mock.MockPolicyEvaluatorTest` | 10 | Risk/rest evaluation per scenario |
| `data.mock.MockSafeDriveGatewayTest` | 11 | Health/session/state/assistant/emergency mock behavior + Developer Mode simulated latency profiles |
| `data.remote.ConfigurationErrorGatewayTest` | 9 | Every method fails fast with `GatewayError.Configuration`, never falls back to Mock behavior |
| `data.remote.MockSafeDriveGatewayContractTest` | 5 | Shared gateway contract, Mock side |
| `data.remote.RemoteSafeDriveGatewayContractTest` | 5 | Shared gateway contract, Remote side (real HTTP via MockWebServer) |
| `data.remote.RemoteSafeDriveGatewayErrorMappingTest` | 14 | 401/404/409/422/5xx/timeout/offline/invalid-JSON status mapping, `ErrorEnvelope` body parsing |
| `data.remote.dto.AssistantDtoSerializationTest` | 6 | Request/response round-trip; decodes the actual `openapi/examples/*.json` files verbatim; backward/forward compat |
| `domain.usecase.AssistantTurnCoordinatorTest` | 53 | **Rewritten this pass (blocker 2), count unchanged**: single-flight, cancel/retry lineage, TTS-on-success-only, latency metrics, capability-gating, atomic terminal transitions, exception-safe terminalization, sixth-pass cancellation-path items. Its "concurrent submits from many real threads at once start exactly one turn" test now gates the accepted turn's gateway call behind a `CompletableDeferred`, asserts every one of the 24 worker threads actually terminated (not merely that the timed join returned), asserts exactly one in-flight turn/user message *while the turn is still genuinely gated*, then awaits the turn's own terminal state via its per-turn `Deferred` — never `Thread.sleep` polling. Run 50 consecutive times, 0 failures. `AssistantTurnCoordinator.submit()` itself was not modified — the rewritten test surfaced no evidence of an actual single-flight defect |
| `domain.usecase.EmergencyVoicePhrasesTest` | 3 | Exact-match voice cancel allowlist, substring-trap safety |
| `domain.usecase.ObserveCockpitUseCaseTest` | 4 | Cockpit snapshot derivation, stateVersion ordering, mode-switch-alone triggers a fresh request |
| `domain.usecase.SessionCoordinatorTest` | 15 | Mode reflects live preference, cache keyed by (mode, baseUrl, contractVersion), no fabricated session id on failure, single connection-error retry, cache-hit gateway-instance consistency, concurrent-first-request session sharing |
| `domain.usecase.VoiceAssistantCoordinatorTest` | 43 | Unchanged this pass — ninth-pass P1-1/P1-3 coverage (compare-and-set `reassignProcessingOwner`, A/B/C pipeline proofs, deterministic stress replacement) re-confirmed green; no new tests needed, since blocker 1/2 live entirely in `AndroidSpeechRecognizerController`/`AssistantTurnCoordinatorTest` |
| `feature.assistant.AssistantViewModelTest` | 10 | Duplicate-submit guard, cancel, retry without duplicate bubble, action confirmation, conversation survives ViewModel recreation |
| `feature.cockpit.CockpitViewModelTest` | 1 | `developerMode` flows into Cockpit state for the Simulator shortcut chip |
| `feature.diagnostics.DiagnosticsViewModelTest` | 4 | Loading/empty/populated states, ask-assistant prefill |
| `feature.emergency.EmergencyReducerTest` | 7 | Exhaustive 5/15/10s transition table |
| `feature.settings.SettingsViewModelTest` | 7 | Real health check (5s-capped), BASE_URL validation, Local Mock/host label + assistant capability |
| `feature.simulator.SimulatorViewModelTest` | 6 | All 8 presets, manual apply, JSON preview safety |
| `vehicle.MockVehicleDataSourceTest` | 4 | Scenario/manual apply, reset |
| `voice.AndroidSpeechRecognizerControllerTest` | 27 | **Grew 26→27 this pass (blocker 1)**: the ninth-pass "newer start" test was rewritten (not merely extended) — it previously called `startListening()` synchronously on the test's own thread, which now deadlocks once the generation mint itself requires `callbackLock`, so the concurrent actor now runs on its own real thread, exactly like `cancel()`'s equivalent test. Proves the *mint itself* (not merely the dispatched block that follows it) cannot happen while a stale callback holds `callbackLock` — a structural, bounded-wait proof, not a timing guess. **+1 new**: the reverse ordering — a newer start that wins first, fully, leaves every generation-1 callback a safe no-op, asserted directly for `onReadyForSpeech`/`onPartialResults` and by code inspection for `onResults`'s event-enqueue path (the pre-existing Bundle-stubbing limitation, unchanged, still prevents a non-blank transcript from reaching that branch here) |
| `core.observability.AssistantTurnMetricsRecorderTest` | 7 | Unchanged this pass — CAS-based patch-then-log ordering, deterministically-forced losing-first-attempt race, multi-call semantics |

## Compose UI tests (compile-clean, 29 total, unchanged this pass — no UI surface touched; execution needs a device/emulator)

| Test class | Tests | Covers |
|---|---:|---|
| `feature.cockpit.CockpitContentTest` | 6 | Portrait 390×844 / landscape 844×390, CRITICAL badge, stale banner, DTC count, font scale 1.3 |
| `feature.assistant.AssistantScreenTest` | 4 | Composer send + quick-prompt chip, the 5th quick-prompt chip present, TTS-unavailable-while-enabled shows a visible banner + CTA |
| `feature.voice.VoiceOverlayTest` | 14 | LISTENING/PROCESSING/ERROR rendering, TTS-driven SPEAKING, cancel/close wiring, `Success`/`Failure` `turnOutcome` display, SPEAKING+reply/stop-reading behavior |
| `feature.emergency.EmergencyScreenTest` | 5 | AWAITING/FINAL_COUNTDOWN/SENT rendering, Back does not dismiss, no-real-dispatch banner |

## Security/release audit performed

- `app-release-unsigned.apk` rebuilt fresh this pass (`isMinifyEnabled = false`, unchanged decision).
- `BuildConfig.REAL_EMERGENCY_DISPATCH_ENABLED` still `false` in `defaultConfig`, asserted by
  `SafetyInvariantsTest`.
- `beginListening()`'s generation mint moving inside `callbackLock` is a plain JVM monitor acquisition
  around an existing `AtomicLong` increment — no new secret/PII surface, no new logging, no new persisted
  state.
- The rewritten concurrent-submit test's `CompletableDeferred` gate and `AtomicReference<StartedTurn?>` are
  test-only constructs — no production code in `AssistantTurnCoordinator.kt` was touched this pass.
- `AssistantTurnCoordinator.logger` (redacted diagnostic sink, sixth pass, untouched this pass) emits only the
  exception's class name plus requestId/generation/source — never `Throwable.message` and never the
  transcript/reply body. Reviewed, no new secret/PII surface.

## Lint (0 errors, both variants)

| Variant | Total findings | Errors | Warnings | Notes |
|---|---:|---:|---:|---|
| debug | 12 | 0 | 12 | Same 12 pre-existing dependency-version findings as every prior pass, confirmed via rule-id diff — nothing new introduced |
| release | 12 | 0 | 12 | Same 12, same rule ids |

## Artifacts (checksums genuinely changed again because source genuinely changed)

| Artifact | SHA-256 |
|---|---|
| `app/build/outputs/apk/debug/app-debug.apk` | `BF91396B0715C3867B65F9A32732EE711432D3EE892125E090E76E27B47218B5` |
| `app/build/outputs/apk/release/app-release-unsigned.apk` (unsigned) | `4B8B4E413554792D887B44C56EAD6B97F3C81E542FA9543DD626A0339CDEEB7C` |

## What changed since the ninth re-audit pass (summary)

See `docs/android-stabilization-progress.md`'s "Tenth independent re-audit" section for full
root-cause/fix/evidence detail. Headline:

- `AndroidSpeechRecognizerController.beginListening()`: `val gen = generation.incrementAndGet()` →
  `val gen = synchronized(callbackLock) { generation.incrementAndGet() }`. `callbackLock`'s KDoc extended to
  document the completed linearization model.
- `AndroidSpeechRecognizerControllerTest.kt`: the "newer start" test rewritten to run its concurrent actor on
  its own real thread (required now that the mint itself can block); a new reverse-ordering test added.
- `AssistantTurnCoordinatorTest.kt`: the concurrent-submit test rewritten with a gated gateway, an
  every-thread-terminated assertion, and a deterministic per-turn completion await, replacing a
  `Thread.sleep`-polling loop that could observe a legitimately-re-accepted second turn as a false failure.
- No change to `AssistantTurnCoordinator.kt`, `VoiceAssistantCoordinator.kt`, `FakeVoiceController.kt`, or
  `VoiceController.kt` this pass — the ninth pass's P1-1 fix is untouched. No wire/DTO/OpenAPI change.

## Root cause this pass closes (completing the ninth pass's `callbackLock` protocol)

The ninth pass correctly serialized every `RecognitionListener` callback's check-then-act, `cancel()`'s
bump-and-publish, `shutdown()`'s bump-and-flag, and `beginListening()`'s *dispatched* check-then-act under one
`callbackLock`. It left exactly one generation-mutating operation outside that protocol:
`beginListening()`'s own mint. Since a callback already holding the lock had no way to detect a concurrent,
unguarded mint happening behind its back, the "every generation bump is serialized against callback
validation" claim was not yet fully true. Moving the mint itself inside `callbackLock` completes the
protocol: every operation that either reads `generation` to decide validity, or writes a new value other
readers depend on, now contends for the exact same monitor — there is no longer any generation-mutating
code path that runs outside it.

Separately, `AssistantTurnCoordinatorTest`'s concurrent-submit test's nondeterminism was traced to the test's
own reliance on the real mock gateway's response speed relative to 24 threads' own scheduling — entirely a
test-construction gap, closed without touching `AssistantTurnCoordinator.submit()`'s actual single-flight
guard, which the rewritten, repeatedly-run (50×) test found no evidence of being defective.

## Known gap carried forward

Real-device verification (`SpeechRecognizer`/`TextToSpeech` behavior, actual layout at target sizes,
actual latency wall-clock numbers, actual install-from-file flow, the real multi-thread timing of every
concurrency fix across all ten passes, the real TTS `onStart` callback timing, and real
`onResults(Bundle)` transcript parsing — genuinely untestable in a plain JVM unit test) remains
**DEVICE_PENDING** — see `KNOWN_LIMITATIONS.md`. This is an environment constraint of this build sandbox,
not a defect in the delivered code.
