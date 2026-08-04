package vn.edu.haui.hvs.safedrive.core.testing

import java.util.Collections
import java.util.concurrent.ConcurrentLinkedQueue
import vn.edu.haui.hvs.safedrive.voice.MainThreadExecutor

/**
 * Test double for [MainThreadExecutor] that never runs a dispatched block immediately — it only
 * queues it, exactly like a real `Handler.post` onto a main Looper that hasn't gotten around to it
 * yet. This is what makes the double useful for regression-testing remediation item 3 ("SpeechRecognizer
 * đang có nguy cơ bị điều khiển ngoài main thread"): if
 * [vn.edu.haui.hvs.safedrive.voice.AndroidSpeechRecognizerController] ever touched a
 * [vn.edu.haui.hvs.safedrive.voice.PlatformSpeechRecognizer] directly instead of going through this
 * executor, a fake recognizer's call count would increment *before* [runAll] is ever invoked — a test
 * asserting it stays at zero until then would catch that regression.
 */
class FakeMainThreadExecutor : MainThreadExecutor {
    private val queue = ConcurrentLinkedQueue<() -> Unit>()

    /** Every thread that ever called [execute], in order — lets a test confirm dispatch happened from
     * more than one caller thread (e.g. a background preferences collector) without ever running the
     * platform work on that thread directly. */
    val executeCallingThreads: MutableList<Thread> = Collections.synchronizedList(mutableListOf())

    val pendingCount: Int get() = queue.size

    override fun execute(block: () -> Unit) {
        executeCallingThreads.add(Thread.currentThread())
        queue.add(block)
    }

    /** Runs every currently-queued block, in FIFO order, on the calling (test) thread — simulating the
     * main Looper draining its message queue. Blocks queued *by* a block run during this call are also
     * drained (matches a real Looper processing newly-posted messages in the same pass). */
    fun runAll() {
        while (true) {
            val next = queue.poll() ?: break
            next()
        }
    }

    /** Removes and returns the next queued block *without* running it — lets a test hold onto an
     * earlier-queued block and run a *later* one first, then run the held one afterward, reproducing a
     * genuine out-of-order execution (independent re-audit follow-up item 2): the real
     * [vn.edu.haui.hvs.safedrive.voice.AndroidMainThreadExecutor] runs a block immediately when the
     * caller is already on the main thread, which can let it "jump ahead of" an earlier block still
     * sitting in the `Handler`'s queue. `null` if nothing is queued. */
    fun pollPending(): (() -> Unit)? = queue.poll()
}
