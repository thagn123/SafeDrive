package vn.edu.haui.hvs.safedrive.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import vn.edu.haui.hvs.safedrive.core.common.AppClock
import vn.edu.haui.hvs.safedrive.domain.repository.TtsController
import vn.edu.haui.hvs.safedrive.domain.repository.TtsState
import vn.edu.haui.hvs.safedrive.domain.repository.TtsUtteranceEvent

/**
 * Android [TextToSpeech] implementation of [TtsController] (docs/android-mvp-plan/12 W3). Owns only
 * TTS lifecycle — no STT/mic concerns; those belong to
 * [vn.edu.haui.hvs.safedrive.voice.AndroidSpeechRecognizerController]. Does not use
 * `synthesizeToFile()` (out of scope for v1). `speechRate`/`pitch` are fixed at `1.0f` — no UI to
 * change them yet (W3.11/W3.12).
 */
class AndroidTextToSpeechController(context: Context, private val clock: AppClock) : TtsController {

    private val _state = MutableStateFlow(TtsState.INITIALIZING)
    override val state: StateFlow<TtsState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<TtsUtteranceEvent>(extraBufferCapacity = 4)
    override val events: SharedFlow<TtsUtteranceEvent> = _events

    private var engine: TextToSpeech? = null
    private var pending: PendingUtterance? = null

    private data class PendingUtterance(val text: String, val utteranceId: String)

    init {
        engine = TextToSpeech(context.applicationContext) { status ->
            Log.d("SafeDriveVoiceDebug", "[TTS] init callback: status=$status (SUCCESS=${TextToSpeech.SUCCESS})")
            if (status != TextToSpeech.SUCCESS) {
                _state.value = TtsState.ERROR
                return@TextToSpeech
            }
            val current = engine
            var langResult = current?.setLanguage(Locale.forLanguageTag("vi-VN"))
            Log.d("SafeDriveVoiceDebug", "[TTS] setLanguage(vi-VN) result=$langResult")
            if (langResult == TextToSpeech.LANG_MISSING_DATA || langResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                langResult = current?.setLanguage(Locale("vi", "VN"))
                Log.d("SafeDriveVoiceDebug", "[TTS] setLanguage(Locale(vi,VN)) result=$langResult")
            }
            if (langResult == TextToSpeech.LANG_MISSING_DATA || langResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                langResult = current?.setLanguage(Locale("vi"))
                Log.d("SafeDriveVoiceDebug", "[TTS] setLanguage(Locale(vi)) result=$langResult")
            }
            if (langResult == TextToSpeech.LANG_MISSING_DATA) {
                Log.w("SafeDriveVoiceDebug", "[TTS] MISSING_DATA for Vietnamese")
                _state.value = TtsState.MISSING_DATA
            } else if (langResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.w("SafeDriveVoiceDebug", "[TTS] UNSUPPORTED for Vietnamese")
                _state.value = TtsState.UNSUPPORTED
            } else {
                current?.setSpeechRate(1.0f)
                current?.setPitch(1.0f)
                current?.setOnUtteranceProgressListener(progressListener)
                Log.d("SafeDriveVoiceDebug", "[TTS] initialized READY for Vietnamese")
                _state.value = TtsState.READY
                pending?.let {
                    Log.d("SafeDriveVoiceDebug", "[TTS] playing queued utterance: id=${it.utteranceId}")
                    speak(it.text, it.utteranceId)
                }
                pending = null
            }
        }
    }

    private val progressListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {
            _state.value = TtsState.SPEAKING
            if (utteranceId != null) _events.tryEmit(TtsUtteranceEvent(utteranceId, clock.nowMs()))
        }

        override fun onDone(utteranceId: String?) {
            if (_state.value == TtsState.SPEAKING) _state.value = TtsState.READY
        }

        @Deprecated("Deprecated in Java", ReplaceWith(""))
        override fun onError(utteranceId: String?) {
            _state.value = TtsState.ERROR
        }

        override fun onError(utteranceId: String?, errorCode: Int) {
            _state.value = TtsState.ERROR
        }
    }

    override fun speak(text: String, utteranceId: String) {
        val current = engine
        val readyState = _state.value
        Log.d("SafeDriveVoiceDebug", "[TTS] speak() called: id=$utteranceId, state=$readyState, engine=${if(current != null) "OK" else "NULL"}, text='${text.take(50)}'")
        if (current == null || readyState == TtsState.INITIALIZING) {
            Log.d("SafeDriveVoiceDebug", "[TTS] speak() -> queued (engine initializing)")
            pending = PendingUtterance(text, utteranceId) // queue only the latest call (W3.4)
            return
        }
        if (readyState == TtsState.UNSUPPORTED || readyState == TtsState.MISSING_DATA) {
            Log.w("SafeDriveVoiceDebug", "[TTS] speak() -> SKIPPED: state=$readyState (cannot speak vi-VN)")
            return // engine cannot speak vi-VN at all; caller/UI already reflects this via state
        }
        val result = current.speak(text, TextToSpeech.QUEUE_ADD, null, utteranceId)
        Log.d("SafeDriveVoiceDebug", "[TTS] speak() -> engine.speak() result=$result (SUCCESS=${TextToSpeech.SUCCESS})")
    }

    override fun stop() {
        engine?.stop()
        if (_state.value == TtsState.SPEAKING) _state.value = TtsState.READY
    }

    /** Called once when the application scope ends (docs/android-mvp-plan/02, "voice" lifecycle). */
    fun shutdown() {
        engine?.stop()
        engine?.shutdown()
        engine = null
    }
}
