package vn.edu.haui.hvs.safedrive.voice

import android.speech.SpeechRecognizer
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import vn.edu.haui.hvs.safedrive.core.testing.FakeMainThreadExecutor
import vn.edu.haui.hvs.safedrive.core.testing.FakeSpeechRecognizerFactory

class SpeechRecognizerWakeWordDetectorTest {

    @Test
    fun `containsWakePhrase matches Mai oi case-insensitively and tolerates a dropped diacritic`() {
        assertThat(containsWakePhrase("Mai ơi, tôi đã lái xe bao lâu rồi?")).isTrue()
        assertThat(containsWakePhrase("MAI ƠI")).isTrue()
        assertThat(containsWakePhrase("mai oi")).isTrue() // diacritic on "ơi" dropped -- still matches
        assertThat(containsWakePhrase("Tôi muốn bật điều hòa")).isFalse()
    }

    @Test
    fun `containsWakePhrase requires the full vocative, not bare mai alone`() {
        // "mai" alone ("tomorrow") is an extremely common Vietnamese word -- matching it bare would
        // false-trigger constantly in ordinary conversation, unlike the distinctive "mai ơi" vocative.
        assertThat(containsWakePhrase("Để mai tính")).isFalse()
        assertThat(containsWakePhrase("Ngày mai trời mưa")).isFalse()
    }

    @Test
    fun `start listens in vi-VN since the device's English language pack is unavailable`() {
        val executor = FakeMainThreadExecutor()
        val factory = FakeSpeechRecognizerFactory()
        val detector = SpeechRecognizerWakeWordDetector(factory, executor)

        detector.start(onWakeWordDetected = {}, onError = {})
        executor.runAll()

        assertThat(factory.createdRecognizers.single().lastLanguageTag).isEqualTo("vi-VN")
    }

    @Test
    fun `start requests the moderate ambient silence timeout, not the command tuning and not unbounded`() {
        val executor = FakeMainThreadExecutor()
        val factory = FakeSpeechRecognizerFactory()
        val detector = SpeechRecognizerWakeWordDetector(factory, executor)

        detector.start(onWakeWordDetected = {}, onError = {})
        executor.runAll()

        val recognizer = factory.createdRecognizers.single()
        assertThat(recognizer.lastCompleteSilenceMs).isEqualTo(AMBIENT_COMPLETE_SILENCE_MS)
        assertThat(recognizer.lastPossiblyCompleteSilenceMs).isEqualTo(AMBIENT_POSSIBLY_COMPLETE_SILENCE_MS)
    }

    @Test
    fun `start is a no-op when already listening`() {
        val executor = FakeMainThreadExecutor()
        val factory = FakeSpeechRecognizerFactory()
        val detector = SpeechRecognizerWakeWordDetector(factory, executor)

        detector.start(onWakeWordDetected = {}, onError = {})
        executor.runAll()
        detector.start(onWakeWordDetected = {}, onError = {})
        executor.runAll()

        assertThat(factory.createCallCount).isEqualTo(1)
    }

    @Test
    fun `unavailable recognition surfaces onError and never creates a recognizer`() {
        val executor = FakeMainThreadExecutor()
        val factory = FakeSpeechRecognizerFactory(available = false)
        val detector = SpeechRecognizerWakeWordDetector(factory, executor)
        var errorMessage: String? = null

        detector.start(onWakeWordDetected = {}, onError = { errorMessage = it })
        executor.runAll()

        assertThat(factory.createCallCount).isEqualTo(0)
        assertThat(errorMessage).isNotNull()
    }

    @Test
    fun `a blank recognition result (no wake phrase, per this environment's Bundle limitation) restarts listening`() {
        val executor = FakeMainThreadExecutor()
        val factory = FakeSpeechRecognizerFactory()
        val detector = SpeechRecognizerWakeWordDetector(factory, executor)
        var detected = false

        detector.start(onWakeWordDetected = { detected = true }, onError = {})
        executor.runAll()
        val firstRecognizer = factory.createdRecognizers.single()

        factory.lastListener!!.onResults(null)
        executor.runAll()

        // Blank transcript never matches the wake phrase — the detector keeps listening by starting a
        // fresh session, and never reports a detection.
        assertThat(detected).isFalse()
        assertThat(factory.createCallCount).isEqualTo(2)
        assertThat(firstRecognizer.destroyCallCount).isEqualTo(1)
    }

    @Test
    fun `ERROR_NO_MATCH and ERROR_SPEECH_TIMEOUT restart listening silently, without surfacing onError`() {
        val executor = FakeMainThreadExecutor()
        val factory = FakeSpeechRecognizerFactory()
        val detector = SpeechRecognizerWakeWordDetector(factory, executor)
        var errorCallCount = 0

        detector.start(onWakeWordDetected = {}, onError = { errorCallCount++ })
        executor.runAll()

        factory.lastListener!!.onError(SpeechRecognizer.ERROR_NO_MATCH)
        executor.runAll()
        factory.lastListener!!.onError(SpeechRecognizer.ERROR_SPEECH_TIMEOUT)
        executor.runAll()

        assertThat(errorCallCount).isEqualTo(0)
        assertThat(factory.createCallCount).isEqualTo(3) // initial + one restart per silent error
    }

    @Test
    fun `a real error (e_g_ network) stops listening and surfaces onError, without auto-restarting`() {
        val executor = FakeMainThreadExecutor()
        val factory = FakeSpeechRecognizerFactory()
        val detector = SpeechRecognizerWakeWordDetector(factory, executor)
        var errorMessage: String? = null

        detector.start(onWakeWordDetected = {}, onError = { errorMessage = it })
        executor.runAll()

        factory.lastListener!!.onError(SpeechRecognizer.ERROR_NETWORK)
        executor.runAll()

        assertThat(errorMessage).isNotNull()
        assertThat(factory.createCallCount).isEqualTo(1) // no restart after a real error
    }

    @Test
    fun `a self-triggered restart is skipped, not errored, when a command-capture session is active`() {
        val executor = FakeMainThreadExecutor()
        val factory = FakeSpeechRecognizerFactory()
        var commandCaptureActive = false
        val detector = SpeechRecognizerWakeWordDetector(factory, executor) { commandCaptureActive }
        var errorMessage: String? = null

        detector.start(onWakeWordDetected = {}, onError = { errorMessage = it })
        executor.runAll()
        assertThat(factory.createCallCount).isEqualTo(1)

        // A manual mic tap claimed the mic in between this session ending and its own restart callback
        // running -- the exact race observed on the physical test device.
        commandCaptureActive = true
        factory.lastListener!!.onError(SpeechRecognizer.ERROR_NO_MATCH)
        executor.runAll()

        assertThat(factory.createCallCount).isEqualTo(1) // restart skipped, no second recognizer created
        assertThat(errorMessage).isNull() // skipped silently -- this is routine, not a failure

        // Once the command session ends, the *next* legitimate restart proceeds normally.
        commandCaptureActive = false
        factory.lastListener!!.onError(SpeechRecognizer.ERROR_NO_MATCH)
        executor.runAll()

        assertThat(factory.createCallCount).isEqualTo(2)
    }

    @Test
    fun `stop cancels and destroys the active recognizer`() {
        val executor = FakeMainThreadExecutor()
        val factory = FakeSpeechRecognizerFactory()
        val detector = SpeechRecognizerWakeWordDetector(factory, executor)

        detector.start(onWakeWordDetected = {}, onError = {})
        executor.runAll()

        detector.stop()
        executor.runAll()

        val recognizer = factory.createdRecognizers.single()
        assertThat(recognizer.cancelCallCount).isEqualTo(1)
        assertThat(recognizer.destroyCallCount).isEqualTo(1)
    }

    @Test
    fun `a recognition result arriving after stop is ignored, never restarting listening`() {
        val executor = FakeMainThreadExecutor()
        val factory = FakeSpeechRecognizerFactory()
        val detector = SpeechRecognizerWakeWordDetector(factory, executor)

        detector.start(onWakeWordDetected = {}, onError = {})
        executor.runAll()
        val staleListener = factory.lastListener!!

        detector.stop()
        executor.runAll()

        staleListener.onResults(null)
        executor.runAll()

        // Only the original session was ever created — the stale callback must not start a new one.
        assertThat(factory.createCallCount).isEqualTo(1)
    }
}
