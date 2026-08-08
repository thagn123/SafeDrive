package vn.edu.haui.hvs.safedrive.vehicle

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import vn.edu.haui.hvs.safedrive.domain.repository.CrashEvidenceFusion
import vn.edu.haui.hvs.safedrive.domain.repository.CrashEvidenceSignal
import vn.edu.haui.hvs.safedrive.domain.repository.CrashEvidenceSource

class CrashEvidenceFusionTest {
    private val fusion = CrashEvidenceFusion()

    @Test
    fun `high g alone is not enough to declare a crash`() {
        assertThat(fusion.record(signal(CrashEvidenceSource.DEVICE_IMU, 1_000))).isNull()
    }

    @Test
    fun `high g plus sudden speed drop declares a crash`() {
        fusion.record(signal(CrashEvidenceSource.DEVICE_IMU, 1_000))
        val decision = fusion.record(signal(CrashEvidenceSource.VHAL_SPEED_DROP, 1_500))

        assertThat(decision?.crashDetected).isTrue()
        assertThat(decision?.signals?.map { it.source }).containsExactly(
            CrashEvidenceSource.DEVICE_IMU,
            CrashEvidenceSource.VHAL_SPEED_DROP,
        )
    }

    @Test
    fun `impact or airbag is independently authoritative`() {
        val impact = fusion.record(signal(CrashEvidenceSource.VHAL_IMPACT, 1_000))

        assertThat(impact?.crashDetected).isTrue()
        assertThat(impact?.signals?.single()?.source).isEqualTo(CrashEvidenceSource.VHAL_IMPACT)
    }

    @Test
    fun `evidence outside the time window does not fuse`() {
        fusion.record(signal(CrashEvidenceSource.DEVICE_IMU, 1_000))
        assertThat(fusion.record(signal(CrashEvidenceSource.VHAL_SPEED_DROP, 3_001))).isNull()
    }

    @Test
    fun `cooldown suppresses duplicate emergency decisions`() {
        assertThat(fusion.record(signal(CrashEvidenceSource.VHAL_IMPACT, 1_000))).isNotNull()
        assertThat(fusion.record(signal(CrashEvidenceSource.VHAL_AIRBAG, 2_000))).isNull()
        assertThat(fusion.record(signal(CrashEvidenceSource.VHAL_AIRBAG, 11_000))).isNotNull()
    }

    private fun signal(source: CrashEvidenceSource, atMs: Long) =
        CrashEvidenceSignal(source = source, detectedAtMs = atMs, confidence = 0.9f)
}
