package vn.edu.haui.hvs.safedrive.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import vn.edu.haui.hvs.safedrive.core.common.AppClock
import vn.edu.haui.hvs.safedrive.core.common.IdGenerator
import vn.edu.haui.hvs.safedrive.core.model.EmergencyResponseType
import vn.edu.haui.hvs.safedrive.core.model.EmergencySnapshot
import vn.edu.haui.hvs.safedrive.core.model.EmergencyState
import vn.edu.haui.hvs.safedrive.core.model.EvidenceItem
import vn.edu.haui.hvs.safedrive.domain.repository.EmergencyRepository
import vn.edu.haui.hvs.safedrive.feature.emergency.EmergencyReducer

private val SNAPSHOT_KEY = stringPreferencesKey("emergency_snapshot")
private val RESOLVED_STATES = setOf(EmergencyState.IDLE, EmergencyState.CANCELLED)
private val ACTIVE_RESPONDABLE_STATES = setOf(
    EmergencyState.VERIFYING_EVIDENCE,
    EmergencyState.AWAITING_USER_RESPONSE,
    EmergencyState.FINAL_COUNTDOWN,
)

/**
 * Demo Mode's Emergency authority: a local reducer + DataStore snapshot, per
 * docs/android-mvp-plan/05-voice-emergency.md ("Deadline implementation rules"). `emergencyId`
 * itself is this MVP's idempotency key — every mutation is guarded by the current persisted state,
 * so a duplicate `respond()`/`tick()` call is always a safe no-op.
 */
class DataStoreEmergencyRepository(
    private val dataStore: DataStore<Preferences>,
    private val clock: AppClock,
    private val idGenerator: IdGenerator,
    private val reducer: EmergencyReducer,
) : EmergencyRepository {

    private val _activeSnapshot = MutableStateFlow<EmergencySnapshot?>(null)
    override val activeSnapshot: StateFlow<EmergencySnapshot?> = _activeSnapshot.asStateFlow()

    private val mutex = Mutex()
    private var restored = false

    private suspend fun ensureRestored() {
        if (restored) return
        val persistedJson = dataStore.data.first()[SNAPSHOT_KEY]
        val persisted = persistedJson?.let { json ->
            runCatching { Json.decodeFromString(PersistedEmergencySnapshot.serializer(), json) }.getOrNull()
        }
        // A snapshot that fails to parse resets safely to IDLE rather than guessing a state
        // (docs: "Snapshot hỏng/không parse → reset an toàn về IDLE"). It is never inferred as sent.
        _activeSnapshot.value = persisted?.toDomain()
        restored = true
    }

    override suspend fun startCandidate(evidence: List<EvidenceItem>): EmergencySnapshot {
        ensureRestored()
        return mutex.withLock {
            val current = _activeSnapshot.value
            if (current != null && current.state !in RESOLVED_STATES) {
                return@withLock current // an emergency is already active; never start a second one
            }
            val candidate = EmergencySnapshot(
                emergencyId = idGenerator.next("emg"),
                state = EmergencyState.CANDIDATE_DETECTED,
                deadlineMs = null,
                evidence = evidence,
            )
            persist(candidate)
            val verifying = reducer.startVerifying(candidate, clock.nowMs())
            persist(verifying)
            verifying
        }
    }

    override suspend fun tick() {
        ensureRestored()
        mutex.withLock {
            val initial = _activeSnapshot.value ?: return@withLock
            var current = initial
            while (true) {
                val next = reducer.advance(current, clock.nowMs())
                if (next == current) break
                current = next
            }
            if (current != initial) persist(current)
        }
    }

    override suspend fun respond(response: EmergencyResponseType): EmergencySnapshot? {
        ensureRestored()
        return mutex.withLock {
            val current = _activeSnapshot.value ?: return@withLock null
            if (current.state !in ACTIVE_RESPONDABLE_STATES) {
                return@withLock current // idempotent: already resolved or not started
            }
            val cancelled = current.copy(state = EmergencyState.CANCELLED, deadlineMs = null)
            persist(cancelled)
            cancelled
        }
    }

    override suspend fun refresh(): EmergencySnapshot? {
        ensureRestored()
        return _activeSnapshot.value
    }

    override suspend fun clear() {
        ensureRestored()
        mutex.withLock {
            _activeSnapshot.value = null
            dataStore.edit { it.remove(SNAPSHOT_KEY) }
        }
    }

    private suspend fun persist(snapshot: EmergencySnapshot) {
        _activeSnapshot.value = snapshot
        val json = Json.encodeToString(PersistedEmergencySnapshot.serializer(), snapshot.toPersisted())
        dataStore.edit { it[SNAPSHOT_KEY] = json }
    }
}
