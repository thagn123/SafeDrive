package vn.edu.haui.hvs.safedrive.feature.emergency

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
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

private const val DISPLAY_TICK_MS = 250L
private val HIDDEN_STATES = setOf(EmergencyState.IDLE, EmergencyState.CANCELLED)

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
) : ViewModel() {

    private val displayTicker: Flow<Unit> = flow {
        while (true) {
            emit(Unit)
            delay(DISPLAY_TICK_MS)
        }
    }

    val uiState: StateFlow<EmergencyUiState> = combine(
        emergencyRepository.activeSnapshot,
        preferencesRepository.preferences,
        displayTicker,
    ) { snapshot, prefs, _ ->
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
