package vn.edu.haui.hvs.safedrive.feature.simulator

import vn.edu.haui.hvs.safedrive.core.model.BackendMode
import vn.edu.haui.hvs.safedrive.core.model.CockpitSnapshot
import vn.edu.haui.hvs.safedrive.core.model.ScenarioPreset

enum class DtcSelection { NONE, P0301, OVERHEAT }

/** Manual telemetry override form, mirroring src/presentation/SimulatorScreen.tsx's local form state. */
data class ManualTelemetryForm(
    val speedKmh: Float = 62f,
    val cabinTemperatureC: Float = 25f,
    val engineTemperatureC: Float = 92f,
    val energyPercent: Float = 74f,
    val hasDrivingMinutes: Boolean = true,
    val drivingMinutes: Int = 135,
    val steeringAvailable: Boolean = true,
    val seatAvailable: Boolean = true,
    val seatOccupied: Boolean = true,
    val wearableConnected: Boolean = false,
    val wearableHeartRateBpm: Int = 72,
    val userReportedFatigue: Boolean = false,
    val crashDetected: Boolean = false,
    val passengerUnresponsive: Boolean = false,
    val dtcSelection: DtcSelection = DtcSelection.NONE,
)

/** Simulator state per docs/android-mvp-plan/04-screen-specs.md ("Vehicle Simulator"). Developer-Mode-only. */
data class SimulatorUiState(
    val presets: List<ScenarioPreset> = emptyList(),
    val selectedPresetId: String? = null,
    val manual: ManualTelemetryForm = ManualTelemetryForm(),
    val jsonPreview: String? = null,
    val developerMode: Boolean = false,
    val adbTelemetryEnabled: Boolean = false,
    val backendMode: BackendMode = BackendMode.DEMO,
    /** Visible proof that the state selected on this screen reached the shared cockpit pipeline. */
    val syncMessage: String = "Mô phỏng cục bộ: trạng thái sẽ được áp dụng ngay.",
    val isSynchronizing: Boolean = false,
    val cockpitSnapshot: CockpitSnapshot? = null,
    val speedHistory: List<Float> = emptyList(),
)

/** One-shot Simulator effects (docs/android-mvp-plan/12 W6.6) — never stored back into [SimulatorUiState]. */
sealed interface SimulatorUiEffect {
    data class ShowMessage(val text: String) : SimulatorUiEffect
}
