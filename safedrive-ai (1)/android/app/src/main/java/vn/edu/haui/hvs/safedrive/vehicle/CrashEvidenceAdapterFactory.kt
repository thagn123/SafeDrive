package vn.edu.haui.hvs.safedrive.vehicle

import android.content.Context
import android.content.pm.PackageManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import vn.edu.haui.hvs.safedrive.core.common.AppClock
import vn.edu.haui.hvs.safedrive.domain.repository.CrashEvidenceAdapter
import vn.edu.haui.hvs.safedrive.domain.repository.CrashEvidenceDecision

object CrashEvidenceAdapterFactory {
    fun create(context: Context, clock: AppClock): CrashEvidenceAdapter {
        val automotive = context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)
        return if (automotive && runCatching { Class.forName("android.car.Car") }.isSuccess) {
            AndroidCrashEvidenceAdapter(context.applicationContext, clock)
        } else {
            object : CrashEvidenceAdapter {
                override val decisions: Flow<CrashEvidenceDecision> = emptyFlow()
                override fun start() = Unit
                override fun stop() = Unit
            }
        }
    }
}
