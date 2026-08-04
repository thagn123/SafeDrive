package vn.edu.haui.hvs.safedrive.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import vn.edu.haui.hvs.safedrive.core.common.AppClock
import vn.edu.haui.hvs.safedrive.core.model.VoiceState
import vn.edu.haui.hvs.safedrive.core.observability.VoiceCaptureTimings
import vn.edu.haui.hvs.safedrive.domain.repository.PreferencesRepository

/**
 * Serializes every platform `SpeechRecognizer` lifecycle call onto a single thread — Android's main
 * thread in production (remediation item 3). `SpeechRecognizer` must be created and driven from one
 * consistent thread; before this fix, [AndroidSpeechRecognizerController]'s public methods could be
 * invoked directly from the preferences collector (which runs on the application's `Dispatchers.Default`
 * scope, see `SafeDriveApplication`) as well as from Compose/ViewModel action handlers, letting two
 * different threads touch the same recognizer instance concurrently. Extracted as an interface (rather
 * than calling `Handler`/`Looper` inline) so [AndroidSpeechRecognizerController]'s threading/generation
 * logic is unit-testable with a fake that never touches the real Android framework classes, which are
 * unusable stubs outside Robolectric/instrumentation.
 */
interface MainThreadExecutor {
    fun execute(block: () -> Unit)
}

class AndroidMainThreadExecutor : MainThreadExecutor {
    private val handler = Handler(Looper.getMainLooper())

    override fun execute(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else handler.post(block)
    }
}

/** A single listening session's platform handle, abstracted so it can be faked in tests (see
 * [MainThreadExecutor]'s KDoc for why). */
interface PlatformSpeechRecognizer {
    /**
     * [completeSilenceMs]/[possiblyCompleteSilenceMs] tune how long the platform recognizer waits after
     * the last detected speech before ending the session — defaults match the tight, snappy cutoff a
     * bounded command capture wants. The continuously-cycling ambient wake-word listener
     * ([vn.edu.haui.hvs.safedrive.voice.SpeechRecognizerWakeWordDetector]) passes
     * [AMBIENT_COMPLETE_SILENCE_MS]/[AMBIENT_POSSIBLY_COMPLETE_SILENCE_MS] instead — history of *why* both
     * ends of this tradeoff were tried and rejected:
     * - The same ~800ms/1000ms command tuning applied to ambient listening made it restart (and
     *   re-trigger the platform's start/stop earcon) roughly once a second even in a quiet room.
     * - Removing the extras entirely (relying on the platform's own default) was tried next and made
     *   things *worse*, not better: on the physical test device, sessions with no explicit silence extras
     *   ran a full ~8 seconds *every single time*, `onStartOfSpeech` firing within ~1s of the session
     *   start and `onEndOfSpeech` not firing until ~8s later, cycle after cycle — consistent with the
     *   platform's own default silence threshold being generous enough that ordinary room noise
     *   (HVAC, handling, distant sound) kept resetting it before a genuine pause was ever seen. The
     *   practical effect: saying "Mai ơi" could take up to ~8 seconds to even be considered, and
     *   often landed inside a long, noise-contaminated transcript instead of a clean short one — the
     *   platform's own start-listening earcon still fired (session started), but no capture/response
     *   followed within any timeframe a real user would wait for.
     * - [AMBIENT_COMPLETE_SILENCE_MS]/[AMBIENT_POSSIBLY_COMPLETE_SILENCE_MS] is the deliberate middle
     *   ground: long enough not to restart on every micro-blip of noise, short enough that a genuine
     *   pause after the wake phrase ends the session within a few seconds, not up to eight.
     */
    fun startListening(languageTag: String, completeSilenceMs: Int = COMPLETE_SILENCE_MS, possiblyCompleteSilenceMs: Int = POSSIBLY_COMPLETE_SILENCE_MS)
    fun stopListening()
    fun cancel()
    fun destroy()
}

/** Wraps `SpeechRecognizer`'s static factory/availability methods. */
interface SpeechRecognizerFactory {
    fun isRecognitionAvailable(): Boolean
    fun create(listener: RecognitionListener): PlatformSpeechRecognizer
}

// RecognizerIntent requires boxed Int extras on the Google recognizer used by
// the physical test device. Passing Long makes the service discard the hint
// and fall back to its OEM default, which leaves short Vietnamese commands
// waiting far longer than the cockpit interaction permits.
const val COMPLETE_SILENCE_MS = 800
const val POSSIBLY_COMPLETE_SILENCE_MS = 1_000

/** See [PlatformSpeechRecognizer.startListening]'s KDoc for why these values, not the command-capture
 * ones and not "no extras at all," are what the ambient wake-word listener uses. */
const val AMBIENT_COMPLETE_SILENCE_MS = 2_500
const val AMBIENT_POSSIBLY_COMPLETE_SILENCE_MS = 3_000

class AndroidSpeechRecognizerFactory(private val context: Context) : SpeechRecognizerFactory {
    override fun isRecognitionAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    override fun create(listener: RecognitionListener): PlatformSpeechRecognizer {
        val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer.setRecognitionListener(listener)
        return object : PlatformSpeechRecognizer {
            override fun startListening(languageTag: String, completeSilenceMs: Int, possiblyCompleteSilenceMs: Int) {
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    // Best-effort end-of-speech hints (docs/android-mvp-plan/12 W4.8) — an optimization,
                    // not a guarantee: some OEM recognizers ignore these entirely, which is exactly why
                    // finishListening() exists as a reliable user-initiated fallback. Always set (see
                    // PlatformSpeechRecognizer.startListening's KDoc for why *not* setting these at all
                    // is actively worse for the ambient listener, not merely unnecessary).
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, completeSilenceMs)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, possiblyCompleteSilenceMs)
                }
                recognizer.startListening(intent)
            }

            override fun stopListening() {
                recognizer.stopListening()
            }

            override fun cancel() {
                recognizer.cancel()
            }

            override fun destroy() {
                recognizer.setRecognitionListener(null)
                recognizer.destroy()
            }
        }
    }
}

/**
 * Command-transcript capture per docs/android-mvp-plan/05-voice-emergency.md and docs/android-mvp-plan/12
 * §4.1/W2/W4. Wake-word detection lives entirely outside this class now
 * ([vn.edu.haui.hvs.safedrive.voice.SpeechRecognizerWakeWordDetector] inside
 * `vn.edu.haui.hvs.safedrive.service.WakeWordListeningService`) — this controller's only job is a bounded
 * `vi-VN` command-transcription session, started either by a manual mic tap or by the wake-word service
 * once it detects "Mai ơi". Owns ONLY the recognizer lifecycle and UI state — it never calls the
 * assistant pipeline or a TTS engine; a final transcript is published on [events] for
 * [vn.edu.haui.hvs.safedrive.domain.usecase.VoiceAssistantCoordinator] to route (docs/android-mvp-plan/12
 * W2.2/W2.3). [generation] guards every recognizer callback so a stale session (after [cancel] or a new
 * [startListening]) can never resurrect state or emit a transcript.
 *
 * All actual platform recognizer calls (create/start/stop/cancel/destroy) are serialized through
 * [mainThreadExecutor] (remediation item 3) — every public method here only ever bumps [generation] and
 * updates [_state] directly (both are thread-safe on their own), then hands the platform work off to the
 * executor; no caller-thread ever touches [PlatformSpeechRecognizer] directly.
 *
 * [events] is backed by a [Channel], not a broadcast [kotlinx.coroutines.flow.SharedFlow] (remediation
 * item 7): a transcript sent via [Channel.trySend] is queued for whichever coroutine eventually calls
 * `collect`/`receive` on it, regardless of subscription timing — unlike a replay-0 `SharedFlow`, where a
 * value emitted before any collector subscribes (or between one collector's cancellation and the next
 * one starting) is lost forever, not merely delayed. `trySend(...).isSuccess` is therefore a genuine
 * backpressure signal (the bounded buffer is actually full), not merely "nobody happened to be listening
 * this instant".
 *
 * `LISTENING` is only ever rendered once the recognizer actually confirms readiness via
 * `onReadyForSpeech` (W4.6) — before that, state stays `WAKE_WORD_DETECTED` ("preparing").
 *
 * Requesting the RECORD_AUDIO runtime permission is the caller's (Compose UI's) responsibility — this
 * class only checks whether it is already granted.
 */
class AndroidSpeechRecognizerController(
    private val context: Context,
    private val preferencesRepository: PreferencesRepository,
    private val clock: AppClock,
    private val externalScope: CoroutineScope,
    private val recognizerFactory: SpeechRecognizerFactory = AndroidSpeechRecognizerFactory(context),
    private val mainThreadExecutor: MainThreadExecutor = AndroidMainThreadExecutor(),
) : VoiceController {

    private val _state = MutableStateFlow(VoiceUiState())
    override val state: StateFlow<VoiceUiState> = _state.asStateFlow()

    private val _events = Channel<VoiceInputEvent>(capacity = 4)
    override val events: Flow<VoiceInputEvent> = _events.receiveAsFlow()

    /** A listening session's platform handle, tagged with the [generation] it belongs to, and owning
     * that exact session's [timings] (blocker 3 below) — never read/written outside a
     * [mainThreadExecutor]-dispatched context: either the block that creates it, or a real
     * `RecognitionListener` callback (which Android always delivers on the same thread the
     * `SpeechRecognizer` was created on — always the main thread here, since [beginListening] only ever
     * calls [SpeechRecognizerFactory.create] from inside [mainThreadExecutor]). Single-thread
     * confinement, so no lock is needed for either field.
     *
     * Tagging matters (independent re-audit follow-up item 2): [AndroidMainThreadExecutor.execute] runs
     * a block *immediately* (skipping the queue) when the caller is already on the main thread, but
     * queues it (via `Handler.post`) otherwise. That means a `cancel()`/`finishListening()` invoked from
     * a background thread (e.g. the preferences collector) can have its dispatched block sitting in the
     * queue *behind* a `startListening()` call that happens to run synchronously on the main thread
     * afterward — so the stale cancel/finish block can end up executing *after* a newer session has
     * already replaced [activeSession]. Every dispatched block below re-checks that [activeSession]
     * still belongs to the exact generation it was issued for before touching the recognizer or
     * [timings], so a stale block/callback can never act on a session that isn't the one it targeted. */
    private class ActiveSession(
        val generation: Long,
        val recognizer: PlatformSpeechRecognizer,
        var timings: PartialCaptureTimings,
    )

    private var activeSession: ActiveSession? = null

    /**
     * Invoked synchronously, on the caller's own thread, as the very first step of [beginListening] —
     * before this controller creates or touches its own recognizer. The ambient wake-word listener
     * ([vn.edu.haui.hvs.safedrive.voice.SpeechRecognizerWakeWordDetector], owned by
     * `vn.edu.haui.hvs.safedrive.service.WakeWordListeningService`) and this controller's own
     * command-capture session can never safely hold the platform `SpeechRecognizer` at the same instant —
     * two concurrent sessions against the same underlying recognition service is what produced the
     * repeated start/stop earcon and failed-to-transcribe reports this hook was added to fix.
     *
     * A purely *reactive* stop — [vn.edu.haui.hvs.safedrive.voice.WakeWordSessionCoordinator] observing
     * this controller's own [state] leave `IDLE`/`ERROR` — is one event-loop turn too late whenever the
     * caller is already on the main thread (a Compose tap handler, or the wake-word-detected callback
     * itself): [beginListening] runs its whole `mainThreadExecutor`-confined block synchronously in that
     * case (see [MainThreadExecutor]'s KDoc), including creating and starting *this* controller's own
     * recognizer, entirely before a `StateFlow` collector elsewhere gets a chance to react to the state
     * change and stop the ambient one. Calling this hook first, in the same synchronous call stack, closes
     * that gap instead of racing it.
     *
     * Set by `WakeWordListeningService` once its detector exists, reset to a no-op when that service
     * stops. Defaults to a no-op so this class keeps zero compile-time dependency on the wake-word system.
     */
    var onBeforeListen: () -> Unit = {}

    /** Set once by [shutdown] — terminal: no dispatched `startListening` block may create a recognizer
     * after this, even if it was queued before shutdown was requested (independent re-audit follow-up
     * item 2). Both the write (in [shutdown]) and the one read (in [beginListening]'s dispatched block)
     * now happen inside [callbackLock] (ninth pass), which alone provides the necessary cross-thread
     * visibility — `@Volatile` is no longer needed on top of that. */
    private var isShutdown = false

    private data class PartialCaptureTimings(
        val micRequestedAtMs: Long,
        val recognizerReadyAtMs: Long? = null,
        val firstPartialAtMs: Long? = null,
    )

    /** Atomic (not merely `@Volatile`) so concurrent callers bumping it can never race each other into
     * assigning the same value twice (remediation item 3). */
    private val generation = AtomicLong(0)

    /** Serializes every [generation] read/bump/mint together with whatever [_state]/[_events]/
     * [activeSession] mutation depends on that read, as one atomic unit (independent re-audit follow-up,
     * ninth pass, closing P1-2; tenth pass, closing a gap left in [beginListening]'s own mint). [cancel],
     * [shutdown] and [beginListening] all run their own generation bump/mint on the *caller's* thread,
     * never confined to [mainThreadExecutor] (only the platform teardown/creation is) — so a bump can
     * happen on a completely different thread *while* a `RecognitionListener` callback (always delivered
     * on the same thread the recognizer was created on — the main thread in production, see this class's
     * own KDoc) is partway through its body, having already read [generation] and found itself still
     * current.
     *
     * The tenth-pass correction: [beginListening]'s `generation.incrementAndGet()` call itself — not just
     * its later dispatched check-then-act — now also happens inside this lock. Before that, a callback
     * already holding this lock (having passed its own check) offered no protection against a *concurrent*
     * mint racing in behind its back: the mint ran unguarded, so it could land in the middle of the
     * callback's locked section, invisibly invalidating the very generation the callback had just decided
     * was still valid, while the callback carried on mutating `_state`/enqueuing a [VoiceInputEvent] as if
     * nothing had changed. With the mint itself requiring this same lock, that ordering is no longer
     * reachable: either a newer [beginListening] call's mint completes *entirely* before any callback
     * holding the lock can have been contradicted by it (because the mint cannot even begin until the lock
     * is free), or the callback's own generation check runs *after* the mint and correctly observes the new
     * value. There is no window in between.
     *
     * Every place below that either reads [generation] to decide validity and mutates state as a
     * consequence, *or* mints/bumps a new value that other readers depend on, now does so inside this one
     * lock, so none of those operations can ever be interleaved with one another: whichever side acquires
     * the lock first completes its whole check-then-act (or mint) atomically before the other side's own
     * read or mint can even happen.
     *
     * Never held across a suspension point (nothing below is a `suspend` function), and never held while
     * calling into the platform `SpeechRecognizer`/[PlatformSpeechRecognizer] itself except inside
     * [beginListening]'s own [mainThreadExecutor]-confined block, where it already only ever runs
     * strictly serialized with every other platform call by construction — so this cannot lock-order-
     * invert against the main Looper or block on I/O. */
    private val callbackLock = Any()

    init {
        externalScope.launch {
            preferencesRepository.preferences.collect { prefs ->
                if (!prefs.wakeWordEnabled && _state.value.state != VoiceState.DISABLED) {
                    cancel()
                    _state.update { it.copy(state = VoiceState.DISABLED) }
                } else if (prefs.wakeWordEnabled && _state.value.state == VoiceState.DISABLED) {
                    _state.update { it.copy(state = VoiceState.IDLE) }
                }
            }
        }
    }

    override fun startListening(screen: String) = beginListening(screen)

    /** Single start path (independent re-audit follow-up item 1), invoked either by a manual mic tap
     * ([vn.edu.haui.hvs.safedrive.feature.voice.rememberVoiceTrigger]) or by the wake-word service once
     * it detects "Mai ơi". Mints [generation] exactly once per call.
     *
     * The permission check runs on the caller's thread (it is a `PackageManager` query, not a
     * `SpeechRecognizer` platform operation, and is already thread-safe by construction) — everything
     * else that touches recognizer state, including [SpeechRecognizerFactory.isRecognitionAvailable]
     * (blocker 3: previously called on the caller's thread, not confined like every other platform
     * operation), runs inside one single [mainThreadExecutor]-dispatched block. Publishing
     * `WAKE_WORD_DETECTED` *inside* that same block — rather than optimistically before dispatching —
     * is what makes [shutdown] genuinely terminal: if [isShutdown] is already true by the time this
     * block runs, it returns immediately *before* ever touching [_state], so a `startListening` call
     * issued after shutdown can never leave the UI stuck showing a fake "preparing" state for a session
     * that will never actually start (independent re-audit follow-up, blocker 3). In production this
     * block still runs synchronously/immediately in the overwhelmingly common case (the caller — a
     * Compose action handler or the permission-result callback — is already on the main thread), so this
     * adds no observable latency. */
    private fun beginListening(screen: String) {
        onBeforeListen()
        if (!hasRecordAudioPermission()) {
            publishError("Cần quyền microphone để dùng giọng nói")
            return
        }
        // Captured on the caller's own thread, immediately — before mainThreadExecutor.execute even
        // enqueues the confined block below. If this timestamp were instead taken *inside* that block
        // (i.e. once it actually starts running), micRequestedAtMs would silently drift from meaning
        // "the instant the user asked for the microphone" to "the instant the main executor got around
        // to processing that request" — hiding any real dispatch/queueing delay on a busy main Looper
        // and reporting a falsely-fast mic latency (independent re-audit follow-up, latency-honesty
        // note). Captured into an immutable local here and threaded into the block's closure — never a
        // shared mutable field read at two different times.
        val requestedAtMs = clock.nowMs()
        // The mint itself now requires callbackLock (tenth pass, closing blocker 1) — not merely the
        // dispatched check-then-act below. A callback already holding this lock (having passed its own
        // check) is what this protects: without the mint itself needing the lock, it could land in the
        // middle of that callback's locked section, invalidating the very generation the callback had just
        // decided was current, while the callback carried on mutating state/enqueuing an event regardless.
        // Requiring the lock here means the mint either completes entirely before such a callback could
        // ever have been contradicted by it, or waits until that callback (and its lock) is done — either
        // way, never in between. See callbackLock's own KDoc for the full argument.
        val gen = synchronized(callbackLock) { generation.incrementAndGet() }
        mainThreadExecutor.execute {
            // The whole check-then-act — re-validating gen/isShutdown together with every mutation that
            // depends on that validation — runs inside callbackLock (ninth pass, closing P1-2): without
            // it, a concurrent cancel()/shutdown() (whose own bump runs on the *caller's* thread, not
            // confined here) could invalidate gen in the gap between this check and these mutations,
            // and this block would still publish WAKE_WORD_DETECTED / create a recognizer for a session
            // that was just superseded or torn down. Structured as if/else rather than an early return —
            // a bare `return@execute` inside a `synchronized` block nested inside this non-inline
            // `execute { }` lambda would rely on non-local-return-through-inlining semantics that are
            // easy to get subtly wrong; if/else keeps the control flow unambiguous.
            synchronized(callbackLock) {
                if (!isShutdown && gen == generation.get()) {
                    if (!recognizerFactory.isRecognitionAvailable()) {
                        publishError("Thiết bị không hỗ trợ nhận diện giọng nói")
                    } else {
                        activeSession?.recognizer?.destroy()
                        val instance = recognizerFactory.create(listenerFor(gen, screen))
                        activeSession = ActiveSession(
                            generation = gen,
                            recognizer = instance,
                            timings = PartialCaptureTimings(micRequestedAtMs = requestedAtMs),
                        )
                        // Not LISTENING yet — stays "preparing" (WAKE_WORD_DETECTED) until onReadyForSpeech (W4.6).
                        _state.update {
                            VoiceUiState(
                                state = VoiceState.WAKE_WORD_DETECTED,
                                generation = gen,
                            )
                        }
                        instance.startListening("vi-VN")
                    }
                }
            }
        }
    }

    override fun cancel() {
        // The generation read/bump and the resulting _state publish happen together inside callbackLock
        // (ninth pass) — this is the exact operation a partway-through RecognitionListener callback (see
        // callbackLock's KDoc) must never be able to interleave with: either this runs entirely before
        // that callback's own check, or entirely after it, never in between.
        val genToCancel = synchronized(callbackLock) {
            // Captured before bumping generation: this cancel targets whichever session is active *right
            // now*, not whatever session happens to be active by the time its dispatched block actually
            // runs (see ActiveSession's KDoc).
            val current = generation.get()
            generation.incrementAndGet() // invalidate any in-flight recognizer callback
            val disabled = _state.value.state == VoiceState.DISABLED
            _state.update { VoiceUiState(state = if (disabled) VoiceState.DISABLED else VoiceState.IDLE) }
            current
        }
        mainThreadExecutor.execute {
            val session = activeSession
            if (session != null && session.generation == genToCancel) {
                session.recognizer.cancel()
                session.recognizer.destroy()
                activeSession = null
            }
        }
    }

    override fun finishListening() {
        // Stops accepting more audio and asks for a final result from what was captured so far —
        // distinct from cancel(), which discards the session entirely (docs/android-mvp-plan/12 W4.7).
        // Targets whichever session is active right now (same reasoning as cancel() above), never a
        // session that supersedes it by the time this dispatched block actually runs.
        val genToFinish = generation.get()
        mainThreadExecutor.execute {
            val session = activeSession
            if (session != null && session.generation == genToFinish) {
                session.recognizer.stopListening()
            }
        }
    }

    override fun clearProcessingState(expectedGeneration: Long) {
        _state.update {
            if (it.state == VoiceState.PROCESSING && it.generation == expectedGeneration) it.copy(state = VoiceState.IDLE) else it
        }
    }

    override fun reassignProcessingOwner(expectedCurrentGeneration: Long, newOwnerGeneration: Long) {
        _state.update {
            if (it.state == VoiceState.PROCESSING && it.generation == expectedCurrentGeneration) {
                it.copy(generation = newOwnerGeneration)
            } else {
                it
            }
        }
    }

    fun shutdown() {
        synchronized(callbackLock) {
            isShutdown = true
            // Also invalidates any RecognitionListener callback already partway through its own body for
            // the currently-active generation (ninth pass) — before this, shutdown() left `generation`
            // untouched, so a callback that had already passed its own gen check could still resurrect
            // WAKE_WORD_DETECTED/LISTENING/PROCESSING after shutdown, since nothing about its guard would
            // ever notice shutdown happened. cancel() already relies on exactly this same bump; shutdown
            // is terminal, so bumping here is strictly safe (nothing can ever come back from it).
            generation.incrementAndGet()
        }
        mainThreadExecutor.execute {
            activeSession?.recognizer?.destroy()
            activeSession = null
        }
    }

    /** Every callback below reads [generation] to decide whether it is still current, then — only if so
     * — mutates [_state]/[activeSession]/[_events] as a consequence of that decision. Both parts always
     * happen inside [callbackLock] together (ninth pass, closing P1-2): see that field's KDoc for the
     * cross-thread race this closes (a concurrent [cancel]/[shutdown]/newer [beginListening] bumping
     * [generation] on a different thread while one of these callbacks is partway through its own body,
     * having already read [generation] and found itself still current). */
    private fun listenerFor(gen: Long, screen: String) = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            synchronized(callbackLock) {
                if (gen != generation.get()) return
                activeSession?.takeIf { it.generation == gen }?.let { it.timings = it.timings.copy(recognizerReadyAtMs = clock.nowMs()) }
                _state.update { if (it.state == VoiceState.WAKE_WORD_DETECTED) it.copy(state = VoiceState.LISTENING) else it }
            }
        }

        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit

        // Raw audio is never captured/stored/uploaded (docs/android-mvp-plan/12 §3.1, W2.10):
        // transcript-first only, this callback is intentionally a no-op.
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() {
            val recognizer = synchronized(callbackLock) {
                if (gen != generation.get()) null else activeSession?.takeIf { it.generation == gen }?.recognizer
            }
            // Ask for final results as soon as Android detects that the user has stopped speaking.
            recognizer?.stopListening()
        }
        override fun onEvent(eventType: Int, params: Bundle?) = Unit

        override fun onError(error: Int) {
            synchronized(callbackLock) {
                if (gen != generation.get()) return
                publishError(mapErrorCode(error))
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            synchronized(callbackLock) {
                if (gen != generation.get()) return
                activeSession?.takeIf { it.generation == gen }?.let { session ->
                    if (session.timings.firstPartialAtMs == null) {
                        session.timings = session.timings.copy(firstPartialAtMs = clock.nowMs())
                    }
                }
                val text = partialResults
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    .orEmpty()
                _state.update { it.copy(partialTranscript = text) }
            }
        }

        override fun onResults(results: Bundle?) {
            synchronized(callbackLock) {
                if (gen != generation.get()) return
                val text = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    .orEmpty()
                if (text.isBlank()) {
                    // Empty transcript is never sent (docs/android-mvp-plan/05-voice-emergency.md).
                    _state.update { it.copy(state = VoiceState.IDLE, partialTranscript = "", finalTranscript = "") }
                    return
                }
                // Explicitly re-stamps generation = gen (not just `.copy()`, which would already preserve
                // it here) so this session's own PROCESSING and the event it is about to enqueue are
                // minted together, unambiguously, from the same source of truth (independent re-audit
                // follow-up, eighth pass) — see VoiceInputEvent.generation's KDoc for the pre-claim race
                // this closes.
                _state.update {
                    it.copy(
                        state = VoiceState.PROCESSING,
                        finalTranscript = text,
                        partialTranscript = "",
                        generation = gen,
                    )
                }
                val captured = activeSession?.takeIf { it.generation == gen }?.timings
                val timings = if (captured != null) {
                    VoiceCaptureTimings(
                        micRequestedAtMs = captured.micRequestedAtMs,
                        recognizerReadyAtMs = captured.recognizerReadyAtMs,
                        firstPartialAtMs = captured.firstPartialAtMs,
                        finalTranscriptAtMs = clock.nowMs(),
                    )
                } else {
                    null
                }
                val emitted = _events.trySend(VoiceInputEvent(text = text, screen = screen, captureTimings = timings, generation = gen)).isSuccess
                if (!emitted) {
                    // The bounded queue is genuinely full (not merely "no collector yet" — see the class
                    // KDoc) — never leave the UI stuck showing PROCESSING for a turn that will never
                    // actually be routed/cleared.
                    _state.update { it.copy(state = VoiceState.ERROR, errorMessage = "Không thể xử lý yêu cầu, vui lòng thử lại") }
                }
            }
        }
    }

    private fun publishError(message: String) {
        _state.update { it.copy(state = VoiceState.ERROR, errorMessage = message) }
    }

    private fun hasRecordAudioPermission(): Boolean = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.RECORD_AUDIO,
    ) == PackageManager.PERMISSION_GRANTED

    private fun mapErrorCode(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_NO_MATCH -> "Không nhận diện được câu nói, vui lòng thử lại"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Không phát hiện giọng nói"
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Lỗi mạng khi nhận diện giọng nói"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Thiếu quyền microphone"
        SpeechRecognizer.ERROR_AUDIO -> "Lỗi ghi âm"
        else -> "Lỗi nhận diện giọng nói"
    }
}
