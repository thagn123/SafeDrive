package vn.edu.haui.hvs.safedrive.feature.simulator

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import vn.edu.haui.hvs.safedrive.core.common.AppClock
import vn.edu.haui.hvs.safedrive.core.common.IdGenerator
import vn.edu.haui.hvs.safedrive.core.model.DriverSupportSignals
import vn.edu.haui.hvs.safedrive.core.model.BackendMode
import vn.edu.haui.hvs.safedrive.core.model.CockpitSnapshot
import vn.edu.haui.hvs.safedrive.core.model.EventPayload
import vn.edu.haui.hvs.safedrive.core.model.PassengerResponse
import vn.edu.haui.hvs.safedrive.core.model.SafeDriveEvent
import vn.edu.haui.hvs.safedrive.core.model.SafeDriveEventType
import vn.edu.haui.hvs.safedrive.core.model.ScenarioPreset
import vn.edu.haui.hvs.safedrive.core.model.VehicleState
import vn.edu.haui.hvs.safedrive.data.mock.MockFixtures
import vn.edu.haui.hvs.safedrive.domain.repository.CrashEvidenceAdapter
import vn.edu.haui.hvs.safedrive.domain.repository.CrashEvidenceSource
import vn.edu.haui.hvs.safedrive.domain.repository.PreferencesRepository
import vn.edu.haui.hvs.safedrive.domain.repository.VehicleDataSource
import vn.edu.haui.hvs.safedrive.domain.usecase.SessionCoordinator

/**
 * Developer-Mode-only vehicle simulator. Every apply goes through [VehicleDataSource] so Cockpit,
 * Diagnostics, Assistant and (later) Emergency all observe the same state
 * (docs/android-mvp-plan/04-screen-specs.md, "Vehicle Simulator acceptance").
 */
class SimulatorViewModel(
    private val vehicleDataSource: VehicleDataSource,
    private val fixtures: MockFixtures,
    preferencesRepository: PreferencesRepository,
    private val sessionCoordinator: SessionCoordinator,
    private val idGenerator: IdGenerator,
    private val clock: AppClock,
    private val crashEvidenceAdapter: CrashEvidenceAdapter,
    private val cockpitSnapshot: StateFlow<CockpitSnapshot?> = MutableStateFlow(null),
    private val forceDisableRealtimePollingForTest: Boolean = false,
) : ViewModel() {

    /** Developer-Mode-only test hook: feeds a single signal straight into the real
     * [CrashEvidenceAdapter]/`CrashEvidenceFusion` pipeline exactly as a genuine VHAL/IMU event
     * would (see `AndroidCrashEvidenceAdapter.injectSignal`), so the SOS screen's signal panel and
     * fusion rules (impact/airbag alone vs. IMU+speed-drop together vs. context-only signals) can
     * be observed on any device -- including one with no real Car Service, like a plain phone. */
    fun injectCrashSignal(source: CrashEvidenceSource) {
        crashEvidenceAdapter.injectSignal(source)
    }

    private val speedHistoryLimit = 60 // Giữ lại 60 điểm gần nhất
    private val _speedHistory = MutableStateFlow<List<Float>>(emptyList())

    private data class SimulatorInputs(
        val selectedPresetId: String?,
        val manual: ManualTelemetryForm,
        val jsonPreview: String?,
        val backendMode: BackendMode,
        val developerMode: Boolean,
        val adbTelemetryEnabled: Boolean,
        val lastAppliedAtMs: Long?,
    )

    private val presets = fixtures.scenarioPresets()
    private val _selectedPresetId = MutableStateFlow<String?>(null)
    private val _manual = MutableStateFlow(ManualTelemetryForm())
    private val _adbTelemetryEnabled = MutableStateFlow(false)
    private val _jsonPreview = MutableStateFlow<String?>(null)
    private val _lastAppliedAtMs = MutableStateFlow<Long?>(null)
    private val _effects = Channel<SimulatorUiEffect>(Channel.BUFFERED)
    val effects: Flow<SimulatorUiEffect> = _effects.receiveAsFlow()

    init {
        viewModelScope.launch {
            if (forceDisableRealtimePollingForTest) {
                // Trong Unit Test, chỉ collect thụ động để tránh infinite loop (delay) làm treo test runner.
                vehicleDataSource.vehicleState.collect { state ->
                    _speedHistory.update { history ->
                        val newHistory = history.toMutableList()
                        newHistory.add(state.speedKmh)
                        if (newHistory.size > speedHistoryLimit) newHistory.removeAt(0)
                        newHistory
                    }
                }
            } else {
                while (isActive) {
                    val currentSpeed = vehicleDataSource.vehicleState.value.speedKmh
                    _speedHistory.update { history ->
                        val newHistory = history.toMutableList()
                        newHistory.add(currentSpeed)
                        if (newHistory.size > speedHistoryLimit) {
                            newHistory.removeAt(0)
                        }
                        newHistory
                    }
                    delay(200) // Sample continuously every 200ms for real-time scrolling
                }
            }
        }
    }

    private val inputs = combine(
        _selectedPresetId,
        _manual,
        _jsonPreview,
        preferencesRepository.preferences,
        _lastAppliedAtMs,
    ) { selectedPresetId, manual, jsonPreview, prefs, lastAppliedAtMs ->
        SimulatorInputs(
            selectedPresetId = selectedPresetId,
            manual = manual,
            jsonPreview = jsonPreview,
            backendMode = prefs.backendMode,
            developerMode = prefs.developerMode,
            adbTelemetryEnabled = _adbTelemetryEnabled.value,
            lastAppliedAtMs = lastAppliedAtMs,
        )
    }

    val uiState: StateFlow<SimulatorUiState> = combine(inputs, cockpitSnapshot, _speedHistory) { inputs, snapshot, history ->
        val selectedPresetId = inputs.selectedPresetId
        val manual = inputs.manual
        val jsonPreview = inputs.jsonPreview
        val lastAppliedAtMs = inputs.lastAppliedAtMs
        val remoteSyncComplete = inputs.backendMode == BackendMode.REMOTE &&
            lastAppliedAtMs != null &&
            snapshot != null &&
            snapshot.vehicleState.updatedAtMs >= lastAppliedAtMs
        val syncMessage = when {
            inputs.backendMode == BackendMode.DEMO -> "Mô phỏng cục bộ: trạng thái sẽ được áp dụng ngay."
            lastAppliedAtMs == null -> "Backend thật: hãy chọn kịch bản hoặc áp dụng thông số để đồng bộ."
            remoteSyncComplete -> "Đã đồng bộ backend & state v${snapshot!!.stateVersion}. Trợ lý sẽ đọc trạng thái này."
            else -> "Đang đồng bộ dữ liệu xe với backend..."
        }
        SimulatorUiState(
            presets = presets,
            selectedPresetId = selectedPresetId,
            manual = manual,
            jsonPreview = jsonPreview,
            developerMode = inputs.developerMode,
            backendMode = inputs.backendMode,
            syncMessage = syncMessage,
            isSynchronizing = inputs.backendMode == BackendMode.REMOTE && lastAppliedAtMs != null && !remoteSyncComplete,
            cockpitSnapshot = snapshot,
            speedHistory = history,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SimulatorUiState(presets = presets))

    fun selectPreset(preset: ScenarioPreset) {
        vehicleDataSource.applyScenario(preset)
        _lastAppliedAtMs.value = vehicleDataSource.vehicleState.value.updatedAtMs
        _selectedPresetId.value = preset.id
        _manual.value = formFrom(preset.vehicleState, preset.driverSupportSignals)
        sendScenarioAppliedEvent(preset.id)
    }

    fun updateManual(transform: (ManualTelemetryForm) -> ManualTelemetryForm) {
        _manual.update(transform)
    }

    fun applyManual() {
        val form = _manual.value
        val dtcs = when (form.dtcSelection) {
            DtcSelection.NONE -> emptyList()
            DtcSelection.P0301 -> listOf(fixtures.dtcP0301())
            DtcSelection.OVERHEAT -> listOf(fixtures.dtcOverheat())
        }
        val now = clock.nowMs()
        val vehicleState = VehicleState(
            speedKmh = form.speedKmh,
            engineTemperatureC = form.engineTemperatureC,
            cabinTemperatureC = form.cabinTemperatureC,
            energyPercent = form.energyPercent.toInt(),
            continuousDrivingMinutes = if (form.hasDrivingMinutes) form.drivingMinutes else null,
            steeringLastInteractionSeconds = if (form.steeringAvailable) 10 else null,
            driverSeatOccupied = form.seatOccupied,
            wearableConnected = form.wearableConnected,
            activeDtcs = dtcs,
            crashDetected = form.crashDetected,
            passengerResponse = if (form.passengerUnresponsive) PassengerResponse.NO_RESPONSE else PassengerResponse.RESPONSIVE,
            updatedAtMs = now,
        )
        var availableCount = 0
        if (vehicleState.continuousDrivingMinutes != null) availableCount++
        if (form.steeringAvailable) availableCount++
        if (form.seatAvailable) availableCount++
        if (form.wearableConnected) availableCount++
        val signals = DriverSupportSignals(
            steeringSignalAvailable = form.steeringAvailable,
            seatSensorAvailable = form.seatAvailable,
            wearableLastUpdateMs = if (form.wearableConnected) now - 5_000 else null,
            wearableHeartRateBpm = if (form.wearableConnected) form.wearableHeartRateBpm else null,
            userReportedFatigue = form.userReportedFatigue,
            availableSourceCount = availableCount,
            totalSourceCount = 4,
        )
        vehicleDataSource.updateManual(vehicleState, signals)
        _lastAppliedAtMs.value = vehicleDataSource.vehicleState.value.updatedAtMs
        sendScenarioAppliedEvent("manual")
        viewModelScope.launch {
            _effects.send(SimulatorUiEffect.ShowMessage("Đã áp dụng: ${form.speedKmh.toInt()} km/h"))
        }
    }

    fun resetToDefault() {
        vehicleDataSource.reset()
        _lastAppliedAtMs.value = vehicleDataSource.vehicleState.value.updatedAtMs
        _selectedPresetId.value = null
        _manual.value = formFrom(fixtures.defaultVehicleState(), fixtures.defaultDriverSupportSignals())
        viewModelScope.launch { _effects.send(SimulatorUiEffect.ShowMessage("Đã khôi phục trạng thái mặc định")) }
    }

    fun showJsonPreview() {
        _jsonPreview.value = buildJsonPreview()
    }

    fun toggleAdbTelemetry(enabled: Boolean) {
        _adbTelemetryEnabled.value = enabled
    }

    fun injectAdbTelemetry(
        newSpeed: Float?,
        newCrash: Boolean?,
        newHeartRate: Int?,
        dtcCode: String?,
        dtcClear: Boolean
    ) {
        val currentVehicleState = vehicleDataSource.vehicleState.value
        val currentDriverSignals = vehicleDataSource.driverSupportSignals.value

        val activeDtcs = currentVehicleState.activeDtcs.toMutableList()
        if (dtcCode != null) {
            if (dtcClear) {
                activeDtcs.removeAll { it.code == dtcCode }
            } else {
                if (activeDtcs.none { it.code == dtcCode }) {
                    activeDtcs.add(
                        vn.edu.haui.hvs.safedrive.core.model.Dtc(
                            code = dtcCode,
                            title = "Lỗi ADB ($dtcCode)",
                            description = "Tiêm từ lệnh ADB",
                            severity = vn.edu.haui.hvs.safedrive.core.model.Severity.CRITICAL,
                            recommendation = "Kiểm tra ngay",
                            updatedAtMs = System.currentTimeMillis()
                        )
                    )
                }
            }
        }

        val updatedVehicleState = currentVehicleState.copy(
            speedKmh = newSpeed ?: currentVehicleState.speedKmh,
            crashDetected = newCrash ?: currentVehicleState.crashDetected,
            activeDtcs = activeDtcs,
            updatedAtMs = System.currentTimeMillis()
        )

        val updatedDriverSignals = currentDriverSignals.copy(
            wearableHeartRateBpm = newHeartRate ?: currentDriverSignals.wearableHeartRateBpm,
            wearableLastUpdateMs = if (newHeartRate != null) System.currentTimeMillis() else currentDriverSignals.wearableLastUpdateMs
        )

        vehicleDataSource.updateManual(updatedVehicleState, updatedDriverSignals)
    }

    fun hideJsonPreview() {
        _jsonPreview.value = null
    }

    private fun sendScenarioAppliedEvent(scenarioId: String) {
        viewModelScope.launch {
            val sessionResult = sessionCoordinator.currentSession()
            if (sessionResult !is vn.edu.haui.hvs.safedrive.core.common.GatewayResult.Success) return@launch
            val resolved = sessionResult.data
            val event = SafeDriveEvent(
                sessionId = resolved.sessionId,
                eventId = idGenerator.next("evt"),
                type = SafeDriveEventType.SCENARIO_APPLIED,
                occurredAtMs = clock.nowMs(),
                payload = EventPayload.ScenarioApplied(scenarioId),
            )
            resolved.gateway.sendEvent(event)
        }
    }

    /** No secrets, no API keys — only the current vehicle state/risk snapshot, matching
     * docs/android-mvp-plan/04-screen-specs.md ("JSON preview không chứa secret"). */
    private fun buildJsonPreview(): String {
        val state = vehicleDataSource.vehicleState.value
        val signals = vehicleDataSource.driverSupportSignals.value
        val dtcArray = JsonArray(
            state.activeDtcs.map { dtc ->
                buildJsonObject {
                    put("code", dtc.code)
                    put("severity", dtc.severity.name)
                }
            },
        )
        val vehicleStateJson = buildJsonObject {
            put("speed_kmh", state.speedKmh)
            put("engine_temperature_c", state.engineTemperatureC)
            put("cabin_temperature_c", state.cabinTemperatureC)
            put("energy_percent", state.energyPercent)
            put("continuous_driving_minutes", state.continuousDrivingMinutes)
            put("crash_detected", state.crashDetected)
            put("passenger_response", state.passengerResponse.name)
            put("active_dtcs", dtcArray)
        }
        val signalsJson = buildJsonObject {
            put("steering_signal_available", signals.steeringSignalAvailable)
            put("seat_sensor_available", signals.seatSensorAvailable)
            put("wearable_heart_rate_bpm", signals.wearableHeartRateBpm)
            put("available_source_count", signals.availableSourceCount)
            put("total_source_count", signals.totalSourceCount)
        }
        val json = buildJsonObject {
            put("request_id", idGenerator.next("req"))
            put("timestamp_ms", clock.nowMs())
            put("vehicle_state", vehicleStateJson)
            put("driver_support_signals", signalsJson)
        }
        return Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), json)
    }

    private fun formFrom(vehicleState: VehicleState, signals: DriverSupportSignals) = ManualTelemetryForm(
        speedKmh = vehicleState.speedKmh,
        cabinTemperatureC = vehicleState.cabinTemperatureC,
        engineTemperatureC = vehicleState.engineTemperatureC,
        energyPercent = vehicleState.energyPercent.toFloat(),
        hasDrivingMinutes = vehicleState.continuousDrivingMinutes != null,
        drivingMinutes = vehicleState.continuousDrivingMinutes ?: 0,
        steeringAvailable = signals.steeringSignalAvailable,
        seatAvailable = signals.seatSensorAvailable,
        seatOccupied = vehicleState.driverSeatOccupied ?: true,
        wearableConnected = vehicleState.wearableConnected,
        wearableHeartRateBpm = signals.wearableHeartRateBpm ?: 72,
        userReportedFatigue = signals.userReportedFatigue ?: false,
        crashDetected = vehicleState.crashDetected,
        passengerUnresponsive = vehicleState.passengerResponse == PassengerResponse.NO_RESPONSE,
        dtcSelection = when {
            vehicleState.activeDtcs.any { it.code == "P0301" } -> DtcSelection.P0301
            vehicleState.activeDtcs.any { it.code == "ENGINE_OVERHEAT" } -> DtcSelection.OVERHEAT
            else -> DtcSelection.NONE
        },
    )
}
