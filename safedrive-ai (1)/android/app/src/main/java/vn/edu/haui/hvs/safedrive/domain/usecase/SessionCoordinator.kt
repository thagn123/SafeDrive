package vn.edu.haui.hvs.safedrive.domain.usecase

import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import vn.edu.haui.hvs.safedrive.core.common.AppClock
import vn.edu.haui.hvs.safedrive.core.common.GatewayError
import vn.edu.haui.hvs.safedrive.core.common.GatewayResult
import vn.edu.haui.hvs.safedrive.core.common.IdGenerator
import vn.edu.haui.hvs.safedrive.core.model.BackendMode
import vn.edu.haui.hvs.safedrive.core.model.StartSessionRequest
import vn.edu.haui.hvs.safedrive.domain.repository.AppPreferences
import vn.edu.haui.hvs.safedrive.domain.repository.GatewayProvider
import vn.edu.haui.hvs.safedrive.domain.repository.SafeDriveGateway

/** The contract (wire-shape) version this build of the app understands, pinned here as the single
 * source of truth rather than scattered as string literals. Matches `info.version`'s major line and
 * `SessionInfo.contractVersion`'s example in `openapi/safedrive-v1.yaml`. A backend reporting a
 * different value is a genuine incompatibility, not a transient error — see [SessionCoordinator]. */
const val EXPECTED_CONTRACT_VERSION: String = "v1"

/**
 * A resolved session id paired with the exact [SafeDriveGateway] instance it was started against.
 * Callers (assistant query, state update, event, action-confirm) must send their follow-up request
 * through [gateway], never through a fresh [GatewayProvider.current] call — that could observe a
 * different backend mode/BASE_URL than the one this session actually belongs to if the user changed
 * Settings in the brief window between session resolution and the follow-up call (remediation item 3:
 * "Preferences và gateway được dùng trong cùng một session attempt phải thuộc cùng một snapshot").
 */
data class ResolvedSession(val sessionId: String, val gateway: SafeDriveGateway)

/**
 * Lazily starts and caches a backend session, keyed by the (mode, baseUrl, expectedContractVersion)
 * triple it was started against (docs/android-mvp-plan/12 W5.1/W5.2, remediation item 3). On Remote
 * failure, this does **not** fabricate a local session id and keep going (W5.5) — the failure is
 * returned to the caller, which must surface it as a real turn/request failure. Demo Mode's
 * `MockSafeDriveGateway.startSession` always succeeds and reports `contractVersion = "v1"`, so Demo
 * Mode is unaffected by this class in practice.
 */
class SessionCoordinator(
    private val gatewayProvider: GatewayProvider,
    private val appPreferences: StateFlow<AppPreferences>,
    private val idGenerator: IdGenerator,
    private val clock: AppClock,
    private val appVersion: String,
    private val expectedContractVersion: String = EXPECTED_CONTRACT_VERSION,
) {
    private val mutex = Mutex()

    /** [gateway] is the exact instance [sessionId] was started against — a cache hit must return
     * *this* instance, never re-resolve one from [gatewayProvider] (remediation item 3 follow-up: even
     * with a correctly-memoized provider, re-resolving on every hit was one extra, unnecessary place a
     * future provider change could reintroduce a mismatch; storing the instance removes that seam
     * entirely). */
    private data class CachedSession(
        val mode: BackendMode,
        val baseUrl: String,
        val contractVersion: String,
        val sessionId: String,
        val gateway: SafeDriveGateway,
        val expiresAtMs: Long,
    )

    @Volatile
    private var cached: CachedSession? = null

    /** Returns the current session (id + the gateway it belongs to), starting or refreshing one if
     * needed. Never returns a fabricated id on failure (W5.5) — callers must treat
     * [GatewayResult.Failure] as the whole operation failing.
     *
     * [gatewayProvider] is only ever consulted on an actual cache miss, inside [mutex] — a cache hit
     * returns the exact [SafeDriveGateway] instance stored in [CachedSession] without calling
     * [GatewayProvider.forPreferences] again. This also closes a startup race: if two callers race on
     * a cold cache, [mutex] serializes them so only the winner ever resolves a gateway/starts a
     * session; the loser's post-lock cache re-check finds the winner's [CachedSession] and reuses its
     * gateway instance verbatim — even if [gatewayProvider] itself returned a different instance on
     * each call, the two callers can never end up pairing one session id with a different gateway than
     * the one it was actually started against (remediation item 3, "cache hit ghép session cũ với
     * gateway mới"). */
    suspend fun currentSession(): GatewayResult<ResolvedSession> {
        val prefs = appPreferences.value
        cachedFor(prefs)?.let { return GatewayResult.Success(ResolvedSession(it.sessionId, it.gateway)) }
        return mutex.withLock {
            cachedFor(prefs)?.let { return@withLock GatewayResult.Success(ResolvedSession(it.sessionId, it.gateway)) }
            val gateway = gatewayProvider.forPreferences(prefs)
            startSession(prefs, gateway)
        }
    }

    private fun cachedFor(prefs: AppPreferences): CachedSession? {
        val current = cached ?: return null
        if (current.mode != prefs.backendMode || current.baseUrl != prefs.baseUrl) return null
        if (current.contractVersion != expectedContractVersion) return null // defensive: never serve a stale-incompatible entry
        if (clock.nowMs() >= current.expiresAtMs) return null // expired — must refresh, not serve stale (W5.3)
        return current
    }

    private suspend fun startSession(prefs: AppPreferences, gateway: SafeDriveGateway): GatewayResult<ResolvedSession> {
        val request = StartSessionRequest(
            deviceId = idGenerator.next("device"),
            appVersion = appVersion,
            platform = "android",
            mode = prefs.backendMode,
            clientTimeMs = clock.nowMs(),
        )
        var result = gateway.startSession(request)
        // Single retry, connection-level errors only, never for the assistant query itself (W5.14).
        if (result is GatewayResult.Failure && isRetryableConnectionError(result.error)) {
            result = gateway.startSession(request)
        }
        return when (result) {
            is GatewayResult.Success -> {
                val info = result.data
                if (info.contractVersion != expectedContractVersion) {
                    // Never cache an incompatible-version session, and never let the assistant query
                    // proceed against a backend whose wire shape this client doesn't understand.
                    cached = null
                    GatewayResult.Failure(GatewayError.Configuration("CONTRACT_VERSION_INCOMPATIBLE"))
                } else {
                    cached = CachedSession(prefs.backendMode, prefs.baseUrl, info.contractVersion, info.sessionId, gateway, info.expiresAtMs)
                    GatewayResult.Success(ResolvedSession(info.sessionId, gateway))
                }
            }

            is GatewayResult.Failure -> {
                cached = null
                result
            }
        }
    }

    private fun isRetryableConnectionError(error: GatewayError): Boolean =
        error is GatewayError.Offline || error is GatewayError.Timeout

    /** Call when backend mode/BASE_URL changes so a fresh session is started against the new gateway. */
    fun invalidate() {
        cached = null
    }
}
