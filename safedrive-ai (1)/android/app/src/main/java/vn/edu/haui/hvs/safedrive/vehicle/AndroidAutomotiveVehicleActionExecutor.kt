package vn.edu.haui.hvs.safedrive.vehicle

import android.app.SearchManager
import android.car.Car
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyConfig
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.provider.MediaStore
import android.view.KeyEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import vn.edu.haui.hvs.safedrive.domain.repository.VehicleActionCommand
import vn.edu.haui.hvs.safedrive.domain.repository.VehicleActionExecution
import vn.edu.haui.hvs.safedrive.domain.repository.VehicleActionExecutor
import vn.edu.haui.hvs.safedrive.domain.repository.VehicleActionMode
import kotlin.math.abs

class AndroidAutomotiveVehicleActionExecutor(
    private val context: Context,
) : VehicleActionExecutor {
    override suspend fun execute(command: VehicleActionCommand): VehicleActionExecution =
        withContext(Dispatchers.IO) {
            try {
                when (command) {
                    is VehicleActionCommand.SetHvacTemperature -> setHvac(command.celsius)
                    VehicleActionCommand.LockDoors -> setDoorLock(true)
                    VehicleActionCommand.UnlockDoors -> setDoorLock(false)
                    VehicleActionCommand.PlayMedia -> playMedia()
                }
            } catch (_: SecurityException) {
                failure("VHAL_PERMISSION_DENIED", "Thiết bị chưa cấp quyền điều khiển xe cho SafeDrive.")
            } catch (error: Throwable) {
                failure("VEHICLE_ADAPTER_ERROR", error.message ?: "Không thể điều khiển xe.")
            }
        }

    private fun propertyManager(): Pair<Car, CarPropertyManager> {
        val car = Car.createCar(context.applicationContext)
        if (!car.isConnected) car.connect()
        val manager = car.getCarManager(CarPropertyManager::class.java)
            ?: error("CarPropertyManager không khả dụng")
        return car to manager
    }

    private fun setHvac(target: Float): VehicleActionExecution {
        val (car, manager) = propertyManager()
        try {
            val temperature = manager.getCarPropertyConfig(VehiclePropertyIds.HVAC_TEMPERATURE_SET)
                ?: return failure("HVAC_PROPERTY_UNAVAILABLE", "Xe không công bố HVAC_TEMPERATURE_SET.")
            manager.getCarPropertyConfig(VehiclePropertyIds.HVAC_POWER_ON)?.let { power ->
                areaIds(power).forEach { area -> manager.setBooleanProperty(power.propertyId, area, true) }
            }
            val areas = areaIds(temperature)
            areas.forEach { area -> manager.setFloatProperty(temperature.propertyId, area, target) }
            val verified = areas.all { area ->
                abs(manager.getFloatProperty(temperature.propertyId, area) - target) <= 0.5f
            }
            return VehicleActionExecution(
                applied = verified,
                readBackVerified = verified,
                mode = VehicleActionMode.VEHICLE,
                message = if (verified) "Điều hòa trên xe đã được đặt ở ${target.toInt()}°C."
                else "Xe chưa xác nhận lại mức điều hòa vừa yêu cầu.",
                reasonCode = if (verified) null else "HVAC_READBACK_MISMATCH",
            )
        } finally {
            car.disconnect()
        }
    }

    private fun setDoorLock(locked: Boolean): VehicleActionExecution {
        val (car, manager) = propertyManager()
        try {
            val config = manager.getCarPropertyConfig(VehiclePropertyIds.DOOR_LOCK)
                ?: return failure("DOOR_PROPERTY_UNAVAILABLE", "Xe không công bố DOOR_LOCK.")
            val areas = areaIds(config)
            areas.forEach { area -> manager.setBooleanProperty(config.propertyId, area, locked) }
            val verified = areas.all { area ->
                manager.getBooleanProperty(config.propertyId, area) == locked
            }
            return VehicleActionExecution(
                applied = verified,
                readBackVerified = verified,
                mode = VehicleActionMode.VEHICLE,
                message = if (verified) {
                    if (locked) "Đã khóa các cửa trên xe." else "Đã mở khóa các cửa trên xe."
                } else {
                    "Xe chưa xác nhận lại trạng thái khóa cửa vừa yêu cầu."
                },
                reasonCode = if (verified) null else "DOOR_READBACK_MISMATCH",
            )
        } finally {
            car.disconnect()
        }
    }

    private fun playMedia(): VehicleActionExecution {
        val intent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
            putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
            putExtra(SearchManager.QUERY, "")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val resolved = intent.resolveActivity(context.packageManager)
        if (resolved != null) {
            context.startActivity(intent)
        }
        val audio = context.getSystemService(AudioManager::class.java)
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY))
        return VehicleActionExecution(
            applied = true,
            readBackVerified = false,
            mode = VehicleActionMode.VEHICLE,
            message = "Đã gửi lệnh phát tới hệ thống media của xe.",
            reasonCode = if (resolved == null) "MEDIA_COMMAND_DISPATCHED_WITHOUT_APP" else null,
        )
    }

    private fun areaIds(config: CarPropertyConfig<*>): IntArray =
        config.areaIds.takeIf { it.isNotEmpty() } ?: intArrayOf(0)

    private fun failure(code: String, message: String) = VehicleActionExecution(
        applied = false,
        readBackVerified = false,
        mode = VehicleActionMode.UNAVAILABLE,
        message = message,
        reasonCode = code,
    )
}
