package vn.edu.haui.hvs.safedrive.data.remote

import java.io.IOException
import java.net.SocketTimeoutException
import kotlinx.serialization.SerializationException
import retrofit2.Response
import vn.edu.haui.hvs.safedrive.core.common.GatewayError
import vn.edu.haui.hvs.safedrive.core.common.GatewayResult
import vn.edu.haui.hvs.safedrive.core.network.NetworkModule
import vn.edu.haui.hvs.safedrive.data.remote.dto.ErrorEnvelopeDto
import vn.edu.haui.hvs.safedrive.core.model.ActionConfirmRequest
import vn.edu.haui.hvs.safedrive.core.model.ActionConfirmResult
import vn.edu.haui.hvs.safedrive.core.model.AssistantQueryRequest
import vn.edu.haui.hvs.safedrive.core.model.AssistantQueryResult
import vn.edu.haui.hvs.safedrive.core.model.EmergencyResponseRequest
import vn.edu.haui.hvs.safedrive.core.model.EmergencySnapshot
import vn.edu.haui.hvs.safedrive.core.model.EventAccepted
import vn.edu.haui.hvs.safedrive.core.model.HealthStatus
import vn.edu.haui.hvs.safedrive.core.model.SafeDriveEvent
import vn.edu.haui.hvs.safedrive.core.model.SessionInfo
import vn.edu.haui.hvs.safedrive.core.model.StartSessionRequest
import vn.edu.haui.hvs.safedrive.core.model.StateEnvelope
import vn.edu.haui.hvs.safedrive.core.model.StateUpdateRequest
import vn.edu.haui.hvs.safedrive.domain.repository.SafeDriveGateway

/**
 * Remote Mode implementation of [SafeDriveGateway], matching
 * docs/android-mvp-plan/03-data-api-contract.md's endpoint matrix and error mapping table exactly.
 * Must pass the same contract tests as `MockSafeDriveGateway` — see
 * `SafeDriveGatewayContractTest` and its two subclasses.
 */
class RemoteSafeDriveGateway(private val api: SafeDriveApi) : SafeDriveGateway {

    override suspend fun checkHealth(): GatewayResult<HealthStatus> {
        val liveness = safeCall({ api.checkHealth() }) { it.toDomain() }
        if (liveness is GatewayResult.Failure) return liveness

        // A 200 `/health` only says the process is alive. Do not show a green Remote connection
        // until the backend can also initialize mobile sessions through `/ready`.
        val readiness = safeCall({ api.checkReadiness() }) { it.status == "ready" }
        return when (readiness) {
            is GatewayResult.Failure -> readiness
            is GatewayResult.Success -> if (readiness.data) {
                liveness
            } else {
                GatewayResult.Failure(GatewayError.Protocol("Backend did not report ready"))
            }
        }
    }

    override suspend fun startSession(request: StartSessionRequest): GatewayResult<SessionInfo> =
        safeCall({ api.startSession(request.toDto()) }) { it.toDomain() }

    override suspend fun updateVehicleState(request: StateUpdateRequest): GatewayResult<StateEnvelope> =
        safeCall({ api.updateState(request.toDto()) }) { it.toDomain() }

    override suspend fun getVehicleState(sessionId: String, sinceVersion: Long?): GatewayResult<StateEnvelope> =
        safeCall({ api.getState(sessionId, sinceVersion) }) { it.toDomain() }

    override suspend fun queryAssistant(request: AssistantQueryRequest): GatewayResult<AssistantQueryResult> =
        safeCall({ api.queryAssistant(request.toDto()) }) { it.toDomain() }

    override suspend fun sendEvent(event: SafeDriveEvent): GatewayResult<EventAccepted> =
        safeCall({ api.sendEvent(event.toDto()) }) { it.toDomain() }

    override suspend fun confirmAction(request: ActionConfirmRequest): GatewayResult<ActionConfirmResult> =
        safeCall({ api.confirmAction(request.toDto()) }) { it.toDomain() }

    override suspend fun getEmergency(emergencyId: String, sessionId: String): GatewayResult<EmergencySnapshot> =
        safeCall({ api.getEmergency(emergencyId, sessionId) }) { it.toDomain() }

    override suspend fun respondEmergency(request: EmergencyResponseRequest): GatewayResult<EmergencySnapshot> =
        safeCall({ api.respondEmergency(request.emergencyId, request.toDto()) }) { it.toDomain() }

    private suspend fun <Dto, Domain> safeCall(
        call: suspend () -> Response<Dto>,
        map: (Dto) -> Domain,
    ): GatewayResult<Domain> = try {
        val response = call()
        val body = response.body()
        if (response.isSuccessful && body != null) {
            GatewayResult.Success(map(body))
        } else if (response.isSuccessful) {
            GatewayResult.Failure(GatewayError.Protocol("Empty response body"))
        } else {
            GatewayResult.Failure(mapErrorResponse(response))
        }
    } catch (e: SocketTimeoutException) {
        GatewayResult.Failure(GatewayError.Timeout)
    } catch (e: SerializationException) {
        GatewayResult.Failure(GatewayError.Protocol(e.message))
    } catch (e: IOException) {
        GatewayResult.Failure(GatewayError.Offline)
    }

    /**
     * Parses the response's `ErrorEnvelope` body (docs/android-mvp-plan/12 W7.2,
     * `openapi/safedrive-v1.yaml`) and maps its `code` to the matching typed [GatewayError] — the HTTP
     * status alone is only ever a fallback, used when the body is missing, empty or fails to parse
     * (a backend not yet returning the envelope, a proxy error page, etc.), never a reason to crash.
     */
    private fun mapErrorResponse(response: Response<*>): GatewayError {
        val envelope = parseErrorEnvelope(response)
        return if (envelope != null) mapErrorCode(envelope, response.code()) else mapHttpError(response.code())
    }

    private fun parseErrorEnvelope(response: Response<*>): ErrorEnvelopeDto? {
        val raw = try {
            response.errorBody()?.string()
        } catch (e: IOException) {
            null
        }
        if (raw.isNullOrBlank()) return null
        return try {
            NetworkModule.json.decodeFromString(ErrorEnvelopeDto.serializer(), raw)
        } catch (e: SerializationException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    private fun mapErrorCode(envelope: ErrorEnvelopeDto, httpStatus: Int): GatewayError = when (envelope.code) {
        "TIMEOUT" -> GatewayError.Timeout
        "OFFLINE" -> GatewayError.Offline
        "UNAUTHORIZED" -> GatewayError.Unauthorized
        "UNSUPPORTED" -> GatewayError.Unsupported
        "CONFLICT" -> GatewayError.Conflict(envelope.message)
        "VALIDATION" -> GatewayError.Validation(envelope.message)
        "SERVER" -> GatewayError.Server(httpStatus, envelope.message)
        "PROTOCOL" -> GatewayError.Protocol(envelope.message)
        // An unrecognized code — e.g. a future backend's new value — falls back to the HTTP status
        // alone rather than guessing or crashing.
        else -> mapHttpError(httpStatus)
    }

    private fun mapHttpError(code: Int): GatewayError = when (code) {
        401, 403 -> GatewayError.Unauthorized
        404 -> GatewayError.Unsupported
        409 -> GatewayError.Conflict()
        422 -> GatewayError.Validation()
        in 500..599 -> GatewayError.Server(code)
        else -> GatewayError.Server(code)
    }
}
