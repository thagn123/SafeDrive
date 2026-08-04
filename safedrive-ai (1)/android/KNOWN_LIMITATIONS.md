# Known Limitations — SafeDrive AI Android

Consolidated from `docs/android-stabilization-progress.md`'s per-workstream notes (W0–W8 of
`docs/android-mvp-plan/12-mobile-completion-before-ai-backend.md`, a first remediation pass that fixed 9
defects, a second independent re-audit that fixed 7 further logic defects, a third independent re-audit
that fixed 6 more logic/race defects, a fourth independent re-audit that fixed 5 further architectural
concurrency blockers, a fifth independent re-audit that fixed 2 more architectural blockers plus a
latency-honesty defect, a sixth independent re-audit that closed a cancellation-path gap (a
`CancellationException` not originating from `cancelCurrent()` could abandon a turn `InFlight` forever) and
fixed a real Processing-state-ownership bug in the voice pipeline, a seventh independent re-audit that
closed an ABA race in that same ownership mechanism (a stale, already-superseded voice turn's late
completion could steal a *newer*, currently-owning voice turn's Processing indicator/outcome), an eighth
independent re-audit that closed a narrower, earlier **pre-claim** race in the same area (a recognizer
session publishes its own `PROCESSING`/generation unconditionally, before `route()` ever gets to accept or
reject the resulting event — an older turn that is still, technically, the assistant-turn-level owner at
that instant could otherwise clear a newer session's Processing before that newer turn even existed), and a
ninth independent re-audit that closed **two further P1 concurrency blockers** an independent source audit
found in that eighth-pass fix itself: (P1-1) `reassignProcessingOwner` applied its target generation
unconditionally once some turn owned Processing, so a rejected event's stale transfer attempt could stamp
over an even-newer, third session's legitimate Processing before that session's own transcript was routed —
now a genuine compare-and-set on both the *from* and *to* generation; (P1-2) each `RecognitionListener`
callback's generation check and the state mutation that followed it were not atomic with respect to a
concurrent `cancel()`/`shutdown()`/newer-start generation bump running on a different thread — now
serialized under one `callbackLock`; and a **tenth** independent re-audit that closed a gap the ninth pass's
own `callbackLock` fix left open: `beginListening()`'s generation *mint* itself
(`generation.incrementAndGet()`) still ran outside `callbackLock`, so a concurrent newer `startListening()`
call could still mint a new generation in the middle of a callback that had already passed its own check
while holding the lock — the mint now also requires `callbackLock`, closing the last unguarded
generation-mutating path — and separately rewrote a genuinely nondeterministic committed test
(`AssistantTurnCoordinatorTest`'s concurrent-submit test, which let its first accepted turn's gateway call
race ahead and free single-flight again before all 24 competing threads had even attempted their own submit,
legitimately admitting a second turn and producing an intermittent `expected 1, got 2`) to gate the first
turn's gateway call deterministically — see that doc's "Post-stabilization remediation pass", "Second
independent re-audit", "Third independent re-audit", "Fourth independent re-audit", "Fifth independent
re-audit", "Sixth independent re-audit", "Seventh independent re-audit", "Eighth independent re-audit",
"Ninth independent re-audit" and "Tenth independent re-audit" sections). None of these block Demo Mode.

## Environment limitations (this build sandbox)

- **No physical device or emulator was available for the stabilization pass or any remediation pass
  that followed** (`adb devices` checked repeatedly, always empty/absent). A phone was connected briefly
  earlier in the original session (used only to install/verify the pre-stabilization APK and diagnose the
  user's live mic/Simulator questions) but was disconnected before any of W1–W8's code existed. This
  means, for every workstream across all passes:
  - `:app:connectedDebugAndroidTest` (29 Compose UI tests, unchanged since the fourth re-audit — every
    pass since then has been JVM-testable concurrency/architecture/correctness work with no Compose UI
    surface change) was compiled but never executed on real hardware.
  - The real multi-thread concurrency fixes (`AssistantTurnCoordinator`'s `synchronized` lock, its
    per-turn `CompletableDeferred` completion primitive, its exception-safe terminalization (fifth pass —
    every non-cancellation exception anywhere in the query pipeline folds into an ordinary
    `GatewayResult.Failure` through the same single terminalization path) and, new in the sixth pass, its
    `Job.invokeOnCompletion`-based cancellation safety net (guarantees every accepted turn terminalizes
    exactly once even when a `CancellationException` does *not* originate from `cancelCurrent()` — the
    parent scope being torn down, the gateway/session self-cancelling, or the Job being cancelled before
    its body ever runs — verified with real background threads, a `CoroutineExceptionHandler` proving
    nothing surfaces uncaught, and a real two-latch handshake proving the gateway coroutine was genuinely
    inside the network call before cancelling it, not merely inferred from `InFlightAssistantTurn` having
    been published); `SessionCoordinator`'s cache-hit/gateway-instance consistency;
    `AndroidSpeechRecognizerController`'s `MainThreadExecutor` serialization, its fully-confined
    `isRecognitionAvailable()` check, generation-tagged `ActiveSession.timings` ownership, and
    `micRequestedAtMs` captured on the caller's own thread before dispatch (fifth pass);
    `VoiceAssistantCoordinator.start()`'s `AtomicBoolean`-based collector guard, its voice-outcome
    correctness proven even when the completion-reading consumer coroutine is deliberately left
    unscheduled across an intervening, overwriting turn, and, new in the seventh pass, its
    `VoiceTurnOwner`-based Processing/outcome ownership (replacing the sixth pass's `AtomicBoolean
    voiceTurnInFlight`, which could not tell *which* voice turn was in flight — only *whether* one was —
    letting a stale, already-superseded turn's late completion consumer steal a newer, currently-owning
    turn's Processing indicator/outcome; closed via a per-turn identity token compared by reference under
    one lock shared by every ownership-affecting operation, verified by revert-and-rerun to genuinely fail
    without the fix, including for the synchronously-resolved health-blocked path via a new,
    independently-pumped `completionScope` test seam), and, new in the eighth pass, generation-correlated
    Processing resolution (`VoiceInputEvent.generation` threaded through `VoiceTurnOwner.voiceGeneration`
    into `clearProcessingState(expectedGeneration)`/`reassignProcessingOwner(generation)`) closing a
    narrower **pre-claim** race the seventh pass's ownership check alone could not: a recognizer session
    publishes `PROCESSING`/its own generation unconditionally, before `route()` ever accepts or rejects the
    resulting event, so an older turn that is still, technically, `activeOwner` at that exact instant could
    otherwise clear a newer session's Processing before the newer turn even existed — verified by
    revert-and-rerun (7 of 10 new tests fail without generation correlation; the other 3 correctly do not,
    since they exercise the unchanged "no owner exists" fast path — precise, not blanket, discrimination);
    and, new in the ninth pass, (P1-1) `reassignProcessingOwner`'s compare-and-set on *both* the generation
    it moves from and the one it moves to (closing a gap where a rejected event's own transfer attempt could
    stamp over an even-newer, third session's legitimate Processing — verified by revert-and-rerun: the
    direct `FakeVoiceController` proof fails without the fix) and (P1-2) `AndroidSpeechRecognizerController`'s
    `callbackLock`, serializing every `RecognitionListener` callback's generation check together with its
    own state mutation against every concurrent generation-bumping operation (`cancel()`/`shutdown()`/a
    newer `beginListening()`) — verified with two genuinely-forced-interleaving tests using a blocking
    `AppClock` double to pause a callback *inside* its own locked section, proving structurally (via a
    bounded wait a concurrent `cancel()`/new-start cannot beat, not a timing guess) that the two operations
    can never interleave; confirmed via revert-and-rerun (a lock neutered to a fresh, unshared monitor per
    access makes exactly those two tests fail, and only those two — a `shutdown()`-generation-bump test and
    basic sequential regression tests correctly remain green, since they do not depend on genuine
    cross-thread lock contention); and, new in the tenth pass, `beginListening()`'s own generation *mint*
    (`generation.incrementAndGet()`) moved inside `callbackLock` too (the ninth pass had only locked the
    later dispatched check-then-act, leaving the mint itself free to race a callback already holding the
    lock) — verified with a forced-interleaving test proving the mint itself (not merely the dispatched
    block that follows it) cannot happen while a callback holds the lock, plus a reverse-ordering test
    proving a newer start that wins first leaves every stale callback a no-op; confirmed via revert-and-rerun
    (moving the mint back outside the lock makes exactly the one new forced-interleaving test fail, while the
    reverse-ordering test correctly does not, since that direction never depended on the mint being locked);
    and
    `AssistantTurnMetricsRecorder`'s `compareAndSet`-based patch-then-log ordering, proven against a
    deterministically-forced losing first CAS attempt via a test-only seam) were all regression-tested
    with real JVM threads/coroutines-under-real-dispatchers (not just single-threaded coroutine-test
    ordering) — but real-device thread/`Looper`/`Handler` scheduling and timing was never exercised.
  - `AssistantTurnCoordinatorTest`'s "concurrent submits from many real threads at once start exactly one
    turn" (24 real threads via `CyclicBarrier`) was rewritten this (tenth) pass: the real, ungated mock
    gateway could answer fast enough that the first accepted turn completed and freed single-flight again
    before all 24 threads had even attempted their own `submit()`, letting a later thread be *legitimately*
    re-accepted as a second, genuinely new turn — an intermittent `expected 1, got 2` caused by a gap in this
    test's own setup, not a defect in `AssistantTurnCoordinator.submit()`'s single-flight guard itself. Now
    gates the accepted turn's gateway call behind a `CompletableDeferred` so it stays genuinely in flight for
    every one of the 24 threads' attempts, joins and asserts every worker thread actually terminated before
    proceeding, and awaits the accepted turn's own terminal state via its per-turn `Deferred` (never
    `Thread.sleep` polling) — run 50 consecutive times, all clean, still real-thread-based (this remains
    real-device/thread-scheduling-dependent in the sense that it exercises genuine OS thread contention, just
    no longer dependent on the *gateway's own response speed* for correctness).
  - `AssistantTurnCoordinator`'s new (sixth pass) injectable `logger` — wired in `SafeDriveContainer` to
    `android.util.Log.w("SafeDriveTurn", ...)` — replaces what used to be silent exception swallowing at
    several sites (a buggy `onStarted` callback, a metrics/TTS post-terminal side-effect failure) with a
    redacted diagnostic line (exception class name + requestId/generation/source only, never
    `Throwable.message` or any transcript/reply content) — this has not been observed against real Android
    `Log` output on a device, only against an injected in-test capture list.
  - `AssistantTurnCoordinator.beginTurnLocked`'s `currentJob` assignment (new, seventh pass) is now guarded
    against a genuinely-inline-completing coroutine clobbering its own correct `null` cleanup with a stale,
    already-dead `Job` reference — real production dispatchers (`Dispatchers.Default`/`applicationScope`)
    never run this eagerly enough to trigger it, so this closes a latent hazard rather than one any real
    test scenario currently exercises (confirmed honestly via revert-and-rerun: no test fails without it).
  - `ConversationRepository.beginTurn`/`rejectBeforeInFlight` (fifth pass) atomically publish a
    turn's user message together with its state transition — closing a gap where a `StateFlow` collector
    on a different thread/dispatcher than `AssistantTurnCoordinator`'s caller could observe the new
    message appended while the turn/retry/error state still reflected the *previous* turn. Verified via
    this suite's `UnconfinedTestDispatcher`'s inline-resumption behavior (which makes such an
    intermediate state directly observable to a collector), not real-device dispatcher behavior.
  - The real TTS `onStart` callback wiring (`TtsController.events`, used for
    `AssistantTurnMetrics.ttsStartedAtMs`) was tested via `FakeTtsController.emitStarted`/
    `autoEmitStartAtMs` and (new this pass) a hand-built `TtsController` firing from a genuine background
    `Thread`, not a real Android `TextToSpeech` engine's actual callback timing — the base-metrics-before-speak()
    reordering fix is now provably race-free against both a fake and a real-thread simulation, but has not
    been exercised against the real engine's actual scheduling behavior.
  - `SpeechRecognizer`/`TextToSpeech` real device behavior (recognizer readiness timing, TTS
    cold/warm start, missing vi-VN voice data, OEM-specific quirks, and whether the real
    `Handler`/`Looper`-backed `AndroidMainThreadExecutor` behaves identically to the
    `FakeMainThreadExecutor` used in tests) was never exercised — only the pure generation-guard/queueing
    logic and the Compose overlays (via fakes) were unit/UI-tested.
  - **`android.os.Bundle` cannot carry real data in this plain JVM unit-test environment** (AGP's
    `isReturnDefaultValues=true` stubs `putStringArrayList`/`getStringArrayList` to no-ops — confirmed
    empirically this pass: a populated Bundle always reads back `null`, causing two initial test drafts
    to time out waiting for a `VoiceInputEvent` that could never arrive). This means
    `RecognitionListener.onResults(Bundle)`/`onPartialResults(Bundle)` parsing a genuine non-blank
    transcript has **never** been exercised at the unit-test level (only reachable via real
    device/instrumented testing) — `pendingCaptureTimings` reset behavior is instead verified via
    reflection on the private field, and end-to-end timing propagation is verified via
    `FakeVoiceController` (which injects `VoiceCaptureTimings` directly, sidestepping `Bundle` entirely).
    Same constraint, carried from the eighth pass: `onResults()`'s explicit `generation = gen` stamp on
    `PROCESSING` (and on the enqueued `VoiceInputEvent`) can never be reached on the real controller in this
    environment either — `AndroidSpeechRecognizerControllerTest`'s eighth-pass tests can only prove
    `clearProcessingState`/`reassignProcessingOwner` are safe no-ops while genuinely idle; the *positive*
    generation-match case (an in-generation clear actually taking effect) is fully proven at the
    `VoiceAssistantCoordinator` + `FakeVoiceController` pipeline level instead, which implements the
    identical conditional logic — confirmed identical by direct source comparison, not merely assumed.
    Same constraint, new this pass (P1-2, ninth pass): the two deep, genuinely-forced-interleaving
    `callbackLock` tests use `onPartialResults`/`onReadyForSpeech`-style callbacks (which do not need
    non-blank Bundle data) to prove the lock's mutual-exclusion property; `onResults`'s own
    PROCESSING-plus-event-enqueue branch specifically still cannot be driven into a genuine cross-thread
    race on the real controller here for the same Bundle reason — its participation in the identical shared
    lock is provable by direct code inspection (same lock object, same guard-then-mutate structure as the
    two callbacks that *are* tested this way) rather than by an independent, end-to-end forced-interleaving
    test. This is a pre-existing environment constraint, not a new gap introduced by this pass's fixes.
  - The new Cockpit Simulator chip (W6.3), Simulator top app bar (W6.4), and TTS icon states (W6.11)
    have not been visually confirmed on a real screen at any size.
  - Real file-backed DataStore persistence across an actual OS-level process kill (Emergency
    snapshot) was not re-verified this pass; it was already documented as verified via an in-memory
    fake in the original MVP build due to a genuine Windows/JVM `PreferenceDataStoreFactory`
    file-rename bug — unchanged, not re-investigated in this pass since Emergency persistence code
    itself was not touched.
  - The full device matrix from `07-testing-security-acceptance.md` / plan 12 W8 (360×800, 390×844,
    412×915, 844×390, TalkBack, real rotation/process-kill, real permission-denial dialogs, backend
    timeout on a slow real network) was not performed.

**Action needed from whoever has hardware access**: install
`app/build/outputs/apk/debug/app-debug.apk`, run through `DEMO_SCRIPT.md`, and run
`:app:connectedDebugAndroidTest` on at least one real device and the two target emulator profiles
(390×844 portrait, 844×390 landscape). Then re-run `docs/mobile-latency-baseline.md`'s pending rows
with real timestamps.

## Scope boundaries (intentional, per docs/android-mvp-plan and the stabilization plan)

- **WebSocket cockpit streaming** was never built — explicitly deferred past the REST gate
  (`openapi/safedrive-v1.yaml`'s `cockpitStream` capability flag is always `false`). Remote Mode works
  via request/response on every vehicle-state change.
- **Voice is transcript-only** — no raw audio is ever recorded, stored, or uploaded. `source: VOICE`
  in the assistant request always carries already-recognized text. Adding raw audio requires a new
  ADR, contract version, and a full consent/retention/privacy review (`docs/android-mvp-plan/12` §3.1)
  — explicitly out of scope for this pass.
- **`safetyMetadata`** on the assistant response is reserved in the OpenAPI spec for a future phase
  and is deliberately not parsed by the Android client yet (relies on `ignoreUnknownKeys` for forward
  compatibility) — see `openapi/safedrive-v1.yaml`.
- **Voice overlay has no typed-text fallback while LISTENING** (the AI Studio prototype has one).
  Found during the W0 parity re-audit; logged as a non-blocking backlog item since typed input is
  always reachable via the Assistant tab as an equivalent path — not a Gate criterion.
- **No dedicated Remote reconnect/backoff loop** beyond `SessionCoordinator`'s cache/expiry/retry-once
  policy and `EmergencyRepository.refresh()` — there is no automatic "resume and refetch" loop for a
  Remote connection that drops mid-session.
- **Remote Mode has never been exercised against a real backend** — only against
  `okhttp3.mockwebserver.MockWebServer` in tests. No staging server exists yet (a backend-side gap,
  tracked in `docs/backend-handoff.md`, not an Android one).
- **R8/ProGuard minification is off** (`isMinifyEnabled = false`). Deliberate MVP simplicity decision
  — enabling it needs keep rules for kotlinx.serialization's reflection-based lookups and Retrofit's
  dynamic proxies, real work with its own risk, not required for MVP functionality or any acceptance
  criterion.
- **APK is unsigned** — no release signing key/keystore was provided; accepted per
  `07-testing-security-acceptance.md` ("Release APK unsigned hoặc internal-signed nếu có").
- **No Gemini Live, Firebase, Google Maps, VHAL, camera/DMS, wearable production, or real emergency
  dispatch** anywhere in the app — out of scope by `00-executive-plan.md` and the stabilization plan's
  own "không mở rộng" list, never added at any point across either the original 8-phase build or this
  stabilization pass.

## Voice

- Wake phrase ("Hey SafeDrive") remains a **simulated** trigger (a button/menu action), not a real
  on-device hotword engine listening 24/7 — unchanged, exactly as scoped.
- Voice only works in the foreground; no foreground service keeps the microphone open while
  backgrounded — unchanged, explicitly out of scope.
- `finishListening()` ("Kết thúc câu nói", W4.7) calls `SpeechRecognizer.stopListening()` — its actual
  behavior (whether the recognizer promptly finalizes vs. some OEM implementations ignoring it) is
  provider-dependent and DEVICE_PENDING.

## Emergency

- The evidence rule (crash + at least one supporting signal) is only wired to the Simulator's crash
  preset and manual crash toggle — no real crash-sensor integration (out of scope; VHAL/real sensors
  are a documented future phase).

## Contract / backend

- The OpenAPI file (`openapi/safedrive-v1.yaml`) was validated **offline and structurally**
  (`openapi-spec-validator`, including full `$ref` resolution of every example file) — this is a real,
  reproducible check, but it cannot substitute for an actual backend implementation round-trip test,
  which does not exist because no backend exists yet.
- `docs/backend-handoff.md` has not been reviewed by a human backend or Android owner — that review
  is a Gate E requirement this automated pass cannot satisfy on its own.
