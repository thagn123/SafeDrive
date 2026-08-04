# 10 - Continuation Prompt: Finish the SafeDrive MVP Voice Pipeline

You are continuing work on **SafeDrive AI Companion**, an in-car safety assistant with two repos under
this `SafeDrive/` root: `backend AI/safedrive-ai-backend` (FastAPI/Python) and `safedrive-ai (1)/android`
(Kotlin/Android). A previous session left one piece of work mid-attempt and safely reverted. Your job is to
pick it back up.

## Read first, in this order

1. `00_CLAUDE_READ_ME_FIRST.md`
2. `00_SAFEDRIVE_MASTER_CONTEXT.md`
3. `SAFE_DRIVE_STATUS.md` — at least the top (most recent) two or three entries
4. `08_MVP_LLM_ACTIVATION_PLAN.md` §9 — background on the LLM work (already done, don't redo it)
5. **`09_MVP_HANDOFF.md` — the critical one.** It documents exactly what's unfinished, what's already been
   tried and ruled out, and the current safe state. Do not start work before reading it in full.

## Mission

Finish (or make an explicit, justified decision to abandon) the voice-pipeline upgrade described in
`09_MVP_HANDOFF.md` §2: better Vietnamese speech recognition (the user's original complaint — "voice bị lỗi
rất nhiều") and, if that succeeds, a better Vietnamese TTS voice. The LLM-quality half of the original
complaint is already fixed — don't touch `app/mobile/llm.py`, `session_store.py`, or `assistant.py` unless
you find a real regression.

## Current state (verify this yourself before trusting it — things may have changed)

- `SafeDriveContainer.kt` uses `AndroidTextToSpeechController` (Android's built-in TTS). **This is the safe,
  working state.** The app builds, installs, and runs without crashing.
- A full sherpa-onnx-based replacement (`SherpaOnnxTtsController.kt` + Vietnamese Piper TTS model assets +
  native `.so` libs) exists in the repo but is **not wired in**, because it crashes the app 100% of the time
  on the test device (Xiaomi `24129PN74G`, MIUI/HyperOS) — see the exact crash signature and the two
  remediation attempts already ruled out in `09_MVP_HANDOFF.md` §2. **Do not repeat those two attempts.**
- STT (Vietnamese command-capture ASR + English keyword-spotter for the "Hey SafeDrive" wake phrase) was
  researched and models were chosen, but never implemented — this is genuinely unstarted work, not broken
  work.

## Rules for this session

- **Verify before acting on any claim in the handoff docs.** They describe state as of 2026-08-04; check
  `git status`/actual file contents before assuming anything is still true.
- **Don't blindly retry what's already been ruled out.** If you want to try another sherpa-onnx build or
  linking variant, that's fine (it's listed as a legitimate next step) — but if it also crashes with the
  same `FORTIFY: pthread_mutex_lock` / `hwuiTask` signature, stop guessing and either get a symbolized
  native backtrace or escalate to the user instead of trying a third blind variant.
- **Native/NDK work needs a real prerequisite check.** Android NDK and CMake were not installed in the dev
  environment as of this handoff. If your plan requires building native code from source (e.g. whisper.cpp),
  check for NDK/CMake first and tell the user plainly if installing them is required — that's a real,
  sizeable download, not a detail to skip past.
- **Ask before taking on new cost/risk the user hasn't approved for this specific step**: a new third-party
  account, a new paid API, a large new native dependency, or anything that meaningfully grows APK size
  again after the last attempt already failed. The user has been consistently willing to make these calls
  when asked clearly — don't guess on their behalf a second time in the same area.
- **Always leave the app in a working state between attempts.** If you wire in an experimental engine to
  test it, and it doesn't pan out by the time you need to stop, revert `SafeDriveContainer.kt` back to the
  known-safe controller before ending the session, the same way the previous session did.
- Keep the two-tier safety architecture intact: the LLM/voice engine choice must never affect *what* the app
  says for HVAC/DTC/fatigue/SOS — those stay fully deterministic regardless of which STT/TTS engine is used.
- Only commit to git if the user explicitly asks.

## Definition of done for this piece of work

Either:
- **(a)** A real STT and/or TTS upgrade is wired in, builds, installs, and has been confirmed **not to
  crash across at least 3 launches** on the real test device (same isolation-test method as
  `09_MVP_HANDOFF.md` §2 describes), with the user having verified actual voice/audio quality themselves
  (you cannot judge audio quality yourself — say so if that verification hasn't happened yet); or
- **(b)** A clear, justified decision (made with the user, not unilaterally) to stop pursuing an on-device
  neural engine and instead improve Android's built-in `SpeechRecognizer`/`TextToSpeech` configuration, with
  that work done instead.

When you reach either outcome, update `SAFE_DRIVE_STATUS.md` with a new dated entry (matching its existing
chronological format) and update or supersede `09_MVP_HANDOFF.md` so the next session isn't reading stale
"still unfinished" state once it no longer is.

## Reporting

Report progress in Vietnamese to match how this project's user prefers to communicate. Keep it concrete: cite
actual test counts, actual crash/no-crash results from the device, not assumptions. If you hit the same
crash signature again, say so plainly rather than declaring success on a build that merely compiled.
