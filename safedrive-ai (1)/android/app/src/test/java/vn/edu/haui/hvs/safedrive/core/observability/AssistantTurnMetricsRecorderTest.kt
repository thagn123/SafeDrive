package vn.edu.haui.hvs.safedrive.core.observability

import com.google.common.truth.Truth.assertThat
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.junit.Test

/**
 * Regression coverage for independent re-audit blocker 4: [AssistantTurnMetricsRecorder.recordTtsStarted]
 * used to perform its side effect (capturing "did this patch apply") *inside* a
 * [kotlinx.coroutines.flow.MutableStateFlow.update] lambda. That lambda can be invoked more than once
 * under CAS contention — [kotlinx.coroutines.flow.MutableStateFlow.update] retries with a freshly-read
 * `current` value every time its internal `compareAndSet` loses a race — so a `var` captured from
 * *inside* the lambda can end up holding a value from an attempt whose CAS never actually won, or whose
 * winning value was for a turn that, by the time the whole `update` call returned, was no longer current.
 * The method would then log "ttsStartedAtMs patched" even though the value it named was never the one
 * genuinely visible in [AssistantTurnMetricsRecorder.lastTurn].
 *
 * The fix replaces `update{}` with a hand-rolled `compareAndSet` retry loop that logs only *after* its
 * own `compareAndSet` call returns `true` — tying the log line to the exact value that just became
 * visible to every other reader, never to an abandoned retry attempt.
 */
class AssistantTurnMetricsRecorderTest {

    @Test
    fun `the correct requestId is patched and logged exactly once`() {
        val logs = mutableListOf<String>()
        val recorder = AssistantTurnMetricsRecorder(logger = logs::add)
        recorder.record(AssistantTurnMetrics(requestId = "req_1", turnStartedAtMs = 1_000L, responseReceivedAtMs = 1_100L))

        recorder.recordTtsStarted("req_1", 1_150L)

        assertThat(recorder.lastTurn.value?.ttsStartedAtMs).isEqualTo(1_150L)
        val patchLogs = logs.filter { it.contains("patched") }
        assertThat(patchLogs).hasSize(1)
        assertThat(patchLogs.single()).contains("requestId=req_1")
        assertThat(patchLogs.single()).contains("responseToTtsMs=50") // 1150 - 1100
    }

    @Test
    fun `a stale (non-matching) requestId is a no-op and is never logged as patched`() {
        val logs = mutableListOf<String>()
        val recorder = AssistantTurnMetricsRecorder(logger = logs::add)
        recorder.record(AssistantTurnMetrics(requestId = "req_current", turnStartedAtMs = 1_000L))

        recorder.recordTtsStarted("req_old_stale", 1_150L)

        assertThat(recorder.lastTurn.value?.requestId).isEqualTo("req_current")
        assertThat(recorder.lastTurn.value?.ttsStartedAtMs).isNull()
        assertThat(logs.none { it.contains("patched") }).isTrue()
    }

    @Test
    fun `a late TTS event for an old turn never overwrites a newer turn's metrics, including when record() races recordTtsStarted() on real threads`() {
        val logs = Collections.synchronizedList(mutableListOf<String>())
        val recorder = AssistantTurnMetricsRecorder(logger = logs::add)
        recorder.record(AssistantTurnMetrics(requestId = "req_old", turnStartedAtMs = 1_000L))

        // Genuinely concurrent, not sequential: one real thread races to record a brand new turn while
        // another simultaneously tries to patch the *old* one — exercising the actual CAS contention
        // path, not just the two deterministic single-threaded cases above.
        val newTurnRecorded = Thread { recorder.record(AssistantTurnMetrics(requestId = "req_new", turnStartedAtMs = 2_000L)) }
        val stalePatchAttempt = Thread { recorder.recordTtsStarted("req_old", 1_150L) }
        newTurnRecorded.start()
        stalePatchAttempt.start()
        newTurnRecorded.join(5_000)
        stalePatchAttempt.join(5_000)

        // Whichever thread's operation the scheduler ran first, the end state must be internally
        // consistent: req_new is current and was never touched by the stale patch, and no "patched" log
        // was ever emitted naming req_old once req_new became current — that would prove a phantom log
        // fired for a CAS that never actually committed.
        assertThat(recorder.lastTurn.value?.requestId).isEqualTo("req_new")
        assertThat(recorder.lastTurn.value?.ttsStartedAtMs).isNull()
        assertThat(logs.none { it.contains("requestId=req_old") && it.contains("patched") }).isTrue()
    }

    @Test
    fun `sustained high-contention concurrent recordTtsStarted calls for an id that is never current are never logged as patched`() {
        val logs = Collections.synchronizedList(mutableListOf<String>())
        val recorder = AssistantTurnMetricsRecorder(logger = logs::add)
        recorder.record(AssistantTurnMetrics(requestId = "req_seed", turnStartedAtMs = 0L))

        // "req_never_valid" is never the argument to any record() call in this test — under *any*
        // correct implementation this can never legitimately win a patch, at any point, regardless of
        // scheduling. A buggy implementation whose logging is decoupled from the winning CAS's actual
        // value (blocker 4's bug) is the only way a "patched" log could ever name it.
        val stopFlag = AtomicBoolean(false)
        val attackerThreads = (1..8).map {
            Thread {
                while (!stopFlag.get()) {
                    recorder.recordTtsStarted("req_never_valid", 999L)
                }
            }
        }
        attackerThreads.forEach { it.start() }

        // Meanwhile, genuinely concurrently, rotate the *real* current turn many times on the main
        // thread — real contention on _lastTurn between the rotations and the attacker threads above.
        repeat(500) { i -> recorder.record(AssistantTurnMetrics(requestId = "req_rotate_$i", turnStartedAtMs = 0L)) }

        stopFlag.set(true)
        attackerThreads.forEach { it.join(5_000) }

        assertThat(recorder.lastTurn.value?.requestId).isEqualTo("req_rotate_499")
        assertThat(logs.none { it.contains("requestId=req_never_valid") }).isTrue()
    }

    // --- Independent re-audit follow-up (second pass), blocker 4: the "stale (non-matching) requestId"
    // test above passes trivially on both the old and the new implementation, since "req_old_stale"
    // never matches "req_current" at any point — it never exercises the actual historical bug shape at
    // all (a *matching* id whose first attempt loses a race, forcing a retry that lands on a *different*
    // turn). The test below forces exactly that interleaving deterministically, via a test-only seam
    // (onBeforeCompareAndSetForTest) that fires after recordTtsStarted() reads its snapshot but before
    // its compareAndSet — rather than hoping real threads happen to land in that exact order. ---

    @Test
    fun `the first compareAndSet attempt losing a race to a concurrently-recorded new turn never produces a phantom patched log`() {
        val logs = mutableListOf<String>()
        val recorder = AssistantTurnMetricsRecorder(logger = logs::add)
        recorder.record(AssistantTurnMetrics(requestId = "req_1", turnStartedAtMs = 1_000L, responseReceivedAtMs = 1_100L))

        var hookFired = false
        recorder.onBeforeCompareAndSetForTest = {
            if (!hookFired) {
                hookFired = true
                // Simulates a concurrent record() for a brand-new turn landing between this call's read
                // of `current` (which matched "req_1") and its compareAndSet attempt on that exact
                // snapshot — forcing the first attempt to lose the race and the loop to retry.
                recorder.record(AssistantTurnMetrics(requestId = "req_2", turnStartedAtMs = 2_000L))
            }
        }

        recorder.recordTtsStarted("req_1", 1_150L)

        // The retry correctly re-reads "req_2" as current, which does not match "req_1" — a genuine,
        // correctly-detected no-op, not a stale id that never mattered in the first place.
        assertThat(recorder.lastTurn.value?.requestId).isEqualTo("req_2")
        assertThat(recorder.lastTurn.value?.ttsStartedAtMs).isNull()
        assertThat(logs.none { it.contains("patched") }).isTrue()
    }

    @Test
    fun `the same forced interleaving reproduces a phantom patched log under the old update{}-based pattern, proving the scenario above is genuinely discriminating`() {
        // A faithful, self-contained reproduction of the pre-fix recordTtsStarted(): the side effect
        // (deciding "did this patch apply") lives *inside* the update{} lambda, which
        // MutableStateFlow.update{} may invoke more than once under contention — retrying with a
        // freshly-read `current` every time its own internal compareAndSet loses a race. A `var`
        // captured from *inside* the lambda is never reset between retries, so it can end up holding a
        // decision made for an attempt whose compareAndSet never actually won. This does not exercise
        // AssistantTurnCoordinator's production AssistantTurnMetricsRecorder at all — it exists purely
        // to prove that the exact interleaving forced in the test above is a real bug shape, not a
        // vacuous scenario no implementation could ever get wrong.
        val logs = mutableListOf<String>()
        val lastTurn = MutableStateFlow(
            AssistantTurnMetrics(requestId = "req_1", turnStartedAtMs = 1_000L, responseReceivedAtMs = 1_100L),
        )
        var sideEffectRun = false
        var patched = false
        lastTurn.update { current ->
            if (!sideEffectRun) {
                sideEffectRun = true
                // The same forced interleaving as the test above: a concurrent record() lands between
                // this (first) invocation's decision and update{}'s internal compareAndSet.
                lastTurn.value = AssistantTurnMetrics(requestId = "req_2", turnStartedAtMs = 2_000L)
            }
            if (current?.requestId == "req_1") {
                patched = true
                current.copy(ttsStartedAtMs = 1_150L)
            } else {
                current
            }
        }
        if (patched) logs.add("AssistantTurnMetrics requestId=req_1 ttsStartedAtMs patched (OLD BUGGY PATTERN)")

        // The actually-committed value is correctly untouched (req_2, unpatched) — same as the fixed
        // implementation. But the *log* fires anyway: `patched` was set by the first (losing) attempt
        // and never reset by the second (winning, correctly-a-no-op) one — exactly blocker 4's bug.
        assertThat(lastTurn.value?.requestId).isEqualTo("req_2")
        assertThat(lastTurn.value?.ttsStartedAtMs).isNull()
        assertThat(logs.any { it.contains("patched") }).isTrue()
    }

    @Test
    fun `two consecutive recordTtsStarted calls for the same still-current requestId both apply and log - last write wins, never touching a different turn`() {
        val logs = mutableListOf<String>()
        val recorder = AssistantTurnMetricsRecorder(logger = logs::add)
        recorder.record(AssistantTurnMetrics(requestId = "req_1", turnStartedAtMs = 1_000L, responseReceivedAtMs = 1_100L))

        recorder.recordTtsStarted("req_1", 1_150L)
        recorder.recordTtsStarted("req_1", 1_200L) // e.g. a corrected timestamp for the same turn

        assertThat(recorder.lastTurn.value?.requestId).isEqualTo("req_1") // never drifted to another turn
        assertThat(recorder.lastTurn.value?.ttsStartedAtMs).isEqualTo(1_200L) // last write wins
        assertThat(logs.count { it.contains("patched") }).isEqualTo(2)
    }
}
