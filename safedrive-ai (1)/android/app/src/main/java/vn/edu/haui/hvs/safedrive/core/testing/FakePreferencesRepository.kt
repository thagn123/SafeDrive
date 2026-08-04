package vn.edu.haui.hvs.safedrive.core.testing

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import vn.edu.haui.hvs.safedrive.core.common.GatewayResult
import vn.edu.haui.hvs.safedrive.core.model.BackendMode
import vn.edu.haui.hvs.safedrive.core.model.SimulatedLatencyProfile
import vn.edu.haui.hvs.safedrive.core.network.BaseUrlValidator
import vn.edu.haui.hvs.safedrive.domain.repository.AppPreferences
import vn.edu.haui.hvs.safedrive.domain.repository.PreferencesRepository

/** In-memory [PreferencesRepository] test double — no DataStore/Context required. */
class FakePreferencesRepository(initial: AppPreferences = AppPreferences()) : PreferencesRepository {
    private val state = MutableStateFlow(initial)
    override val preferences: StateFlow<AppPreferences> = state

    override suspend fun setBackendMode(mode: BackendMode) {
        state.value = state.value.copy(backendMode = mode)
    }

    override suspend fun setBaseUrl(url: String): GatewayResult<Unit> =
        when (val validated = BaseUrlValidator.validate(url, allowCleartext = true)) {
            is GatewayResult.Success -> {
                state.value = state.value.copy(baseUrl = validated.data)
                GatewayResult.Success(Unit)
            }
            is GatewayResult.Failure -> validated
        }

    override suspend fun setTtsEnabled(enabled: Boolean) {
        state.value = state.value.copy(ttsEnabled = enabled)
    }

    override suspend fun setWakeWordEnabled(enabled: Boolean) {
        state.value = state.value.copy(wakeWordEnabled = enabled)
    }

    override suspend fun setDeveloperMode(enabled: Boolean) {
        state.value = state.value.copy(developerMode = enabled)
    }

    override suspend fun setDeveloperLatencyProfile(profile: SimulatedLatencyProfile) {
        state.value = state.value.copy(developerLatencyProfile = profile)
    }
}
