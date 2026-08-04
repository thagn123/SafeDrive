package vn.edu.haui.hvs.safedrive.core.testing

import android.speech.RecognitionListener
import vn.edu.haui.hvs.safedrive.voice.PlatformSpeechRecognizer
import vn.edu.haui.hvs.safedrive.voice.SpeechRecognizerFactory

/** Test double for a single listening session's platform handle — records calls instead of touching
 * the real (unusable-outside-Robolectric/instrumentation) `android.speech.SpeechRecognizer`. */
class FakePlatformSpeechRecognizer : PlatformSpeechRecognizer {
    var startListeningCallCount = 0
        private set
    var stopListeningCallCount = 0
        private set
    var cancelCallCount = 0
        private set
    var destroyCallCount = 0
        private set
    var lastLanguageTag: String? = null
        private set
    var lastCompleteSilenceMs: Int? = null
        private set
    var lastPossiblyCompleteSilenceMs: Int? = null
        private set

    override fun startListening(languageTag: String, completeSilenceMs: Int, possiblyCompleteSilenceMs: Int) {
        startListeningCallCount++
        lastLanguageTag = languageTag
        lastCompleteSilenceMs = completeSilenceMs
        lastPossiblyCompleteSilenceMs = possiblyCompleteSilenceMs
    }

    override fun stopListening() {
        stopListeningCallCount++
    }

    override fun cancel() {
        cancelCallCount++
    }

    override fun destroy() {
        destroyCallCount++
    }
}

/** Test double for [SpeechRecognizerFactory] — lets
 * [vn.edu.haui.hvs.safedrive.voice.AndroidSpeechRecognizerController]'s threading/generation logic be
 * exercised in a plain JVM unit test without the real Android framework class, which is an unusable
 * stub outside Robolectric/instrumentation. */
class FakeSpeechRecognizerFactory(private val available: Boolean = true) : SpeechRecognizerFactory {
    var createCallCount = 0
        private set
    val createdRecognizers: MutableList<FakePlatformSpeechRecognizer> = mutableListOf()
    var lastListener: RecognitionListener? = null
        private set

    /** Records every call, and the thread it was made on — lets a test prove
     * [vn.edu.haui.hvs.safedrive.voice.AndroidSpeechRecognizerController] only ever calls this from
     * inside its `MainThreadExecutor` dispatch, never directly on a caller thread (independent re-audit
     * follow-up, blocker 3). */
    var isRecognitionAvailableCallCount = 0
        private set
    val isRecognitionAvailableCallingThreads: MutableList<Thread> = java.util.Collections.synchronizedList(mutableListOf())

    override fun isRecognitionAvailable(): Boolean {
        isRecognitionAvailableCallCount++
        isRecognitionAvailableCallingThreads.add(Thread.currentThread())
        return available
    }

    override fun create(listener: RecognitionListener): PlatformSpeechRecognizer {
        createCallCount++
        lastListener = listener
        val recognizer = FakePlatformSpeechRecognizer()
        createdRecognizers.add(recognizer)
        return recognizer
    }
}
