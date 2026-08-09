package vn.edu.haui.hvs.safedrive.domain.repository

import kotlinx.coroutines.flow.Flow

enum class CrashEvidenceSource {
    VHAL_IMPACT, VHAL_AIRBAG, DEVICE_IMU, VHAL_SPEED_DROP,
    HIGH_SPEED, CRITICAL_SENSOR_FAULT,
}

data class CrashEvidenceSignal(
    val source: CrashEvidenceSource,
    val detectedAtMs: Long,
    val confidence: Float,
)

data class CrashEvidenceDecision(
    val crashDetected: Boolean,
    val signals: List<CrashEvidenceSignal>,
    val decidedAtMs: Long,
)

class CrashEvidenceFusion(
    private val evidenceWindowMs: Long = 2_000L,
    private val decisionCooldownMs: Long = 10_000L,
) {
    private val recent = mutableListOf<CrashEvidenceSignal>()
    private var lastDecisionAtMs: Long? = null

    @Synchronized
    fun record(signal: CrashEvidenceSignal): CrashEvidenceDecision? {
        recent.removeAll { signal.detectedAtMs - it.detectedAtMs > evidenceWindowMs }
        recent.removeAll { it.source == signal.source }
        recent += signal

        val strongPrimary = recent.any {
            it.source == CrashEvidenceSource.VHAL_IMPACT ||
                it.source == CrashEvidenceSource.VHAL_AIRBAG
        }
        val fusedInertial = recent.any { it.source == CrashEvidenceSource.DEVICE_IMU } &&
            recent.any { it.source == CrashEvidenceSource.VHAL_SPEED_DROP }
        // High speed and sensor faults are useful context, but are not independent collision
        // evidence. Hard braking must never start an emergency without impact/airbag or IMU proof.
        if (!strongPrimary && !fusedInertial) return null
        if (lastDecisionAtMs?.let { signal.detectedAtMs - it < decisionCooldownMs } == true) {
            return null
        }

        lastDecisionAtMs = signal.detectedAtMs
        return CrashEvidenceDecision(
            crashDetected = true,
            signals = recent.toList(),
            decidedAtMs = signal.detectedAtMs,
        )
    }
}

interface CrashEvidenceAdapter {
    val decisions: Flow<CrashEvidenceDecision>
    fun start()
    fun stop()
    fun injectSignal(source: CrashEvidenceSource, confidence: Float = 1.0f)
}

/**
 * Tracks which perimeter/ultrasonic sensor area IDs are currently reporting
 * [android.car.hardware.CarPropertyValue.STATUS_ERROR] on `ULTRASONICS_SENSOR_MEASURED_DISTANCE`.
 * A single sensor erroring is routine noise (EMI, a loose connector); [minFaultySensors] or more
 * distinct sensors erroring at the same time is what actually indicates physical damage (e.g. a
 * crushed bumper), so only that combination is reported. Pure Kotlin (no Android dependency) so
 * this threshold is unit-testable without Robolectric, matching [CrashEvidenceFusion].
 */
class PerimeterSensorFaultTracker(
    private val windowMs: Long = 2_000L,
    private val minFaultySensors: Int = 2,
) {
    private val faultyAreaIds = mutableMapOf<Int, Long>()

    @Synchronized
    fun onSensorStatus(areaId: Int, isError: Boolean, nowMs: Long): Boolean {
        if (isError) faultyAreaIds[areaId] = nowMs else faultyAreaIds.remove(areaId)
        faultyAreaIds.entries.removeAll { nowMs - it.value > windowMs }
        return faultyAreaIds.size >= minFaultySensors
    }
}
