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
import vn.edu.haui.hvs.safedrive.core.network.EndpointConfig
import vn.edu.haui.hvs.safedrive.domain.repository.AppPreferences
import vn.edu.haui.hvs.safedrive.domain.repository.PreferencesRepository

private object Keys {
    val BACKEND_MODE = stringPreferencesKey("backend_mode")
    val BASE_URL = stringPreferencesKey("base_url")
    val TTS_ENABLED = booleanPreferencesKey("tts_enabled")
    val WAKE_WORD_ENABLED = booleanPreferencesKey("wake_word_enabled")
    val DEVELOPER_MODE = booleanPreferencesKey("developer_mode")
    val DEVELOPER_LATENCY_PROFILE = stringPreferencesKey("developer_latency_profile")
    val PRODUCTION_ENDPOINT_MIGRATED = booleanPreferencesKey("production_endpoint_migrated_v2")
}

/** DataStore-backed [PreferencesRepository]. Never stores secrets or API keys. */
class DataStorePreferencesRepository(
    private val dataStore: DataStore<Preferences>,
    private val allowCleartext: Boolean,
) : PreferencesRepository {

    override val preferences: Flow<AppPreferences> = dataStore.data.map { prefs ->
        val storedBaseUrl = prefs[Keys.BASE_URL]
        val migrationPending = prefs[Keys.PRODUCTION_ENDPOINT_MIGRATED] != true
        val isLegacyDemoEndpoint = storedBaseUrl == null || storedBaseUrl in EndpointConfig.LEGACY_DEMO_BASE_URLS
        AppPreferences(
            backendMode = if (migrationPending && isLegacyDemoEndpoint) {
                BackendMode.REMOTE
            } else {
                prefs[Keys.BACKEND_MODE]?.let { runCatching { BackendMode.valueOf(it) }.getOrNull() }
                    ?: BackendMode.REMOTE
            },
            baseUrl = if (migrationPending && isLegacyDemoEndpoint) {
                EndpointConfig.PRODUCTION_BASE_URL
            } else {
                storedBaseUrl ?: EndpointConfig.PRODUCTION_BASE_URL
            },
            ttsEnabled = prefs[Keys.TTS_ENABLED] ?: true,
            wakeWordEnabled = prefs[Keys.WAKE_WORD_ENABLED] ?: true,
            developerMode = prefs[Keys.DEVELOPER_MODE] ?: false,
            developerLatencyProfile = prefs[Keys.DEVELOPER_LATENCY_PROFILE]
                ?.let { runCatching { SimulatedLatencyProfile.valueOf(it) }.getOrNull() }
                ?: SimulatedLatencyProfile.NONE,
        )
    }

    /**
     * One-time upgrade for APKs that previously pointed phones/AAOS nodes at localhost, the
     * emulator host alias, or a sample LAN address. Merely changing a default is insufficient
     * because DataStore survives `adb install -r` and the v1 migration marker may already have
     * been written by an older CarSky artifact. The v2 marker intentionally re-checks the stored
     * URL once, persists the production URL and Remote mode for known legacy endpoints, then lets
     * a developer deliberately select a local preset again without it being remapped.
     */
    suspend fun migrateLegacyEndpointToProduction() {
        dataStore.edit { prefs ->
            if (prefs[Keys.PRODUCTION_ENDPOINT_MIGRATED] == true) return@edit
            val storedBaseUrl = prefs[Keys.BASE_URL]
            if (storedBaseUrl == null || storedBaseUrl in EndpointConfig.LEGACY_DEMO_BASE_URLS) {
                prefs[Keys.BASE_URL] = EndpointConfig.PRODUCTION_BASE_URL
                prefs[Keys.BACKEND_MODE] = BackendMode.REMOTE.name
            }
            prefs[Keys.PRODUCTION_ENDPOINT_MIGRATED] = true
        }
    }

    override suspend fun setBackendMode(mode: BackendMode) {
        dataStore.edit {
            it[Keys.BACKEND_MODE] = mode.name
            // An explicit user choice always wins over the automatic legacy-endpoint migration.
            it[Keys.PRODUCTION_ENDPOINT_MIGRATED] = true
        }
    }

    override suspend fun setBaseUrl(url: String): GatewayResult<Unit> =
        when (val validated = BaseUrlValidator.validate(url, allowCleartext)) {
            is GatewayResult.Success -> {
                dataStore.edit {
                    it[Keys.BASE_URL] = validated.data
                    // Preserve a deliberate Developer Mode local/LAN selection on future reads.
                    it[Keys.PRODUCTION_ENDPOINT_MIGRATED] = true
                }
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
