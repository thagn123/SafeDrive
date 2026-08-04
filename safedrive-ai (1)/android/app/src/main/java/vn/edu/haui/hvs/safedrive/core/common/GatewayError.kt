package vn.edu.haui.hvs.safedrive.core.common

/** Typed gateway error mapping per docs/android-mvp-plan/03-data-api-contract.md ("Gateway error mapping"). */
sealed interface GatewayError {
    data object Timeout : GatewayError
    data object Offline : GatewayError
    data object Unauthorized : GatewayError
    data object Unsupported : GatewayError
    data class Conflict(val message: String? = null) : GatewayError
    data class Validation(val message: String? = null) : GatewayError
    data class Server(val code: Int? = null, val message: String? = null) : GatewayError
    data class Protocol(val message: String? = null) : GatewayError

    /** Local, client-side configuration/compatibility problem — never sent over the wire, never
     * mapped to/from an HTTP status (docs/android-mvp-plan/12 W5.6). Two cases today, distinguished by
     * [reasonCode]: `"REMOTE_BASE_URL_MISSING"` (Remote Mode selected with no BASE_URL) and
     * `"CONTRACT_VERSION_INCOMPATIBLE"` ([vn.edu.haui.hvs.safedrive.domain.usecase.SessionCoordinator]
     * received a session whose `contractVersion` does not match this client's expected version). Both
     * must fail fast with a clear message, never silently fall back to Mock or proceed with a query
     * the backend cannot actually service. */
    data class Configuration(val reasonCode: String) : GatewayError

    /** An exception was thrown somewhere in the turn pipeline (session resolution, gateway call,
     * response mapping) instead of a typed [GatewayResult.Failure] being returned — e.g. a malformed
     * response tripping an unchecked mapper exception, or any other unanticipated `RuntimeException`.
     * Distinguished from [Server] (a confirmed backend-side error response) so a caller/log reader can
     * tell "the backend told us it failed" apart from "our own pipeline blew up unexpectedly" (independent
     * re-audit follow-up, blocker 1). Never sent over the wire; only ever constructed locally. */
    data class Unexpected(val message: String? = null) : GatewayError
}
