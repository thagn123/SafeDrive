package vn.edu.haui.hvs.safedrive.domain.usecase

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Test
import vn.edu.haui.hvs.safedrive.core.common.GatewayError
import vn.edu.haui.hvs.safedrive.core.common.GatewayResult
import vn.edu.haui.hvs.safedrive.core.common.UuidIdGenerator
import vn.edu.haui.hvs.safedrive.core.model.BackendMode
import vn.edu.haui.hvs.safedrive.core.model.SessionInfo
import vn.edu.haui.hvs.safedrive.core.model.StartSessionRequest
import vn.edu.haui.hvs.safedrive.core.testing.FakeClock
import vn.edu.haui.hvs.safedrive.data.mock.MockFixtures
import vn.edu.haui.hvs.safedrive.data.mock.MockPolicyEvaluator
import vn.edu.haui.hvs.safedrive.data.mock.MockSafeDriveGateway
import vn.edu.haui.hvs.safedrive.domain.repository.AppPreferences
import vn.edu.haui.hvs.safedrive.domain.repository.GatewayProvider
import vn.edu.haui.hvs.safedrive.domain.repository.SafeDriveGateway

/**
 * Covers docs/android-mvp-plan/12 W5.1/W5.2/W5.3/W5.5/W5.14 plus the remediation-item-3 fixes: mode
 * reflects actual preference (not hard-coded DEMO), cache is keyed by (mode, baseUrl,
 * expectedContractVersion) with expiry, a Remote failure is returned as-is — never masked by a
 * fabricated local session id — an incompatible `contractVersion` fails fast and is never cached, and
 * every [SessionCoordinator.currentSession] call resolves its gateway via
 * [GatewayProvider.forPreferences] using a single captured preferences snapshot, never a second,
 * independently-re-read [GatewayProvider.current].
 */
class SessionCoordinatorTest {

    private val clock = FakeClock(initialMs = 1_000L)
    private val idGenerator = UuidIdGenerator()
    private val fixtures = MockFixtures(clock)

    /** Records every call so tests can assert [SessionCoordinator] never calls the ambient,
     * snapshot-unsafe [GatewayProvider.current] — only the explicit, snapshot-safe
     * [GatewayProvider.forPreferences] (remediation item 3). */
    private class RecordingGatewayProvider(private val gateway: SafeDriveGateway) : GatewayProvider {
        var currentCallCount = 0
            private set
        var forPreferencesCallCount = 0
            private set
        val forPreferencesArgs = mutableListOf<AppPreferences>()

        override fun current(): SafeDriveGateway {
            currentCallCount++
            return gateway
        }

        override fun forPreferences(prefs: AppPreferences): SafeDriveGateway {
            forPreferencesCallCount++
            forPreferencesArgs.add(prefs)
            return gateway
        }
    }

    private class RecordingGateway(
        private val base: SafeDriveGateway,
        private val onStartSession: (StartSessionRequest) -> GatewayResult<SessionInfo>,
    ) : SafeDriveGateway by base {
        var startSessionCallCount = 0
            private set
        val lastRequest: StartSessionRequest? get() = requests.lastOrNull()
        private val requests = mutableListOf<StartSessionRequest>()

        override suspend fun startSession(request: StartSessionRequest): GatewayResult<SessionInfo> {
            startSessionCallCount++
            requests.add(request)
            return onStartSession(request)
        }
    }

    private fun successResult(sessionId: String, expiresAtMs: Long, contractVersion: String = "v1") = GatewayResult.Success(
        SessionInfo(sessionId = sessionId, expiresAtMs = expiresAtMs, serverTimeMs = clock.nowMs(), contractVersion = contractVersion),
    )

    private fun buildCoordinator(
        gateway: RecordingGateway,
        appPreferences: MutableStateFlow<AppPreferences> = MutableStateFlow(AppPreferences()),
        expectedContractVersion: String = EXPECTED_CONTRACT_VERSION,
    ): Triple<SessionCoordinator, MutableStateFlow<AppPreferences>, RecordingGatewayProvider> {
        val provider = RecordingGatewayProvider(gateway)
        val coordinator = SessionCoordinator(provider, appPreferences, idGenerator, clock, "test", expectedContractVersion)
        return Triple(coordinator, appPreferences, provider)
    }

    private fun baseGateway(): SafeDriveGateway = MockSafeDriveGateway(clock, idGenerator, fixtures, MockPolicyEvaluator(clock))

    @Test
    fun `mode sent to startSession reflects current backend mode, not hard-coded DEMO`() = runTest {
        var capturedMode: BackendMode? = null
        val gateway = RecordingGateway(baseGateway()) { request ->
            capturedMode = request.mode
            successResult("sess_1", clock.nowMs() + 60_000L)
        }
        val (coordinator, prefs, _) = buildCoordinator(gateway)
        prefs.value = AppPreferences(backendMode = BackendMode.REMOTE, baseUrl = "https://api.example.com/")

        coordinator.currentSession()

        assertThat(capturedMode).isEqualTo(BackendMode.REMOTE)
    }

    @Test
    fun `session is cached and reused for the same mode and baseUrl`() = runTest {
        val gateway = RecordingGateway(baseGateway()) { successResult("sess_1", clock.nowMs() + 60_000L) }
        val (coordinator, _, _) = buildCoordinator(gateway)

        val first = coordinator.currentSession()
        val second = coordinator.currentSession()

        check(first is GatewayResult.Success && second is GatewayResult.Success)
        assertThat(first.data.sessionId).isEqualTo("sess_1")
        assertThat(second.data.sessionId).isEqualTo("sess_1")
        assertThat(gateway.startSessionCallCount).isEqualTo(1)
    }

    @Test
    fun `switching baseUrl starts a new session instead of reusing the old one`() = runTest {
        var counter = 0
        val gateway = RecordingGateway(baseGateway()) {
            counter++
            successResult("sess_$counter", clock.nowMs() + 60_000L)
        }
        val (coordinator, prefs, _) = buildCoordinator(
            gateway,
            MutableStateFlow(AppPreferences(backendMode = BackendMode.REMOTE, baseUrl = "https://a.example.com/")),
        )

        val first = coordinator.currentSession()
        prefs.value = prefs.value.copy(baseUrl = "https://b.example.com/")
        val second = coordinator.currentSession()

        check(first is GatewayResult.Success && second is GatewayResult.Success)
        assertThat(first.data.sessionId).isEqualTo("sess_1")
        assertThat(second.data.sessionId).isEqualTo("sess_2")
        assertThat(gateway.startSessionCallCount).isEqualTo(2)
    }

    @Test
    fun `expired session triggers a fresh start`() = runTest {
        var counter = 0
        val gateway = RecordingGateway(baseGateway()) {
            counter++
            successResult("sess_$counter", clock.nowMs() + 1_000L) // expires quickly
        }
        val (coordinator, _, _) = buildCoordinator(gateway)

        val first = coordinator.currentSession()
        clock.advanceBy(2_000L) // past expiry
        val second = coordinator.currentSession()

        check(first is GatewayResult.Success && second is GatewayResult.Success)
        assertThat(first.data.sessionId).isEqualTo("sess_1")
        assertThat(second.data.sessionId).isEqualTo("sess_2")
        assertThat(gateway.startSessionCallCount).isEqualTo(2)
    }

    @Test
    fun `Remote start-session failure is returned as-is, never a fabricated local session id`() = runTest {
        val gateway = RecordingGateway(baseGateway()) { GatewayResult.Failure(GatewayError.Unauthorized) }
        val (coordinator, _, _) = buildCoordinator(gateway)

        val result = coordinator.currentSession()

        assertThat(result).isInstanceOf(GatewayResult.Failure::class.java)
        assertThat((result as GatewayResult.Failure).error).isEqualTo(GatewayError.Unauthorized)
    }

    @Test
    fun `a connection error retries exactly once then succeeds`() = runTest {
        var attempt = 0
        val gateway = RecordingGateway(baseGateway()) {
            attempt++
            if (attempt == 1) GatewayResult.Failure(GatewayError.Offline) else successResult("sess_ok", clock.nowMs() + 60_000L)
        }
        val (coordinator, _, _) = buildCoordinator(gateway)

        val result = coordinator.currentSession()

        check(result is GatewayResult.Success)
        assertThat(result.data.sessionId).isEqualTo("sess_ok")
        assertThat(gateway.startSessionCallCount).isEqualTo(2)
    }

    @Test
    fun `a non-connection error does not retry`() = runTest {
        val gateway = RecordingGateway(baseGateway()) { GatewayResult.Failure(GatewayError.Validation("bad request")) }
        val (coordinator, _, _) = buildCoordinator(gateway)

        val result = coordinator.currentSession()

        assertThat(result).isInstanceOf(GatewayResult.Failure::class.java)
        assertThat(gateway.startSessionCallCount).isEqualTo(1)
    }

    @Test
    fun `invalidate forces the next call to start a new session`() = runTest {
        var counter = 0
        val gateway = RecordingGateway(baseGateway()) {
            counter++
            successResult("sess_$counter", clock.nowMs() + 60_000L)
        }
        val (coordinator, _, _) = buildCoordinator(gateway)

        coordinator.currentSession()
        coordinator.invalidate()
        coordinator.currentSession()

        assertThat(gateway.startSessionCallCount).isEqualTo(2)
    }

    // --- Remediation item 3: contractVersion pinning/compatibility ---

    @Test
    fun `a compatible contractVersion resolves and caches the session normally`() = runTest {
        val gateway = RecordingGateway(baseGateway()) { successResult("sess_1", clock.nowMs() + 60_000L, contractVersion = "v1") }
        val (coordinator, _, _) = buildCoordinator(gateway, expectedContractVersion = "v1")

        val result = coordinator.currentSession()

        check(result is GatewayResult.Success)
        assertThat(result.data.sessionId).isEqualTo("sess_1")
    }

    @Test
    fun `an incompatible contractVersion fails fast with a typed Configuration error, never proceeds`() = runTest {
        val gateway = RecordingGateway(baseGateway()) { successResult("sess_1", clock.nowMs() + 60_000L, contractVersion = "v2") }
        val (coordinator, _, _) = buildCoordinator(gateway, expectedContractVersion = "v1")

        val result = coordinator.currentSession()

        assertThat(result).isInstanceOf(GatewayResult.Failure::class.java)
        val error = (result as GatewayResult.Failure).error
        assertThat(error).isInstanceOf(GatewayError.Configuration::class.java)
        assertThat((error as GatewayError.Configuration).reasonCode).isEqualTo("CONTRACT_VERSION_INCOMPATIBLE")
    }

    @Test
    fun `an incompatible contractVersion session is never cached, so every call re-attempts startSession`() = runTest {
        var counter = 0
        val gateway = RecordingGateway(baseGateway()) {
            counter++
            successResult("sess_$counter", clock.nowMs() + 60_000L, contractVersion = "v2")
        }
        val (coordinator, _, _) = buildCoordinator(gateway, expectedContractVersion = "v1")

        coordinator.currentSession()
        coordinator.currentSession()

        // Never cached (incompatible) — a real backend would reject every request the same way, but
        // the important invariant here is that the client never silently treats it as reusable.
        assertThat(gateway.startSessionCallCount).isEqualTo(2)
    }

    // --- Remediation item 3: gateway/preferences snapshot consistency ---

    @Test
    fun `currentSession resolves its gateway via forPreferences only on a cache miss, never the ambient current(), and a cache hit never re-resolves`() =
        runTest {
            val gateway = RecordingGateway(baseGateway()) { successResult("sess_1", clock.nowMs() + 60_000L) }
            val (coordinator, prefs, provider) = buildCoordinator(
                gateway,
                MutableStateFlow(AppPreferences(backendMode = BackendMode.REMOTE, baseUrl = "https://a.example.com/")),
            )

            coordinator.currentSession() // cache miss -> startSession path, resolves the gateway once
            coordinator.currentSession() // cache hit -> must reuse the stored gateway, not re-resolve

            assertThat(provider.currentCallCount).isEqualTo(0)
            assertThat(provider.forPreferencesCallCount).isEqualTo(1)
            assertThat(provider.forPreferencesArgs).containsExactly(prefs.value)
        }

    @Test
    fun `the ResolvedSession's gateway is the exact instance the session was started against`() = runTest {
        val gateway = RecordingGateway(baseGateway()) { successResult("sess_1", clock.nowMs() + 60_000L) }
        val (coordinator, _, provider) = buildCoordinator(gateway)

        val result = coordinator.currentSession()

        check(result is GatewayResult.Success)
        assertThat(result.data.gateway).isSameInstanceAs(gateway)
        assertThat(provider.forPreferencesCallCount).isAtLeast(1)
    }

    // --- Remediation item 3 (bug 2): a cache hit must never pair a cached session id with a
    // different gateway instance than the one it was actually started against, even if the provider
    // itself is racy/non-memoizing. ---

    @Test
    fun `even when the gateway provider returns a fresh instance on every call, a cache hit still returns the exact instance the session was started against`() =
        runTest {
            var resolveCount = 0
            val provider = object : GatewayProvider {
                override fun current(): SafeDriveGateway = error("must not be called")
                override fun forPreferences(prefs: AppPreferences): SafeDriveGateway {
                    resolveCount++
                    return RecordingGateway(baseGateway()) { successResult("sess_1", clock.nowMs() + 60_000L) }
                }
            }
            val coordinator = SessionCoordinator(provider, MutableStateFlow(AppPreferences()), idGenerator, clock, "test")

            val first = coordinator.currentSession()
            val second = coordinator.currentSession()

            check(first is GatewayResult.Success && second is GatewayResult.Success)
            assertThat(resolveCount).isEqualTo(1) // only the cache-miss path ever resolves a gateway
            assertThat(second.data.gateway).isSameInstanceAs(first.data.gateway)
        }

    @Test
    fun `two concurrent currentSession calls on a cold cache start exactly one session and share the same gateway instance`() = runTest {
        var startCount = 0
        val base = baseGateway()
        val gateway = object : SafeDriveGateway by base {
            override suspend fun startSession(request: StartSessionRequest): GatewayResult<SessionInfo> {
                startCount++
                delay(50) // widen the race window between the two concurrent callers
                return successResult("sess_1", clock.nowMs() + 60_000L)
            }
        }
        val provider = object : GatewayProvider {
            override fun current(): SafeDriveGateway = error("must not be called")
            override fun forPreferences(prefs: AppPreferences): SafeDriveGateway = gateway
        }
        val coordinator = SessionCoordinator(provider, MutableStateFlow(AppPreferences()), idGenerator, clock, "test")

        val first = async { coordinator.currentSession() }
        val second = async { coordinator.currentSession() }
        val results = listOf(first.await(), second.await())

        assertThat(startCount).isEqualTo(1)
        val successes = results.filterIsInstance<GatewayResult.Success<ResolvedSession>>()
        assertThat(successes).hasSize(2)
        assertThat(successes[0].data.sessionId).isEqualTo(successes[1].data.sessionId)
        assertThat(successes[0].data.gateway).isSameInstanceAs(successes[1].data.gateway)
    }
}
