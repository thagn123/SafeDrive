package vn.edu.haui.hvs.safedrive.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test
import vn.edu.haui.hvs.safedrive.core.common.GatewayResult
import vn.edu.haui.hvs.safedrive.core.model.BackendMode
import vn.edu.haui.hvs.safedrive.core.network.EndpointConfig

/**
 * A phone must work without USB reverse or a specific Wi-Fi LAN. Both debug- and release-shaped
 * repositories therefore default to the public HTTPS Cloud Run endpoint. Local port 8000 remains
 * an explicit Developer Mode choice.
 */
class DataStorePreferencesRepositoryTest {

    private fun newDataStore(dir: File): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(produceFile = { File(dir, "test-${System.nanoTime()}.preferences_pb") })

    @Test
    fun `debug-shaped repository defaults to Remote Mode against production cloud`() = runTest {
        val dir = File.createTempFile("prefs", "dir").apply { delete(); mkdirs() }
        val repository = DataStorePreferencesRepository(newDataStore(dir), allowCleartext = true)

        val prefs = repository.preferences.first()

        assertThat(prefs.backendMode).isEqualTo(BackendMode.REMOTE)
        assertThat(prefs.baseUrl).isEqualTo(EndpointConfig.PRODUCTION_BASE_URL)
    }

    @Test
    fun `release-shaped repository defaults to Remote Mode against production cloud`() = runTest {
        val dir = File.createTempFile("prefs", "dir").apply { delete(); mkdirs() }
        val repository = DataStorePreferencesRepository(newDataStore(dir), allowCleartext = false)

        val prefs = repository.preferences.first()

        assertThat(prefs.backendMode).isEqualTo(BackendMode.REMOTE)
        assertThat(prefs.baseUrl).isEqualTo(EndpointConfig.PRODUCTION_BASE_URL)
    }

    @Test
    fun `explicitly set backend mode persists across a new repository instance over the same store`() = runTest {
        val dir = File.createTempFile("prefs", "dir").apply { delete(); mkdirs() }
        val file = File(dir, "shared.preferences_pb")
        val store = PreferenceDataStoreFactory.create(produceFile = { file })
        val first = DataStorePreferencesRepository(store, allowCleartext = true)

        first.setBackendMode(BackendMode.DEMO)

        val second = DataStorePreferencesRepository(store, allowCleartext = true)
        assertThat(second.preferences.first().backendMode).isEqualTo(BackendMode.DEMO)
    }

    @Test
    fun `a valid https URL is accepted and persisted`() = runTest {
        val dir = File.createTempFile("prefs", "dir").apply { delete(); mkdirs() }
        val repository = DataStorePreferencesRepository(newDataStore(dir), allowCleartext = true)

        val result = repository.setBaseUrl("https://backend.example.com")

        assertThat(result).isInstanceOf(GatewayResult.Success::class.java)
        assertThat(repository.preferences.first().baseUrl).isEqualTo("https://backend.example.com/")
    }

    @Test
    fun `a malformed URL is rejected with a user-readable error and does not change the stored URL`() = runTest {
        val dir = File.createTempFile("prefs", "dir").apply { delete(); mkdirs() }
        val repository = DataStorePreferencesRepository(newDataStore(dir), allowCleartext = true)

        val result = repository.setBaseUrl("not a url")

        assertThat(result).isInstanceOf(GatewayResult.Failure::class.java)
        // Still the cloud default -- the rejected input was never persisted.
        assertThat(repository.preferences.first().baseUrl).isEqualTo(EndpointConfig.PRODUCTION_BASE_URL)
    }

    @Test
    fun `legacy LAN endpoint is migrated to cloud and Remote Mode`() = runTest {
        val store = FakePreferencesDataStore(
            mutablePreferencesOf(
                stringPreferencesKey("base_url") to EndpointConfig.LEGACY_LAN_BASE_URL,
                stringPreferencesKey("backend_mode") to BackendMode.DEMO.name,
            ),
        )
        val repository = DataStorePreferencesRepository(store, allowCleartext = true)

        repository.migrateLegacyEndpointToProduction()

        val prefs = repository.preferences.first()
        assertThat(prefs.backendMode).isEqualTo(BackendMode.REMOTE)
        assertThat(prefs.baseUrl).isEqualTo(EndpointConfig.PRODUCTION_BASE_URL)
    }

    @Test
    fun `v2 migration repairs legacy endpoint even when old v1 marker exists`() = runTest {
        val store = FakePreferencesDataStore(
            mutablePreferencesOf(
                stringPreferencesKey("base_url") to EndpointConfig.USB_LOCAL_BASE_URL,
                stringPreferencesKey("backend_mode") to BackendMode.REMOTE.name,
                booleanPreferencesKey("production_endpoint_migrated_v1") to true,
            ),
        )
        val repository = DataStorePreferencesRepository(store, allowCleartext = true)

        repository.migrateLegacyEndpointToProduction()

        val prefs = repository.preferences.first()
        assertThat(prefs.backendMode).isEqualTo(BackendMode.REMOTE)
        assertThat(prefs.baseUrl).isEqualTo(EndpointConfig.PRODUCTION_BASE_URL)
    }

    @Test
    fun `developer can select local port again after the one-time migration`() = runTest {
        val store = FakePreferencesDataStore()
        val repository = DataStorePreferencesRepository(store, allowCleartext = true)
        repository.migrateLegacyEndpointToProduction()

        repository.setBaseUrl(EndpointConfig.USB_LOCAL_BASE_URL)

        assertThat(repository.preferences.first().baseUrl).isEqualTo(EndpointConfig.USB_LOCAL_BASE_URL)
    }
}
