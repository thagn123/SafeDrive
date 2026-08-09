package vn.edu.haui.hvs.safedrive.vehicle

import android.car.Car
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import vn.edu.haui.hvs.safedrive.core.common.AppClock
import vn.edu.haui.hvs.safedrive.domain.repository.CrashEvidenceAdapter
import vn.edu.haui.hvs.safedrive.domain.repository.CrashEvidenceDecision
import vn.edu.haui.hvs.safedrive.domain.repository.CrashEvidenceFusion
import vn.edu.haui.hvs.safedrive.domain.repository.CrashEvidenceSignal
import vn.edu.haui.hvs.safedrive.domain.repository.CrashEvidenceSource
import vn.edu.haui.hvs.safedrive.domain.repository.PerimeterSensorFaultTracker
import kotlin.math.sqrt

private const val EVIDENCE_WINDOW_MS = 2_000L
private const val HIGH_G_THRESHOLD = 2.5f
private const val SPEED_DROP_MPS = 8.0f

class AndroidCrashEvidenceAdapter(
    private val context: Context,
    private val clock: AppClock,
) : CrashEvidenceAdapter, SensorEventListener,
    CarPropertyManager.CarPropertyEventCallback {

    private val _decisions = MutableSharedFlow<CrashEvidenceDecision>(extraBufferCapacity = 4)
    override val decisions: Flow<CrashEvidenceDecision> = _decisions

    private val sensorManager = context.getSystemService(SensorManager::class.java)
    private val fusion = CrashEvidenceFusion(evidenceWindowMs = EVIDENCE_WINDOW_MS)
    private val perimeterFaults = PerimeterSensorFaultTracker(windowMs = EVIDENCE_WINDOW_MS)
    private var car: Car? = null
    private var properties: CarPropertyManager? = null
    private var lastSpeedMps: Float? = null
    private var lastSpeedAtMs: Long? = null

    override fun start() {
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let { sensor ->
            sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
        }
        runCatching {
            val connectedCar = Car.createCar(context.applicationContext)
            if (!connectedCar.isConnected) connectedCar.connect()
            val manager = connectedCar.getCarManager(CarPropertyManager::class.java)
            car = connectedCar
            properties = manager
            registerIfAvailable(manager, VehiclePropertyIds.IMPACT_DETECTED)
            registerIfAvailable(manager, VehiclePropertyIds.SEAT_AIRBAGS_DEPLOYED)
            registerIfAvailable(manager, VehiclePropertyIds.PERF_VEHICLE_SPEED)
            registerIfAvailable(manager, VehiclePropertyIds.ULTRASONICS_SENSOR_MEASURED_DISTANCE)
        }
    }

    override fun stop() {
        sensorManager.unregisterListener(this)
        properties?.unregisterCallback(this)
        car?.disconnect()
        properties = null
        car = null
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) return
        val magnitude = sqrt(
            event.values[0] * event.values[0] +
                event.values[1] * event.values[1] +
                event.values[2] * event.values[2],
        ) / SensorManager.GRAVITY_EARTH
        if (magnitude >= HIGH_G_THRESHOLD) {
            record(CrashEvidenceSource.DEVICE_IMU, confidence = 0.65f)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    override fun onChangeEvent(value: CarPropertyValue<*>) {
        when (value.propertyId) {
            VehiclePropertyIds.IMPACT_DETECTED -> if (isTriggered(value.value)) {
                record(CrashEvidenceSource.VHAL_IMPACT, confidence = 0.98f)
            }
            VehiclePropertyIds.SEAT_AIRBAGS_DEPLOYED -> if (isTriggered(value.value)) {
                record(CrashEvidenceSource.VHAL_AIRBAG, confidence = 1.0f)
            }
            VehiclePropertyIds.PERF_VEHICLE_SPEED -> {
                val speed = (value.value as? Number)?.toFloat() ?: return
                val now = clock.nowMs()
                val previous = lastSpeedMps
                val previousAt = lastSpeedAtMs
                
                // Track high speed
                if (speed >= 22.2f) { // ~80 km/h
                    record(CrashEvidenceSource.HIGH_SPEED, confidence = 0.8f, timestampMs = now)
                }
                
                if (previous != null && previousAt != null && now - previousAt <= EVIDENCE_WINDOW_MS &&
                    previous - speed >= SPEED_DROP_MPS
                ) {
                    record(CrashEvidenceSource.VHAL_SPEED_DROP, confidence = 0.7f, timestampMs = now)
                }
                lastSpeedMps = speed
                lastSpeedAtMs = now
            }
            VehiclePropertyIds.ULTRASONICS_SENSOR_MEASURED_DISTANCE -> {
                val now = clock.nowMs()
                val severeFault = perimeterFaults.onSensorStatus(
                    areaId = value.areaId,
                    isError = value.propertyStatus == CarPropertyValue.STATUS_ERROR,
                    nowMs = now,
                )
                if (severeFault) {
                    record(CrashEvidenceSource.CRITICAL_SENSOR_FAULT, confidence = 0.6f, timestampMs = now)
                }
            }
        }
    }

    override fun onErrorEvent(propertyId: Int, areaId: Int) = Unit

    override fun injectSignal(source: CrashEvidenceSource, confidence: Float) {
        record(source, confidence)
    }

    private fun record(
        source: CrashEvidenceSource,
        confidence: Float,
        timestampMs: Long = clock.nowMs(),
    ) {
        val decision = fusion.record(CrashEvidenceSignal(source, timestampMs, confidence)) ?: return
        _decisions.tryEmit(decision)
    }

    private fun registerIfAvailable(manager: CarPropertyManager, propertyId: Int) {
        if (manager.getCarPropertyConfig(propertyId) != null) {
            manager.registerCallback(this, propertyId, CarPropertyManager.SENSOR_RATE_FASTEST)
        }
    }

    private fun isTriggered(value: Any?): Boolean = when (value) {
        is Boolean -> value
        is Number -> value.toInt() != 0
        is IntArray -> value.any { it != 0 }
        is Array<*> -> value.any { isTriggered(it) }
        else -> value != null
    }
}
