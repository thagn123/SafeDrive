package vn.edu.haui.hvs.safedrive.feature.emergency

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import vn.edu.haui.hvs.safedrive.core.common.AppClock
import vn.edu.haui.hvs.safedrive.core.model.EmergencyResponseType
import vn.edu.haui.hvs.safedrive.core.model.EmergencyState
import vn.edu.haui.hvs.safedrive.domain.repository.EmergencyRepository
import vn.edu.haui.hvs.safedrive.domain.repository.PreferencesRepository
import vn.edu.haui.hvs.safedrive.domain.repository.VehicleDataSource

private const val DISPLAY_TICK_MS = 250L
private val HIDDEN_STATES = setOf(EmergencyState.IDLE, EmergencyState.CANCELLED)

/** Sampled on a fixed timer (not on `vehicleState` change events) so the sparkline behaves like a
 * real oscilloscope/strip-chart — the line keeps moving even while speed is holding steady,
 * instead of going static between manual Simulator applies (`MockVehicleDataSource` has no
 * periodic auto-tick of its own). */
private const val SPEED_SAMPLE_TICK_MS = 500L

/** 40 samples * 500ms = a 20s rolling window, bounded the same way as the backend's
 * engine-temperature trend (`_ENGINE_TREND_MAX_SAMPLES`, session_store.py) — a fixed cap, never
 * unbounded growth. */
private const val SPEED_HISTORY_MAX_SAMPLES = 40

/**
 * Renders the authoritative snapshot from [EmergencyRepository] — this ViewModel never invents or
 * restarts a countdown itself. [remainingSeconds] is recomputed from the persisted absolute
 * `deadlineMs` on a 250ms display-only ticker (docs/android-mvp-plan/05-voice-emergency.md,
 * "UI chỉ tính max(0, deadlineMs - clock.nowMs())"); the ticker never mutates state, only re-renders.
 */
class EmergencyViewModel(
    private val emergencyRepository: EmergencyRepository,
    private val clock: AppClock,
    preferencesRepository: PreferencesRepository,
    vehicleDataSource: VehicleDataSource,
) : ViewModel() {

    private val displayTicker: Flow<Unit> = flow {
        while (true) {
            emit(Unit)
            delay(DISPLAY_TICK_MS)
        }
    }

    /** A bare `(atMs, speedKmh)` pair would look identical to plain `Float` for equality purposes,
     * but [atMs] is what actually matters here: `MutableStateFlow.value =` silently drops an
     * update whose new value is `equal()` to the current one (StateFlow conflation). Once the
     * rolling window fills with N consecutive identical readings (the very common "speed is
     * holding steady" case), appending one more identical reading and dropping the oldest
     * produces a list that is content-equal to the previous list — without [atMs] making every
     * entry distinct, the flow would silently stop emitting and both the sparkline and the
     * "Hiện tại: X km/h" label would freeze, even though the sampling loop below is still
     * running. Confirmed live: this exact freeze was reported and reproduced on-device. */
    private data class SpeedSample(val atMs: Long, val speedKmh: Float)

    /** Rolling window of recent speed readings for [EvidenceCard]'s Developer-Mode sparkline —
     * "tốc độ thì phải biểu diễn bằng đồ thị" (speed shown as a graph, not just a light),
     * "biểu diễn theo thời gian thực" (rendered live, not a frozen one-shot snapshot). Reads the
     * same live [VehicleDataSource.vehicleState] the Cockpit screen already renders — never a
     * separate, possibly-diverging data path — but samples it on [SPEED_SAMPLE_TICK_MS] rather
     * than on each state-change event, so the trace keeps moving in real time. */
    private val _speedHistory = MutableStateFlow<List<SpeedSample>>(emptyList())

    init {
        viewModelScope.launch {
            while (true) {
                val sample = SpeedSample(clock.nowMs(), vehicleDataSource.vehicleState.value.speedKmh)
                _speedHistory.value = (_speedHistory.value + sample).takeLast(SPEED_HISTORY_MAX_SAMPLES)
                delay(SPEED_SAMPLE_TICK_MS)
            }
        }
    }

    val uiState: StateFlow<EmergencyUiState> = combine(
        emergencyRepository.activeSnapshot,
        preferencesRepository.preferences,
        displayTicker,
        _speedHistory,
    ) { snapshot, prefs, _, speedHistory ->
        if (snapshot == null || snapshot.state in HIDDEN_STATES) {
            EmergencyUiState.Hidden
        } else {
            val remainingMs = (snapshot.deadlineMs?.let { it - clock.nowMs() } ?: 0L).coerceAtLeast(0L)
            EmergencyUiState.Active(
                emergencyId = snapshot.emergencyId,
                state = snapshot.state,
                remainingSeconds = Math.ceil(remainingMs / 1000.0).toInt(),
                evidence = snapshot.evidence,
                rescueBrief = snapshot.rescueBrief,
                rescueDispatch = snapshot.rescueDispatch,
                developerMode = prefs.developerMode,
                reasoningSummary = snapshot.reasoningSummary,
                speedHistoryKmh = speedHistory.map { it.speedKmh },
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), EmergencyUiState.Hidden)

    fun confirmSafe() {
        viewModelScope.launch { emergencyRepository.respond(EmergencyResponseType.USER_OK) }
    }

    fun cancelSos() {
        viewModelScope.launch { emergencyRepository.respond(EmergencyResponseType.CANCEL_SOS) }
    }

    fun acknowledgeSent() {
        viewModelScope.launch { emergencyRepository.clear() }
    }
}
