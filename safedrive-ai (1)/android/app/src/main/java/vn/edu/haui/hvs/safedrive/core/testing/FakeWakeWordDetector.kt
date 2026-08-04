package vn.edu.haui.hvs.safedrive.core.testing

import vn.edu.haui.hvs.safedrive.voice.WakeWordDetector

/** In-memory [WakeWordDetector] test double — no real recognizer/native-engine dependency. Records call counts
 * and exposes the most recently captured callbacks so a test can invoke them directly to simulate a
 * detection or an engine error. */
class FakeWakeWordDetector : WakeWordDetector {
    var startCallCount = 0
        private set
    var stopCallCount = 0
        private set
    var destroyCallCount = 0
        private set
    var isListening = false
        private set

    private var lastOnWakeWordDetected: (() -> Unit)? = null
    private var lastOnError: ((String) -> Unit)? = null

    override fun start(onWakeWordDetected: () -> Unit, onError: (String) -> Unit) {
        startCallCount++
        isListening = true
        lastOnWakeWordDetected = onWakeWordDetected
        lastOnError = onError
    }

    override fun stop() {
        stopCallCount++
        isListening = false
    }

    override fun destroy() {
        destroyCallCount++
        isListening = false
    }

    fun emitWakeWordDetected() {
        requireNotNull(lastOnWakeWordDetected) { "start() was never called" }.invoke()
    }

    fun emitError(message: String) {
        requireNotNull(lastOnError) { "start() was never called" }.invoke(message)
    }
}
