# 09 - MVP Handoff: Unfinished Work (Voice Pipeline Upgrade)

**Date:** 2026-08-04
**Purpose:** A self-contained handoff for the next AI session (or human) picking up SafeDrive's MVP work.
Read this after `00_CLAUDE_READ_ME_FIRST.md` / `00_SAFEDRIVE_MASTER_CONTEXT.md` / `SAFE_DRIVE_STATUS.md`
(that file's "documentation map" section explains precedence across all `.md` files in this repo — this
doc doesn't repeat it). This file exists specifically because a large piece of work was left **mid-attempt
and reverted to a safe state** — read §2 before touching `voice/` on the Android side, or you will likely
repeat two already-ruled-out fixes.

---

## 1. What's done and verified (don't re-litigate this)

- **Backend `/api/v1` contract**: fully implemented, 9 routes, matches Android's `SafeDriveApi.kt` exactly.
  No work needed here.
- **Real local LLM (Ollama, `qwen2.5:7b-instruct-q4_K_M`)**: wired in as `OllamaNarrator` (wording only, 3
  safety-cleared routes) + `OllamaIntentClassifier` (advisory reclassification for text the deterministic
  router can't match, closed 6-label set) + `EmergencyLLMReasoner` (advisory second opinion only). Backend
  **184/184 tests passing**, `ruff` clean. Full design/rationale/live-verification evidence is in
  `08_MVP_LLM_ACTIVATION_PLAN.md` §9 — read that before changing anything in `app/mobile/llm.py`,
  `app/mobile/session_store.py`, or `app/mobile/assistant.py`.
- **"LLM feels dumb" complaint**: fixed this session by improving the narrator/classifier system prompts
  (in-car persona, explicit permission to weave in real context) and enriching the `companion.conversation`
  template with real grounded facts (speed/cabin/duration). Verified live against the real model: an
  off-keyword DTC question correctly got reclassified and answered with the real fault code. Don't redo this
  — it's done and tested.
- **Android app**: 310/310 JVM unit tests passing (31 classes). Cockpit/Assistant/Simulator/Diagnostics/
  Settings screens, Demo↔Remote gateway switch, wake-word background listening via Android's own
  `SpeechRecognizer` (see `voice/SpeechRecognizerWakeWordDetector.kt` and `voice/WakeWordSessionCoordinator.kt`
  — this is a *different* system from the one this handoff is about). **2026-08-04**: six real bugs found
  and fixed — see `SAFE_DRIVE_STATUS.md`'s top entry for full details on each:
  1. Mic-ownership race between the ambient detector and command capture.
  2. Ambient restart/silence-timeout mistuning (too aggressive, then too loose; settled on a moderate
     explicit 2.5s/3s).
  3. No self-heal after a real (non-silent) recognizer error — the detector just stayed dead.
  4. A residual restart collision (the detector's own self-triggered restart could still race a fresh
     command-capture session).
  5. The wake phrase depended on an English on-device language pack this test device doesn't have
     (Vietnamese's pack works fine) — fixed by switching the wake phrase to Vietnamese, **"Mai ơi"**,
     recognized in `vi-VN` (see `voice/SpeechRecognizerWakeWordDetector.kt`), not by requiring a Settings
     fix.
  6. A pre-existing Safety Guardian bug (`SafeDriveContainer.kt`) was re-speaking its proactive TTS warning
     roughly once a second whenever its risk signature flickered — since the ambient listener never
     listens while TTS speaks, this silently starved wake-word detection of any real chance to run. Fixed
     with an escalation-aware 30s cooldown on the TTS half only (chat notice unaffected; a genuine
     severity increase still always speaks immediately).
  The wake phrase is no longer "Hey SafeDrive" anywhere in this codebase; don't reintroduce English
  recognition here without re-confirming this device's language pack situation.
- **Live device verification (2026-08-03, same physical device as §2 below)**: Remote Mode session start,
  heartbeat, and assistant queries all confirmed working end-to-end against the real backend, including the
  newly-reclassified/narrated replies.

---

## 2. What's unfinished: the voice-pipeline upgrade attempt (start here)

### The ask
User reported two things in the same message: (a) voice recognition ("STT") is still frequently wrong and
they want to change the engine, (b) LLM answers felt unintelligent (**this half is done, see §1**). After
back-and-forth (`AskUserQuestion`), the user confirmed: upgrade **both STT and TTS**, prioritizing a
**lightweight/fast** model over maximum accuracy.

### The chosen design (researched, not guessed — keep this reasoning if you resume it)
Picked **sherpa-onnx** (`k2-fsa/sherpa-onnx`, Apache-2.0) as the single engine for both STT and TTS, because
it has an official Android Kotlin API + prebuilt native binaries (no NDK build required) and covers ASR, TTS,
and keyword-spotting in one dependency. Specific models selected, all confirmed to exist and downloaded:

| Purpose | Model | Size | Why |
| --- | --- | --- | --- |
| TTS (Vietnamese) | `vits-piper-vi_VN-vivos-x_low-int8` | ~11MB (pruned) | Lightest Piper-based Vietnamese voice tier |
| Command-capture STT (Vietnamese) | `sherpa-onnx-zipformer-vi-30M-int8-2026-02-09` | ~34MB | Small (30M params), int8, trained on ~6000h Vietnamese |
| Wake-word ("Hey SafeDrive") | `sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01-mobile` | ~6MB (pruned) | **"Hey SafeDrive" is an English phrase** — a dedicated English keyword-spotter is lighter and more purpose-built than running a full Vietnamese streaming ASR continuously just to catch two English words. Not yet wired up (see below). |

License note: sherpa-onnx itself is Apache-2.0. The TTS model is Piper-derived but run through sherpa-onnx's
own (non-GPL) inference engine, not the GPL-3.0 `piper1-gpl` codebase itself — reasonable, but the exact
per-voice model license (from `rhasspy/piper-voices`) was **not independently re-verified**; do that before
any real submission if this path is resumed.

### What was actually built
- `app/src/main/java/com/k2fsa/sherpa/onnx/Tts.kt` — upstream Kotlin API source, copied verbatim (package
  name must stay `com.k2fsa.sherpa.onnx` — it's hardcoded in the native JNI symbol names).
- `app/src/main/java/vn/edu/haui/hvs/safedrive/voice/SherpaOnnxTtsController.kt` — full `TtsController`
  implementation: lazy-ish native `OfflineTts` init (currently gated behind a 1.5s `delay()` — see below),
  espeak-ng-data extraction from assets to `filesDir` (espeak can't read through `AssetManager`), audio
  playback via `AudioTrack` in `PCM_FLOAT`/`MODE_STATIC`. **Code compiles and the APK builds successfully.**
- `app/src/main/assets/sherpa/tts/` — the pruned Vietnamese Piper model + trimmed `espeak-ng-data` (only
  `vi`/`en` language files kept; original was 18MB, pruned to fit in ~11MB total).
- `app/src/main/jniLibs/{arm64-v8a,x86_64}/libsherpa-onnx-jni.so` — **currently the static-link-onnxruntime
  variant** (single self-contained .so per ABI; the original dynamic-link variant with 4 separate .so files
  was tried first and removed — see the crash history below for why).
- `app/build.gradle.kts` — added `ndk { abiFilters += listOf("arm64-v8a", "x86_64") }` inside `defaultConfig`.
- STT (command-capture ASR + wake-word KWS) was **never implemented** — only researched and downloaded (see
  `sherpa-onnx-zipformer-vi-30M-int8-2026-02-09` and the KWS model above; both models sit in this session's
  scratchpad, not yet copied into the Android project's assets). Work stopped after TTS alone proved broken.

### Current wiring state — **the app is currently SAFE and WORKING**
`SafeDriveContainer.kt` currently constructs **`AndroidTextToSpeechController`** (the original, Android
built-in engine), **not** `SherpaOnnxTtsController`. This is intentional — do not "fix" this back to
`SherpaOnnxTtsController` without reading the crash section below first. All the sherpa-onnx files above
still exist in the repo, fully built, just unused/dormant.

### The blocking bug: 100% reproducible native crash
Wiring `SherpaOnnxTtsController` into the container and launching the app on the real test device
(Xiaomi `24129PN74G` / `dada_global`, MIUI/HyperOS, arm64-v8a, the same device used throughout this
project) crashes the app **every single time**, immediately on launch, before any TTS call is ever made by
the user. Exact signature:

```
F libc: FORTIFY: pthread_mutex_lock called on a destroyed mutex (0x...)
F libc: Fatal signal 6 (SIGABRT), code -1 (SI_QUEUE) in tid ... (hwuiTaskN), pid ... (i.hvs.safedrive)
```

This is a native crash inside **Android's own HWUI render-thread pool** (`hwuiTaskN`), not in any Kotlin
code — it happens purely from constructing the native `OfflineTts` engine (which loads a ~20MB onnxruntime
binary and does real init work on a background coroutine).

**Two remediation attempts, both cleanly ruled out (3/3 reproducible crash each time), so don't repeat them:**
1. **Delayed init** — added a `delay(1_500)` inside `SherpaOnnxTtsController`'s init coroutine so native
   loading happens well after the very first Compose/HWUI frame, in case it was a cold-start timing race.
   Still crashed 3/3. (The `delay(1_500)` is still in the code — harmless, but didn't fix anything on its
   own.)
2. **Static-link-onnxruntime variant** — swapped the native library for the
   `sherpa-onnx-v1.13.4-android-static-link-onnxruntime.tar.bz2` build (one self-contained `.so` per ABI
   instead of 4 separate ones sharing symbols dynamically), in case it was a dynamic-linking/symbol
   interaction. Still crashed 3/3. **This is the variant currently sitting in `jniLibs/`.**

**Isolation method used** (repeat this if you resume debugging, it's cheap and decisive): swap the one
import+construction line in `SafeDriveContainer.kt` between `AndroidTextToSpeechController` and
`SherpaOnnxTtsController`, `./gradlew.bat assembleDebug`, `adb install -r`, then 3x:
`adb shell am force-stop ...` → `adb logcat -c -b crash` → `adb shell am start -n .../.MainActivity` →
`sleep 4` → `adb logcat -b crash -d | grep -c "Fatal signal"` + `adb shell ps -A | grep safedrive`.
Zero crashes + process alive = working; any nonzero count = still broken.

**What was NOT tried, and why:**
- **whisper.cpp** (doesn't depend on onnxruntime at all, so plausibly avoids this exact bug) — requires
  building native C++ from source via NDK + CMake. **Neither is installed in this dev environment**
  (`/c/Android/sdk/ndk` doesn't exist, `cmake` isn't on PATH). This is a real prerequisite, not a quick
  detour, if this path is chosen.
- **Getting a symbolized native backtrace** (`ndk-stack`, or a proper debugger) to actually see which
  library/function aborts — would turn this from "two plausible guesses, both wrong" into an actual root
  cause. Wasn't available/attempted in this environment.
- **Testing on a second device or an emulator** to check whether this is device/ROM-specific (very possible
  given how many MIUI-specific restrictions this project has already hit — see `input tap`/`pm grant`
  blocks noted elsewhere) or a genuine cross-device sherpa-onnx bug.

### Recommended next steps (pick one; don't blindly retry the two ruled-out fixes)
1. **Cheapest next diagnostic**: try yet another sherpa-onnx release version (not just the linking variant)
   — e.g. an older `v1.1x.x` tag — in case this specific `v1.13.4` build has a regression. Same swap-and-3x
   test loop as above.
2. **Most likely to actually work, but real setup cost**: install Android NDK + CMake, build whisper.cpp
   from source for the STT half (command capture + a phonetically-encoded "Hey SafeDrive" wake phrase via
   its own keyword mechanism, or keep using Android's existing wake-word system unchanged). Does not touch
   TTS at all — would need a separate decision for that half.
3. **Lowest risk, matches this project's own precedent** (see the Picovoice→SpeechRecognizer pivot earlier
   in this project's history): abandon the on-device-neural-engine path entirely, and instead spend the
   effort tuning Android's own `SpeechRecognizer`/`TextToSpeech` configuration (recognition hints, locale,
   `EXTRA_PREFER_OFFLINE`, etc.) — no native code, no APK size growth, no crash risk, but a lower accuracy
   ceiling.
4. Whichever path: **always leave `SafeDriveContainer.kt` on `AndroidTextToSpeechController` between
   attempts** so the app stays demoable if you run out of time mid-session.

---

## 3. Standing invariants — do not regress these while working on any of the above

- `realEmergencyDispatchEnabled` is always `false` (backend schema + Android mapping, multiple sites).
- The LLM (narrator/classifier/reasoner) never decides risk level, HVAC target, DTC severity, or an SOS
  transition — those stay 100% deterministic. A voice-engine swap must not change *what* gets said for
  HVAC/DTC/fatigue/SOS routes, only *how the app hears the user and speaks back*.
- No raw audio/video/CAN data is ever sent to the LLM — irrelevant to STT/TTS engine choice, but don't
  accidentally wire microphone audio into an LLM call while restructuring the voice pipeline.
- Only commit to git when explicitly asked — nothing in this session was committed.

## 4. Quick reference

- Backend: `cd "backend AI/safedrive-ai-backend" && .venv/Scripts/python.exe -m pytest -q` (expect 184
  passed). Start with real LLM: see `08_MVP_LLM_ACTIVATION_PLAN.md` §3 for the exact env vars.
- Android: `cd "safedrive-ai (1)/android" && ./gradlew.bat testDebugUnitTest --rerun-tasks` (expect 310
  passed, 31 classes) and `./gradlew.bat assembleDebug` for the APK.
- Test device: `adb devices -l` → Xiaomi `24129PN74G` (`dada_global`), arm64-v8a, MIUI/HyperOS. Known MIUI
  restrictions on this device: `adb shell input tap` and `adb shell pm grant` both throw `SecurityException`
  (no `INJECT_EVENTS`/`GRANT_RUNTIME_PERMISSIONS`) — UI can be screenshotted (`adb shell screencap`) and
  driven via `am start`, but never tapped programmatically; permission grants need the user's own hand.
- `SAFE_DRIVE_STATUS.md` has the full chronological history if you need more context than this file gives.
