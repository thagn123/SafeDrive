package vn.edu.haui.hvs.safedrive.vehicle

import android.content.Context
import vn.edu.haui.hvs.safedrive.domain.repository.VehicleActionCommand
import vn.edu.haui.hvs.safedrive.domain.repository.VehicleActionExecution
import vn.edu.haui.hvs.safedrive.domain.repository.VehicleActionExecutor
import vn.edu.haui.hvs.safedrive.domain.repository.VehicleActionMode

object VehicleActionExecutorFactory {
    fun create(context: Context): VehicleActionExecutor {
        // Some AAOS virtualization platforms expose a fully working CarService/VHAL but omit the
        // optional FEATURE_AUTOMOTIVE PackageManager flag. The android.car shared library is the
        // capability that this adapter actually needs; execution still fails closed if CarService
        // cannot be reached.
        return if (runCatching { Class.forName("android.car.Car") }.isSuccess) {
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
