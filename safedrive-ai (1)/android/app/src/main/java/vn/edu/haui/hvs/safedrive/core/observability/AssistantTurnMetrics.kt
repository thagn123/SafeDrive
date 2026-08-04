package vn.edu.haui.hvs.safedrive.core.observability

/**
 * Mic/recognizer-side timestamps for a voice-originated turn (docs/android-mvp-plan/12 W4 timing
 * model). Absent (`null`) for text/quick-prompt/retry turns.
 */
data class VoiceCaptureTimings(
    val micRequestedAtMs: Long,
    val recognizerReadyAtMs: Long? = null,
    val firstPartialAtMs: Long? = null,
    val finalTranscriptAtMs: Long,
)

/**
 * Per-turn latency breakdown (docs/android-mvp-plan/12 W4 timing model). All timestamps are Unix ms
 * from [vn.edu.haui.hvs.safedrive.core.common.AppClock]. Never contains transcript text or
 * request/response bodies (W4.10) — timestamps and derived deltas only. `null` on a derived field
 * means "not measured for this turn" (e.g. [sessionMs] is null until session/query timing is threaded
 * through, or [voiceCapture] is null for a text turn) — never a fabricated `0`.
 */
data class AssistantTurnMetrics(
    val requestId: String,
    val turnStartedAtMs: Long,
    val voiceCapture: VoiceCaptureTimings? = null,
    val sessionStartedAtMs: Long? = null,
    val requestSentAtMs: Long? = null,
    val responseReceivedAtMs: Long? = null,
    /** Server-reported processing time for this request (`AssistantQueryResult.serverProcessingMs`,
     * docs/android-mvp-plan/12 W7.4) — how long the backend itself spent, as distinct from [networkMs]
     * which also includes round-trip transport time. `null` when the backend didn't report it. */
    val serverProcessingMs: Long? = null,
    /** When [vn.edu.haui.hvs.safedrive.domain.repository.TtsController.speak] was called — this is
     * *not* when audio actually started (the engine may still be initializing or queued behind
     * another utterance); see [ttsStartedAtMs] for that. */
    val ttsRequestedAtMs: Long? = null,
    /** When the TTS engine's own callback reported it actually began producing audio for this turn's
     * utterance ([vn.edu.haui.hvs.safedrive.domain.repository.TtsUtteranceEvent]) — `null` until that
     * callback fires, or forever if TTS was disabled/never enabled for this turn. */
    val ttsStartedAtMs: Long? = null,
    val turnCompletedAtMs: Long? = null,
) {
    val micStartToReadyMs: Long? get() = delta(voiceCapture?.micRequestedAtMs, voiceCapture?.recognizerReadyAtMs)
    val speechToFirstPartialMs: Long? get() = delta(voiceCapture?.recognizerReadyAtMs, voiceCapture?.firstPartialAtMs)
    val finalTranscriptToRequestMs: Long? get() = delta(voiceCapture?.finalTranscriptAtMs, requestSentAtMs)
    val sessionMs: Long? get() = delta(sessionStartedAtMs, requestSentAtMs)
    val networkMs: Long? get() = delta(requestSentAtMs, responseReceivedAtMs)

    /** Time from receiving the reply to audio actually starting — uses [ttsStartedAtMs] (the real
     * `onStart` callback), never [ttsRequestedAtMs] (merely when `speak()` was called), so this never
     * understates TTS engine warm-up/queueing latency. */
    val responseToTtsStartMs: Long? get() = delta(responseReceivedAtMs, ttsStartedAtMs)
    val totalTurnMs: Long? get() = delta(turnStartedAtMs, turnCompletedAtMs)

    private fun delta(start: Long?, end: Long?): Long? = if (start != null && end != null) end - start else null
}
