package vn.edu.haui.hvs.safedrive.vehicle

import android.content.Context
import android.content.pm.PackageManager
import vn.edu.haui.hvs.safedrive.domain.repository.VehicleActionCommand
import vn.edu.haui.hvs.safedrive.domain.repository.VehicleActionExecution
import vn.edu.haui.hvs.safedrive.domain.repository.VehicleActionExecutor
import vn.edu.haui.hvs.safedrive.domain.repository.VehicleActionMode

object VehicleActionExecutorFactory {
    fun create(context: Context): VehicleActionExecutor {
        val automotive = context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)
        return if (automotive && runCatching { Class.forName("android.car.Car") }.isSuccess) {
            AndroidAutomotiveVehicleActionExecutor(context.applicationContext)
        } else {
            VehicleActionExecutor {
                VehicleActionExecution(
                    applied = false,
                    readBackVerified = false,
                    mode = VehicleActionMode.UNAVAILABLE,
                    message = "Thiết bị này không cung cấp Android Automotive Car Service.",
                    reasonCode = "CAR_SERVICE_UNAVAILABLE",
                )
            }
        }
    }
}
