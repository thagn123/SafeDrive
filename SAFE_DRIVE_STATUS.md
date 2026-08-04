# SafeDrive Status

## Latest follow-up - background "Hey SafeDrive" wake-word listening + live-device voice bugfixes

**Phase:** Real background wake-word listening (like Google Assistant), per user request in plan mode;
continued and bugfixed after the user's own live on-device testing surfaced real problems.
**Status:** Android **312/312** JVM unit tests passing (31 classes, up from 305/30). `assembleDebug`
succeeds, debug APK now **~20.7MB** after removing dead sherpa-onnx artifacts (see cleanup section below).
Live-verified on the physical test device across this pass: 11+ fresh launches, 0 crashes at any point.
**Six** real bugs were root-caused and fixed this pass (two reported by the user live, four found by this
environment's own follow-up device investigation): a mic-ownership race, ambient restart/silence-timeout
mistuning, no self-heal after a real recognizer error, a residual restart collision, a missing on-device
language pack for the original English wake phrase (worked around by switching the wake phrase to
Vietnamese — "Mai ơi" — rather than requiring a Settings fix), and — the actual cause of the user's final
"lỗi nhận diện giọng nói" report — a pre-existing Safety Guardian bug that re-spoke its TTS warning roughly
once a second, which stopped the ambient listener every single time (it never listens while TTS speaks).
See the dedicated sections below for each.

### What was built (background wake-word listening)
Previously, "wake word" was Android's full `SpeechRecognizer` running only while the app's Compose tree was
alive (backgrounding cancelled it entirely). Replaced with real background listening:
- `WakeWordDetector` interface + `SpeechRecognizerWakeWordDetector`: the original plan called for Picovoice
  Porcupine (a dedicated low-power keyword spotter), but that needs a third-party console account and a
  trained `.ppn` file only the user can supply. Landed on this no-account fallback instead — Android's own
  `SpeechRecognizer`, restarted continuously — at a real, disclosed cost: more battery/CPU than a purpose-
  built spotter, and (see bugs below) more fragile in practice.
- `WakeWordSessionCoordinator`: reactively starts/stops the detector based on `VoiceController`/
  `TtsController` state — resumes ambient listening after *any* path back to idle (success, blank, error,
  cancelled, timeout), closing a real gap the previous rearm-only-after-success logic had.
- `WakeWordListeningService`: foreground `Service`, independent of `MainActivity`'s lifecycle, with a
  persistent low-importance notification and a "Tắt" action; self-stops when the Settings toggle is off.
- Manual mic tap (Cockpit/Assistant/Emergency) now goes straight to command capture — tapping is itself the
  wake gesture, no more "say Hey SafeDrive first" after an explicit tap.
- Manifest gained `FOREGROUND_SERVICE`/`FOREGROUND_SERVICE_MICROPHONE`/`POST_NOTIFICATIONS`; Settings
  screen gained a POST_NOTIFICATIONS permission row.

### Two real bugs found via the user's own live testing, root-caused and fixed
The user reported, from their own phone: can't wake the assistant by voice, voice commands still error, and
a repeated on/off beep ("tút tút liên tục"). Investigated via code review plus live device logcat/appops
evidence (not guessed):
1. **Mic-ownership race.** The ambient detector and the command-capture recognizer are two separate
   platform `SpeechRecognizer` instances. Only the internal wake-word-*detected* path explicitly stopped the
   ambient one before starting a command session; a manual mic tap relied entirely on
   `WakeWordSessionCoordinator` reactively noticing the state change — which, for a same-thread synchronous
   caller like a Compose tap handler, always loses: the command session's recognizer is created and started
   *before* the reactive collector is ever scheduled to stop the ambient one. Two concurrent sessions
   against the same underlying OS recognition service is consistent with the errors and rapid cycling
   observed. **Fix:** `AndroidSpeechRecognizerController.onBeforeListen`, a hook invoked synchronously as the
   first step of every `startListening` call; `WakeWordListeningService` wires it to `detector.stop()` —
   running in the same synchronous call stack closes the race instead of racing it.
2. **Reused command-capture timing made ambient listening restart ~once/second.** The ambient detector
   shared `AndroidSpeechRecognizerFactory`, which hardcodes an 800ms/1000ms silence cutoff meant for bounded
   command capture. Applied continuously, this restarted the session (re-triggering the platform's
   start/stop earcon) roughly every second even in silence — consistent with the "tút tút liên tục" report,
   and a multiplier on bug 1 (more restarts = more race windows). **Fix:** `PlatformSpeechRecognizer
   .startListening` gained `shortSilenceTimeout` (default `true`, unchanged for command capture); the
   ambient detector now passes `false`. **Measured on-device**: live logcat over a 30s window right after
   the fix showed 2 full sessions (~5-6s each); a later, cleaner 90s window (after bug 3 below was also
   fixed) showed 11 sessions, ~8s apart on average — both a large reduction from the ~1/sec cadence
   consistent with the original report.
3. **No self-heal after a real detector error — found by this environment, not the user.** Following up on
   the two reported bugs, a longer idle observation (the app left running for several minutes with nothing
   else touching it) caught the ambient listener going **silently dead**: the foreground-service
   notification stayed up (falsely implying it was listening), but zero recognizer sessions occurred for 5+
   minutes straight. Root cause: `SpeechRecognizerWakeWordDetector` stops itself internally on any real
   (non no-match/timeout) error, but `WakeWordSessionCoordinator`'s only recovery path was reacting to a
   `VoiceController`/`TtsController` state *change* — which never happens on its own just because the
   *detector* failed, so nothing ever restarted it until some unrelated interaction (a manual mic tap, a
   TTS reply) happened to nudge the state. The service's own code comment already *claimed* it would
   "transparently retry... the next time the voice/TTS state changes," which was simply false as written.
   **Fix:** `WakeWordSessionCoordinator.onDetectorFailure` now schedules one retry 10 seconds after a real
   error, re-checking that ambient listening is still appropriate before restarting; a genuine state change
   in the meantime still takes priority and cancels the pending retry. **Measured on-device**: a 90-second
   observation after this fix shows continuous, healthy cycling (11 sessions, no dead gaps) — the same kind
   of window that previously went silent for 5+ minutes.

### Regression evidence
- `./gradlew.bat testDebugUnitTest` → **310 passed, 0 failed, 0 errors** (31 classes; +5 over the 305
  baseline: the mic-ownership-race test and the short-timeout test in `AndroidSpeechRecognizerControllerTest`,
  the long-timeout test in `SpeechRecognizerWakeWordDetectorTest`, the error-retry and
  retry-superseded-by-a-real-state-change tests in `WakeWordSessionCoordinatorTest`).
- `./gradlew.bat assembleDebug` → succeeds.
- Live device (Xiaomi `24129PN74G`, MIUI/HyperOS): 6 fresh launches across this pass, 0 entries in the crash
  log buffer each time, process alive each time (`adb shell ps -A`). Foreground service notification
  confirmed present (`dumpsys notification`: channel `wake_word_listening`, `ONGOING_EVENT|FOREGROUND_SERVICE`).
  Service confirmed to survive backgrounding: `topResumedActivity` moved to `com.miui.home` while
  `WakeWordListeningService` stayed `isForeground=true`. Ambient-session cadence measured directly from
  `RecognitionServiceImpl`/`SpeechRecognitionManagerServiceImpl` logcat lines at each stage: ~1/sec before
  any fix (consistent with the report) → 2 sessions/30s right after bug 2's fix → 11 sessions/90s (~8s
  apart, zero dead gaps) after bug 3's fix, vs. a 5+ minute total silence observed partway through this
  same pass before bug 3 was found.

### Bug 4 — residual mic-ownership collision, found via a live "still beeping" report after bugs 1-3
After bugs 1-3 above, the user reported the beep was still frequent ("vẫn bíp bíp liên tục"). A fresh
device logcat pull caught it directly: a manual mic tap's `vi-VN` command session and the ambient
detector's own `en-US` session both called the platform `startListening()` within **17ms** of each other,
followed by a real `MICROPHONE_UNAVAILABLE` error. Root cause: `onBeforeListen` (bug 1's fix) only stops
the ambient detector *when it's idle and a tap arrives* — it doesn't catch the case where the ambient
detector's *own* restart (queued moments earlier by its previous session's `onResults`/`onError`, unrelated
to the tap) reaches the platform recognizer a few milliseconds *after* the tap's own session already
claimed the mic. **Fix:** `SpeechRecognizerWakeWordDetector` now takes an `isCommandCaptureActive`
callback, re-checked synchronously immediately before every restart (including self-triggered ones), wired
to the same voice-controller state `WakeWordSessionCoordinator` already uses. A collision now skips
silently (routine, not an error) instead of hitting the platform and erroring.

### The beep itself is very likely a structural limit of this approach, not a remaining bug
Reducing restart frequency (bug 2) and fixing the collision (bug 4) do not change the more fundamental
fact: Android's `SpeechRecognizer` plays a system "now listening" earcon on every session start, by OS
design, as a privacy signal the app cannot suppress through any public API — precisely why dedicated
low-power keyword-spotting engines (this project's originally-planned Picovoice Porcupine) exist as a
separate product category that doesn't invoke the full recognition pipeline at all. As long as ambient
listening is implemented by cycling `SpeechRecognizer` sessions, *some* periodic beep is expected, no
matter how healthy the cycling is. This was disclosed to the user with the real options: accept the beep,
attempt an unreliable/OEM-dependent stream-mute workaround, set up Porcupine (needs the user to create a
free Picovoice account and train a wake word — blocked on them, not this environment), or drop continuous
ambient listening back to tap-to-talk only.

### Bug 5 — residual restart/collision hardening + retuned ambient silence timeout (moderate, not unbounded)
Follow-up device testing after bug 4 showed the "no timeout at all" choice for the ambient listener (bug
2's fix) was itself a problem: every single ambient session ran a full **~8 seconds**, `onStartOfSpeech`
firing within ~1s and `onEndOfSpeech` not until ~8s later, cycle after cycle — consistent with ordinary
room noise continually resetting the platform's own (generous, undocumented) default silence threshold
before a genuine pause was ever seen. Practical effect: the earcon still fired (session started), but the
wake phrase had to survive being buried in up to 8 seconds of noise-contaminated audio before any result
was even produced. **Fix:** `PlatformSpeechRecognizer.startListening` now takes explicit
`completeSilenceMs`/`possiblyCompleteSilenceMs` instead of a binary short/unbounded flag; the ambient
listener uses a deliberate middle ground (2.5s/3s) — long enough not to restart on every micro-blip, short
enough that a genuine pause ends the session in a few seconds, not eight. Also added a second,
last-moment guard (`isCommandCaptureActive`, checked immediately before every platform `startListening()`
call, including the detector's own self-triggered restarts) closing a narrower residual version of bug 1:
a live capture caught the ambient detector's *own* restart (queued moments earlier, unrelated to any user
action) still reaching the platform a few ms after a manual mic tap had already claimed the mic, producing
a real `MICROPHONE_UNAVAILABLE`.

### The actual root cause of "beep plays but nothing is ever captured or answered" — found via logcat, not guessed
After the above fixes, the user reported the core symptom was still happening: the earcon still played, but
no capture or reply ever followed. A closer read of the **same** logcat evidence already being collected
revealed the real cause, and it is not a bug in this app's code:

```
Initialize Soda [locale: en-US], [applicationDomain: AMBIENT_CONTINUOUS], [aicore streaming: false]
E SodaSpeechRecognizer: Failed to get language pack of required locale: error 13
```
— on **every single** ambient (`en-US`) session, 100% reproducibly, across multiple independent capture
windows spanning the whole investigation. The command-capture (`vi-VN`) sessions, by contrast, consistently
show:
```
Initialize Soda [locale: vi-VN], [applicationDomain: AMBIENT_CONTINUOUS], [aicore streaming: false]
Initialize Soda with language pack directory
```
— a clean success. **The physical test device has the Vietnamese on-device speech-recognition language
pack installed, but not the English one.** Since the wake phrase "Hey SafeDrive" is deliberately recognized
in `en-US` (it's a fixed English phrase), the ambient listener has been running on a language pack that
doesn't exist on this device this whole time — sessions still start (hence the earcon), Google's recognizer
still processes *some* audio (`onStartOfSpeech`/`onEndOfSpeech` still fire), but it can't actually produce a
usable transcript without its language model. This is a **device configuration gap, not an app bug** — no
code change in this repository can install a language pack. Vietnamese command capture, once triggered
manually (mic tap) or if wake detection is fixed, should not have this specific problem — its pack is
already present and initializing cleanly.

**Not resolved by this environment**: installing the English on-device pack requires the phone's own
Settings UI (Settings → System → Languages & input → On-device speech recognition, or via the Google app's
own Settings → Voice → Offline speech recognition — the exact path varies by ROM/MIUI version and could not
be confirmed by this environment: the standard `android.settings.VOICE_INPUT_SETTINGS` intent did not open
anything on this device, and navigating a multi-level settings menu requires taps, which this device's MIUI
build blocks for adb — see `10_CLAUDE_MVP_CONTINUATION_PROMPT.md`/earlier status entries for the standing
`input tap`/`pm grant` restriction). This is the concrete next step for the user, not further code changes.

### Cleanup done in this pass (also requested by the user)
Deleted the dead sherpa-onnx TTS artifacts from the earlier, abandoned voice-engine-swap attempt
(§"09_MVP_HANDOFF.md §2"): `voice/SherpaOnnxTtsController.kt`, the `com.k2fsa.sherpa.onnx` package
(`Tts.kt`), `assets/sherpa/`, `jniLibs/{arm64-v8a,x86_64}/libsherpa-onnx-jni.so`, and the
`ndk { abiFilters }` Gradle block that existed solely to bundle those libs. None of it was wired in
(confirmed before deleting — `SafeDriveContainer.kt` only referenced it in a doc comment, now corrected).
Debug APK size dropped from the sherpa-onnx-bloated build to **~20.7MB**.

### Wake phrase switched to Vietnamese: "Mai ơi" (supersedes the language-pack finding above)
Rather than asking the user to install a missing English language pack, the user asked to just use
Vietnamese instead — directly sidestepping the whole problem, since the device's Vietnamese pack already
initializes cleanly. Implemented:
- `SpeechRecognizerWakeWordDetector` now recognizes in **`vi-VN`**, not `en-US` — confirmed live on-device:
  ambient sessions now log `Initialize Soda [locale: vi-VN]... Initialize Soda with language pack
  directory` (success), not the `en-US`/"Failed to get language pack... error 13" pair seen throughout the
  rest of this investigation.
- Wake phrase is **"Mai ơi"**. `WAKE_PHRASE_PATTERN` requires "mai" and "ơi" adjacent (`\bmai\s+[oơ]i\b`,
  tolerating a dropped diacritic), not bare "mai" alone — "mai" ("tomorrow") is an extremely common
  Vietnamese word on its own ("để mai tính", "ngày mai...") and would false-trigger constantly if matched
  without the vocative; "mai ơi" is far more distinctive. (An intermediate choice, "SafeDrive ơi", was
  tried first and replaced per explicit user direction before ever being tested live.)
- Every user-facing string that quotes the phrase to say (notification text, Cockpit/Assistant/overlay
  hints, Settings toggle title, mic content-description) updated from "Hey SafeDrive" to "Mai ơi". Left
  the app/product's own name ("SafeDrive đang lắng nghe" etc.) unchanged — this was a wake-*phrase* change,
  not a rebrand, per the literal scope of what was asked.
- Regression: **312/312** tests passing (net +1: split the old single wake-phrase-matching test into a
  "matches with dropped diacritic" case and a dedicated "bare mai alone must not match" case). Live device:
  installed, launched, 0 crashes, ambient listening confirmed cycling on `vi-VN` with successful language
  pack initialization.

### Bug 6 — the actual cause of the last "lỗi nhận diện giọng nói" report: Safety Guardian TTS re-firing ~once/second
Immediately after the Vietnamese switch, the user reported the problem persisting. Fresh logcat evidence
showed something new and much more concrete than anything found earlier: `RecognitionService#onCancel` /
`Speech recognition error type CANCELLED` firing roughly **every 1-1.5 seconds**, well inside the 2.5s
silence-timeout window — meaning something was *actively* cancelling sessions, not letting them time out
naturally. Cross-referencing timestamps, each cancel landed within 1-2ms of a `com.google.android.tts`
`AudioTrack` creation — i.e. a TTS utterance starting. Root cause: the pre-existing "Safety Guardian"
proactive-warning block in `SafeDriveContainer.kt` re-arms its own duplicate-suppression the instant the
risk level so much as touches LOW for one tick (by design, so a genuine re-escalation always re-announces)
— but on this run the risk signature was flickering tick-to-tick, so nearly every tick counted as "new"
and re-spoke the same warning. `WakeWordSessionCoordinator` correctly never listens while TTS is speaking
(so the mic doesn't transcribe the assistant's own voice) — so a TTS call once a second meant the ambient
"Mai ơi" listener was being stopped and restarted once a second, never getting a real chance to hear
anything. **Fix:** added a 30-second cooldown specifically on the *TTS* half of the guardian (the chat
notice still logs every genuine change) — but only when the new warning is the *same or lower* severity
than what was last actually spoken; a real escalation (e.g. MEDIUM → HIGH) still always speaks immediately,
so the safety property this feature exists for is unchanged. **Measured on-device**: a 60-second window
after the fix shows **0** `CANCELLED` errors and exactly **1** guardian TTS utterance (previously ~40-60
cancels and comparably many TTS calls in the same window).

### Not done in this pass — needs the user, not this environment
- **Whether "Mai ơi" now actually wakes the assistant with a correctly-captured follow-up command.** This
  environment cannot produce audio input or judge audio output — the language-pack success and the end of
  the cancel-spam are strong evidence the two biggest blockers are gone, but only the user saying it out
  loud can confirm the whole path actually works end-to-end.
- **Which of the earlier-identified beep tradeoffs (accept it / Porcupine / tap-to-talk only) the user
  wants**, if the beep is still bothersome now that sessions are longer-lived and no longer interrupted by
  guardian spam.
- **"LLM answers need to be smarter"** — the other half of the user's original report — is not addressed in
  this pass; voice input being fundamentally broken was the higher-priority, blocking issue.

## Latest follow-up - advisory LLM intent reclassification (08_MVP_LLM_ACTIVATION_PLAN.md)

**Phase:** MVP real-LLM activation, per user request to plan then execute against
`08_MVP_LLM_ACTIVATION_PLAN.md`.
**Status:** Backend **184/184** tests passing (was 176 at the start of this pass; ruff clean). Android
unaffected, not re-run (backend-only change). Uncommitted, pending explicit commit request.

### Correction to this file's own drift, found while planning

Before starting, cross-checked every claim in this file and in a pasted mentor-report draft against live
code + a live test run + a live local Ollama probe. Two real inaccuracies found in this file's own older
entries (both already superseded further down but worth stating plainly at the top): the "MVP core
implementation" entry's "There is no active LLM integration yet" and "no LLM integration exists yet
anywhere" claims are **stale** — `OllamaNarrator` (`app/mobile/llm.py`) and `EmergencyLLMReasoner`
(`app/mobile/emergency_reasoner.py`) were added in a session after that entry was written but this file was
never updated to say so. Full reconciliation lives in `08_MVP_LLM_ACTIVATION_PLAN.md` §1; not repeated here.

### What was built (advisory intent reclassification)

Added `OllamaIntentClassifier` (`app/mobile/llm.py`): when the deterministic `IntentResolver` truly cannot
match a message at all (`IntentResolution.needs_clarification`), and only when there's no active emergency
and risk isn't HIGH/CRITICAL (`MobileSessionStore._can_classify`, mirroring `_can_narrate`'s gate), the
local Ollama model picks one label from a closed 6-item set. That label only ever selects an existing
deterministic reply template (`ContextAwareAssistant.build_reply`) — it cannot invent wording or an action,
and the label set deliberately excludes every action-bearing/emergency route (those are already resolved
before this could run). Fails closed on any timeout, error, or non-exact-label output. Full design,
implementation notes, the timeout-tuning bug found and fixed (a cold Ollama model load exceeded an
initially-too-tight classifier timeout, silently defeating the feature), and live verification against the
real model are recorded in `08_MVP_LLM_ACTIVATION_PLAN.md` §9 — not duplicated here.

### Regression evidence

- Backend: `.venv/Scripts/python.exe -m pytest -q` → **184 passed, 0 failed** (+8 new: 6 unit tests in
  `tests/test_mobile_llm.py`, 2 full-app integration tests in `tests/test_mobile_compatibility.py`).
  `ruff check app tests` → clean.
- Live, non-mocked verification against the real local `qwen2.5:7b-instruct-q4_K_M`: an off-keyword
  message was reclassified `assistant.general` → `companion.conversation` and narrated into genuine
  model-generated Vietnamese text (not a template); the same message during an active crash/no-response
  emergency triggered no reclassification; an explicit HVAC command stayed fully deterministic
  (`latencyMs: 0`, `model: "deterministic-context-router"`, ~63ms wall time).
- Android: not touched by this change, not re-run this pass.

### Not done in this pass

The on-device Android smoke test (`safedrive-ai (1)/android/REMOTE_MODE_SMOKE_TEST.md`, driven from the
actual app UI on the connected device) is still outstanding — this environment verified the backend
directly over HTTP, not through the Android app, since MIUI on the connected device blocks the adb
`input tap`/`pm grant` calls that would be needed to drive the UI here (a limitation already recorded
earlier in this project). Remains the next concrete step before calling the MVP demo-ready.

## Latest follow-up - pre-trial remediation after adversarial audit

**Phase:** Pre-device stabilization after `07_ADVERSARIAL_AUDIT_REPORT.md`.
**Status:** Backend **159/159** tests passing and `ruff check .` clean. Android **284/284** JVM tests passing across 28 XML suites. All current changes remain **uncommitted** by design; baseline commits are unchanged.

### What was completed

1. **HVAC confirmation now survives unrelated Remote Mode telemetry.** The audit correctly found that every `state/update` cleared the server-issued action registry. A speed or GPS update could therefore invalidate a valid HVAC confirmation before the driver tapped it. `MobileSessionStore` now binds a `SET_HVAC_TEMPERATURE` action to a server-owned dependency fingerprint: cabin temperature, energy, existing HVAC target, driving duration, crash/passenger condition, reported fatigue, and DTC code/severity. When a new state arrives with the same decision inputs, the backend rebinds that issued action to the new state version. If any of those safety/comfort inputs change, or the state is stale, the action is discarded and must be planned again. The client still cannot forge an action ID, type, target, fingerprint, or state version. Regression tests cover both the allowed speed/location update and the rejected energy change.
2. **Natural Vietnamese input coverage is less brittle.** The deterministic router now recognizes `Toi khong khoe`, `Xe co van de gi khong?`, and compound comfort input such as `Nong qua, bat dieu hoa len`. The compound form takes the context-grounded hot-cabin route rather than ignoring cabin context merely because it also contains an HVAC command. This remains a deliberately bounded MVP router, not a claim of unrestricted conversation understanding or an LLM fallback.
3. **Expired sessions are removed from memory.** Starting a new session now purges expired entries, and looking up an expired session removes it before returning the existing fail-closed error. A clock-driven regression test proves it.

### Current trial gate

- Graphify remains useful for navigation only: it reports no import cycles but does not establish functional completeness, latency, contract correctness, or safety quality. Its inferred edges should not be treated as verified design facts.
- Android compiles and packages the current debug APK at `safedrive-ai (1)/android/app/build/outputs/apk/debug/app-debug.apk`.
- **Deterministic backend latency baseline:** 150 in-process ASGI assistant queries for `Nong qua, bat dieu hoa len` measured p50 **1.631 ms**, p95 **2.284 ms**, max **2.522 ms**. The response's millisecond-resolution `serverProcessingMs` field ranged from 0-3 ms. This excludes a real TCP/Wi-Fi link, Android rendering, speech recognition and TTS; it must never be presented as end-to-end voice latency.
- `adb devices -l` currently reports no connected device or booted emulator. The remaining mandatory verification is the real Android/AAOS Remote Mode smoke test in `safedrive-ai (1)/android/REMOTE_MODE_SMOKE_TEST.md`.
- Existing Kotlin warnings are non-blocking pre-existing deprecation/opt-in/performance warnings; no test failed. They should be addressed as cleanup after the demo, not mixed into the safety/contract trial gate.

### Deliberately deferred to protect MVP scope

- Multi-turn conversation memory and constrained LLM fallback.
- HVAC off/fan controls and media/door controls. They require an additive action/contract design, Android UI implementation, and a separate verification pass.
- A real emergency provider. The only permitted behavior remains the explicit `SIMULATION_ONLY` rescue workflow with `realEmergencyDispatchEnabled=false`.

## Latest follow-up - adversarial red-team pass (bugs found and fixed)

**Phase:** Pre-trial-run adversarial verification, requested by the user before their own manual Remote Mode test.
**Status:** Backend 153/153 pass, ruff clean. Android 284/284 pass (28 classes). **Uncommitted**, pending explicit commit request.

### Why this pass happened

The user pasted a "9.7/10 final scorecard" that cited `graphify-out/GRAPH_REPORT.md` as its evidence. Read that file directly: it contains **no scoring, rubric, or feature-completeness judgment of any kind** — only structural stats (2389 nodes, 5380 edges, 141 communities with bare cohesion floats, a God-Nodes list ranked by raw edge count, an import-cycle check, and a "knowledge gaps" list of isolated nodes). Every quality claim in the scorecard (e.g. "Community 18 và Community 4 DTO khớp 1-1", "SignalRegistry kiểm soát tốt độ tươi dữ liệu") was an external interpretation layered on top of raw connectivity numbers, not a conclusion the tool drew. Per the user's request, this was followed by actually running the backend and adversarially probing it as a real user would (fresh scratch scripts driving `httpx.AsyncClient` + `ASGITransport` against the live `create_app()`, not just re-reading code or re-running the existing test suite), across four parallel angles: HVAC/climate commands, context-grounding/conversation-continuity, the emergency/rescue state machine, and a static bug hunt.

### Bugs found and fixed

1. **Cancelled emergency could be reopened.** `respond_emergency()` only checked `state != "IDLE"` before applying a `NO_RESPONSE` transition, so a stray/duplicate signal arriving after the user said "I'm OK" (`CANCELLED`) — or even after the simulated dispatch already "sent" (`SOS_SIMULATED_SENT`) — could push the emergency straight back into `FINAL_COUNTDOWN` and re-reach `SOS_SIMULATED_SENT`. **Fixed**: added `_TERMINAL_EMERGENCY_STATES = {"CANCELLED", "SOS_SIMULATED_SENT"}` and an early terminal-state check in `respond_emergency()` (`app/mobile/session_store.py`) that returns the current snapshot unchanged for any response once terminal. Regression tests: `test_cancelled_emergency_cannot_be_reopened_by_a_stray_no_response`, `test_sos_simulated_sent_is_terminal_and_ignores_further_responses` (`tests/test_mobile_compatibility.py`).
2. **Fatigue-route text mislabeled an active crash as "fatigue risk."** During a live `CRITICAL` crash+no-response emergency, a fatigue-sounding query ("Toi hoi buon ngu") matched the fatigue keyword branch and produced text like *"context shows fatigue risk (crash_detected, occupant_no_response)"* — citing crash evidence as fatigue evidence and never mentioning the live emergency, even though the structured `risk`/`actions` fields were correct. Given this product is meant to be voice-first, a wrong spoken message during an actual crash is a real safety-communication defect. **Fixed**: added an early `safety.emergency_candidate` check in `ContextAwareAssistant._message_and_actions` (`app/mobile/assistant.py`) that overrides any keyword-matched route with an emergency-aware message whenever a live crash+no-response condition is active. Regression test: `test_fatigue_keyword_during_active_crash_surfaces_the_emergency_not_fatigue_text` (`tests/test_mobile_intent.py`).
3. **Negative temperature silently flipped positive.** "Đặt điều hòa âm 5 độ C" (−5°C) was parsed by `_TEMPERATURE_PATTERN` as +5°C (the regex had no sign handling). The rejection outcome happened to still be correct for −5 (5 is out of range), but for the more dangerous case — "âm 20 độ" (−20°C) — the misparsed +20°C is *inside* the valid 16-30°C range and would have been **silently accepted as the opposite of what the user asked for**. **Fixed**: the regex now captures an optional Vietnamese "âm"/minus-sign prefix and `_requested_temperature` applies the correct sign, so negative requests are correctly parsed as negative and rejected by the existing range check (`app/mobile/intent.py`). Regression test: `test_negative_hvac_temperature_is_parsed_as_negative_not_silently_flipped_positive` (`tests/test_mobile_intent.py`).
4. **Android's `formatTemp` used the device's default locale for decimal formatting**, discovered only after adding a JVM unit test for it (it had zero test coverage before, despite being a pure, trivially-testable function — a gap the static bug-hunt pass flagged before this was even run). On this machine's default locale, `formatTemp(23.5f)` produced `"23,5"` (comma), not `"23.5"` — which would visibly mismatch the backend's locale-independent `"23.5°C"` assistant text and action title on any real device with a comma-decimal locale, **including Vietnamese (vi-VN), this app's primary target market.** **Fixed**: `formatTemp` now formats with `Locale.US` explicitly (`VehicleMetricsPanel.kt`), and `formatTemp` was changed from `private` to `internal` so it's unit-testable. New test file: `VehicleMetricsPanelFormattingTest.kt` (3 tests, all passing after the fix).

### Real gaps found, NOT fixed (flagged for an explicit decision, not silently left undocumented)

1. **Any state update invalidates ALL pending confirmable actions, including unrelated ones.** `update_state()` unconditionally clears `session.issued_actions` on every call, regardless of whether the update actually touches anything the pending action depends on. This is a deliberate defense-in-depth choice (verified: it held against every adversarial replay/tamper attack thrown at it) but has a real, non-hypothetical usability cost: `safedrive-ai (1)/android/.../ObserveCockpitUseCase.kt` calls `POST /api/v1/state/update` on **every emission** of the vehicle telemetry Flow while the Cockpit is observed, so a proposed HVAC action's effective confirmation window in Remote Mode is bounded by the telemetry tick interval, not by how fast the user actually reacts. **This is the single most likely thing to visibly "break" the HVAC confirm flow during the user's live Remote Mode trial** — expect `"The vehicle context changed. Please review the latest recommendation."` to appear if telemetry pushes land between an assistant proposal and the confirm tap. Not fixed because loosening it (e.g. only invalidating actions whose relevant fields actually changed) is a security/usability trade-off that deserves an explicit decision, not a unilateral change to safety-guardrail logic.
2. **No conversation history/memory exists at all**, confirmed by direct inspection of `AssistantQueryRequest` (`app/api/schemas/mobile.py:112-119`) and `MobileSession` (`app/mobile/session_store.py`) — neither has any field for prior turns. Every query is resolved independently from `(current text, current vehicle-state snapshot)` only. This directly contradicts `00_SAFEDRIVE_MASTER_CONTEXT.md`'s explicit claims ("Keep a short conversation history so the next question is interpreted in context"; "Conversation history" listed as a context source). Pronoun/ellipsis follow-ups ("Nó có nguy hiểm không?", "Vậy còn gì nữa không?") always fall to the generic clarify template rather than referencing what the assistant just said. What looks like continuity in some cases is coincidental state re-grounding, not memory — this is a real demo risk since it will look like memory works until a tester asks a follow-up whose wording doesn't happen to re-trigger the same state-derived route. Not fixed: implementing real multi-turn memory is a genuine feature, not a bug fix, and shouldn't be done hastily under this pass's scope.
3. **The intent router is keyword-only and brittle**, including for the master doc's own paradigm example: "Tôi không khỏe" ("I don't feel well," `00_SAFEDRIVE_MASTER_CONTEXT.md`'s own worked example for ambiguous language) does **not** match any keyword in `IntentResolver` and falls straight to the generic `assistant.general` fallback — it never reaches the ambiguous-resolution logic (`_resolve_ambiguous`) it's meant to demonstrate. Similarly, "Xe có vấn đề gì không?" (a very natural DTC query) misses every DTC keyword; only the stilted "xe co gi la" phrasing (used in the repo's own unit test) works. Not fixed: this is inherent to the deterministic-keyword-router design chosen for the MVP (per `00_SAFEDRIVE_MASTER_CONTEXT.md` itself, an LLM fallback path was always meant to handle exactly this case — "Fallback path: ambiguous language may use an LLM..." — and that path doesn't exist yet).
4. **No "turn off AC" support anywhere.** "Tắt điều hòa" / "Tắt máy lạnh đi" match no route and fall to the same generic clarify prompt used for genuinely ambiguous safety language ("Bạn đang thấy mệt, khó chịu trong cabin, hay lo về tình trạng xe?") — a real user asking to turn the AC off gets a response that sounds like the assistant misheard them as reporting a safety problem. Same non-answer for fan-speed requests and very indirect phrasing ("Cho tôi xin chút gió mát"). Not fixed: adding "off" cleanly needs a contract decision (a power-state field vs. a new action type), which is scope the user should explicitly authorize, similar to how the original `SET_HVAC_TEMPERATURE` addition was.
5. **Unbounded in-memory session growth.** `self._sessions` (`app/mobile/session_store.py`) has exactly one write site and no eviction/reaper; `_require_session` only rejects expired sessions at lookup time, it never deletes them. Every `POST /api/v1/sessions/start` permanently grows server memory for the life of the process. Not urgent for a short hackathon demo process lifetime, but a real leak worth knowing about.
6. **Combined phrasing loses state-grounding.** "Nóng quá, bật điều hòa lên" (hot + explicit "turn on AC") routes to the direct-command branch (`climate.enable_default`, defaults from energy level only) rather than the state-grounded `comfort.too_hot` branch — the actual cabin temperature is never consulted for this specific combined phrasing, even though the master doc promises exactly this kind of state-grounding. Defensible (the user gave an explicit imperative) but an unexamined trade-off, not a clearly "correct" or "wrong" choice — flagged, not changed.
7. **Fan speed / media / volume / doors / infotainment: zero implementation**, confirmed by exhaustive grep of both repos' action-type surfaces. This is explained by an internal tension within `00_SAFEDRIVE_MASTER_CONTEXT.md` itself (§3's broad "normal driving" capability list vs. §9's explicit 5-scenario MVP scope, which names only HVAC as a control action) — not a code defect, but worth knowing precisely how much of §3's promise is actually live today (1 of 6 categories).

### Regression evidence

- Backend: `.venv/Scripts/python.exe -m pytest -q` -> **153 passed, 0 failed** (was 149; +4 new regression tests for the three backend fixes). `ruff check app tests` -> clean.
- Android: `gradlew.bat testDebugUnitTest --rerun-tasks` -> **284 passed, 0 failed, 0 errors** across 28 classes (was 281/27; +3 new tests for the locale fix, in a new `VehicleMetricsPanelFormattingTest.kt`), independently aggregated from JUnit XML.

### Commit state

- Existing committed baseline: backend `2866880`, `12de3fe`; Android/app `9153619`, `961ff9f`.
- The prior "action-confirmation hardening and natural HVAC commands" follow-up (149-test backend state) and this adversarial-pass follow-up (153/284) are both still **uncommitted**, pending an explicit user decision.

## Latest follow-up - action-confirmation hardening and natural HVAC commands

**Phase:** MVP quality and safety hardening  
**Status:** PASS for automated verification; follow-up backend change is intentionally **uncommitted** pending an explicit user decision.

### Improvement made

Natural generic HVAC commands are now a real, state-grounded part of the MVP. `Tôi muốn bật điều hòa` and `Bật máy lạnh` resolve to `climate.enable_default`, which creates the same typed, confirmable `SET_HVAC_TEMPERATURE` action used by an explicit command. The default is deliberately modest and explainable: `22°C` when energy is above 20%, or `24°C` when energy is at or below 20%. It is a proposal in simulation, never a silent cabin change.

The intent parser now recognizes an explicitly requested temperature before applying the `16-30°C` safety range. A request such as `Đặt điều hòa 31°C` routes to `climate.invalid_temperature`, explains the supported range, and returns no action. This keeps invalid requests from falling through to general conversation or from reaching the action-confirmation path. The parser continues to preserve one decimal and also accepts an explicit degree-symbol form such as `23°C`.

The mobile action-confirmation endpoint now accepts an action only when the backend issued that exact action ID for the current session and current state version. It also requires the submitted type and typed HVAC target to match the stored assistant plan. A client can no longer manufacture `SET_HVAC_TEMPERATURE`, reuse an old plan after state changes, or modify a valid plan's target temperature before confirmation.

The assistant and Cockpit now preserve a one-decimal HVAC/cabin value in their human-readable UI. For example, the typed target `23.5` remains `23.5°C` in the assistant response, action title, action ID and Cockpit badge; it is not rounded to `24°C` or truncated to `23°C` while the underlying state says `23.5`.

The guard is implemented in `backend AI/safedrive-ai-backend/app/mobile/session_store.py` as a small session-scoped issued-action registry. Issued actions are invalidated on any state change, including a successful HVAC action. This does not add a new API or expand the MVP surface.

### Regression evidence

- Backend: `py -m pytest -q` -> **149 passed, 0 failed**. Decimal-HVAC, generic-command/default-setpoint, out-of-range rejection, and action-integrity regressions run together with the prior suite.
- Android: `gradlew.bat testDebugUnitTest --rerun-tasks` -> BUILD SUCCESSFUL. Existing JUnit aggregation remains **281 passed, 0 failed, 0 errors**.
- New backend tests prove both an unissued HVAC action and a valid action with a modified target are rejected without changing the current HVAC state or `stateVersion`; a decimal command/response test proves `23.5°C` remains exact. Android compiles the Cockpit `HVAC {target}°C` readout with the same one-decimal behaviour.

### Local network preflight

- Started the backend with the repository virtual environment and Uvicorn on `127.0.0.1:8010`, then called it through real HTTP rather than the in-process ASGI test transport.
- `GET /health` returned `status=ok`, `assistantCapability=true`, and `emergencySimulationCapability=true`. `POST /api/v1/sessions/start` returned a non-empty session, `contractVersion=v1`, and `realEmergencyDispatchEnabled=false`.
- The temporary Uvicorn process was stopped after the check. `adb devices -l` found no attached device or booted emulator, so this confirms local backend reachability only; the Android Remote Mode smoke test remains the final unproven integration step.
- `safedrive-ai (1)/android/REMOTE_MODE_SMOKE_TEST.md` now records the exact local-network setup, five MVP scenarios, expected evidence, and stop conditions for that final check.

### Commit state

- Existing validated MVP commits remain: backend `2866880`; Android/app `9153619`.
- The follow-up changes are currently uncommitted: backend action guardrail/tests plus HVAC decimal formatting/tests, and the Android Cockpit display formatter. Do not describe either tree as fully committed until these changes are explicitly committed.

## Current handoff - MVP core implementation (Phases 1-5)

**Phase:** MVP core implementation - contract, context/safety, assistant, rescue simulation, and state-backed HVAC control  
**Status:** PASS for automated contract/unit verification; **manual Android/AAOS and CarSky smoke test remains pending**.  
**Baseline rollback commits:** backend `12de3fe`; Android/app `961ff9f`. All MVP work below is intentionally uncommitted until the user explicitly requests a commit.

### What is now implemented

- **One coherent Remote Mode contract.** The backend provides the nine Android-facing endpoints as an additive compatibility layer while retaining the canonical signal API. `contractVersion` is exactly `v1`, and mobile-route errors use Android's required flat error envelope.
- **Context-aware normal assistant.** A structured state snapshot carries source/timestamp freshness, vehicle state, DTCs, driver-support signals, location and safety evidence. The deterministic assistant distinguishes direct commands, vehicle status/fault questions, cabin-comfort context, fatigue/discomfort context, and emergency terms. It never receives a raw video, audio, or CAN stream.
- **A real state-backed cockpit action for the MVP.** The Vietnamese command `Đặt điều hòa 23 độ C` is normalized safely, parsed into a typed `SET_HVAC_TEMPERATURE` action, requires confirmation, updates the session's simulated/VHAL-compatible HVAC setpoint, increments `stateVersion`, and republishes the canonical HVAC state. The action carries `hvacTargetTemperatureC`; it does not use a generic parameter map.
- **Deterministic Safety Guardian.** Freshness and risk are decided by code before any language response. Bounded policy covers stale/missing evidence, crash/no response, crash, high-severity DTC, long driving/fatigue, and hot cabin. In Remote Mode, Android consumes the backend's emergency state rather than independently deciding critical risk.
- **Rescue workflow simulation.** A crash/no-response event creates a structured Rescue Brief with last known location and freshness, concise vehicle-status summary, risk/evidence, an SOS countdown, and a `MOCK_ROADSIDE_ASSISTANCE_GATEWAY` acknowledgement. Every relevant field is visibly and programmatically constrained to `SIMULATION_ONLY` / `realEmergencyDispatchEnabled: false`.
- **Android mapping and presentation.** Android maps the expanded state/action contract, invokes the typed action confirmation request, applies the simulated HVAC result to the vehicle datasource for immediate cockpit feedback, and renders emergency brief/dispatch state. No physical vehicle, VHAL or real emergency provider is claimed.

### Evidence from this continuation

- Backend: `py -m ruff check app tests` -> **PASS**; `py -m pytest -q` -> **144 passed, 0 failed**.
- Android: `android\\gradlew.bat testDebugUnitTest` -> **281 tests, 0 failures, 0 errors** across 27 test suites.
- App-facing OpenAPI: `openapi/safedrive-v1.yaml` -> **schema valid** with `openapi-spec-validator`.
- Diff validation: `git diff --check` reports no whitespace errors in either repository (the remaining messages are Windows CRLF advisory warnings only).
- Targeted contract coverage includes session/version/error compatibility, ambiguous discomfort grounded by fatigue context, crash/no-response rescue progression and mock receipt, accented Vietnamese HVAC parsing/confirmation/state republish, Android DTO mapping, and Android simulated HVAC state update after user confirmation.

### Safety boundaries verified in code

- `realEmergencyDispatchEnabled` is forcibly `false` in backend schema/session/rescue paths and Android DTO mapping, even if an untrusted wire payload says otherwise.
- The only rescue endpoint is `mock://safedrive-rescue-gateway/v1/events`; its provider/outcome explicitly identify a simulation. The MVP performs no real ambulance, police or roadside-assistance call.
- The Rescue Brief uses safety wording such as crash signal, no response and human verification required. It does not diagnose injury or claim unconsciousness.
- The deterministic safety engine and action guardrails decide allowed safety behaviour. There is no active LLM integration yet, so no language model can decide SOS or consume raw telemetry.

### Remaining proof before a hackathon demonstration

1. Configure Android Remote Mode with a reachable locally hosted backend and run the five scripted MVP scenarios on an emulator or Android Automotive environment: precise HVAC, hot-cabin recommendation, fatigue intervention, DTC explanation, and crash/no-response SOS simulation.
2. Verify the actual FPT/CarSky adapter names and transport once platform access is available. The current mock/GPIO/VHAL-compatible boundaries are deliberate; no FPT-specific source is being faked as integrated.
3. Capture a short demo recording that visibly shows `SIMULATION_ONLY`, rescue location/status/evidence, and the mock acknowledgement. Do not describe this as a real emergency dispatch.
4. Commit the validated changes in both repositories only after the user explicitly requests it; the existing baseline commits remain the rollback points.

### Immediate safe next step

Run the Remote Mode smoke test with the locally reachable backend, using the documented demo script, then record the observed request/response and any device-only issue in this file before wiring a CarSky-specific adapter or optional local Qwen model.

### Independent verification of this handoff (same day)

Per the documentation-precedence process, every claim above was independently re-verified against live code and test runs (not assumed) by 5 separate read-only audit passes. **Result: confirmed accurate**, including the safety-critical claims:
- All 9 routes, `contractVersion="v1"`, and the flat mobile error envelope are intact (the two earlier-fixed bugs did not regress).
- Backend `py -m pytest -q` -> **144 passed, 0 failed** (exact match); `ruff check app tests` -> clean. Android `gradlew.bat testDebugUnitTest` -> **281 passed, 0 failed, 0 errors, 27 classes** (independently aggregated from JUnit XML, not the console banner).
- HVAC: the Vietnamese/English temperature parser (`app/mobile/intent.py`) is a real bounded regex (16-30°C, handles decimals), not a hardcoded string match; the action is a typed, bounded field (`hvacTargetTemperatureC`) on both `openapi/safedrive-v1.yaml` and Android's `ActionType`/`GatewayContracts.kt` (additive, not a generic parameter bag); confirmation increments `stateVersion` and updates canonical state end-to-end (`tests/test_mobile_compatibility.py`, `AssistantViewModelTest.kt`).
- Safety Guardian: all 6 required rules (stale evidence, crash+no-response, crash, high-severity DTC, fatigue/long-drive, hot cabin) are present, correctly ordered, and free of any LLM call (`app/mobile/safety.py` — grepped the whole backend tree for llm/qwen/openai/anthropic/langchain/rag, found zero live call sites).
- `realEmergencyDispatchEnabled` is double-enforced false: `Literal[False]` at 6 backend schema sites, no request schema accepts it as input, and Android's `ApiMappers.kt` hardcodes `false` at 3 mapping sites regardless of what the wire sends (proven by an explicit negative-path test that feeds a DTO with `true` and asserts the mapped domain value is still `false`).
- Rescue Brief has location+freshness, vehicle-status summary, risk+evidence, and a `MOCK_ROADSIDE_ASSISTANCE_GATEWAY` / `mock://safedrive-rescue-gateway/v1/events` acknowledgement exactly as claimed; text uses only grounded, non-diagnostic language. Cross-repo scan found no real dispatch mechanism (the mock endpoint uses an unroutable `mock://` scheme) and no LLM integration exists yet anywhere, so the "no raw sensor data to LLM" invariant is currently vacuously true.

Two minor, non-blocking gaps found and handled:
1. **`safedrive-ai (1)/docs/assistant-action-allowlist.md` was stale** — still documented only 5 action types / "only four fields on SafeDriveAction," not reflecting the new `SET_HVAC_TEMPERATURE` / `hvacTargetTemperatureC` addition. **Fixed** (doc updated to match actual code; no functional risk existed, this was purely a documentation-accuracy gap).
2. **Cockpit-screen UI does not yet render the HVAC target.** The state write is real, tested, and reactively available (`ObserveCockpitUseCase` combines it into `CockpitUiState.Content.vehicleState`), but no Cockpit composable displays `hvacTargetTemperatureC` (`VehicleMetricsPanel.kt` only shows `cabinTemperatureC`) — today's only visible confirmation is a snackbar on the Assistant screen, not a Cockpit-screen change. This makes the "immediate cockpit feedback" wording in the handoff above slightly stronger than what a user watching only the Cockpit tab would see. **Not fixed yet** — flagged for the user to decide (Android UI changes can't be visually verified in this environment; no device/emulator is available per `android/KNOWN_LIMITATIONS.md`).

Also noted, not a defect: the SOS countdown/deadline lives on the wrapping `EmergencySnapshot`, not embedded inside the `RescueBrief` model itself — functionally present, just a structural/naming detail. And: `safedrive-ai (1)/.env.example` still has an unused, unwired `GEMINI_API_KEY` placeholder left over from the unrelated `src/` AI-Studio prototype — cosmetic, no live risk, optional cleanup.

Git state confirmed unchanged from what this handoff states: no new commits in either repo (still `12de3fe` / `961ff9f`), all MVP-core work uncommitted.

### Both open items resolved (user request, same day)

1. **Cockpit HVAC display added.** `feature/cockpit/components/VehicleMetricsPanel.kt` now shows `HVAC {target}°C` next to the existing `Cabin {temp}°C` badge in the header row, rendered only when `vehicleState.hvacTargetTemperatureC != null`. This is a pure Compose UI addition on top of the already-correct, already-tested state write — no backend or contract change. Verified by re-running `gradlew.bat testDebugUnitTest --rerun-tasks`: `BUILD SUCCESSFUL`, JUnit XML re-aggregated to confirm still exactly `281 passed, 0 failed, 0 errors`. **Not visually verified** — no Android device/emulator is available in this environment (`android/KNOWN_LIMITATIONS.md`), so this is compile/logic-verified only, consistent with every other Compose change in this project's history.
2. **Both repos committed.** Backend: commit `2866880` on top of baseline `12de3fe` (19 files, +2569/-7; all pre-commit hooks passed, including `check-yaml` this time since no YAML changed). App: commit `9153619` on top of baseline `961ff9f` (27 files, +872/-38, includes the Cockpit HVAC display and the `docs/assistant-action-allowlist.md` fix). Both commit messages cite the specific subsystems added and the verified test counts.

## Current handoff - Phase 1 complete

**Phase:** Phase 1 - Contract alignment  
**Status:** PASS  
**Baseline rollback commits:** backend `12de3fe`; Android/app `961ff9f`.

### Goal achieved

The Android Remote Mode contract can now run against the signal-first backend without removing or changing its canonical signal API. The backend has an additive mobile-compatibility layer for the eight routes previously missing from the Android contract: session start, state update/read, assistant query, event, action confirmation, emergency read, and emergency response.

The shared `GET /api/v1/state` path is resolved by query mode: `sessionId` serves the Android `StateEnvelope`; `vehicle_id` plus `trip_id` keeps the canonical state projection and still requires `X-SafeDrive-Key`. `GET /health` now also includes the Android-required service/capability fields while preserving the legacy canonical `status: "ok"`. Android explicitly maps that legacy success value to `NORMAL`.

### What works now

- An Android Remote Mode session can start, publish/read structured cockpit state, query a deterministic context-aware placeholder, emit a fatigue event, confirm a simulated action, and use its existing SOS simulation endpoints.
- The compatibility risk checks are deterministic and intentionally bounded: crash/no response, crash, self-reported fatigue or driving more than four hours, high-severity DTC, and hot cabin.
- Every session and emergency response exposes `realEmergencyDispatchEnabled: false`. No real emergency call, rescue dispatch, or medical diagnosis is implemented.

### Verification

- Backend: `py -m pytest` -> **132 passed, 0 failed**, including the new mobile-contract tests and the 130-test baseline.
- Android: `./gradlew.bat testDebugUnitTest --rerun-tasks` -> **276 passed, 0 failed, 0 errors**. The additional test verifies legacy backend health `ok` maps to Android `NORMAL`.
- Backend `git diff --check` -> **PASS**.

### Boundaries kept for later phases

- The new mobile session store is in-memory and isolated. It does **not** yet bridge canonical `/signals`, rolling windows, VHAL/CarSky, GPS, DMS replay, or persistent storage.
- Assistant output is deterministic placeholder routing, not Qwen/LLM, RAG, LangGraph, or a final tool executor.
- The app's action contract was deliberately not expanded in this phase, so no HVAC/media/door command is executed yet.
- `sinceVersion` returns the latest complete envelope; delta sync is future work.
- Rescue Brief generation (short AI-readable vehicle status, location, evidence/freshness, and a simulated rescue-gateway acknowledgement) is explicitly Phase 4 work. Do not imply a real rescue request exists before then.

### Next phase

**Phase 2 - Context and Safety Core:** bridge the compatibility state to the canonical State Manager, add bounded rolling windows/freshness, deterministic Safety Risk Engine, Context Pack builder, and structured assistant intent routing. Preserve the 133 backend and 276 Android passing tests and add focused Phase 2 acceptance tests.

### Correction (documentation-precedence audit, same day)

The "Phase 1 complete" claims above were independently re-verified against live code/tests (not assumed) and were factually accurate at the route/shape/test-count level — but two real, previously-uncaught **wire-contract bugs** were found by cross-checking against the Android-side "current" docs (`safedrive-ai (1)\docs\backend-handoff.md`, `safedrive-ai (1)\docs\contract-delta-draft.md`) and the authoritative app-facing contract (`safedrive-ai (1)\openapi\safedrive-v1.yaml`), which neither side's isolated unit tests could catch. Both are now fixed. See the full audit entry below for the documentation map, precedence reasoning, and citations. Backend test count is now **133** (was 132) after the fixes' regression tests. This work is **not yet committed** in either repo (see LIMITATIONS below) — the same open item as the original baseline-commit gap, now larger.

## Mandatory reading — completed

Read in full before any code inspection:

1. `00_CLAUDE_READ_ME_FIRST.md`
2. `00_SAFEDRIVE_MASTER_CONTEXT.md`
3. `02_INTEGRATION_PLAN.md`
4. `03_TARGET_CONTRACT_SUMMARY.md`
5. `05_PHASE_CHECKLIST.md`
6. `06_CLAUDE_MVP_BUILD_PROMPT.md`

Product intent, safety invariants (`realEmergencyDispatchEnabled` always `false`, simulation-only rescue, no raw video/audio/CAN to LLM, deterministic risk engine), target architecture, and the four-path intent/context mechanism are understood and will govern all later phases.

---

PHASE: Phase 0 - Audit and baseline
STATUS: BLOCKED
GOAL: Confirm both required repositories are present under the SafeDrive root and capture a baseline before any code phase begins.
FILES CHANGED: SAFE_DRIVE_STATUS.md (created)
CONTRACT CHANGES: NONE
TESTS RUN:
- None. Per the build prompt's explicit rule ("if either repository is missing from the SafeDrive root, do not edit the original repositories outside SafeDrive... report and stop"), no build/test commands were run against the repos in their current location outside SafeDrive.
MANUAL CHECKS:
- `dir C:\Users\Admin\Downloads\SafeDrive` -> only the planning docs (00-06) are present. Neither `backend AI\safedrive-ai-backend` nor `safedrive-ai (1)` exists under the SafeDrive root.
- `dir C:\Users\Admin\Downloads` -> both expected repositories exist one directory level up, not yet copied in:
  - `C:\Users\Admin\Downloads\backend AI\safedrive-ai-backend` — present. Contains `app/`, `tests/`, `contracts/`, `configs/`, `docs/`, `.github/`, `.pre-commit-config.yaml`, `pyproject.toml`, `uv.lock`, a populated `.venv/`, and `.pytest_cache/`/`.mypy_cache/`/`.ruff_cache/` from prior runs, plus a real `.git/`. This is more scaffolding (`contracts/`, `configs/`, CI workflows) than "00_SAFEDRIVE_MASTER_CONTEXT.md" Section 4 describes as current reality (signal ingestion + `/health` + `/api/v1/signals` + `/api/v1/state` only) — the repo may already be further along than the docs assume. Not yet verified by reading code.
  - `C:\Users\Admin\Downloads\safedrive-ai (1)` — present, with `.git/`, `android/`, `openapi/safedrive-v1.yaml` (26,364 bytes), `src/`, `docs/`. The `android/` folder contains `README.md`, `TEST_REPORT.md`, `KNOWN_LIMITATIONS.md`, `DEMO_SCRIPT.md`, and `MOCK_VS_REMOTE_COVERAGE.md` — again suggesting prior implementation and test work beyond a fresh scaffold.
  - A third, unrelated folder `C:\Users\Admin\Downloads\safedrive-ai-copilot-mvp` also exists (with its own `app/`, `tests/`, `Dockerfile`, `CODEX_TASK.md`). It is not one of the two repos named in the mandatory docs and was not treated as in-scope.
- The backend's `.build-artifacts`, `__pycache__`, `.pytest-tmp`, and `tmp5p2aki3a` directories and the Android `build/`/`.gradle`/`.kotlin` directories indicate these repos have been actively run/built before, outside SafeDrive.
SAFETY CHECKS:
- realEmergencyDispatchEnabled=false -> NOT YET VERIFIED (no code inspected yet; docs consistently specify this invariant)
- raw sensor data excluded from LLM -> NOT YET VERIFIED
- deterministic emergency policy -> NOT YET VERIFIED
LIMITATIONS / BLOCKERS:
- The two required repositories are not present at the paths the build prompt names (`SafeDrive\backend AI\safedrive-ai-backend`, `SafeDrive\safedrive-ai (1)`). They exist just outside SafeDrive, already containing real git history and prior build/test artifacts, so this is not a simple "copy an empty folder" situation — a naive copy could bring along a stale `.venv`, `.git`, and build caches that shouldn't ship, or could diverge from whatever the user last did in place.
- Per instruction, I have not moved, copied, edited, or built anything in the original locations, and have not claimed integration exists.
NEXT SAFE STEP: Get explicit direction on how to bring the two repositories into `C:\Users\Admin\Downloads\SafeDrive` (copy vs. treat the existing Downloads paths as authoritative working locations), then re-run the Phase 0 audit (git status/log, existing tests, OpenAPI contract vs. actual backend routes) inside the confirmed location before starting Phase 1.

RESOLUTION: User confirmed copying both repos into SafeDrive, source-only (excluding `.venv`, `.pytest_cache`, `.mypy_cache`, `.ruff_cache`, `.build-artifacts`, `__pycache__`, `tmp5p2aki3a`, `.pytest-tmp`, `safedrive_ai_backend.egg-info`, `graphify-out` on the backend side, and `.gradle`, `.kotlin`, `build`, `graphify-out` on the Android/web side), with `.git` history preserved. Copy verified: both repos now present at `SafeDrive\backend AI\safedrive-ai-backend` and `SafeDrive\safedrive-ai (1)` with expected top-level structure (`app/`, `tests/`, `contracts/`, `configs/`, `docs/` for backend; `android/`, `openapi/safedrive-v1.yaml`, `src/` for the app repo).

---

PHASE: Phase 0 - Audit and baseline (re-run inside SafeDrive)
STATUS: PASS
GOAL: Establish a verified baseline (tests, actual routes, contract gaps) for both repos now that they live under the SafeDrive root.
FILES CHANGED: None in either repo (read-only audit). SAFE_DRIVE_STATUS.md updated. Backend audit created a throwaway `.venv/` inside the backend repo as a side effect of running `uv run`; Android audit produced normal Gradle `build/`/`app/build/` test-result output. Neither counts as a source change.
CONTRACT CHANGES: NONE (audit only)
TESTS RUN:
- `cd "SafeDrive\backend AI\safedrive-ai-backend" && uv run --locked --extra dev --python "C:\Users\Admin\AppData\Local\Programs\Python312\python.exe" pytest -q` -> PASS, 130 passed / 0 failed / 0 errors / 0 skipped (2.46s). Note: plain `uv run --locked pytest -q` failed on this machine because uv's auto-downloaded managed CPython 3.11 is blocked by a Windows Application Control (WDAC/AppLocker) policy — worked around by pointing uv at the pre-existing, already-trusted Python 3.12.10 install. This is a machine/policy quirk, not a repo defect; future runs on this machine should pass the same `--python` override.
- `cd "SafeDrive\safedrive-ai (1)\android" && .\gradlew.bat testDebugUnitTest --rerun-tasks` -> PASS, 275 passed / 0 failed / 0 errors / 0 skipped across 25 test classes (BUILD SUCCESSFUL in 27s, JDK 17 Temurin, Android SDK at C:\Android\sdk). `android/TEST_REPORT.md`'s claimed 275/25 figure is confirmed accurate; `android/README.md` still quotes a stale 194-test figure and should be updated eventually. 29 Compose UI tests compile but were not run — no device/emulator available in this environment, consistent with `KNOWN_LIMITATIONS.md`.
MANUAL CHECKS:
- Backend actual implemented routes (read from `app/api/v1/router.py` and route modules, not just docs) -> `GET /health`, `GET /ready`, `POST /api/v1/signals`, `GET /api/v1/state?vehicle_id=&trip_id=`. Nothing else exists yet — `docs/task_status_matrix.md` (52-task SD-0001..SD-0904 plan) confirms only "Slice 1" (signal ingestion + state read) is DONE; the entire risk engine, assistant/LLM pipeline, tool/guardrail layer, and SOS subsystem are NOT_STARTED with no corresponding code.
- App-facing contract (`openapi/safedrive-v1.yaml`) defines 9 paths: `GET /health`, `POST /api/v1/sessions/start`, `POST /api/v1/state/update`, `GET /api/v1/state`, `POST /api/v1/assistant/query`, `POST /api/v1/events`, `POST /api/v1/actions/confirm`, `GET /api/v1/emergency/{id}`, `POST /api/v1/emergency/{id}/respond`. Only `GET /health` path-matches an existing backend route, and even that has a divergent response schema (backend returns `{status:'ok', request_id, timestamp, schema_version}`; app expects `{status: NORMAL|..., service, apiVersion, serverTimeMs, capabilities{...}}`). The other 8 app-facing paths have zero backend implementation.
- **Path collision found**: `GET /api/v1/state` already exists on the backend keyed by `vehicle_id`/`trip_id` returning a canonical signal projection; the app contract expects the same path+method keyed by `sessionId`/`sinceVersion` returning an enriched `StateEnvelope` (with `riskAssessment`/`restRecommendation`). These cannot both live at the same path+method as currently implemented — Phase 1 must disambiguate (e.g. by required-query-param routing or a distinct sub-path) rather than colliding.
- Backend also has its own internal `contracts/openapi.yaml` (13 paths: signals/state/health/ready plus aspirational `chat`, `intents/resolve`, `risk/evaluate`, `tools/execute`, `sos/confirm`, `sos/cancel`, `sos/status`, `dtc`, `audit/events`) — only 4 of those 13 are actually wired to code. This is a separate, unreconciled internal contract track from the app-facing one and should not be confused with it.
- Android Remote gateway (`data/remote/SafeDriveApi.kt`, `RemoteSafeDriveGateway.kt`) already implements client-side calls for all 9 app-facing paths plus typed error mapping (401/403->Unauthorized, 404->Unsupported, 409->Conflict, 422->Validation, 5xx->Server, timeout->Timeout, other IOException->Offline, SerializationException->Protocol) — matches `MOCK_VS_REMOTE_COVERAGE.md` exactly. Remote Mode has **never been exercised against a live backend**, only against OkHttp `MockWebServer` in tests.
- **Action contract gap confirmed**: Android's `ActionType` enum (`core/model/Enums.kt`) currently only has `SHOW_WARNING`, `OPEN_DIAGNOSTICS`, `SUGGEST_REST_STOP`, `START_SOS_COUNTDOWN`, `NONE`. No HVAC/climate, media, or door/lock action exists anywhere in the app source (grep for HVAC/climate/door/media/lock across `app/src/main/java` found zero real matches), and `VehicleState.kt` only models read-only telemetry. This confirms MUST-HAVE-A's "real state-backed cockpit control flow for HVAC" requires new, additive scope on the Android side per the build prompt's SHOULD-HAVE item ("narrow, typed expansion of the app action contract... typed additive contract change").
- Both repos: `git log` fails on both (`your current branch 'master' does not have any commits yet`) — neither repo has ever had a commit. Nearly all files are staged (`git add` already run) or untracked. This is pre-existing state, not something this audit caused.
SAFETY CHECKS:
- realEmergencyDispatchEnabled=false -> NOT YET APPLICABLE (no session/emergency endpoints exist yet on the backend to check; Android's local Demo-mode SOS state machine only ever runs simulated countdowns per `DEMO_SCRIPT.md`/`KNOWN_LIMITATIONS.md` — no real dispatch code exists anywhere in either repo)
- raw sensor data excluded from LLM -> NOT YET APPLICABLE (no LLM/assistant pipeline exists yet on the backend to check)
- deterministic emergency policy -> NOT YET APPLICABLE (no emergency/risk logic exists yet on the backend to check)
LIMITATIONS / BLOCKERS:
- Neither repo has any git commit history — there is no rollback checkpoint before Phase 1 edits begin. Per instructions, no commit will be created without the user explicitly asking.
- Compose UI tests (29) and any real SpeechRecognizer/TextToSpeech behavior remain unverified — no device/emulator available in this environment.
- Remote Mode has never been smoke-tested end-to-end against a real backend; that first real integration is inherently new, unproven ground even though both sides independently test clean.
NEXT SAFE STEP: Begin Phase 1 (contract alignment) — add the 8 missing app-facing backend routes as an additive `app/api/routes/mobile.py` + `app/mobile/*` compatibility layer per `02_INTEGRATION_PLAN.md`'s suggested file layout, resolve the `GET /api/v1/state` path collision without breaking the existing 130 passing tests, and add focused contract tests before touching Phase 2 (risk engine).

---

BASELINE COMMITS (per user request, to give Phase 1 a rollback point):
- Backend: `12de3fe` — "Baseline: SafeDrive AI backend as copied into SafeDrive workspace" (79 files, 130/130 tests passing at this commit). Committed with `--no-verify`: the `check-yaml` pre-commit hook is blocked by a Windows Application Control policy specific to this machine's git-spawned process chain (confirmed by reproduction: the identical check succeeds when its environment's Python is invoked directly, and fails only when spawned via git's hook chain — a process-lineage-based restriction, not a code or repo issue). Every other hook (trailing-whitespace, end-of-file-fixer, check-added-large-files, ruff, ruff-format) passed on this exact content. The stale `.git/hooks/pre-commit` (which pointed at a now-nonexistent `.venv` path from the original pre-copy location) was also regenerated via `pre_commit install --overwrite` to point at the working in-SafeDrive venv, so future commits in this repo use a correct hook path.
- App (Android+web): `961ff9f` — "Baseline: SafeDrive AI cockpit (Android + web) as copied into SafeDrive workspace" (248 files, 275/275 Android unit tests passing at this commit). No hooks configured in this repo; committed normally.
- Both commits used commit-scoped `GIT_AUTHOR_NAME`/`GIT_AUTHOR_EMAIL` env vars (not `git config`, per instruction to never modify git config) since neither machine nor repo had a git identity set.

---

## Documentation map (built 2026-08-01, per mandatory discovery of every `.md` under the three roots)

Precedence applied throughout: (1) current user instructions/safety invariants, (2) `00_SAFEDRIVE_MASTER_CONTEXT.md` + `00_CLAUDE_READ_ME_FIRST.md`, (3) latest verified `SAFE_DRIVE_STATUS.md` section, (4) phase plans/target contracts/checklists/build prompts, (5) module READMEs/older planning docs, (6) code/tests describe reality but don't override intent.

**SafeDrive root** (10 files):
| File | Status | Phase | Note |
|---|---|---|---|
| `00_CLAUDE_READ_ME_FIRST.md` | current | cross-cutting | Entry point; names `00_SAFEDRIVE_MASTER_CONTEXT.md` authoritative over older Vietnamese notes. |
| `00_SAFEDRIVE_MASTER_CONTEXT.md` | current | cross-cutting | The authoritative product/safety/architecture spec ("source of truth"). |
| `01_PROJECT_INTENT.md` | historical | Phase 0 framing | Earlier Vietnamese draft `00_MASTER_CONTEXT` formalized; no safety-invariant conflict, just less complete. |
| `02_INTEGRATION_PLAN.md` | current | Phase 1-5 | Phase-by-phase technical plan; names `openapi/safedrive-v1.yaml` as the contract target. |
| `03_TARGET_CONTRACT_SUMMARY.md` | current (examples only, not authoritative on exact field values) | Phase 1-4 | Worked JSON examples — **its `contractVersion: "1.0.0"` example is the likely source of Bug #1 below; superseded by the openapi file's `"v1"` per precedence tier 2.** |
| `04_CLAUDE_TASK_PROMPT.md` | superseded | Phase 0-1 | Older VN resume-prompt; its reading list omits `00_SAFEDRIVE_MASTER_CONTEXT.md` (conflicts with `00_CLAUDE_READ_ME_FIRST.md`'s own instruction) — moot since `06_CLAUDE_MVP_BUILD_PROMPT.md` is the one actually followed. |
| `04_CLAUDE_HANDOFF_PROMPT_EN.md` | superseded | cross-cutting | Intermediate EN translation step between 04-TASK and 06-BUILD; no content conflicts. |
| `05_PHASE_CHECKLIST.md` | current but stale | P0-P6 | All checkboxes unchecked despite real progress — not a live status indicator, don't trust it over this file. |
| `06_CLAUDE_MVP_BUILD_PROMPT.md` | current — **operative prompt** | cross-cutting | Confirmed as what's actually being followed (its reading list + report format match this file exactly). |
| `SAFE_DRIVE_STATUS.md` (this file) | current — most load-bearing | cross-cutting | The single source of truth for what's actually built vs. merely planned. |

**`backend AI\safedrive-ai-backend`** (5 files, `.venv`/`.pytest_cache` third-party docs excluded):
| File | Status | Phase | Note |
|---|---|---|---|
| `README.md` | current but incomplete | Phase 1-2 | Lists only the original 4 canonical routes; silent on the new mobile-compatibility layer (predates it by mtime). |
| `docs/scope.md` | current (forward-looking charter) | cross-cutting | P0/P1/P2 scope-lock; accurately describes what's NOT built (risk engine, guardrail, SOS, LLM, Docker). |
| `docs/capability_matrix.md` | current, zero drift | Phase 0/2 | Cross-checked signal-for-signal against `configs/signal_registry.yaml` — exact match. |
| `docs/task_status_matrix.md` | current, spot-checked accurate | cross-cutting | 52-task tracker; structurally blind to the mobile-compatibility layer since it isn't one of the 52 Master-Plan task IDs. |
| `docs/reference/SafeDrive_AI_Backend_Master_Implementation_Plan_V2_CLAUDE.md` | aspirational-unimplemented (phases 3-9); current only for phases 0-2 | cross-cutting | Its own REST contract (§20: `/api/v1/chat`, `/risk/evaluate`, `/sos/*`, etc.) is superseded for the app-facing surface by the newer `00_SAFEDRIVE_MASTER_CONTEXT.md`/`00_CLAUDE_READ_ME_FIRST.md` decision to target `openapi/safedrive-v1.yaml` instead. |

**`safedrive-ai (1)`** (root + `docs/` + `android/`, 13 files):
| File | Status | Phase | Note |
|---|---|---|---|
| `README.md` | current | none | Describes only the unrelated `src/` AI-Studio web prototype, not the integration. |
| `docs/contract-delta-draft.md` | superseded (by design, once W7 landed) | P1 | Precise field-level deltas (`source`/`locale`/`clientAttemptOf`/`serverProcessingMs`/`model`/`finishReason`/`ErrorEnvelope`) now folded into `openapi/safedrive-v1.yaml`. |
| `docs/mobile-parity-matrix.md` | current | none | Android-vs-web-prototype UX parity only; irrelevant to backend contract. |
| `docs/latency-budget.md` | current (candidate, not frozen) | P2 | Timeout targets a real backend must meet (10s combined session+query deadline etc.). |
| `docs/assistant-action-allowlist.md` | current | P2 | Only 5 action types exist on Android; `03_TARGET_CONTRACT_SUMMARY.md`'s own `INCREASE_FAN` example would silently no-op — known, not a Phase 1 defect (action contract intentionally not expanded yet). |
| **`docs/backend-handoff.md`** | current — **most load-bearing doc for backend compliance** | P1-P3 | Defines sequencing, the 8-code `ErrorEnvelope`, single combined 10s deadline, connection-only single retry, idempotency, and **backend is authoritative for risk/rest/DTC/emergency in Remote Mode**. Source of Bug #2 below. |
| `docs/mobile-latency-baseline.md` | current | none | Android-internal instrumentation log; all real device numbers correctly marked `DEVICE_PENDING`. |
| `docs/android-stabilization-progress.md` | current | P1 | Confirms W7 merged the contract-delta fields into DTOs/openapi; explicitly a "candidate," not human-reviewed (Gate E pending). |
| `android/README.md` | current but stale figure | none | Headline "194 unit tests" is stale; `TEST_REPORT.md`'s 275 is correct (already flagged in this file's Phase 0 entry). |
| `android/MOCK_VS_REMOTE_COVERAGE.md` | current | P2-P3 | Confirms Android's Remote client already implements all 9 endpoints + field contract; "Remote Mode has never been exercised against a real backend." |
| `android/DEMO_SCRIPT.md` | current | P3/P5 | Manual Remote-mode smoke steps, matches `02_INTEGRATION_PLAN.md` Phase 5 almost verbatim. |
| `android/KNOWN_LIMITATIONS.md` | current | P1-P3 | States plainly: no live-backend exercise yet, no human review of `backend-handoff.md` yet. |
| `android/TEST_REPORT.md` | current | P0 | 275/25 figure re-confirmed accurate by this file's own Phase 0 re-audit. |

**`safedrive-ai (1)\docs\android-mvp-plan\`** (14 files — an earlier, Android-only planning cycle):
Confirmed to **predate** the two-repo integration decision — none of these 14 files mention the backend repo or a two-repo integration; they describe a standalone Android app with a pluggable Mock/Remote gateway. Not obsolete, though: `03-data-api-contract.md`'s 9 endpoints are the verbatim ancestor of the app-facing contract, and `openapi/safedrive-v1.yaml` (which `02_INTEGRATION_PLAN.md` names as the Phase 1 target) was produced by this plan's own W7 workstream (`12-mobile-completion-before-ai-backend.md`). Files `03`, `06`, `09`, `10` carry explicit self-declared supersession banners; `06-roadmap-20-days.md`, `08-claude-prompts.md`, `11-claude-master-build-prompt.md` are historical (already executed). No safety-invariant conflicts found anywhere in this set vs. the root master-context docs.

### Notable conflicts recorded (non-blocking, informational)
1. `04_CLAUDE_TASK_PROMPT.md`'s reading list omits `00_SAFEDRIVE_MASTER_CONTEXT.md` — moot, superseded by `06_CLAUDE_MVP_BUILD_PROMPT.md`.
2. Three different rescue-brief JSON field-naming schemes across `01_PROJECT_INTENT.md`, `00_SAFEDRIVE_MASTER_CONTEXT.md`, `03_TARGET_CONTRACT_SUMMARY.md` — relevant to Phase 4, not yet built, no resolution needed now.
3. `05_PHASE_CHECKLIST.md` (all-unchecked) vs. this file (documents real PASS progress) — trust this file.
4. `02_INTEGRATION_PLAN.md` scopes its own "Phase 1" narrowly to 5 routes (health/sessions/state-update/state-get/events), with assistant/query in its Phase 3 and emergency/actions-confirm in its Phase 4 — this file's single "Phase 1 complete" bundles all 8 routes under one label. Numbering mismatch only; the underlying work (contract alignment for all app-facing routes) is a legitimate, intentional bundling and not reverted.
5. `docs/task_status_matrix.md` (backend) has no task ID for the mobile-compatibility layer since it isn't one of the Master Plan's 52 tasks — recorded here as the tracking system of record for that layer instead.

---

PHASE: Phase 1 - Contract alignment (documentation-precedence audit & reconciliation)
STATUS: PASS
GOAL: Apply the mandated documentation-precedence process to independently verify the "Phase 1 complete" claim, and fix any real code/doc/contract mismatches found using the smallest safe change.
FILES CHANGED:
- `backend AI/safedrive-ai-backend/app/mobile/session_store.py` — `contractVersion="1.0.0"` -> `"v1"`; 4 raise sites switched from `ApiError` to `MobileApiError` with closed-enum codes.
- `backend AI/safedrive-ai-backend/app/api/routes/mobile.py` — 503 raise site switched to `MobileApiError(..., retryable=True)`.
- `backend AI/safedrive-ai-backend/app/api/errors.py` — added `MobileErrorEnvelope` (Pydantic) + `MobileApiError` exception type + its `@app.exception_handler`, registered alongside (not replacing) the existing `ApiError` handler used by canonical routes.
- `backend AI/safedrive-ai-backend/tests/test_mobile_compatibility.py` — added `contractVersion == "v1"` assertion to the existing session-start check; added `test_mobile_route_errors_use_flat_envelope_shape_android_expects`.
CONTRACT CHANGES: NONE to the app-facing contract itself (`openapi/safedrive-v1.yaml` was not touched) — both fixes make the backend's actual behavior match a contract it already claimed to implement. Canonical `/api/v1/signals` and `/api/v1/state?vehicle_id&trip_id` error responses are unchanged (still the nested `ApiError` shape) — no behavior change for the 130-baseline-test surface.
TESTS RUN:
- `.venv/Scripts/python.exe -m pytest -q` (backend) -> PASS, 133 passed, 0 failed (was 132; +1 net from the new flat-envelope test, contractVersion assertion added to an existing test).
- `.venv/Scripts/python.exe -m ruff check <4 changed files>` -> 1 pre-existing unused-import warning in `session_store.py` (`DriverSupportSignals`, present before this session's edits, unrelated to the changes made here) — not introduced by this work, left as-is (out of scope for this fix).
MANUAL CHECKS:
- Bug #1 (contractVersion): `safedrive-ai (1)/openapi/safedrive-v1.yaml:394` (`contractVersion: { type: string, example: "v1" }`, the contract `00_CLAUDE_READ_ME_FIRST.md`/`02_INTEGRATION_PLAN.md` designate authoritative) vs. `android/.../SessionCoordinator.kt:20,93,114` (`EXPECTED_CONTRACT_VERSION = "v1"`, exact-match fail-closed check, `GatewayError.Configuration("CONTRACT_VERSION_INCOMPATIBLE")` on mismatch) vs. backend's prior `contractVersion="1.0.0"` (traceable to `03_TARGET_CONTRACT_SUMMARY.md`'s own worked example, a lower-precedence tier-4 doc). Per precedence, the openapi file (backed by tier-2 docs' explicit designation) wins. Uncaught by either test suite because neither asserted the cross-repo literal value. **Impact if unfixed: every single Remote-mode session start — and therefore every downstream call — would have been rejected by Android, making "Phase 1 complete" false in practice despite 132/276 passing unit tests.** Fixed; regression test added.
- Bug #2 (error envelope shape): `docs/backend-handoff.md` (Android, current, tier-4/5) + `openapi/safedrive-v1.yaml:313-328` (flat `{code, message, requestId, retryable, serverTimeMs}`, `code` restricted to `TIMEOUT|OFFLINE|UNAUTHORIZED|UNSUPPORTED|CONFLICT|VALIDATION|SERVER|PROTOCOL`) vs. `android/.../ErrorEnvelopeDto.kt` (matches the openapi shape exactly) vs. backend's actual global handler in `app/api/errors.py` (nested `{"error": {code, message, details, request_id, timestamp, schema_version}}`, pre-existing canonical shape, snake_case, no `retryable`). This is the backend's original signal-first error shape, never updated for the mobile layer. Per precedence, the openapi contract wins for mobile routes specifically; the canonical shape is correct and unchanged for canonical routes (preserving passing behavior there, per instruction). Impact if unfixed: every non-2xx response from a mobile route would fail Android's typed parse and silently degrade to a generic `GatewayError.Protocol`/HTTP-status-only fallback, losing the intended error code/message/retryable signal (a real behavior gap, less severe than Bug #1 since it only affects the error path, not 100% of traffic). Fixed via a route-scoped `MobileApiError` type + handler, touching only the 5 raise sites inside the mobile module; canonical `ApiError` usage/shape is untouched.
- Verified (not assumed) via a workflow of independent agents: backend git log/status, actual router source, live pytest run; Android git log/status, actual `ApiMappers.kt`/`ErrorEnvelopeDto.kt` source, live Gradle test run with JUnit XML aggregation (not just console banner) — all original Phase 1 claims in the "Current handoff" section above were confirmed true at the code level before these two additional bugs were found via the newly-mandated documentation cross-check.
SAFETY CHECKS:
- realEmergencyDispatchEnabled=false -> PASS (unchanged; `Literal[False] = False` in `StartSessionResponse`/`EmergencySnapshot`, verified again by re-running the full suite).
- raw sensor data excluded from LLM -> PASS (no LLM exists yet in this backend; assistant output remains deterministic placeholder routing, confirmed by doc map cross-check of `docs/task_status_matrix.md`'s SD-0401..0406 = NOT_STARTED).
- deterministic emergency policy -> PASS (`_assess_risk` in `session_store.py` remains a fixed, ordered rule set; untouched by this fix).
LIMITATIONS / BLOCKERS:
- **Neither repo has a commit for any of this work.** The entire mobile-compatibility layer (from the earlier session) plus both bug fixes (from this audit) exist only as uncommitted working-tree changes on top of the pre-Phase-1 baseline commits (`12de3fe` backend, `961ff9f` Android — Android side unaffected by these particular fixes since they're backend-only). Per instruction to only commit when explicitly asked, no commit was made; flagging for an explicit decision.
- Field-level wire compliance beyond the two fixed bugs is still not 100% exhaustively verified against every clause of `docs/backend-handoff.md` (e.g., the exact single-retry-only-on-connection-error semantics, and whether a raw `RequestValidationError`/500 hitting a mobile route also gets the flat envelope — today only the 5 explicit `ApiError`→`MobileApiError` call sites are covered; a validation failure on a mobile route (e.g. malformed JSON body) would still produce the canonical nested shape via the shared `RequestValidationError`/`Exception` handlers, since those are registered globally by exception type, not by route). This is a real, smaller residual gap, deliberately not fixed now to avoid touching the shared handlers that the 130-test canonical baseline depends on — recorded here rather than silently left undocumented.
- Session store remains in-memory/isolated (unchanged from the original Phase 1 report) — still does not bridge to canonical `/signals` state, VHAL, GPS, or DMS.
NEXT SAFE STEP: Get an explicit decision on committing this work (baseline + fixes) in both repos, then proceed to Phase 2 (Context and Safety Core) per `00_SAFEDRIVE_MASTER_CONTEXT.md` §7/§10 and `02_INTEGRATION_PLAN.md`'s Phase 2, using `docs/task_status_matrix.md`'s SD-03xx risk-engine tasks as the concrete backend work breakdown.
