package vn.edu.haui.hvs.safedrive.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import vn.edu.haui.hvs.safedrive.core.common.GatewayResult
import vn.edu.haui.hvs.safedrive.core.model.BackendMode
import vn.edu.haui.hvs.safedrive.core.model.SimulatedLatencyProfile
import vn.edu.haui.hvs.safedrive.core.network.BaseUrlValidator
import vn.edu.haui.hvs.safedrive.domain.repository.AppPreferences
import vn.edu.haui.hvs.safedrive.domain.repository.PreferencesRepository

private object Keys {
    val BACKEND_MODE = stringPreferencesKey("backend_mode")
    val BASE_URL = stringPreferencesKey("base_url")
    val TTS_ENABLED = booleanPreferencesKey("tts_enabled")
    val WAKE_WORD_ENABLED = booleanPreferencesKey("wake_word_enabled")
    val DEVELOPER_MODE = booleanPreferencesKey("developer_mode")
    val DEVELOPER_LATENCY_PROFILE = stringPreferencesKey("developer_latency_profile")
}

/** DataStore-backed [PreferencesRepository]. Never stores secrets or API keys. */
class DataStorePreferencesRepository(
    private val dataStore: DataStore<Preferences>,
    private val allowCleartext: Boolean,
) : PreferencesRepository {

    // Competition/debug builds (allowCleartext=true, same BuildConfig.ALLOW_CLEARTEXT_DEBUG flag
    // as BaseUrlValidator) default to Remote Mode against localhost -- the verified `adb reverse
    // tcp:8000 tcp:8000` path -- so a fresh install demonstrates the real backend/Ollama pipeline
    // without a manual Settings step. A release build (allowCleartext=false) still defaults to
    // Demo, since a cleartext localhost URL would be rejected by BaseUrlValidator there anyway.
    private val defaultBackendMode: BackendMode
        get() = if (allowCleartext) BackendMode.REMOTE else BackendMode.DEMO
    private val defaultBaseUrl: String
        get() = if (allowCleartext) "http://127.0.0.1:8000/" else ""

    override val preferences: Flow<AppPreferences> = dataStore.data.map { prefs ->
        AppPreferences(
            backendMode = prefs[Keys.BACKEND_MODE]?.let { runCatching { BackendMode.valueOf(it) }.getOrNull() }
                ?: defaultBackendMode,
            baseUrl = prefs[Keys.BASE_URL] ?: defaultBaseUrl,
            ttsEnabled = prefs[Keys.TTS_ENABLED] ?: true,
            wakeWordEnabled = prefs[Keys.WAKE_WORD_ENABLED] ?: true,
            developerMode = prefs[Keys.DEVELOPER_MODE] ?: false,
            developerLatencyProfile = prefs[Keys.DEVELOPER_LATENCY_PROFILE]
                ?.let { runCatching { SimulatedLatencyProfile.valueOf(it) }.getOrNull() }
                ?: SimulatedLatencyProfile.NONE,
        )
    }

    override suspend fun setBackendMode(mode: BackendMode) {
        dataStore.edit { it[Keys.BACKEND_MODE] = mode.name }
    }

    override suspend fun setBaseUrl(url: String): GatewayResult<Unit> =
        when (val validated = BaseUrlValidator.validate(url, allowCleartext)) {
            is GatewayResult.Success -> {
                dataStore.edit { it[Keys.BASE_URL] = validated.data }
                GatewayResult.Success(Unit)
            }
            is GatewayResult.Failure -> validated
        }

    override suspend fun setTtsEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.TTS_ENABLED] = enabled }
    }

    override suspend fun setWakeWordEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.WAKE_WORD_ENABLED] = enabled }
    }

    override suspend fun setDeveloperMode(enabled: Boolean) {
        dataStore.edit { it[Keys.DEVELOPER_MODE] = enabled }
    }

    override suspend fun setDeveloperLatencyProfile(profile: SimulatedLatencyProfile) {
        dataStore.edit { it[Keys.DEVELOPER_LATENCY_PROFILE] = profile.name }
    }
}
