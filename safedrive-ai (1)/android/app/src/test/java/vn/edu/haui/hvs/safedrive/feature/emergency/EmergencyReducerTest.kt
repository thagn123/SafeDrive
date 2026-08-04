package vn.edu.haui.hvs.safedrive.feature.emergency

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import vn.edu.haui.hvs.safedrive.core.model.EmergencySnapshot
import vn.edu.haui.hvs.safedrive.core.model.EmergencyState
import vn.edu.haui.hvs.safedrive.core.model.EvidenceItem

/**
 * Exhaustive transition-table test per docs/android-mvp-plan/07-testing-security-acceptance.md
 * ("Emergency timeline test") and 05-voice-emergency.md's "Emergency test timeline": 0→5s verifying,
 * 5→20s awaiting, 20→30s final countdown, at 30s exactly one SOS_SIMULATED_SENT. Uses plain
 * `advance(snapshot, nowMs)` calls (no [Thread.sleep], no wall-clock dependency at all).
 */
class EmergencyReducerTest {

    private val reducer = EmergencyReducer()
    private val evidence = listOf(EvidenceItem("crash_detected", "Va chạm", 0L))

    private fun candidateAt(nowMs: Long) = reducer.startVerifying(
        EmergencySnapshot("emg_1", EmergencyState.CANDIDATE_DETECTED, null, evidence),
        nowMs,
    )

    @Test
    fun `startVerifying sets a 5 second deadline from now`() {
        val snapshot = candidateAt(nowMs = 1_000L)
        assertThat(snapshot.state).isEqualTo(EmergencyState.VERIFYING_EVIDENCE)
        assertThat(snapshot.deadlineMs).isEqualTo(6_000L)
    }

    @Test
    fun `before the deadline, advance is a no-op`() {
        val snapshot = candidateAt(nowMs = 0L)
        val result = reducer.advance(snapshot, nowMs = 4_999L)
        assertThat(result).isEqualTo(snapshot)
    }

    @Test
    fun `at T+5s VERIFYING_EVIDENCE becomes AWAITING_USER_RESPONSE with a 15s deadline`() {
        val snapshot = candidateAt(nowMs = 0L)
        val result = reducer.advance(snapshot, nowMs = 5_000L)
        assertThat(result.state).isEqualTo(EmergencyState.AWAITING_USER_RESPONSE)
        assertThat(result.deadlineMs).isEqualTo(20_000L)
    }

    @Test
    fun `at T+20s AWAITING_USER_RESPONSE becomes FINAL_COUNTDOWN with a 10s deadline`() {
        var snapshot = candidateAt(nowMs = 0L)
        snapshot = reducer.advance(snapshot, nowMs = 5_000L)
        val result = reducer.advance(snapshot, nowMs = 20_000L)
        assertThat(result.state).isEqualTo(EmergencyState.FINAL_COUNTDOWN)
        assertThat(result.deadlineMs).isEqualTo(30_000L)
    }

    @Test
    fun `at T+30s FINAL_COUNTDOWN becomes SOS_SIMULATED_SENT exactly once`() {
        var snapshot = candidateAt(nowMs = 0L)
        snapshot = reducer.advance(snapshot, nowMs = 5_000L)
        snapshot = reducer.advance(snapshot, nowMs = 20_000L)
        val sent = reducer.advance(snapshot, nowMs = 30_000L)
        assertThat(sent.state).isEqualTo(EmergencyState.SOS_SIMULATED_SENT)
        assertThat(sent.deadlineMs).isNull()

        // Calling advance again (e.g. a duplicate tick) must never re-trigger or change anything.
        val again = reducer.advance(sent, nowMs = 999_999L)
        assertThat(again).isEqualTo(sent)
    }

    @Test
    fun `repeatedly advancing from T0 all the way past 30s lands on SOS_SIMULATED_SENT in one loop`() {
        var current = candidateAt(nowMs = 0L)
        // Simulates DataStoreEmergencyRepository.tick()'s loop after the app was closed for a while.
        while (true) {
            val next = reducer.advance(current, nowMs = 45_000L)
            if (next == current) break
            current = next
        }
        assertThat(current.state).isEqualTo(EmergencyState.SOS_SIMULATED_SENT)
    }

    @Test
    fun `CANCELLED and IDLE snapshots are never advanced by the reducer`() {
        val cancelled = EmergencySnapshot("emg_1", EmergencyState.CANCELLED, null, evidence)
        assertThat(reducer.advance(cancelled, nowMs = 999_999L)).isEqualTo(cancelled)

        val idle = EmergencySnapshot("emg_1", EmergencyState.IDLE, null, evidence)
        assertThat(reducer.advance(idle, nowMs = 999_999L)).isEqualTo(idle)
    }
}
