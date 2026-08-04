package vn.edu.haui.hvs.safedrive.voice

import android.content.ContextWrapper
import android.speech.SpeechRecognizer
import com.google.common.truth.Truth.assertThat
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Test
import vn.edu.haui.hvs.safedrive.core.common.AppClock
import vn.edu.haui.hvs.safedrive.core.model.VoiceState
import vn.edu.haui.hvs.safedrive.core.testing.FakeClock
import vn.edu.haui.hvs.safedrive.core.testing.FakeMainThreadExecutor
import vn.edu.haui.hvs.safedrive.core.testing.FakePreferencesRepository
import vn.edu.haui.hvs.safedrive.core.testing.FakeSpeechRecognizerFactory
import vn.edu.haui.hvs.safedrive.domain.repository.AppPreferences

/**
 * Regression coverage for remediation item 3 ("SpeechRecognizer đang có nguy cơ bị điều khiển ngoài
 * main thread"): every platform recognizer call ([SpeechRecognizerFactory.create],
 * [PlatformSpeechRecognizer.startListening]/`stopListening`/`cancel`/`destroy`) must go through
 * [MainThreadExecutor], never run directly on whichever thread called a public [VoiceController]
 * method — including [AndroidSpeechRecognizerController]'s internal preferences collector, which runs
 * on the application's background scope (`Dispatchers.Default` in production, see
 * `SafeDriveApplication`), not necessarily the same thread a Compose/ViewModel action would call
 * `startListening`/`cancel` from.
 *
 * Uses [FakeMainThreadExecutor] (which only *queues* dispatched work instead of running it) and
 * [FakeSpeechRecognizerFactory] (a pure-Kotlin double for the otherwise real-device/Robolectric-only
 * `android.speech.SpeechRecognizer`) so this class's threading/generation logic is exercised without
 * either.
 */
class AndroidSpeechRecognizerControllerTest {

    private val clock = FakeClock(1_000L)

    private fun buildController(
        executor: FakeMainThreadExecutor,
        factory: FakeSpeechRecognizerFactory,
        scope: CoroutineScope,
        preferences: FakePreferencesRepository = FakePreferencesRepository(),
    ): AndroidSpeechRecognizerController = AndroidSpeechRecognizerController(
        context = ContextWrapper(null),
        preferencesRepository = preferences,
        clock = clock,
        externalScope = scope,
        recognizerFactory = factory,
        mainThreadExecutor = executor,
    )

    @Test
    fun `startListening never touches the platform recognizer until the executor actually runs the dispatched block`() = runTest {
        val executor = FakeMainThreadExecutor()
        val factory = FakeSpeechRecognizerFactory()
        val controller = buildController(executor, factory, scope = backgroundScope)

        controller.startListening("cockpit")

        assertThat(factory.createCallCount).isEqualTo(0) // still only queued, not yet run
        executor.runAll()
        assertThat(factory.createCallCount).isEqualTo(1)
        assertThat(factory.createdRecognizers.single().startListeningCallCount).isEqualTo(1)
        assertThat(factory.createdRecognizers.single().lastLanguageTag).isEqualTo("vi-VN")
    }

    @Test
    fun `startListening uses the short (command) silence timeout, unlike the ambient wake-word listener`() = runTest {
        val executor = FakeMainThreadExecutor()
        val factory = FakeSpeechRecognizerFactory()
        val controller = buildController(executor, factory, scope = backgroundScope)

        controller.startListening("cockpit")
        executor.runAll()

        val recognizer = factory.createdRecognizers.single()
        assertThat(recognizer.lastCompleteSilenceMs).isEqualTo(COMPLETE_SILENCE_MS)
        assertThat(recognizer.lastPossiblyCompleteSilenceMs).isEqualTo(POSSIBLY_COMPLETE_SILENCE_MS)
    }

    @Test
    fun `onBeforeListen runs synchronously before the recognizer is created, closing the ambient wake-word race`() = runTest {
        val executor = FakeMainThreadExecutor()
        val factory = FakeSpeechRecognizerFactory()
        val controller = buildController(executor, factory, scope = backgroundScope)
        var hookRanBeforeCreate = false
        controller.onBeforeListen = { hookRanBeforeCreate = factory.createCallCount == 0 }

        controller.startListening("cockpit")

        assertThat(hookRanBeforeCreate).isTrue()
        executor.runAll()
        assertThat(factory.createCallCount).isEqualTo(1)
    }

    @Test
    fun `finishListening and cancel always go through the executor, never touch the recognizer inline`() = runTest {
        val executor = FakeMainThreadExecutor()
        val factory = FakeSpeechRecognizerFactory()
        val controller = buildController(executor, factory, scope = backgroundScope)
        controller.startListening("cockpit")
        executor.runAll()

        controller.finishListening()
        assertThat(factory.createdRecognizers.single().stopListeningCallCount).isEqualTo(0) // queued only
        executor.runAll()
        assertThat(factory.createdRecognizers.single().stopListeningCallCount).isEqualTo(1)

        controller.cancel()
        assertThat(factory.createdRecognizers.single().destroyCallCount).isEqualTo(0) // queued only
        executor.runAll()
        assertThat(factory.createdRecognizers.single().destroyCallCount).isEqualTo(1)
    }

    @Test
    fun `toggling wakeWordEnabled off from a real background thread still dispatches cancel through the executor, never inline on that thread`() {
        val executor = FakeMainThreadExecutor()
        val factory = FakeSpeechRecognizerFactory()
        val preferences = FakePreferencesRepository(AppPreferences(wakeWordEnabled = true))
        // A genuine background thread (Dispatchers.Default), not the virtual/single-threaded test
        // dispatcher — this is what actually exercises the cross-thread race the bug report described,
        // matching AssistantTurnCoordinatorTest's real-thread concurrency test for the same reason.
        val realScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val controller = buildController(executor, factory, scope = realScope, preferences = preferences)
            controller.startListening("cockpit")
            executor.runAll()
            assertThat(factory.createdRecognizers.single().destroyCallCount).isEqualTo(0)

            realScope.launch { preferences.setWakeWordEnabled(false) }
            val deadline = System.currentTimeMillis() + 5_000
            while (executor.pendingCount == 0 && System.currentTimeMillis() < deadline) Thread.sleep(10)

            // cancel() ran on the background collector's thread, but only far enough to queue the
            // actual recognizer teardown — it never touched the fake recognizer directly.
            assertThat(factory.createdRecognizers.single().destroyCallCount).isEqualTo(0)
            executor.runAll()
            assertThat(factory.createdRecognizers.single().destroyCallCount).isEqualTo(1)
            assertThat(controller.state.value.state).isEqualTo(VoiceState.DISABLED)
        } finally {
            realScope.cancel()
        }
    }

    @Test
    fun `a stale recognizer callback delivered after cancel does not resurrect state`() = runTest {
        val executor = FakeMainThreadExecutor()
        val factory = FakeSpeechRecognizerFactory()
        val controller = buildController(executor, factory, scope = backgroundScope)

        controller.startListening("cockpit")
        executor.runAll()
        val staleListener = factory.lastListener!!
        staleListener.onReadyForSpeech(null)
        assertThat(controller.state.value.state).isEqualTo(VoiceState.LISTENING)

        controller.cancel()
        executor.runAll()
        assertThat(controller.state.value.state).isEqualTo(VoiceState.IDLE)

        // The old (now-superseded) listener instance still fires a late callback from the recognizer
        // that was just torn down — the generation guard must drop it, not resurrect LISTENING/partial
        // transcript state.
        staleListener.onPartialResults(null)
        assertThat(controller.state.value.state).isEqualTo(VoiceState.IDLE)
        assertThat(controller.state.value.partialTranscript).isEmpty()
    }

    @Test
    fun `a new startListening supersedes the previous generation before the old dispatched block runs`() = runTest {
        val executor = FakeMainThreadExecutor()
        val factory = FakeSpeechRecognizerFactory()
        val controller = buildController(executor, factory, scope = backgroundScope)

        controller.startListening("cockpit") // queues gen 1's create/startListening
        controller.startListening("assistant") // queues gen 2's create/startListening before gen 1 ran at all
        executor.runAll()

        // gen 1's dispatched block detects it was superseded and never creates a recognizer for it.
        assertThat(factory.createCallCount).isEqualTo(1)
        assertThat(factory.createdRecognizers.single().startListeningCallCount).isEqualTo(1)
    }

    @Test
    fun `startListening checks recognition availability before creating a recognizer`() = runTest {
        val executor = FakeMainThreadExecutor()
        val factory = FakeSpeechRecognizerFactory(available = false)
        val controller = buildController(executor, factory, scope = backgroundScope)

        controller.startListening("cockpit")
        assertThat(factory.isRecognitionAvailableCallCount).isEqualTo(0) // still only queued, not yet run
        executor.runAll()

        assertThat(factory.isRecognitionAvailableCallCount).isEqualTo(1)
        assertThat(factory.createCallCount).isEqualTo(0)
        assertThat(controller.state.value.state).isEqualTo(VoiceState.ERROR)
    }

    // --- Blocker 3: isRecognitionAvailable() must be confined to the main-thread executor exactly like
    // create/start/stop/cancel/destroy — before this fix it ran directly on whichever thread called
    // startListening(). ---

    @Test
    fun `isRecognitionAvailable is only ever called from inside the main-thread executor, never on the caller's thread`() {
        val executor = FakeMainThreadExecutor()
        val factory = FakeSpeechRecognizerFactory()
        val realScope = realThreadScope()
        try {
            val controller = buildController(executor, factory, scope = realScope)
            val callerThread = Thread { controller.startListening("cockpit") }
            callerThread.start()
            callerThread.join(5_000)

            // Queued, not yet run — the caller's own thread must never have touched the factory at all.
            assertThat(factory.isRecognitionAvailableCallCount).isEqualTo(0)

            executor.runAll() // runs on *this* (the test) thread, simulating the main Looper
            assertThat(factory.isRecognitionAvailableCallCount).isEqualTo(1)
            assertThat(factory.isRecognitionAvailableCallingThreads.single()).isNotEqualTo(callerThread)
        } finally {
            realScope.cancel()
        }
    }

    // Note: `android.os.Bundle` (as used by the real `RecognitionListener.onResults(Bundle)`) cannot
    // carry real data in a plain JVM unit test — `putStringArrayList`/`getStringArrayList` are stubbed
    // by AGP's `isReturnDefaultValues` to no-ops (confirmed empirically: a populated Bundle always
    // reads back `null` here without Robolectric/instrumentation), so a genuinely non-blank transcript
    // can never reach `onResults` in this environment. The two tests below verify the actual regression
    // (fresh, per-session `pendingCaptureTimings`) by reading that private field directly via
    // reflection instead — end-to-end proof that a correct `VoiceCaptureTimings` reaches a real
    // `VoiceInputEvent` is instead covered by `VoiceAssistantCoordinatorTest`'s
    // "mic-recognizer capture timings ... reach the recorded metrics (W4)" test (via `FakeVoiceController`,
    // which injects `VoiceCaptureTimings` directly, sidestepping Bundle entirely) plus device/instrumented QA.

    @Test
    fun `startListening initializes fresh capture timings`() = runTest {
        val executor = FakeMainThreadExecutor()
        val factory = FakeSpeechRecognizerFactory()
        val controller = buildController(executor, factory, scope = backgroundScope)

        controller.startListening("cockpit")
        executor.runAll() // activeSession/timings are only populated once the confined block actually runs

        assertThat(controller.activeSessionMicRequestedAtMsForTest()).isEqualTo(clock.nowMs())
    }

    // --- Independent re-audit follow-up (second pass), latency-honesty note: micRequestedAtMs must mean
    // "the instant the caller asked for the microphone", never "the instant mainThreadExecutor got
    // around to draining its queue" — the two can differ on a real, busy main Looper. The test above
    // doesn't distinguish the two readings because nothing advances the clock between the call and
    // executor.runAll(); this test explicitly does, and would fail on a version of beginListening() that
    // reads clock.nowMs() *inside* the dispatched block instead of capturing it on the caller's thread
    // first. ---

    @Test
    fun `micRequestedAtMs reflects the moment startListening was actually called, not the moment the executor later drains its queue`() =
        runTest {
            val executor = FakeMainThreadExecutor()
            val factory = FakeSpeechRecognizerFactory()
            val controller = buildController(executor, factory, scope = backgroundScope)

            controller.startListening("cockpit")
            val callTimeMs = clock.nowMs()
            // Simulates a busy main Looper: time moves on before the queued block actually runs. On the
            // old (pre-fix) code, reading clock.nowMs() *inside* the confined block would report this
            // later value instead, silently hiding the real dispatch delay and reporting a falsely-fast
            // mic latency.
            clock.advanceBy(250L)
            executor.runAll()

            assertThat(controller.activeSessionMicRequestedAtMsForTest()).isEqualTo(callTimeMs)
            assertThat(controller.activeSessionMicRequestedAtMsForTest()).isNotEqualTo(clock.nowMs())
        }

    @Test
    fun `two consecutive startListening sessions never reuse the previous session's capture timings`() = runTest {
        val executor = FakeMainThreadExecutor()
        val factory = FakeSpeechRecognizerFactory()
        val controller = buildController(executor, factory, scope = backgroundScope)

        controller.startListening("cockpit")
        executor.runAll()
        val firstMicRequestedAtMs = controller.activeSessionMicRequestedAtMsForTest()
        assertThat(firstMicRequestedAtMs).isNotNull()

        clock.advanceBy(500L)
        controller.startListening("cockpit") // a second session — must reset timings, not carry gen 1's
        executor.runAll()

        val secondMicRequestedAtMs = controller.activeSessionMicRequestedAtMsForTest()
        assertThat(secondMicRequestedAtMs).isEqualTo(clock.nowMs()) // the *second* session's timing
        assertThat(secondMicRequestedAtMs).isNotEqualTo(firstMicRequestedAtMs)
    }

    @Test
    fun `startListening only increments generation once per call`() = runTest {
        val executor = FakeMainThreadExecutor()
        val factory = FakeSpeechRecognizerFactory()
        val controller = buildController(executor, factory, scope = backgroundScope)

        controller.startListening("cockpit")
        executor.runAll() // generation is published on VoiceUiState only once the confined block runs

        assertThat(controller.state.value.generation).isEqualTo(1L)
    }

    @Test
    fun `a stale generation's RecognitionListener callback arriving after a new session started never modifies the new session's capture timings`() = runTest {
        val executor = FakeMainThreadExecutor()
        val factory = FakeSpeechRecognizerFactory()
        val controller = buildController(executor, factory, scope = backgroundScope)

        controller.startListening("cockpit") // gen 1
        executor.runAll()
        val staleListener = factory.lastListener!!
        staleListener.onReadyForSpeech(null) // legitimately updates gen 1's own timings
        val gen1TimingsAfterReady = controller.activeSessionMicRequestedAtMsForTest()

        clock.advanceBy(1_000L)
        controller.startListening("assistant") // gen 2 supersedes
        executor.runAll()
        val gen2MicRequestedAtMs = controller.activeSessionMicRequestedAtMsForTest()
        assertThat(gen2MicRequestedAtMs).isNotEqualTo(gen1TimingsAfterReady)

        // The stale (gen 1) listener instance fires again — the generation guard must drop it entirely,
        // never touching gen 2's ActiveSession/timings (ownership is tagged, not just "whatever is
        // currently in the field").
        staleListener.onReadyForSpeech(null)
        assertThat(controller.activeSessionMicRequestedAtMsForTest()).isEqualTo(gen2MicRequestedAtMs)
    }

    @Test
    fun `startListening called after shutdown never creates a recognizer and never leaves state stuck at WAKE_WORD_DETECTED`() = runTest {
        val executor = FakeMainThreadExecutor()
        val factory = FakeSpeechRecognizerFactory()
        val controller = buildController(executor, factory, scope = backgroundScope)

        controller.shutdown()
        executor.runAll()
        val stateBeforeShutdownAttempt = controller.state.value.state

        controller.startListening("cockpit")
        executor.runAll()

        assertThat(factory.createCallCount).isEqualTo(0)
        // Before this fix, WAKE_WORD_DETECTED was published *before* dispatching to the executor, so a
        // start-after-shutdown call would leave the UI stuck showing "preparing" forever even though no
        // recognizer would ever actually be created for it.
        assertThat(controller.state.value.state).isNotEqualTo(VoiceState.WAKE_WORD_DETECTED)
        assertThat(controller.state.value.state).isEqualTo(stateBeforeShutdownAttempt)
    }

    @Test
    fun `permission denied never calls recognition availability or create`() = runTest {
        val executor = FakeMainThreadExecutor()
        val factory = FakeSpeechRecognizerFactory()
        val controller = AndroidSpeechRecognizerController(
            context = object : ContextWrapper(null) {
                override fun checkPermission(permission: String, pid: Int, uid: Int): Int =
                    android.content.pm.PackageManager.PERMISSION_DENIED
            },
            preferencesRepository = FakePreferencesRepository(),
            clock = clock,
            externalScope = backgroundScope,
            recognizerFactory = factory,
            mainThreadExecutor = executor,
        )

        controller.startListening("cockpit")

        assertThat(factory.createCallCount).isEqualTo(0)
        assertThat(controller.state.value.state).isEqualTo(VoiceState.ERROR)
    }

    // --- Independent re-audit follow-up, item 2: a dispatched cancel/finish block must never act on a
    // *newer* generation's recognizer than the one it targeted, even if it happens to run after the
    // newer session already replaced the active recognizer (AndroidMainThreadExecutor's immediate-
    // execution-when-already-on-main-thread path can reorder relative to an already-queued block). ---

    @Test
    fun `a cancel block queued for generation 1 that runs after generation 2 already started does not destroy generation 2's recognizer`() = runTest {
        val executor = FakeMainThreadExecutor()
        val factory = FakeSpeechRecognizerFactory()
        val controller = buildController(executor, factory, scope = backgroundScope)

        controller.startListening("cockpit") // gen 1
        executor.runAll()
        val gen1Recognizer = factory.createdRecognizers.single()

        controller.cancel() // targets gen 1, dispatched block queued first
        val staleCancelBlock = executor.pollPending()!! // hold it — don't run it yet
        controller.startListening("assistant") // gen 2 supersedes before the stale cancel block ever runs
        executor.runAll() // runs gen 2's start block (the only thing left queued)
        staleCancelBlock() // now run the stale cancel block, out of order, after gen 2 is already active

        val gen2Recognizer = factory.createdRecognizers[1]
        assertThat(gen2Recognizer.destroyCallCount).isEqualTo(0) // must never destroy the new session
        assertThat(gen2Recognizer.startListeningCallCount).isEqualTo(1)
        // gen 1 was already torn down by the new start's own destroy-before-replace, not by this cancel.
        assertThat(gen1Recognizer.cancelCallCount).isEqualTo(0)
    }

    @Test
    fun `a finishListening block queued for generation 1 that runs after generation 2 already started does not stop generation 2's recognizer`() = runTest {
        val executor = FakeMainThreadExecutor()
        val factory = FakeSpeechRecognizerFactory()
        val controller = buildController(executor, factory, scope = backgroundScope)

        controller.startListening("cockpit") // gen 1
        executor.runAll()

        controller.finishListening() // targets gen 1, dispatched block queued first
        val staleFinishBlock = executor.pollPending()!! // hold it — don't run it yet
        controller.startListening("assistant") // gen 2 supersedes before the stale finish block ever runs
        executor.runAll() // runs gen 2's start block (the only thing left queued)
        staleFinishBlock() // now run the stale finish block, out of order, after gen 2 is already active

        val gen1Recognizer = factory.createdRecognizers[0]
        val gen2Recognizer = factory.createdRecognizers[1]
        assertThat(gen2Recognizer.stopListeningCallCount).isEqualTo(0) // must never stop the new session
        assertThat(gen1Recognizer.stopListeningCallCount).isEqualTo(0) // the stale block targeted gen 1 but never ran in time
    }

    @Test
    fun `finishListening in normal FIFO order (no reordering) correctly stops the session that was actually active when it runs`() = runTest {
        val executor = FakeMainThreadExecutor()
        val factory = FakeSpeechRecognizerFactory()
        val controller = buildController(executor, factory, scope = backgroundScope)
        controller.startListening("cockpit")
        executor.runAll()

        controller.finishListening()
        executor.runAll()

        assertThat(factory.createdRecognizers.single().stopListeningCallCount).isEqualTo(1)
    }

    @Test
    fun `shutdown is terminal - a startListening block already queued before shutdown never creates a recognizer`() = runTest {
        val executor = FakeMainThreadExecutor()
        val factory = FakeSpeechRecognizerFactory()
        val controller = buildController(executor, factory, scope = backgroundScope)

        controller.startListening("cockpit") // queues gen 1's create/start, not yet run
        controller.shutdown() // queues a destroy of whatever is active (nothing yet) and flips isShutdown
        executor.runAll()

        // Even though gen 1's start block was queued *before* shutdown and is still the current
        // generation, shutdown must win: no recognizer is ever created after shutdown was requested.
        assertThat(factory.createCallCount).isEqualTo(0)
    }

    // --- Independent re-audit follow-up, eighth pass: clearProcessingState/reassignProcessingOwner are
    // now generation-keyed (closing a pre-claim race — see VoiceController.clearProcessingState's and
    // VoiceAssistantCoordinatorTest's KDoc for the full mechanism). `onResults(Bundle)` cannot carry a
    // genuine non-blank transcript in this plain-JVM environment (see the class note above), so this
    // controller can never actually be driven into PROCESSING here — the *positive* match case (a
    // generation-matching clear/reassign actually taking effect) is instead fully proven at the
    // VoiceAssistantCoordinator + FakeVoiceController pipeline level, which implements the identical
    // conditional logic. What *is* directly provable here is that neither method can ever resurrect or
    // corrupt state while genuinely idle. ---

    @Test
    fun `clearProcessingState is a safe no-op when not currently PROCESSING, regardless of generation`() = runTest {
        val executor = FakeMainThreadExecutor()
        val factory = FakeSpeechRecognizerFactory()
        val controller = buildController(executor, factory, scope = backgroundScope)

        controller.clearProcessingState(expectedGeneration = 999L) // nothing is PROCESSING yet
        assertThat(controller.state.value.state).isEqualTo(VoiceState.IDLE)
    }

    @Test
    fun `reassignProcessingOwner is a safe no-op when not currently PROCESSING`() = runTest {
        val executor = FakeMainThreadExecutor()
        val factory = FakeSpeechRecognizerFactory()
        val controller = buildController(executor, factory, scope = backgroundScope)

        controller.reassignProcessingOwner(expectedCurrentGeneration = 999L, newOwnerGeneration = 123L)
        assertThat(controller.state.value.state).isEqualTo(VoiceState.IDLE)
        assertThat(controller.state.value.generation).isEqualTo(0L) // untouched
    }

    @Test
    fun `concurrent start and cancel from two real threads never leave two active recognizers`() {
        val executor = FakeMainThreadExecutor()
        val factory = FakeSpeechRecognizerFactory()
        val realScope = realThreadScope()
        try {
            val controller = buildController(executor, factory, scope = realScope)

            val threadCount = 16
            val barrier = java.util.concurrent.CyclicBarrier(threadCount)
            val threads = (1..threadCount).map { i ->
                Thread {
                    barrier.await()
                    if (i % 2 == 0) controller.startListening("cockpit") else controller.cancel()
                }
            }
            threads.forEach { it.start() }
            threads.forEach { it.join(10_000) }
            val testThread = Thread.currentThread()
            executor.runAll()

            // Whatever the final interleaving, at most one recognizer instance is ever "active" — never
            // a state where two distinct instances both think they're the current session.
            val destroyedOrStale = factory.createdRecognizers.count { it.destroyCallCount > 0 }
            val survivingCount = factory.createdRecognizers.size - destroyedOrStale
            assertThat(survivingCount).isAtMost(1)

            // isRecognitionAvailable() — like every other platform operation — was only ever invoked
            // from inside the executor's runAll() (this thread), never directly from any of the 16
            // spawned caller threads.
            assertThat(factory.isRecognitionAvailableCallingThreads.all { it == testThread }).isTrue()
        } finally {
            realScope.cancel()
        }
    }

    // --- Independent re-audit follow-up, ninth pass (P1-2) and tenth pass (blocker 1): every
    // RecognitionListener callback's own "check generation, then mutate _state/activeSession/_events
    // accordingly" was check-then-act, not atomic — cancel()/shutdown()/a newer beginListening() all bump
    // generation on the *caller's* thread, never confined to mainThreadExecutor (only the platform
    // teardown/creation is), so a bump could happen on a different thread while a callback was already
    // partway through its own body, having already read generation and found itself still current.
    // callbackLock now serializes every such read-then-mutate against every generation-bumping operation —
    // including, since the tenth pass, beginListening's own generation *mint* itself (not merely its later
    // dispatched check-then-act), closing a gap the ninth-pass fix left open. The two tests below force the
    // actual interleaving deterministically via a blocking AppClock double (an already-existing constructor
    // seam, not a new production-only hook) that pauses a callback *inside* its own locked section, then
    // prove — structurally, not by timing luck — that the concurrent invalidating operation cannot
    // complete until the callback releases the lock: on the pre-(ninth-pass)-fix implementation cancel()
    // has nothing to wait on; on the pre-(tenth-pass)-fix implementation a newer start's own generation
    // mint has nothing to wait on either (only its later-dispatched block was ever gated) — either way the
    // concurrent operation completes instantly instead of blocking. ---

    @Test
    fun `cancel cannot bump generation while a stale callback holds the callback lock`() {
        val executor = FakeMainThreadExecutor()
        val factory = FakeSpeechRecognizerFactory()
        val realScope = realThreadScope()
        try {
            // Arms exactly once: beginListening() *also* calls clock.nowMs() (for requestedAtMs), so a
            // clock that always blocks would incorrectly pause the setup startListening() call below
            // too, not just the intended onPartialResults call. armed is flipped false by whichever call
            // consumes it — only onPartialResults's call is ever armed for in this test.
            val armed = java.util.concurrent.atomic.AtomicBoolean(false)
            val reached = CountDownLatch(1)
            val proceed = CountDownLatch(1)
            val blockingClock = object : AppClock {
                override fun nowMs(): Long {
                    if (armed.compareAndSet(true, false)) {
                        reached.countDown()
                        proceed.await(5, TimeUnit.SECONDS)
                    }
                    return clock.nowMs()
                }
            }
            val controller = AndroidSpeechRecognizerController(
                context = ContextWrapper(null),
                preferencesRepository = FakePreferencesRepository(),
                clock = blockingClock,
                externalScope = realScope,
                recognizerFactory = factory,
                mainThreadExecutor = executor,
            )
            controller.startListening("cockpit") // not armed yet — passes straight through
            executor.runAll()
            val listener = factory.lastListener!!

            armed.set(true)
            val callbackThread = Thread { listener.onPartialResults(null) }
            callbackThread.start()
            // Confirms onPartialResults has passed its own generation check and is now blocked *inside*
            // callbackLock, mid-flight, at clock.nowMs().
            assertThat(reached.await(5, TimeUnit.SECONDS)).isTrue()

            val cancelDone = CountDownLatch(1)
            val cancelThread = Thread {
                controller.cancel()
                cancelDone.countDown()
            }
            cancelThread.start()

            // Structural, not a timing guess: cancel() needs the exact lock onPartialResults is holding,
            // so on the fixed implementation it cannot complete no matter how long we wait here. On the
            // pre-fix implementation there is no shared lock, so cancel() completes essentially instantly.
            assertThat(cancelDone.await(300, TimeUnit.MILLISECONDS)).isFalse()

            proceed.countDown() // let onPartialResults finish and release the lock
            callbackThread.join(5_000)
            assertThat(cancelDone.await(5, TimeUnit.SECONDS)).isTrue() // only now does cancel proceed
            cancelThread.join(5_000)

            executor.runAll()
            assertThat(controller.state.value.state).isEqualTo(VoiceState.IDLE)
        } finally {
            realScope.cancel()
        }
    }

    @Test
    fun `a newer start's own generation mint cannot happen while a stale callback holds the callback lock`() {
        val executor = FakeMainThreadExecutor()
        val factory = FakeSpeechRecognizerFactory()
        val realScope = realThreadScope()
        try {
            // Arms exactly once — see the cancel() test above for why an always-blocking clock would be
            // wrong here: both the setup startListening("cockpit") *and* the concurrent
            // startListening("assistant") below also call clock.nowMs() for requestedAtMs, and only the
            // callback thread's call is meant to pause.
            val armed = java.util.concurrent.atomic.AtomicBoolean(false)
            val reached = CountDownLatch(1)
            val proceed = CountDownLatch(1)
            val blockingClock = object : AppClock {
                override fun nowMs(): Long {
                    if (armed.compareAndSet(true, false)) {
                        reached.countDown()
                        proceed.await(5, TimeUnit.SECONDS)
                    }
                    return clock.nowMs()
                }
            }
            val controller = AndroidSpeechRecognizerController(
                context = ContextWrapper(null),
                preferencesRepository = FakePreferencesRepository(),
                clock = blockingClock,
                externalScope = realScope,
                recognizerFactory = factory,
                mainThreadExecutor = executor,
            )
            controller.startListening("cockpit") // gen 1, not armed yet — passes straight through
            executor.runAll()
            val gen1Listener = factory.lastListener!!

            armed.set(true)
            val callbackThread = Thread { gen1Listener.onPartialResults(null) }
            callbackThread.start()
            // Confirms gen 1's callback has passed its own generation check and is now blocked *inside*
            // callbackLock, mid-flight, at the armed clock call.
            assertThat(reached.await(5, TimeUnit.SECONDS)).isTrue()

            // The mint itself (generation.incrementAndGet()) now requires callbackLock too (tenth pass,
            // closing blocker 1) — so startListening() must be invoked from its own real thread: on the
            // fixed implementation this call blocks synchronously, not merely its later-dispatched block.
            val startDone = CountDownLatch(1)
            val startThread = Thread {
                controller.startListening("assistant")
                startDone.countDown()
            }
            startThread.start()

            // Structural, not a timing guess (see the cancel() test above for why): gen 2 cannot even be
            // *minted* — let alone dispatched — while gen 1's callback holds callbackLock. Nothing has even
            // been queued on the executor yet, since beginListening's dispatch happens only after the mint
            // returns.
            assertThat(startDone.await(300, TimeUnit.MILLISECONDS)).isFalse()
            assertThat(executor.pendingCount).isEqualTo(0)
            assertThat(factory.createCallCount).isEqualTo(1) // still only gen 1

            proceed.countDown() // let gen 1's callback finish and release callbackLock
            callbackThread.join(5_000)
            assertThat(startDone.await(5, TimeUnit.SECONDS)).isTrue() // only now can gen 2 be minted
            startThread.join(5_000)
            assertThat(startThread.isAlive).isFalse()

            executor.runAll() // now run gen 2's dispatched block
            assertThat(factory.createCallCount).isEqualTo(2)
            assertThat(controller.state.value.state).isEqualTo(VoiceState.WAKE_WORD_DETECTED)
            assertThat(controller.state.value.generation).isEqualTo(2L)
        } finally {
            realScope.cancel()
        }
    }

    @Test
    fun `reverse order - a newer start that wins first leaves every generation-1 callback a no-op`() = runTest {
        val executor = FakeMainThreadExecutor()
        val factory = FakeSpeechRecognizerFactory()
        val controller = buildController(executor, factory, scope = backgroundScope)
        controller.startListening("cockpit") // gen 1
        executor.runAll()
        val gen1Listener = factory.lastListener!!

        // gen 2 wins first, fully — mint, dispatch and executor run all complete before gen 1's stale
        // listener ever fires again.
        controller.startListening("assistant") // gen 2
        executor.runAll()
        val gen2StateAfterStart = controller.state.value
        assertThat(gen2StateAfterStart.generation).isEqualTo(2L)
        assertThat(gen2StateAfterStart.state).isEqualTo(VoiceState.WAKE_WORD_DETECTED)

        // Every generation-1 callback is now a stale no-op: none of them may touch _state/activeSession for
        // generation 2. onResults's own text-extraction path cannot carry a non-blank transcript in this
        // environment (Bundle stubbing, see the class note above), so "emits no event" for that callback
        // specifically is proven by code inspection (the identical `gen != generation.get()` guard, inside
        // the identical callbackLock, guards the enqueue too) rather than an independent runtime assertion
        // here; onReadyForSpeech/onPartialResults need no Bundle data and are asserted directly below.
        gen1Listener.onReadyForSpeech(null)
        assertThat(controller.state.value).isEqualTo(gen2StateAfterStart)

        gen1Listener.onPartialResults(null)
        assertThat(controller.state.value.generation).isEqualTo(2L)
        assertThat(controller.state.value.partialTranscript).isEmpty()

        gen1Listener.onResults(null) // blank-transcript branch only, per the Bundle limitation
        assertThat(controller.state.value).isEqualTo(gen2StateAfterStart)
    }

    @Test
    fun `shutdown bumps generation too, so a stale onReadyForSpeech cannot resurrect LISTENING`() = runTest {
        val executor = FakeMainThreadExecutor()
        val factory = FakeSpeechRecognizerFactory()
        val controller = buildController(executor, factory, scope = backgroundScope)

        controller.startListening("cockpit") // gen 1, state = WAKE_WORD_DETECTED
        executor.runAll()
        val staleListener = factory.lastListener!!

        controller.shutdown()
        executor.runAll()
        assertThat(controller.state.value.state).isEqualTo(VoiceState.WAKE_WORD_DETECTED) // shutdown itself never touches _state

        // Before this fix, shutdown() never bumped generation, so this stale (not superseded by any new
        // session, only by shutdown) callback would still pass its own check and transition to LISTENING —
        // resurrecting a "live" indication for a controller that has already been torn down.
        staleListener.onReadyForSpeech(null)
        assertThat(controller.state.value.state).isNotEqualTo(VoiceState.LISTENING)
        assertThat(controller.state.value.state).isEqualTo(VoiceState.WAKE_WORD_DETECTED)
    }

    // Basic regression coverage for the remaining callbacks (item 8) — these confirm the pre-existing
    // generation guard still correctly rejects a callback once cancel()'s bump has *already* fully
    // completed (a plain sequential ordering, not a forced interleaving — cancel()'s bump has always run
    // unguarded on the caller's thread, so this property predates callbackLock; the deep tests above are
    // what actually prove the new, interleaved case).

    @Test
    fun `a stale onError delivered after cancel does not resurrect ERROR`() = runTest {
        val executor = FakeMainThreadExecutor()
        val factory = FakeSpeechRecognizerFactory()
        val controller = buildController(executor, factory, scope = backgroundScope)

        controller.startListening("cockpit")
        executor.runAll()
        val staleListener = factory.lastListener!!

        controller.cancel()
        executor.runAll()
        assertThat(controller.state.value.state).isEqualTo(VoiceState.IDLE)

        staleListener.onError(SpeechRecognizer.ERROR_NETWORK)
        assertThat(controller.state.value.state).isEqualTo(VoiceState.IDLE)
    }

    @Test
    fun `a stale onResults delivered after a newer start does not touch the new generation's state`() = runTest {
        val executor = FakeMainThreadExecutor()
        val factory = FakeSpeechRecognizerFactory()
        val controller = buildController(executor, factory, scope = backgroundScope)

        controller.startListening("cockpit")
        executor.runAll()
        val staleListener = factory.lastListener!!

        controller.startListening("assistant") // gen 2 supersedes
        executor.runAll()
        val gen2Generation = controller.state.value.generation
        assertThat(gen2Generation).isEqualTo(2L)

        // The stale gen 1 listener fires onResults (blank transcript, per this environment's Bundle
        // limitation noted above) — the generation guard must drop it entirely, never touching gen 2's
        // state, not even the blank-transcript branch's own IDLE/cleared-transcript mutation.
        staleListener.onResults(null)
        assertThat(controller.state.value.generation).isEqualTo(gen2Generation)
        assertThat(controller.state.value.state).isEqualTo(VoiceState.WAKE_WORD_DETECTED)
    }
}

private fun realThreadScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

/** Reads the controller's private `activeSession.timings.micRequestedAtMs` via reflection — see the
 * KDoc above the tests that use this for why `onResults(Bundle)` cannot be used instead in this
 * environment. `activeSession` (and its `timings`) is only ever populated once the dispatched
 * `beginListening` block has actually run (blocker 3: main-thread confinement) — callers must run the
 * executor first. */
private fun AndroidSpeechRecognizerController.activeSessionMicRequestedAtMsForTest(): Long? {
    val sessionField = AndroidSpeechRecognizerController::class.java.getDeclaredField("activeSession")
    sessionField.isAccessible = true
    val session = sessionField.get(this) ?: return null
    val timingsField = session.javaClass.getDeclaredField("timings")
    timingsField.isAccessible = true
    val timings = timingsField.get(session) ?: return null
    val micField = timings.javaClass.getDeclaredField("micRequestedAtMs")
    micField.isAccessible = true
    return micField.get(timings) as Long
}
