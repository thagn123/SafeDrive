package vn.edu.haui.hvs.safedrive.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Minimal in-memory [DataStore] test double. The real file-backed `PreferenceDataStoreFactory`
 * writes via a temp-file-then-rename sequence that fails on Windows when the destination already
 * exists (`File.renameTo` does not overwrite there), so a second write to the same DataStore
 * instance always throws in this environment — an environment/library bug unrelated to
 * [DataStoreEmergencyRepository]'s own logic. This fake exercises the exact same
 * `dataStore.edit { ... }` / `dataStore.data` contract without any file I/O.
 */
class FakePreferencesDataStore(initial: Preferences = emptyPreferences()) : DataStore<Preferences> {
    private val state = MutableStateFlow(initial)
    override val data: Flow<Preferences> = state

    override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
        val updated = transform(state.value)
        state.value = updated
        return updated
    }
}
