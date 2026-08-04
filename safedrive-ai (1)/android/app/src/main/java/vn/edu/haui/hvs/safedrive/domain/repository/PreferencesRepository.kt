package vn.edu.haui.hvs.safedrive.domain.repository

import kotlinx.coroutines.flow.Flow
import vn.edu.haui.hvs.safedrive.core.common.GatewayResult
import vn.edu.haui.hvs.safedrive.core.model.BackendMode
import vn.edu.haui.hvs.safedrive.core.model.SimulatedLatencyProfile

data class AppPreferences(
    val backendMode: BackendMode = BackendMode.DEMO,
    val baseUrl: String = "",
    val ttsEnabled: Boolean = true,
    val wakeWordEnabled: Boolean = true,
    val developerMode: Boolean = false,
    val developerLatencyProfile: SimulatedLatencyProfile = SimulatedLatencyProfile.NONE,
)

/**
 * Persists settings/session/app flags. Never stores secrets and never logs transcripts by default
 * (see docs/android-mvp-plan/02-android-architecture.md, "PreferencesRepository").
 */
interface PreferencesRepository {
    val preferences: Flow<AppPreferences>

    suspend fun setBackendMode(mode: BackendMode)

    /** Validates scheme/host before persisting; rejects arbitrary hosts outside Developer Mode. */
    suspend fun setBaseUrl(url: String): GatewayResult<Unit>

    suspend fun setTtsEnabled(enabled: Boolean)

    suspend fun setWakeWordEnabled(enabled: Boolean)

    suspend fun setDeveloperMode(enabled: Boolean)

    /** Developer Mode only; ignored/reset in production UX flows (docs/android-mvp-plan/12 W4.4). */
    suspend fun setDeveloperLatencyProfile(profile: SimulatedLatencyProfile)
}
