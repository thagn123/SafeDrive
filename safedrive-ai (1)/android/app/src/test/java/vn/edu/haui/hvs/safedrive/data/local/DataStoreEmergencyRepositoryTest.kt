package vn.edu.haui.hvs.safedrive.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import vn.edu.haui.hvs.safedrive.core.common.UuidIdGenerator
import vn.edu.haui.hvs.safedrive.core.model.EmergencyResponseType
import vn.edu.haui.hvs.safedrive.core.model.EmergencyState
import vn.edu.haui.hvs.safedrive.core.model.EvidenceItem
import vn.edu.haui.hvs.safedrive.core.testing.FakeClock
import vn.edu.haui.hvs.safedrive.feature.emergency.EmergencyReducer

/**
 * Uses [FakePreferencesDataStore] (in-memory, no file I/O) so persistence, rotation/process-
 * recreation and corrupt-snapshot recovery are exercised through the real `dataStore.edit{}`/
 * `dataStore.data` contract without hitting a Windows-specific file-rename bug in the real
 * file-backed DataStore factory (see FakePreferencesDataStore's doc comment).
 */
class DataStoreEmergencyRepositoryTest {

    private val evidence = listOf(
        EvidenceItem("crash_detected", "Va chạm", 0L),
        EvidenceItem("passenger_no_response", "Không phản hồi", 0L),
    )

    private fun repositoryFor(dataStore: DataStore<Preferences>, clock: FakeClock) = DataStoreEmergencyRepository(
        dataStore = dataStore,
        clock = clock,
        idGenerator = UuidIdGenerator(),
        reducer = EmergencyReducer(),
    )

    @Test
    fun `startCandidate immediately produces VERIFYING_EVIDENCE with a 5s deadline`() = runTest {
        val clock = FakeClock(1_000L)
        val repository = repositoryFor(FakePreferencesDataStore(), clock)

        val snapshot = repository.startCandidate(evidence)

        assertThat(snapshot.state).isEqualTo(EmergencyState.VERIFYING_EVIDENCE)
        assertThat(snapshot.deadlineMs).isEqualTo(6_000L)
    }

    @Test
    fun `startCandidate while already active does not restart a second emergency`() = runTest {
        val clock = FakeClock(0L)
        val repository = repositoryFor(FakePreferencesDataStore(), clock)

        val first = repository.startCandidate(evidence)
        val second = repository.startCandidate(evidence)

        assertThat(second.emergencyId).isEqualTo(first.emergencyId)
        assertThat(second.state).isEqualTo(first.state)
    }

    @Test
    fun `tick advances all the way to SOS_SIMULATED_SENT exactly once at T+30s`() = runTest {
        val clock = FakeClock(0L)
        val repository = repositoryFor(FakePreferencesDataStore(), clock)
        repository.startCandidate(evidence)

        clock.setNowMs(5_000L)
        repository.tick()
        assertThat(repository.activeSnapshot.value?.state).isEqualTo(EmergencyState.AWAITING_USER_RESPONSE)

        clock.setNowMs(20_000L)
        repository.tick()
        assertThat(repository.activeSnapshot.value?.state).isEqualTo(EmergencyState.FINAL_COUNTDOWN)

        clock.setNowMs(30_000L)
        repository.tick()
        assertThat(repository.activeSnapshot.value?.state).isEqualTo(EmergencyState.SOS_SIMULATED_SENT)

        // A duplicate tick after the deadline must never send a second time.
        clock.setNowMs(999_999L)
        repository.tick()
        assertThat(repository.activeSnapshot.value?.state).isEqualTo(EmergencyState.SOS_SIMULATED_SENT)
    }

    @Test
    fun `tick catches up through multiple expired deadlines in one call after a long absence`() = runTest {
        val clock = FakeClock(0L)
        val repository = repositoryFor(FakePreferencesDataStore(), clock)
        repository.startCandidate(evidence)

        clock.setNowMs(45_000L) // as if the app was closed past all three deadlines
        repository.tick()

        assertThat(repository.activeSnapshot.value?.state).isEqualTo(EmergencyState.SOS_SIMULATED_SENT)
    }

    @Test
    fun `respond CANCEL_SOS during AWAITING_USER_RESPONSE moves to CANCELLED and is idempotent`() = runTest {
        val clock = FakeClock(0L)
        val repository = repositoryFor(FakePreferencesDataStore(), clock)
        repository.startCandidate(evidence)
        clock.setNowMs(5_000L)
        repository.tick()

        val first = repository.respond(EmergencyResponseType.CANCEL_SOS)
        assertThat(first?.state).isEqualTo(EmergencyState.CANCELLED)

        val second = repository.respond(EmergencyResponseType.CANCEL_SOS)
        assertThat(second?.state).isEqualTo(EmergencyState.CANCELLED)
    }

    @Test
    fun `respond after SOS_SIMULATED_SENT is a safe no-op, never un-sends`() = runTest {
        val clock = FakeClock(0L)
        val repository = repositoryFor(FakePreferencesDataStore(), clock)
        repository.startCandidate(evidence)
        clock.setNowMs(30_000L)
        repository.tick()
        assertThat(repository.activeSnapshot.value?.state).isEqualTo(EmergencyState.SOS_SIMULATED_SENT)

        repository.respond(EmergencyResponseType.CANCEL_SOS)
        assertThat(repository.activeSnapshot.value?.state).isEqualTo(EmergencyState.SOS_SIMULATED_SENT)
    }

    @Test
    fun `a fresh repository instance restores the persisted snapshot (rotation, process recreation)`() = runTest {
        val clock = FakeClock(0L)
        val dataStore = FakePreferencesDataStore()
        val first = repositoryFor(dataStore, clock)
        val started = first.startCandidate(evidence)

        // A brand new repository instance backed by the SAME DataStore simulates process death:
        // its in-memory cache (`restored = false`) is gone, so it must re-read persisted state.
        val recreated = repositoryFor(dataStore, clock)
        val restored = recreated.refresh()

        assertThat(restored?.emergencyId).isEqualTo(started.emergencyId)
        assertThat(restored?.state).isEqualTo(EmergencyState.VERIFYING_EVIDENCE)
        assertThat(restored?.deadlineMs).isEqualTo(started.deadlineMs)
    }

    @Test
    fun `an unparseable persisted snapshot resets safely to no active emergency, never to sent`() = runTest {
        val dataStore = FakePreferencesDataStore()
        dataStore.edit { it[stringPreferencesKey("emergency_snapshot")] = "{not valid json" }

        val repository = repositoryFor(dataStore, FakeClock(0L))
        val restored = repository.refresh()

        assertThat(restored).isNull()
    }

    @Test
    fun `clear resets to no active emergency`() = runTest {
        val clock = FakeClock(0L)
        val repository = repositoryFor(FakePreferencesDataStore(), clock)
        repository.startCandidate(evidence)

        repository.clear()

        assertThat(repository.activeSnapshot.value).isNull()
        assertThat(repository.refresh()).isNull()
    }
}
