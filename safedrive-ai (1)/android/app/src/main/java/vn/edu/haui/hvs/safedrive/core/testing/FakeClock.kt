package vn.edu.haui.hvs.safedrive.core.testing

import vn.edu.haui.hvs.safedrive.core.common.AppClock

/**
 * Deterministic [AppClock] for emergency timeline and use-case tests — no [Thread.sleep] required.
 * Shipped in the main source set (not test-only) since it has no test-framework dependency and is
 * reused by Compose previews and the Phase 4 Simulator "preview JSON" deterministic timestamps.
 */
class FakeClock(initialMs: Long = 0L) : AppClock {
    private var currentMs: Long = initialMs

    override fun nowMs(): Long = currentMs

    fun advanceBy(deltaMs: Long) {
        currentMs += deltaMs
    }

    fun setNowMs(newMs: Long) {
        currentMs = newMs
    }
}
