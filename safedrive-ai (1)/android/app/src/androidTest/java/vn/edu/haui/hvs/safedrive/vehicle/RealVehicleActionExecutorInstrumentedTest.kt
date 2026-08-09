package vn.edu.haui.hvs.safedrive.vehicle

import android.car.Car
import android.car.VehiclePropertyIds
import android.car.hardware.property.CarPropertyManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import vn.edu.haui.hvs.safedrive.core.common.SystemAppClock
import vn.edu.haui.hvs.safedrive.domain.repository.VehicleActionCommand
import vn.edu.haui.hvs.safedrive.domain.repository.VehicleActionMode

@RunWith(AndroidJUnit4::class)
class RealVehicleActionExecutorInstrumentedTest {
    @Test
    fun hvacDoorsAndMedia_executeOnRealAaosAndReadBack() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val car = Car.createCar(context)
        if (!car.isConnected) car.connect()
        val manager = requireNotNull(car.getCarManager(CarPropertyManager::class.java))
        val temperature = requireNotNull(
            manager.getCarPropertyConfig(VehiclePropertyIds.HVAC_TEMPERATURE_SET),
        )
        val doors = requireNotNull(manager.getCarPropertyConfig(VehiclePropertyIds.DOOR_LOCK))
        val temperatureAreas = temperature.areaIds.takeIf { it.isNotEmpty() } ?: intArrayOf(0)
        val doorAreas = doors.areaIds.takeIf { it.isNotEmpty() } ?: intArrayOf(0)
        val originalTemperatures = temperatureAreas.associateWith { area ->
            manager.getFloatProperty(temperature.propertyId, area)
        }
        val originalDoorLocks = doorAreas.associateWith { area ->
            manager.getBooleanProperty(doors.propertyId, area)
        }
        val executor = VehicleActionExecutorFactory.create(context)
        assertTrue(executor is AndroidAutomotiveVehicleActionExecutor)
        assertTrue(CrashEvidenceAdapterFactory.create(context, SystemAppClock()) is AndroidCrashEvidenceAdapter)

        try {
            val hvac = executor.execute(VehicleActionCommand.SetHvacTemperature(26f))
            assertTrue(hvac.applied)
            assertTrue(hvac.readBackVerified)
            assertEquals(VehicleActionMode.VEHICLE, hvac.mode)

            val unlock = executor.execute(VehicleActionCommand.UnlockDoors)
            assertTrue(unlock.applied)
            assertTrue(unlock.readBackVerified)
            assertEquals(VehicleActionMode.VEHICLE, unlock.mode)

            val lock = executor.execute(VehicleActionCommand.LockDoors)
            assertTrue(lock.applied)
            assertTrue(lock.readBackVerified)
            assertEquals(VehicleActionMode.VEHICLE, lock.mode)

            val media = executor.execute(VehicleActionCommand.PlayMedia)
            assertTrue(media.applied)
            assertEquals(VehicleActionMode.VEHICLE, media.mode)

            println(
                "SAFEDRIVE_REAL_VEHICLE_PROBE " +
                    "hvac=${hvac.readBackVerified} " +
                    "unlock=${unlock.readBackVerified} " +
                    "lock=${lock.readBackVerified} " +
                    "media=${media.applied}",
            )
        } finally {
            originalTemperatures.forEach { (area, value) ->
                manager.setFloatProperty(temperature.propertyId, area, value)
            }
            originalDoorLocks.forEach { (area, value) ->
                manager.setBooleanProperty(doors.propertyId, area, value)
            }
            car.disconnect()
        }
    }
}
