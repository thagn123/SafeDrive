package vn.edu.haui.hvs.safedrive.feature.emergency

import vn.edu.haui.hvs.safedrive.core.model.EmergencySnapshot
import vn.edu.haui.hvs.safedrive.core.model.EmergencyState

private const val VERIFYING_EVIDENCE_MS = 5_000L
private const val AWAITING_USER_RESPONSE_MS = 15_000L
private const val FINAL_COUNTDOWN_MS = 10_000L

/**
 * Pure Emergency State Machine transition function per the authoritative table in
 * docs/android-mvp-plan/05-voice-emergency.md. No side effects, no I/O — safe to unit test with
 * [vn.edu.haui.hvs.safedrive.core.testing.FakeClock] and to call repeatedly in a loop to "catch up"
 * through several expired deadlines after process recreation, capping at exactly one
 * `SOS_SIMULATED_SENT` (once `deadlineMs` becomes null, [advance] is a no-op forever after).
 */
class EmergencyReducer {

    fun advance(current: EmergencySnapshot, nowMs: Long): EmergencySnapshot {
        val deadline = current.deadlineMs ?: return current
        if (nowMs < deadline) return current
        return when (current.state) {
            EmergencyState.VERIFYING_EVIDENCE ->
                current.copy(state = EmergencyState.AWAITING_USER_RESPONSE, deadlineMs = deadline + AWAITING_USER_RESPONSE_MS)

            EmergencyState.AWAITING_USER_RESPONSE ->
                current.copy(state = EmergencyState.FINAL_COUNTDOWN, deadlineMs = deadline + FINAL_COUNTDOWN_MS)

            EmergencyState.FINAL_COUNTDOWN ->
                current.copy(state = EmergencyState.SOS_SIMULATED_SENT, deadlineMs = null)

            EmergencyState.CANDIDATE_DETECTED,
            EmergencyState.IDLE,
            EmergencyState.SOS_SIMULATED_SENT,
            EmergencyState.CANCELLED,
            -> current
        }
    }

    /** First real deadline-bearing state after a candidate is accepted. */
    fun startVerifying(snapshot: EmergencySnapshot, nowMs: Long): EmergencySnapshot =
        snapshot.copy(state = EmergencyState.VERIFYING_EVIDENCE, deadlineMs = nowMs + VERIFYING_EVIDENCE_MS)
}
