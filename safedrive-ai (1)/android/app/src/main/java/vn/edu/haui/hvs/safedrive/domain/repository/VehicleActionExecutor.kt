package vn.edu.haui.hvs.safedrive.domain.repository

sealed interface VehicleActionCommand {
    data class SetHvacTemperature(val celsius: Float) : VehicleActionCommand
    data object LockDoors : VehicleActionCommand
    data object UnlockDoors : VehicleActionCommand
    data object PlayMedia : VehicleActionCommand
}

enum class VehicleActionMode { VEHICLE, SIMULATION, UNAVAILABLE }

data class VehicleActionExecution(
    val applied: Boolean,
    val readBackVerified: Boolean,
    val mode: VehicleActionMode,
    val message: String,
    val reasonCode: String? = null,
)

fun interface VehicleActionExecutor {
    suspend fun execute(command: VehicleActionCommand): VehicleActionExecution
}
