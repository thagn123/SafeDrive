package vn.edu.haui.hvs.safedrive.voice

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test
import vn.edu.haui.hvs.safedrive.core.model.VoiceState
import vn.edu.haui.hvs.safedrive.core.testing.FakeTtsController
import vn.edu.haui.hvs.safedrive.core.testing.FakeVoiceController
import vn.edu.haui.hvs.safedrive.core.testing.FakeWakeWordDetector
import vn.edu.haui.hvs.safedrive.domain.repository.TtsState

/**
 * Covers the orchestration this class replaces `VoiceAssistantCoordinator.resumeWakeListeningAfterResponse`
 * with: reacting to [VoiceController.state]/[vn.edu.haui.hvs.safedrive.domain.repository.TtsController.state]
 * directly, rather than to a specific turn's outcome, so ambient listening resumes after *every* path back
 * to idle — not only a successfully-routed transcript.
 *
 * Uses [UnconfinedTestDispatcher] (matching [vn.edu.haui.hvs.safedrive.domain.usecase.VoiceAssistantCoordinatorTest]'s
 * `MainDispatcherRule` default) so the coordinator's `combine(...).onEach{}.launchIn(scope)` collector — a
 * genuinely reactive loop over two `StateFlow`s, not a one-shot suspend call — actually runs its initial
 * collection synchronously on `start()`/state-change instead of sitting queued until an explicit pump.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WakeWordSessionCoordinatorTest {

    private fun buildCoordinator(
        voice: FakeVoiceController,
        tts: FakeTtsController,
        detector: FakeWakeWordDetector,
        scope: kotlinx.coroutines.CoroutineScope,
        onDetectorError: (String) -> Unit = {},
    ) = WakeWordSessionCoordinator(voice, tts, detector, scope, onDetectorError)

    @Test
    fun `detector starts as soon as start() runs while idle`() = runTest(UnconfinedTestDispatcher()) {
        val voice = FakeVoiceController()
        val tts = FakeTtsController()
        val detector = FakeWakeWordDetector()
        val coordinator = buildCoordinator(voice, tts, detector, backgroundScope)

        coordinator.start()
        advanceUntilIdle()

        assertThat(detector.isListening).isTrue()
    }

    @Test
    fun `detector stops the instant the voice controller enters a non-idle state`() = runTest(UnconfinedTestDispatcher()) {
        val voice = FakeVoiceController()
        val tts = FakeTtsController()
        val detector = FakeWakeWordDetector()
        val coordinator = buildCoordinator(voice, tts, detector, backgroundScope)
        coordinator.start()
        advanceUntilIdle()
        assertThat(detector.isListening).isTrue()

        voice.setState(VoiceUiState(state = VoiceState.LISTENING))
        advanceUntilIdle()

        assertThat(detector.isListening).isFalse()
    }

    @Test
    fun `wake-word detection stops the detector then starts background command capture`() = runTest(UnconfinedTestDispatcher()) {
        val voice = FakeVoiceController()
        val tts = FakeTtsController()
        val detector = FakeWakeWordDetector()
        val coordinator = buildCoordinator(voice, tts, detector, backgroundScope)
        coordinator.start()
        advanceUntilIdle()

        detector.emitWakeWordDetected()

        assertThat(detector.isListening).isFalse()
        assertThat(voice.startListeningCallCount).isEqualTo(1)
        assertThat(voice.lastScreen).isEqualTo("background")
    }

    @Test
    fun `detector resumes once a successful command turn returns the voice controller to IDLE`() = runTest(UnconfinedTestDispatcher()) {
        val voice = FakeVoiceController()
        val tts = FakeTtsController()
        val detector = FakeWakeWordDetector()
        val coordinator = buildCoordinator(voice, tts, detector, backgroundScope)
        coordinator.start()
        advanceUntilIdle()

        voice.setState(VoiceUiState(state = VoiceState.PROCESSING))
        advanceUntilIdle()
        assertThat(detector.isListening).isFalse()

        voice.setState(VoiceUiState(state = VoiceState.IDLE))
        advanceUntilIdle()
        assertThat(detector.isListening).isTrue()
    }

    @Test
    fun `detector also resumes after a blank, timed-out or errored command capture — the gap the old rearm never closed`() =
        runTest(UnconfinedTestDispatcher()) {
            val voice = FakeVoiceController()
            val tts = FakeTtsController()
            val detector = FakeWakeWordDetector()
            val coordinator = buildCoordinator(voice, tts, detector, backgroundScope)
            coordinator.start()
            advanceUntilIdle()

            voice.setState(VoiceUiState(state = VoiceState.PROCESSING))
            advanceUntilIdle()
            assertThat(detector.isListening).isFalse()

            // A recognizer error/timeout/blank result never reaches AssistantTurnCoordinator at all — the
            // old resumeWakeListeningAfterResponse() only rearmed a *successfully routed* transcript, so
            // this path used to leave ambient listening permanently off.
            voice.setState(VoiceUiState(state = VoiceState.ERROR, errorMessage = "Không nhận diện được câu nói, vui lòng thử lại"))
            advanceUntilIdle()

            assertThat(detector.isListening).isTrue()
        }

    @Test
    fun `a real detector error is retried after a delay, instead of staying silently deaf forever`() =
        runTest(UnconfinedTestDispatcher()) {
            val voice = FakeVoiceController()
            val tts = FakeTtsController()
            val detector = FakeWakeWordDetector()
            var reportedError: String? = null
            val coordinator = buildCoordinator(voice, tts, detector, backgroundScope) { reportedError = it }
            coordinator.start()
            advanceUntilIdle()
            assertThat(detector.startCallCount).isEqualTo(1)

            // Neither VoiceController nor TtsController state changes here -- this is exactly the gap a
            // live device was observed hitting: nothing in the reactive combine() loop would ever notice
            // the detector died on its own.
            detector.emitError("Lỗi mạng khi nhận diện giọng nói")

            assertThat(reportedError).isEqualTo("Lỗi mạng khi nhận diện giọng nói")
            assertThat(detector.startCallCount).isEqualTo(1) // not yet retried

            advanceTimeBy(10_001L) // fast-forwards past the virtual-time retry delay
            advanceUntilIdle()

            assertThat(detector.startCallCount).isEqualTo(2)
            assertThat(detector.isListening).isTrue()
        }

    @Test
    fun `a pending error retry is dropped if the state stops being listenable before it fires`() =
        runTest(UnconfinedTestDispatcher()) {
            val voice = FakeVoiceController()
            val tts = FakeTtsController()
            val detector = FakeWakeWordDetector()
            val coordinator = buildCoordinator(voice, tts, detector, backgroundScope)
            coordinator.start()
            advanceUntilIdle()

            detector.emitError("Lỗi mạng khi nhận diện giọng nói")
            // A manual mic tap (or any other real transition away from IDLE/ERROR) arrives before the
            // retry delay elapses -- the stale retry must not override this newer, legitimate decision.
            voice.setState(VoiceUiState(state = VoiceState.LISTENING))
            advanceUntilIdle()

            assertThat(detector.startCallCount).isEqualTo(1) // never retried
            assertThat(detector.isListening).isFalse()
        }

    @Test
    fun `detector never starts while TTS is speaking, even if the voice controller is idle`() = runTest(UnconfinedTestDispatcher()) {
        val voice = FakeVoiceController()
        val tts = FakeTtsController(initial = TtsState.SPEAKING)
        val detector = FakeWakeWordDetector()
        val coordinator = buildCoordinator(voice, tts, detector, backgroundScope)

        coordinator.start()
        advanceUntilIdle()
        assertThat(detector.isListening).isFalse()

        tts.setState(TtsState.READY)
        advanceUntilIdle()
        assertThat(detector.isListening).isTrue()
    }

    @Test
    fun `stop cancels the collector so a later voice-state change no longer restarts the detector`() = runTest(UnconfinedTestDispatcher()) {
        val voice = FakeVoiceController()
        val tts = FakeTtsController()
        val detector = FakeWakeWordDetector()
        val coordinator = buildCoordinator(voice, tts, detector, backgroundScope)
        coordinator.start()
        advanceUntilIdle()
        assertThat(detector.isListening).isTrue()

        coordinator.stop()
        assertThat(detector.isListening).isFalse()

        voice.setState(VoiceUiState(state = VoiceState.LISTENING))
        voice.setState(VoiceUiState(state = VoiceState.IDLE))
        advanceUntilIdle()

        assertThat(detector.isListening).isFalse()
    }

    @Test
    fun `stop is idempotent`() = runTest(UnconfinedTestDispatcher()) {
        val voice = FakeVoiceController()
        val tts = FakeTtsController()
        val detector = FakeWakeWordDetector()
        val coordinator = buildCoordinator(voice, tts, detector, backgroundScope)
        coordinator.start()
        advanceUntilIdle()

        coordinator.stop()
        coordinator.stop()

        assertThat(detector.isListening).isFalse()
    }
}
