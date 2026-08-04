# Mobile Latency Baseline — Before Stabilization (W0)

Captured 2026-07-27, before any W1–W8 code changes. Purpose: give W4 a "before" number to diff
against. Numbers below are either read directly from source (deterministic constants) or computed
from JVM unit-test timing; anything that needs a real device/microphone/TTS engine is marked
`DEVICE_PENDING` and will be measured on first device access, not guessed.

## App-controlled (source-verified, no device needed)

| Flow | Current value | Source | Note |
|---|---|---|---|
| Demo Mode assistant reply artificial delay | `(240..440).random()` ms, **every** query | `data/mock/MockSafeDriveGateway.kt:95-96` | GAP-06. This is the entire "Demo feels slow" complaint — there is no other app-controlled delay in the Demo path. Target after W4: 0 ms default, opt-in profile in Developer Mode. |
| Assistant query outer timeout | 12,000 ms (`withTimeoutOrNull`) | `domain/usecase/AssistantQueryUseCase.kt:12` | Plan 12 target: 10,000 ms total including session (W5). |
| Network connect/read/write timeout | 10 s / 15 s / 10 s | `core/network/NetworkModule.kt:11-13` | Plan 12 target: 3 s / 8 s / 5 s (W5). Current values allow a first Remote turn to wait up to 15 s read timeout **in addition to** a separate session call — confirms GAP-07 (double wait, no shared outer deadline). |
| Session start | No explicit timeout; wrapped in nothing before W5 | `domain/usecase/SessionCoordinator.kt` | Currently NOT included in `AssistantQueryUseCase`'s 12 s budget — a slow/hanging Remote `startSession` call is not bounded at all today. |
| Assistant reply → TTS call | N/A today | — | GAP-02: text replies never call TTS, so there is nothing to measure yet. Voice replies do call `speak()` immediately after `GatewayResult.Success` in `AndroidSpeechRecognizerController.submitTranscript()` — no added app delay there. |

## Provider/device-dependent — `DEVICE_PENDING`

No phone was attached to this build sandbox at measurement time (`adb devices` returned empty; a
device was briefly connected earlier in this session for install/permission verification but
disconnected before latency instrumentation existed). These rows cannot be filled with real numbers
without a device and must not be estimated:

| Flow | Status | Command to run once a device is available |
|---|---|---|
| Tap mic → `SpeechRecognizer` ready (`onReadyForSpeech`) | DEVICE_PENDING | Manual stopwatch or logcat timestamp diff around `startListening()`/`onReadyForSpeech` once W4 instrumentation lands |
| Speech end → final transcript delivered | DEVICE_PENDING | Same, around `onEndOfSpeech`/`onResults` |
| `TtsController.speak()` call → audio start, warm engine | DEVICE_PENDING | Logcat timestamp diff once `AssistantTurnMetrics` (W4) is wired |
| `TtsController.speak()` call → audio start, cold init | DEVICE_PENDING | Same, first call after process start |
| Remote request round-trip on real Wi-Fi/LAN | DEVICE_PENDING | `MockWebServer`-based p50/p95 is possible pre-device (W5 tests); real-network number needs a reachable backend, which does not exist yet per `KNOWN_LIMITATIONS.md` |

## Reference device/profile (to be filled in W8)

| Field | Value |
|---|---|
| Device model | *(pending — record from `adb shell getprop ro.product.model` at test time)* |
| Android OS/API | *(pending)* |
| STT provider | *(pending — typically Google app's on-device/cloud recognizer; record `adb shell dumpsys package` for the resolved recognition service)* |
| TTS engine | *(pending — record `adb shell settings get secure tts_default_synth`)* |
| Sample count per flow | *(pending — target ≥20 warm samples per flow per plan 12 §4 latency budget)* |
| Network condition | *(pending — Wi-Fi/LAN, weak network, offline per device matrix in plan 12 W8)* |

## After W4 (2026-07-27)

App-controlled numbers, re-verified against the actual post-W4 source:

| Flow | New value | Source | Note |
|---|---|---|---|
| Demo Mode assistant reply artificial delay | **0 ms by default** (`SimulatedLatencyProfile.NONE`) | `data/mock/MockSafeDriveGateway.kt` | GAP-06 fixed. Developer Mode can opt into `MS_100`/`MS_500`/`MS_2000`/`TIMEOUT` via Settings → "Độ trễ giả lập"; never on by default. |
| Session/network split | `AssistantTurnMetrics.sessionMs` / `.networkMs`, both derived from real timestamps taken immediately before/after `SessionCoordinator.currentSessionId()` and the gateway call | `domain/usecase/AssistantQueryUseCase.kt` (`onTiming` callback), `domain/usecase/AssistantTurnCoordinator.kt` | Recorded per-turn, not just estimated; unit-tested in `AssistantTurnCoordinatorTest` ("records latency metrics"). |
| Mic/recognizer timings | `micStartToReadyMs`, `speechToFirstPartialMs` derived from real `AppClock` reads at `startListening()`, `onReadyForSpeech`, `onPartialResults`, `onResults` | `voice/AndroidSpeechRecognizerController.kt` | Logic verified via `VoiceAssistantCoordinatorTest` ("mic-recognizer capture timings ... reach the recorded metrics"); **actual millisecond values are still DEVICE_PENDING** — this only proves the plumbing is correct, not real-world speed. |
| Reply → TTS call | `responseToTtsStartMs`, timestamped right before `TtsController.speak()` in the `GatewayResult.Success` branch | `domain/usecase/AssistantTurnCoordinator.kt` | Now populated for both text and voice turns (previously N/A — text never called TTS at all, GAP-02). |
| Recorder | `AssistantTurnMetricsRecorder.lastTurn` (`StateFlow`), redacted one-line log per turn (numbers only, W4.10) | `core/observability/AssistantTurnMetricsRecorder.kt` | Surfaced in Settings → Developer Mode ("Lượt gần nhất — ..."), never shown to a normal user. |
| Assistant query outer timeout | Still 12,000 ms | `domain/usecase/AssistantQueryUseCase.kt` | Unchanged in W4 — reducing to the 10 s/3s/8s/5s budget and folding session into the same deadline is explicitly **W5's** job, not W4's. |

Still `DEVICE_PENDING` (unchanged by W4 — these need real hardware, not more Kotlin code): tap-mic→ready
wall-clock value, speech-end→transcript wall-clock value, TTS cold/warm start wall-clock value, and
any real-network Remote round trip. W4 built the instrumentation that will capture these the moment a
device is available; it does not and cannot fabricate the numbers themselves.

## Update (second independent re-audit, 2026-07-28)

The method names in the two tables above are historical snapshots (`SessionCoordinator.currentSessionId()`,
`AndroidSpeechRecognizerController.submitTranscript()`) and predate later renames/refactors — the current
equivalents are `SessionCoordinator.currentSession()` (returns a `ResolvedSession`, see
`docs/android-stabilization-progress.md`'s remediation-pass item 3) and voice transcripts are routed via
`VoiceAssistantCoordinator`, not submitted directly from the recognizer controller (item 2 of the same
section). This pass additionally closed a real subscription race in the TTS-start timing path
(`AssistantTurnCoordinator.awaitTtsStarted` now registers its `TtsController.events` collector *before*
calling `speak()`, see the "Second independent re-audit" section, item 4) — the plumbing is now provably
correct against a fake TTS controller, but the actual wall-clock `ttsStartedAtMs`/`responseToTtsStartMs`
numbers remain `DEVICE_PENDING`, unchanged in status by this fix (only its correctness changed, not its
device-dependency).

## Update (third independent re-audit, 2026-07-28)

A further race in the *same* timing path was found and closed: `AssistantTurnCoordinator` was recording
`AssistantTurnMetrics` (the base record, `ttsStartedAtMs=null`) **after** calling `awaitTtsStarted()`/
`speak()` — on a real multi-threaded scope, an engine whose `onStart` callback reached another thread
fast enough could let `AssistantTurnMetricsRecorder.recordTtsStarted()` run *before* that base record
existed, finding no matching `lastTurn` and silently, permanently dropping the real timestamp. Base
metrics are now recorded unconditionally before `speak()` is even called, closing the window
structurally; proven with a hand-built `TtsController` firing its onStart event from a genuine new
`Thread` on a real `CoroutineScope(Dispatchers.Default)` (see
`AssistantTurnCoordinatorTest.kt`'s "fired from a genuine background thread" test) — not merely a
virtual-time trick, since the prior pass's fix was itself only provable against a single-threaded test
dispatcher that could never reproduce this specific race. `AssistantTurnMetricsRecorder.recordTtsStarted()`
also now emits its own log line when the patch applies, so `responseToTtsMs` actually appears non-null in
the redacted debug log at least once per successfully-timed turn (previously only the base record's
line — always `responseToTtsMs=null` — was ever logged). Real wall-clock `ttsStartedAtMs`/
`responseToTtsStartMs` values remain `DEVICE_PENDING`, unchanged in status (only correctness changed).

Separately, mic/recognizer timing propagation for the *actual production* voice-trigger entry point
(`VoiceController.startWakeWord`, not `startListening` — see `docs/android-stabilization-progress.md`'s
"Third independent re-audit" item 1) was found to have been silently broken: `startWakeWord` never reset
`pendingCaptureTimings`, so every wake-word-triggered voice turn's mic/recognizer timing fields would have
been `null` (or, worse, a stale previous session's) in production, even though `startListening`'s
equivalent path — the only one any existing test exercised — was correct. Both entry points now share one
validated timing-reset path; `VoiceCaptureTimings` end-to-end propagation into `AssistantTurnMetrics` was
already correctly plumbed and remains verified via `VoiceAssistantCoordinatorTest`'s
`FakeVoiceController`-based test, unaffected by this fix.

## Update (fourth independent re-audit, 2026-07-28)

No latency numbers changed this pass — all 5 blockers fixed were internal architecture/concurrency
correctness issues, not timing changes, and no new delay/polling was introduced anywhere in production
code (confirmed by a full-repo scan; see `docs/android-stabilization-progress.md`'s "Fourth independent
re-audit" section). Two items are still worth recording here because they touch the timing/completion
path directly:

- **The voice-turn "terminal state" wait is now a `Deferred.await()`, not a `StateFlow` subscription.**
  `Deferred.await()` wakes the instant `complete()` is called, with zero polling and no added latency —
  if anything, this is now *more* immediate than before, since it no longer depends on
  `ConversationRepository`'s `StateFlow` actually re-emitting to a collector that might not yet be
  subscribed.
- **`AssistantTurnMetricsRecorder.recordTtsStarted()`'s new `compareAndSet` retry loop** is a standard
  non-blocking CAS loop with no `delay`/`sleep` — under real contention it may retry a handful of times,
  each retry costing only a `StateFlow` read and an object copy (microseconds), never a wait.

Real wall-clock `ttsStartedAtMs`/`responseToTtsStartMs` values, and all other provider/device-dependent
numbers in the tables above, remain `DEVICE_PENDING` — unchanged in status by this pass.

## Update (fifth independent re-audit, 2026-07-28)

One genuine latency-**measurement** defect was found and fixed this pass (distinct from all prior
passes, which only ever touched plumbing/race-safety, not the meaning of a captured timestamp):
`AndroidSpeechRecognizerController.beginListening` was computing `micRequestedAtMs = clock.nowMs()`
**inside** the `mainThreadExecutor.execute { ... }` block — i.e. at the instant that confined block
actually ran, not at the instant the caller (a Compose action handler or the permission-result callback)
actually asked for the microphone. On a real device, if the main `Looper` had other pending messages
ahead of this dispatch, this would have silently redefined "mic requested" to mean "the main executor got
around to processing the request," hiding any real dispatch/queueing delay and reporting a **falsely
fast** mic latency number once real device measurement begins.

**Fix:** the timestamp is now captured on the caller's own thread, immediately, before
`mainThreadExecutor.execute` even enqueues the confined block, and threaded into it via the closure (an
immutable local, never a shared mutable field). This makes `micRequestedAtMs` strictly ≥ what the old code
would have reported (it can now include real dispatch delay that was previously invisible) — the fix can
only make the *reported* number larger/more honest on a real device, never smaller, and adds no new
wait/delay of its own (it is a single `clock.nowMs()` read, not a blocking call).

No other latency numbers changed this pass. The other two blockers fixed this pass (exception-unsafe turn
termination; non-atomic user-message+turn-transition publishing — see
`docs/android-stabilization-progress.md`'s "Fifth independent re-audit" section) are both pure
correctness/architecture fixes with no timing dimension: the exception-safety wrapper around the query
call adds no delay on the success path (the try/catch itself has zero runtime cost when no exception is
thrown), and the atomic `ConversationRepository.beginTurn`/`rejectBeforeInFlight` methods still perform
exactly one `StateFlow` update each, the same as before — merging two updates into one, if anything,
removes one redundant `StateFlow` emission per turn rather than adding overhead.

Real wall-clock `ttsStartedAtMs`/`responseToTtsStartMs`/`micStartToReadyMs`/`speechToFirstPartialMs`
values, and all other provider/device-dependent numbers in the tables above, remain `DEVICE_PENDING` —
unchanged in status by this pass. The fixed `micRequestedAtMs` capture point means that once a real device
is available, the mic-latency number it produces will be trustworthy for the first time — a correctness
improvement to the measurement itself, not a new measurement.

## Update (sixth independent re-audit, 2026-07-28)

No latency numbers changed this pass. The cancellation-path fix
(`AssistantTurnCoordinator`'s `Job.invokeOnCompletion` safety net — see
`docs/android-stabilization-progress.md`'s "Sixth independent re-audit" section) adds no delay/polling of
any kind: `invokeOnCompletion` is a single, one-time completion callback that coroutines' own machinery
invokes immediately when the Job reaches a final state — never a loop, never a wait. It also runs on
exactly the *same* code paths that were already going to run (a turn either completes normally, is
cancelled by `cancelCurrent()`, or is abandoned by something else) — it only ever adds a cheap guard check
(`generation == currentGeneration && currentCompletion === completion`) under the same `lock` the rest of
the class already uses, never a new suspension point. The `VoiceAssistantCoordinator.voiceTurnInFlight`
ownership flag (fixing the Processing-ownership bug, same section) is a single `AtomicBoolean` read/write —
no measurable cost. The new `logger` calls are plain, synchronous, no-op-by-default function invocations.

## Update (seventh independent re-audit, 2026-07-28)

No latency numbers changed this pass. The ABA-race fix (`VoiceAssistantCoordinator`'s `VoiceTurnOwner`
identity token + `voiceOwnershipLock`, replacing the sixth pass's `AtomicBoolean voiceTurnInFlight` — see
`docs/android-stabilization-progress.md`'s "Seventh independent re-audit" section) adds no delay/polling:
every critical section under `voiceOwnershipLock` is a synchronous reference comparison/assignment, a
`StateFlow` value set, or a call to the already-synchronous `VoiceController.clearProcessingState()` — never
a suspension point, never a loop. The new `completionScope` constructor parameter defaults to `externalScope`
in every real construction site (`SafeDriveContainer` does not pass it), so production dispatch is
byte-for-byte unchanged; it exists solely as a test seam. `AssistantTurnCoordinator`'s `currentJob` assignment
guard adds one `synchronized` block around a single reference comparison — negligible, and only ever
exercised on a path (an eagerly-inline-completing coroutine) that does not occur with the production
dispatchers actually in use. No wall-clock/device latency numbers below were re-measured or re-estimated this
pass; they remain `DEVICE_PENDING` as before.

## Update (eighth independent re-audit, 2026-07-29)

No latency numbers changed this pass. The pre-claim-race fix (`VoiceInputEvent.generation` threaded through
`VoiceTurnOwner.voiceGeneration` into generation-keyed `clearProcessingState`/`reassignProcessingOwner` — see
`docs/android-stabilization-progress.md`'s "Eighth independent re-audit" section) adds no delay/polling:
every new/changed code path is a single `Long` equality comparison or a `StateFlow.update`/`.value`
assignment, executed synchronously inside the same `voiceOwnershipLock` critical sections the seventh pass
already established — no new suspension point, no loop, no wait. `AndroidSpeechRecognizerController
.onResults()`'s explicit `generation = gen` stamp replaces what was already an implicit `.copy()`-preserved
value with an explicit one — same number of `StateFlow` updates as before, zero added cost.
`FakeVoiceController.emitFinalTranscript`'s new `generation` parameter defaults to an `AtomicLong
.incrementAndGet()` call — a single, non-blocking hardware instruction. No wall-clock/device latency numbers
below were re-measured or re-estimated this pass; they remain `DEVICE_PENDING` as before.

## Update (ninth independent re-audit, 2026-07-29)

No latency numbers changed this pass. Both P1 fixes (`docs/android-stabilization-progress.md`'s "Ninth
independent re-audit" section) add no delay/polling of any kind:

- `reassignProcessingOwner`'s new `expectedCurrentGeneration` parameter is one additional `Long` equality
  check inside a `StateFlow.update {}` lambda that was already running — same number of state updates as
  before, zero added suspension points.
- `AndroidSpeechRecognizerController`'s new `callbackLock` is a plain JVM monitor (`synchronized`) around
  code that was already executing synchronously and non-suspending — in the overwhelming common case (one
  active recognizer session, no concurrent preferences-toggle mid-callback) it is acquired uncontended, the
  same cost as any other `synchronized` block already used throughout this class (`voiceOwnershipLock` in
  `VoiceAssistantCoordinator`, unchanged since the sixth pass). Never held across a suspension point, never
  held while blocking on I/O.

No wall-clock/device latency numbers below were re-measured or re-estimated this pass; they remain
`DEVICE_PENDING` as before.
