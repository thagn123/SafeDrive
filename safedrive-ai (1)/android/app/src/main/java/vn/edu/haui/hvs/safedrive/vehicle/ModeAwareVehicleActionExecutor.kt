package vn.edu.haui.hvs.safedrive.vehicle

import kotlinx.coroutines.flow.StateFlow
import vn.edu.haui.hvs.safedrive.core.model.BackendMode
import vn.edu.haui.hvs.safedrive.domain.repository.AppPreferences
import vn.edu.haui.hvs.safedrive.domain.repository.VehicleActionCommand
import vn.edu.haui.hvs.safedrive.domain.repository.VehicleActionExecution
import vn.edu.haui.hvs.safedrive.domain.repository.VehicleActionExecutor
import vn.edu.haui.hvs.safedrive.domain.repository.VehicleActionMode

class ModeAwareVehicleActionExecutor(
    private val preferences: StateFlow<AppPreferences>,
    private val realExecutor: VehicleActionExecutor,
) : VehicleActionExecutor {
    override suspend fun execute(command: VehicleActionCommand): VehicleActionExecution =
        if (preferences.value.backendMode == BackendMode.DEMO) {
            VehicleActionExecution(
                applied = true,
                readBackVerified = false,
                mode = VehicleActionMode.SIMULATION,
                message = "Đã thực thi trong chế độ mô phỏng.",
            )
        } else {
            realExecutor.execute(command)
        }
}
