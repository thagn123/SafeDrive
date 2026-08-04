package vn.edu.haui.hvs.safedrive.domain.repository

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/** docs/android-mvp-plan/12 W3.2. */
enum class TtsState { INITIALIZING, READY, SPEAKING, UNSUPPORTED, MISSING_DATA, ERROR }

/** Fired the moment the engine actually begins producing audio for [utteranceId] (the platform
 * `onStart` callback), not when [TtsController.speak] was merely called — the two can differ by a
 * noticeable amount if the engine was still warming up or another utterance was queued ahead of it.
 * Latency instrumentation (`AssistantTurnMetrics.ttsStartedAtMs`) correlates on [utteranceId], which
 * is always the same id as the turn's network `requestId` (see `AssistantTurnCoordinator`). */
data class TtsUtteranceEvent(val utteranceId: String, val startedAtMs: Long)

/**
 * Platform-neutral TTS output (docs/android-mvp-plan/12 §4.1/W3). The only caller that should invoke
 * [speak] for an assistant reply is
 * [vn.edu.haui.hvs.safedrive.domain.usecase.AssistantTurnCoordinator], and only for a successful
 * reply when the user has TTS enabled — never for typed errors, snackbars or debug/system text
 * (W3.13). [vn.edu.haui.hvs.safedrive.voice.VoiceController] must never hold a reference to this
 * interface (W2.11) — voice-overlay UI combines both states for *display* only
 * ([vn.edu.haui.hvs.safedrive.feature.voice.VoiceOverlay]).
 */
interface TtsController {
    val state: StateFlow<TtsState>

    /** Emits once per utterance that actually starts producing audio — see [TtsUtteranceEvent]. */
    val events: SharedFlow<TtsUtteranceEvent>

    /** Speaks [text]. If the engine is still [TtsState.INITIALIZING], queues only this latest call
     * (does not silently drop it, does not queue more than one — W3.4). */
    fun speak(text: String, utteranceId: String)

    /** Stops any speech in progress. Never affects chat messages or turn state. */
    fun stop()
}
