package vn.edu.haui.hvs.safedrive.domain.usecase

import kotlinx.coroutines.withTimeoutOrNull
import vn.edu.haui.hvs.safedrive.core.common.AppClock
import vn.edu.haui.hvs.safedrive.core.common.GatewayError
import vn.edu.haui.hvs.safedrive.core.common.GatewayResult
import vn.edu.haui.hvs.safedrive.core.model.AssistantContext
import vn.edu.haui.hvs.safedrive.core.model.AssistantQueryRequest
import vn.edu.haui.hvs.safedrive.core.model.AssistantQueryResult
import vn.edu.haui.hvs.safedrive.core.model.AssistantTurnSource

/** Absolute backstop for the whole turn (session resolution + query), regardless of how the
 * gateway internally waits for its answer. Not a routine ceiling: for
 * [vn.edu.haui.hvs.safedrive.data.remote.RemoteSafeDriveGateway], the query itself now runs over a
 * heartbeating WebSocket transport that can legitimately take longer than any single fixed HTTP
 * read timeout (e.g. cold-start Ollama, observed ~15s in manual device testing) as long as the
 * server keeps proving it's still working -- this outer cap only fires for a connection that's
 * genuinely hung. */
private const val ASSISTANT_TURN_TOTAL_TIMEOUT_MS = 30_000L

/**
 * Single entry point for both typed and voice assistant queries (see
 * docs/android-mvp-plan/08-claude-prompts.md, Prompt 3: "Assistant phải dùng một
 * AssistantQueryUseCase cho text và voice"). Empty transcript/text never reaches the gateway.
 *
 * The [ASSISTANT_TURN_TOTAL_TIMEOUT_MS] timeout wraps session resolution **and** the query
 * together — a slow/hanging Remote session-start can no longer let the total wait exceed the
 * budget by waiting a further full network timeout on top (the old GAP-07 double-wait).
 *
 * [requestId] is minted by the caller ([AssistantTurnCoordinator]) — this class never mints its own
 * network request id — so the id used for [InFlightAssistantTurn][vn.edu.haui.hvs.safedrive.core.model.InFlightAssistantTurn]
 * tracking, latency metrics, TTS utterance correlation and retry lineage is always the exact same id
 * that actually went on the wire (remediation item 1). The session used for the query is the exact
 * [ResolvedSession.gateway] this attempt's session resolution returned, never an independently
 * re-resolved gateway (remediation item 3).
 *
 * [clock]/[onTiming] are optional (default no-op) purely for latency instrumentation
 * (docs/android-mvp-plan/12 W4): when provided, [onTiming] reports when session resolution started
 * and when the request was actually sent — [onTiming] is only ever invoked once a session was
 * resolved successfully, immediately before the network call, so a session failure never reports a
 * fabricated "request sent" timestamp for a request that was never sent (remediation item 5).
 */
class AssistantQueryUseCase(
    private val sessionCoordinator: SessionCoordinator,
    private val clock: AppClock? = null,
) {
    suspend operator fun invoke(
        text: String,
        screen: String,
        stateVersion: Long,
        source: AssistantTurnSource = AssistantTurnSource.TEXT,
        requestId: String,
        clientAttemptOf: String? = null,
        onTiming: (sessionStartedAtMs: Long, requestSentAtMs: Long) -> Unit = { _, _ -> },
    ): GatewayResult<AssistantQueryResult> {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) {
            return GatewayResult.Failure(GatewayError.Validation("Câu hỏi trống"))
        }
        return withTimeoutOrNull(ASSISTANT_TURN_TOTAL_TIMEOUT_MS) {
            val sessionStartedAtMs = clock?.nowMs() ?: 0L
            val sessionResult = sessionCoordinator.currentSession()

            when (sessionResult) {
                is GatewayResult.Failure -> sessionResult
                is GatewayResult.Success -> {
                    val requestSentAtMs = clock?.nowMs() ?: 0L
                    onTiming(sessionStartedAtMs, requestSentAtMs)
                    val request = AssistantQueryRequest(
                        sessionId = sessionResult.data.sessionId,
                        requestId = requestId,
                        text = trimmed,
                        source = source,
                        clientAttemptOf = clientAttemptOf,
                        context = AssistantContext(stateVersion = stateVersion, screen = screen),
                    )
                    sessionResult.data.gateway.queryAssistant(request)
                }
            }
        } ?: GatewayResult.Failure(GatewayError.Timeout)
    }
}
