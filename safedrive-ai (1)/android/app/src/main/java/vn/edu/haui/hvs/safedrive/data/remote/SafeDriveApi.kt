package vn.edu.haui.hvs.safedrive.data.remote

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import vn.edu.haui.hvs.safedrive.data.remote.dto.ActionConfirmRequestDto
import vn.edu.haui.hvs.safedrive.data.remote.dto.ActionConfirmResponseDto
import vn.edu.haui.hvs.safedrive.data.remote.dto.AssistantQueryRequestDto
import vn.edu.haui.hvs.safedrive.data.remote.dto.AssistantQueryResponseDto
import vn.edu.haui.hvs.safedrive.data.remote.dto.EmergencyResponseRequestDto
import vn.edu.haui.hvs.safedrive.data.remote.dto.EmergencySnapshotDto
import vn.edu.haui.hvs.safedrive.data.remote.dto.EventAcceptedDto
import vn.edu.haui.hvs.safedrive.data.remote.dto.EventRequestDto
import vn.edu.haui.hvs.safedrive.data.remote.dto.HealthResponseDto
import vn.edu.haui.hvs.safedrive.data.remote.dto.ReadinessResponseDto
import vn.edu.haui.hvs.safedrive.data.remote.dto.StartSessionRequestDto
import vn.edu.haui.hvs.safedrive.data.remote.dto.StartSessionResponseDto
import vn.edu.haui.hvs.safedrive.data.remote.dto.StateEnvelopeDto
import vn.edu.haui.hvs.safedrive.data.remote.dto.StateUpdateRequestDto

/**
 * Retrofit service matching the endpoint matrix in docs/android-mvp-plan/03-data-api-contract.md.
 * `/health` is intentionally outside the versioned `api/v1` path (per that doc's own section
 * headers); everything else is under it.
 */
interface SafeDriveApi {

    @GET("health")
    suspend fun checkHealth(): Response<HealthResponseDto>

    @GET("ready")
    suspend fun checkReadiness(): Response<ReadinessResponseDto>

    @POST("api/v1/sessions/start")
    suspend fun startSession(@Body request: StartSessionRequestDto): Response<StartSessionResponseDto>

    @POST("api/v1/state/update")
    suspend fun updateState(@Body request: StateUpdateRequestDto): Response<StateEnvelopeDto>

    @GET("api/v1/state")
    suspend fun getState(
        @Query("sessionId") sessionId: String,
        @Query("sinceVersion") sinceVersion: Long?,
    ): Response<StateEnvelopeDto>

    @POST("api/v1/assistant/query")
    suspend fun queryAssistant(@Body request: AssistantQueryRequestDto): Response<AssistantQueryResponseDto>

    @POST("api/v1/events")
    suspend fun sendEvent(@Body request: EventRequestDto): Response<EventAcceptedDto>

    @POST("api/v1/actions/confirm")
    suspend fun confirmAction(@Body request: ActionConfirmRequestDto): Response<ActionConfirmResponseDto>

    @GET("api/v1/emergency/{id}")
    suspend fun getEmergency(
        @Path("id") emergencyId: String,
        @Query("sessionId") sessionId: String,
    ): Response<EmergencySnapshotDto>

    @POST("api/v1/emergency/{id}/respond")
    suspend fun respondEmergency(
        @Path("id") emergencyId: String,
        @Body request: EmergencyResponseRequestDto,
    ): Response<EmergencySnapshotDto>
}
