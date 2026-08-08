package vn.edu.haui.hvs.safedrive.domain.repository

import kotlinx.coroutines.flow.Flow

enum class CrashEvidenceSource { VHAL_IMPACT, VHAL_AIRBAG, DEVICE_IMU, VHAL_SPEED_DROP }

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
}
