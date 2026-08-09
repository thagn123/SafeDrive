package vn.edu.haui.hvs.safedrive.vehicle

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import vn.edu.haui.hvs.safedrive.core.common.AppClock
import vn.edu.haui.hvs.safedrive.domain.repository.CrashEvidenceAdapter
import vn.edu.haui.hvs.safedrive.domain.repository.CrashEvidenceDecision
import vn.edu.haui.hvs.safedrive.domain.repository.CrashEvidenceFusion
import vn.edu.haui.hvs.safedrive.domain.repository.CrashEvidenceSignal
import vn.edu.haui.hvs.safedrive.domain.repository.CrashEvidenceSource

object CrashEvidenceAdapterFactory {
    fun create(context: Context, clock: AppClock): CrashEvidenceAdapter {
        // CarSky and some OEM userdebug images expose CarService without advertising the optional
        // FEATURE_AUTOMOTIVE flag. Prefer the real shared-library capability and let start() fail
        // closed when no CarService is available.
        return if (runCatching { Class.forName("android.car.Car") }.isSuccess) {
            AndroidCrashEvidenceAdapter(context.applicationContext, clock)
        } else {
            // A phone has no CarService/VHAL, but Developer Mode must still exercise the exact same
            // deterministic fusion policy end-to-end. The previous empty adapter silently accepted
            // dashboard broadcasts and discarded every injected signal, so SOS could never open on
            // the Xiaomi demo device. Physical sampling stays unavailable; only explicit injection
            // is supported by this fallback.
            InjectableCrashEvidenceAdapter(clock)
        }
    }
}

private class InjectableCrashEvidenceAdapter(
    private val clock: AppClock,
) : CrashEvidenceAdapter {
    private val fusion = CrashEvidenceFusion()
    private val mutableDecisions = MutableSharedFlow<CrashEvidenceDecision>(extraBufferCapacity = 4)

    override val decisions: Flow<CrashEvidenceDecision> = mutableDecisions

    override fun start() = Unit

    override fun stop() = Unit

    override fun injectSignal(source: CrashEvidenceSource, confidence: Float) {
        val decision = fusion.record(
            CrashEvidenceSignal(
                source = source,
                detectedAtMs = clock.nowMs(),
                confidence = confidence,
            ),
        ) ?: return
        mutableDecisions.tryEmit(decision)
    }
}
