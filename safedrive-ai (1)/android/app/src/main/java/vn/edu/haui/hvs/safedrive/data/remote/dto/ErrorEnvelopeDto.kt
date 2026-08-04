package vn.edu.haui.hvs.safedrive.data.remote.dto

import kotlinx.serialization.Serializable

/**
 * Matches `openapi/safedrive-v1.yaml`'s `ErrorEnvelope` schema exactly — the typed error body a
 * backend returns on any non-2xx response. [code] maps 1:1 to `GatewayError`'s wire-facing variants
 * (`TIMEOUT`/`OFFLINE`/`UNAUTHORIZED`/`UNSUPPORTED`/`CONFLICT`/`VALIDATION`/`SERVER`/`PROTOCOL`); an
 * unrecognized value (a future backend's new code, or no body/a malformed body at all) falls back to
 * the HTTP status code alone in [vn.edu.haui.hvs.safedrive.data.remote.RemoteSafeDriveGateway] — it
 * never crashes and never silently becomes `Success`.
 */
@Serializable
data class ErrorEnvelopeDto(
    val code: String,
    val message: String,
    val requestId: String? = null,
    val retryable: Boolean = false,
    val serverTimeMs: Long? = null,
)
