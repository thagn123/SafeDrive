package vn.edu.haui.hvs.safedrive.core.observability

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Holds the most recently completed turn's latency breakdown for Developer Mode display
 * (docs/android-mvp-plan/12 W4.1/W4.2) and emits a redacted one-line log per turn (W4.10 — numbers
 * only, never transcript/request/response bodies). Injectable/testable: [logger] defaults to a no-op
 * so unit tests never touch `android.util.Log`.
 */
class AssistantTurnMetricsRecorder(private val logger: (String) -> Unit = {}) {

    private val _lastTurn = MutableStateFlow<AssistantTurnMetrics?>(null)
    val lastTurn: StateFlow<AssistantTurnMetrics?> = _lastTurn

    /** Test-only seam (independent re-audit follow-up, blocker 4): invoked exactly once per loop
     * iteration of [recordTtsStarted], immediately after it reads [_lastTurn]'s current snapshot and
     * *before* it attempts to [MutableStateFlow.compareAndSet] that exact snapshot. A test can use this
     * to deterministically force that specific compareAndSet to lose its race — by writing a
     * conflicting value to [_lastTurn] from inside the hook — instead of relying on real-thread
     * scheduling luck to ever reproduce that interleaving. Always `null` (a no-op) in production; never
     * invoked by [record]; `internal` so it never reaches this class's public API surface. */
    internal var onBeforeCompareAndSetForTest: (() -> Unit)? = null

    fun record(metrics: AssistantTurnMetrics) {
        _lastTurn.value = metrics
        logger(
            "AssistantTurnMetrics requestId=${metrics.requestId} " +
                "totalMs=${metrics.totalTurnMs} networkMs=${metrics.networkMs} sessionMs=${metrics.sessionMs} " +
                "serverProcessingMs=${metrics.serverProcessingMs} " +
                "micToReadyMs=${metrics.micStartToReadyMs} speechToPartialMs=${metrics.speechToFirstPartialMs} " +
                "finalToRequestMs=${metrics.finalTranscriptToRequestMs} responseToTtsMs=${metrics.responseToTtsStartMs}",
        )
    }

    /** Patches in the real TTS "audio started" timestamp for [requestId]'s turn once the engine's own
     * callback fires, asynchronously after [record] already ran (docs/android-mvp-plan/12 remediation
     * item 5) — a no-op if a newer turn has already been recorded in the meantime, so a late/stale
     * TTS-start event can never overwrite a different turn's metrics.
     *
     * Also emits its own redacted log line, but only once the patch has *genuinely* committed
     * (independent re-audit follow-up, blocker 4). This is a hand-rolled compare-and-set retry loop,
     * deliberately not [kotlinx.coroutines.flow.MutableStateFlow.update]: that extension's lambda can
     * be invoked more than once under CAS contention (concurrent [record]/[recordTtsStarted] calls each
     * retry with a freshly-read `current` until their own CAS succeeds), so a side effect performed
     * *inside* the lambda — capturing "was this a match" into an outer `var` — can fire for an attempt
     * whose CAS never actually won, or whose winning value was for a *different* (by-then-current)
     * turn. That would let this method log "patched" even though the value it computed was never the
     * one actually stored, or was stored against a turn other than the one just logged. Looping on
     * [MutableStateFlow.compareAndSet] directly and logging only after it returns `true` ties the log
     * line to the exact value that is now genuinely visible to every other reader.
     *
     * Multi-call semantics: calling this more than once for the same [requestId] while it is still
     * [lastTurn]'s current turn is allowed — each call that finds a genuine match patches and logs
     * again, last write wins (there is no one-shot lock). This is deliberate, not an oversight: the one
     * production caller ([vn.edu.haui.hvs.safedrive.domain.usecase.AssistantTurnCoordinator.awaitTtsStarted])
     * only ever calls this once per turn, so the extra generality costs nothing there, and it means a
     * hypothetical future caller correcting/re-reporting a timestamp for the *same still-current* turn
     * is not silently ignored. It can never touch a *different* turn's metrics regardless of how many
     * times it is called, since every call re-checks `requestId` against whatever is genuinely current
     * at that moment. */
    fun recordTtsStarted(requestId: String, startedAtMs: Long) {
        while (true) {
            val current = _lastTurn.value
            if (current?.requestId != requestId) return // stale/mismatched turn — no-op, never logged
            val patched = current.copy(ttsStartedAtMs = startedAtMs)
            onBeforeCompareAndSetForTest?.invoke()
            if (_lastTurn.compareAndSet(current, patched)) {
                logger(
                    "AssistantTurnMetrics requestId=${patched.requestId} ttsStartedAtMs patched " +
                        "responseToTtsMs=${patched.responseToTtsStartMs}",
                )
                return
            }
            // CAS lost a race against a concurrent record()/recordTtsStarted() — loop and re-read the
            // now-current value; if a different turn has since been recorded, the requestId check above
            // will correctly turn this into a no-op on the next iteration instead of stomping it.
        }
    }
}
