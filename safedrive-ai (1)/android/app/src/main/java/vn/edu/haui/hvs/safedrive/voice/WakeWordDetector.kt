package vn.edu.haui.hvs.safedrive.voice

/**
 * An always-on ambient listener for the fixed "Mai ơi" phrase — distinct from
 * [PlatformSpeechRecognizer], which transcribes a bounded command *after* the wake word fires. Abstracted
 * behind this interface (see [SpeechRecognizerWakeWordDetector] for the real implementation) so
 * [WakeWordSessionCoordinator]'s orchestration logic is unit-testable without the real recognizer engine,
 * mirroring how [SpeechRecognizerFactory]/[PlatformSpeechRecognizer] keep
 * [AndroidSpeechRecognizerController] testable.
 */
interface WakeWordDetector {
    /** Begins (or resumes) listening for the wake word. Safe to call when already listening — a no-op.
     * [onWakeWordDetected] and [onError] are always delivered on the main thread. */
    fun start(onWakeWordDetected: () -> Unit, onError: (String) -> Unit)

    /** Stops listening without releasing the underlying engine. Safe to call when already stopped. */
    fun stop()

    /** Releases the underlying engine entirely — no further [start] call is valid after this. */
    fun destroy()
}
