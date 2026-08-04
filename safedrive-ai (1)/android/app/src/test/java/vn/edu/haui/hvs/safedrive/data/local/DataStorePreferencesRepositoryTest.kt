package vn.edu.haui.hvs.safedrive.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test
import vn.edu.haui.hvs.safedrive.core.common.GatewayResult
import vn.edu.haui.hvs.safedrive.core.model.BackendMode

/**
 * Competition MVP requirement: a fresh debug install must default to Remote Mode against the
 * verified `adb reverse tcp:8000 tcp:8000` localhost path, while a release-shaped instance
 * (allowCleartext=false) must still default to Demo -- a cleartext localhost URL would be
 * rejected by BaseUrlValidator there anyway. Mode must also survive repository recreation
 * (proves DataStore persistence, not just an in-memory default).
 */
class DataStorePreferencesRepositoryTest {

    private fun newDataStore(dir: File): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(produceFile = { File(dir, "test-${System.nanoTime()}.preferences_pb") })

    @Test
    fun `debug-shaped repository defaults to Remote Mode against localhost`() = runTest {
        val dir = File.createTempFile("prefs", "dir").apply { delete(); mkdirs() }
        val repository = DataStorePreferencesRepository(newDataStore(dir), allowCleartext = true)

        val prefs = repository.preferences.first()

        assertThat(prefs.backendMode).isEqualTo(BackendMode.REMOTE)
        assertThat(prefs.baseUrl).isEqualTo("http://127.0.0.1:8000/")
    }

    @Test
    fun `release-shaped repository still defaults to Demo Mode`() = runTest {
        val dir = File.createTempFile("prefs", "dir").apply { delete(); mkdirs() }
        val repository = DataStorePreferencesRepository(newDataStore(dir), allowCleartext = false)

        val prefs = repository.preferences.first()

        assertThat(prefs.backendMode).isEqualTo(BackendMode.DEMO)
        assertThat(prefs.baseUrl).isEqualTo("")
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
        // Still the debug default -- the rejected input was never persisted.
        assertThat(repository.preferences.first().baseUrl).isEqualTo("http://127.0.0.1:8000/")
    }
}
