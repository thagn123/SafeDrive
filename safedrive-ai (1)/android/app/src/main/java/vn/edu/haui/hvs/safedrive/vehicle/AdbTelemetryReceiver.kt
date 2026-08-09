package vn.edu.haui.hvs.safedrive.vehicle

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import vn.edu.haui.hvs.safedrive.core.common.AppClock
import vn.edu.haui.hvs.safedrive.core.model.Dtc
import vn.edu.haui.hvs.safedrive.core.model.Severity
import vn.edu.haui.hvs.safedrive.domain.repository.VehicleDataSource

data class AdbTelemetryCommand(
    val speedKmh: Float? = null,
    val crashDetected: Boolean? = null,
    val heartRateBpm: Int? = null,
    val clearHeartRate: Boolean = false,
    val dtcCode: String? = null,
    val clearDtc: Boolean = false,
)

/**
 * Process-wide gate for developer telemetry. It remains alive while the user navigates from the
 * Simulator to Assistant/Emergency, but rejects commands unless both this gate and the supplied
 * debug/developer policy are enabled.
 */
class AdbTelemetryController(
    private val vehicleDataSource: VehicleDataSource,
    private val clock: AppClock,
    initiallyEnabled: Boolean,
    private val commandsAllowed: () -> Boolean,
) {
    private val _enabled = MutableStateFlow(initiallyEnabled)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    fun setEnabled(enabled: Boolean) {
        _enabled.value = enabled
    }

    fun submit(command: AdbTelemetryCommand): Boolean {
        if (!_enabled.value || !commandsAllowed()) return false

        val vehicleState = vehicleDataSource.vehicleState.value
        val driverSignals = vehicleDataSource.driverSupportSignals.value
        val now = clock.nowMs()
        val dtcCode = command.dtcCode
            ?.trim()
            ?.takeIf { it.matches(Regex("[A-Z0-9_]{1,64}")) }
        val activeDtcs = vehicleState.activeDtcs.toMutableList()

        if (dtcCode != null) {
            if (command.clearDtc) {
                activeDtcs.removeAll { it.code == dtcCode }
            } else if (activeDtcs.none { it.code == dtcCode }) {
                activeDtcs += Dtc(
                    code = dtcCode,
                    title = "Lỗi mô phỏng ($dtcCode)",
                    description = "Tín hiệu Developer Mode được gửi từ PC qua ADB",
                    severity = Severity.CRITICAL,
                    recommendation = "Kiểm tra ngay",
                    updatedAtMs = now,
                )
            }
        }

        vehicleDataSource.updateManual(
            vehicleState = vehicleState.copy(
                speedKmh = command.speedKmh?.coerceIn(0f, MAX_SPEED_KMH) ?: vehicleState.speedKmh,
                crashDetected = command.crashDetected ?: vehicleState.crashDetected,
                activeDtcs = activeDtcs,
                updatedAtMs = now,
            ),
            driverSupportSignals = driverSignals.copy(
                wearableHeartRateBpm = when {
                    command.clearHeartRate -> null
                    command.heartRateBpm != null -> command.heartRateBpm.coerceIn(MIN_HEART_RATE, MAX_HEART_RATE)
                    else -> driverSignals.wearableHeartRateBpm
                },
                wearableLastUpdateMs = when {
                    command.clearHeartRate -> null
                    command.heartRateBpm != null -> now
                    else -> driverSignals.wearableLastUpdateMs
                },
            ),
        )
        return true
    }

    private companion object {
        const val MAX_SPEED_KMH = 300f
        const val MIN_HEART_RATE = 20
        const val MAX_HEART_RATE = 250
    }
}

/** Parses the exported debug broadcast; policy and mutation stay in [AdbTelemetryController]. */
class AdbTelemetryReceiver(
    private val controller: AdbTelemetryController,
) : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action != ACTION_MOCK_TELEMETRY) return
        val heartRatePresent = intent.hasExtra(EXTRA_HEART_RATE)
        val rawHeartRate = intent.getIntExtra(EXTRA_HEART_RATE, -1)
        val accepted = controller.submit(
            AdbTelemetryCommand(
                speedKmh = intent.takeIf { it.hasExtra(EXTRA_SPEED) }
                    ?.getFloatExtra(EXTRA_SPEED, 0f),
                crashDetected = intent.takeIf { it.hasExtra(EXTRA_CRASH) }
                    ?.getBooleanExtra(EXTRA_CRASH, false),
                heartRateBpm = rawHeartRate.takeIf { heartRatePresent && it >= 0 },
                clearHeartRate = heartRatePresent && rawHeartRate < 0,
                dtcCode = intent.getStringExtra(EXTRA_DTC_CODE),
                clearDtc = intent.getBooleanExtra(EXTRA_DTC_CLEAR, false),
            ),
        )
        Log.i(LOG_TAG, if (accepted) "telemetry_applied" else "telemetry_rejected_by_policy")
    }

    companion object {
        const val ACTION_MOCK_TELEMETRY = "vn.edu.haui.hvs.safedrive.action.MOCK_TELEMETRY"
        const val EXTRA_SPEED = "speedKmh"
        const val EXTRA_CRASH = "crashDetected"
        const val EXTRA_HEART_RATE = "heartRate"
        const val EXTRA_DTC_CODE = "dtcCode"
        const val EXTRA_DTC_CLEAR = "dtcClear"
        private const val LOG_TAG = "SafeDriveADB"
    }
}
