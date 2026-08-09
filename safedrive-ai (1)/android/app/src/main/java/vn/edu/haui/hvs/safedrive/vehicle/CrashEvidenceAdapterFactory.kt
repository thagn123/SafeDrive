package vn.edu.haui.hvs.safedrive.vehicle

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import vn.edu.haui.hvs.safedrive.core.common.AppClock
import vn.edu.haui.hvs.safedrive.domain.repository.CrashEvidenceAdapter
import vn.edu.haui.hvs.safedrive.domain.repository.CrashEvidenceDecision

object CrashEvidenceAdapterFactory {
    fun create(context: Context, clock: AppClock): CrashEvidenceAdapter {
        // CarSky and some OEM userdebug images expose CarService without advertising the optional
        // FEATURE_AUTOMOTIVE flag. Prefer the real shared-library capability and let start() fail
        // closed when no CarService is available.
        return if (runCatching { Class.forName("android.car.Car") }.isSuccess) {
            AndroidCrashEvidenceAdapter(context.applicationContext, clock)
        } else {
            object : CrashEvidenceAdapter {
                override val decisions: Flow<CrashEvidenceDecision> = emptyFlow()
                override fun start() = Unit
                override fun stop() = Unit
                override fun injectSignal(
                    source: vn.edu.haui.hvs.safedrive.domain.repository.CrashEvidenceSource,
                    confidence: Float,
                ) = Unit
            }
        }
    }
}
