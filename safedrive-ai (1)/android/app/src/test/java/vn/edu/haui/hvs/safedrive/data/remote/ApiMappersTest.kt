package vn.edu.haui.hvs.safedrive.data.remote

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import vn.edu.haui.hvs.safedrive.core.model.ActionConfirmRequest
import vn.edu.haui.hvs.safedrive.core.model.ActionType
import vn.edu.haui.hvs.safedrive.core.model.SystemConnectionStatus
import vn.edu.haui.hvs.safedrive.core.model.Severity
import vn.edu.haui.hvs.safedrive.data.remote.dto.AssistantQueryResponseDto
import vn.edu.haui.hvs.safedrive.data.remote.dto.ChatMessageDto
import vn.edu.haui.hvs.safedrive.data.remote.dto.EmergencySnapshotDto
import vn.edu.haui.hvs.safedrive.data.remote.dto.HealthCapabilitiesDto
import vn.edu.haui.hvs.safedrive.data.remote.dto.HealthResponseDto
import vn.edu.haui.hvs.safedrive.data.remote.dto.RescueBriefDto
import vn.edu.haui.hvs.safedrive.data.remote.dto.RescueDispatchReceiptDto
import vn.edu.haui.hvs.safedrive.data.remote.dto.RescueLocationDto
import vn.edu.haui.hvs.safedrive.data.remote.dto.SafeDriveActionDto
import vn.edu.haui.hvs.safedrive.data.remote.dto.VehicleLocationDto
import vn.edu.haui.hvs.safedrive.data.remote.dto.VehicleStateDto

class ApiMappersTest {

    @Test
    fun legacyBackendOkHealthMapsToNormalConnection() {
        val result = HealthResponseDto(
            status = "ok",
            service = "SafeDrive AI Backend",
            apiVersion = "1.0.0",
            serverTimeMs = 1_000L,
            capabilities = HealthCapabilitiesDto(
                assistant = true,
                emergencySimulation = true,
                cockpitStream = false,
            ),
        ).toDomain()

        assertThat(result.status).isEqualTo(SystemConnectionStatus.NORMAL)
    }

    @Test
    fun locationAndRescueBriefMapWithoutRelaxingSimulationBoundary() {
        val location = VehicleLocationDto(
            latitude = 21.0285,
            longitude = 105.8542,
            source = "SIMULATOR",
            capturedAtMs = 1_000L,
        )
        val state = VehicleStateDto(
            speedKmh = 0f,
            engineTemperatureC = 88f,
            cabinTemperatureC = 29f,
            energyPercent = 24,
            continuousDrivingMinutes = 245,
            steeringLastInteractionSeconds = 8,
            driverSeatOccupied = true,
            wearableConnected = false,
            activeDtcs = emptyList(),
            crashDetected = true,
            passengerResponse = "NO_RESPONSE",
            updatedAtMs = 1_000L,
            location = location,
        ).toDomain()
        assertThat(state.location?.latitude).isEqualTo(21.0285)

        val emergency = EmergencySnapshotDto(
            emergencyId = "emg_1",
            state = "SOS_SIMULATED_SENT",
            rescueBrief = RescueBriefDto(
                dispatchMode = "SIMULATION_ONLY",
                eventType = "CRASH_AND_NO_RESPONSE",
                vehicleId = "veh_sim_1",
                timestampMs = 1_100L,
                lastKnownLocation = RescueLocationDto(
                    latitude = 21.0285,
                    longitude = 105.8542,
                    source = "SIMULATOR",
                    ageMs = 100L,
                    freshness = "FRESH",
                ),
                locationStatus = "FRESH",
                vehicleStatusSummary = "Crash signal detected. Human verification is required.",
                riskLevel = "CRITICAL",
                evidence = listOf("crash_detected", "occupant_no_response"),
                realEmergencyDispatchEnabled = true,
            ),
            rescueDispatch = RescueDispatchReceiptDto(
                provider = "MOCK_ROADSIDE_ASSISTANCE_GATEWAY",
                endpoint = "mock://safedrive-rescue-gateway/v1/events",
                outcome = "SIMULATED_ACCEPTED",
                referenceId = "mock_rescue_1",
                receivedAtMs = 1_200L,
            ),
            realEmergencyDispatchEnabled = true,
        ).toDomain()

        assertThat(emergency.rescueBrief?.riskLevel).isEqualTo(Severity.CRITICAL)
        assertThat(emergency.rescueBrief?.lastKnownLocation?.freshness).isEqualTo("FRESH")
        assertThat(emergency.rescueDispatch?.outcome).isEqualTo("SIMULATED_ACCEPTED")
        assertThat(emergency.rescueBrief?.realEmergencyDispatchEnabled).isFalse()
        assertThat(emergency.realEmergencyDispatchEnabled).isFalse()
    }

    @Test
    fun typedHvacTargetSurvivesAssistantActionAndConfirmationMappers() {
        val action = SafeDriveActionDto(
            id = "act_hvac_23",
            type = "SET_HVAC_TEMPERATURE",
            title = "Set HVAC temperature",
            requiresConfirmation = true,
            hvacTargetTemperatureC = 23f,
        ).toDomain()

        assertThat(action.type).isEqualTo(ActionType.SET_HVAC_TEMPERATURE)
        assertThat(action.hvacTargetTemperatureC).isEqualTo(23f)

        val confirmation = ActionConfirmRequest(
            sessionId = "session_1",
            actionId = action.id,
            actionType = action.type,
            confirmed = true,
            confirmationId = "confirm_1",
            contextVersion = 2,
            hvacTargetTemperatureC = action.hvacTargetTemperatureC,
        ).toDto()

        assertThat(confirmation.actionType).isEqualTo("SET_HVAC_TEMPERATURE")
        assertThat(confirmation.hvacTargetTemperatureC).isEqualTo(23f)
    }

    @Test
    fun assistantQueryResponseMapsLlmUsedAndFallbackMetadataExplicitly() {
        val successResult = AssistantQueryResponseDto(
            requestId = "req_1",
            message = ChatMessageDto(id = "msg_1", sender = "SAFEDRIVE", text = "OK", timestampMs = 0L),
            serverTimeMs = 0L,
            model = "ollama/qwen2.5:7b-instruct-q4_K_M",
            llmUsed = true,
            fallback = false,
            fallbackReason = null,
        ).toDomain()

        assertThat(successResult.llmUsed).isTrue()
        assertThat(successResult.fallback).isFalse()
        assertThat(successResult.fallbackReason).isNull()

        val fallbackResult = AssistantQueryResponseDto(
            requestId = "req_2",
            message = ChatMessageDto(id = "msg_2", sender = "SAFEDRIVE", text = "OK", timestampMs = 0L),
            serverTimeMs = 0L,
            model = "deterministic-context-router",
            llmUsed = false,
            fallback = true,
            fallbackReason = "provider_unavailable",
        ).toDomain()

        // Never inferred from the model string -- read the explicit fields only.
        assertThat(fallbackResult.llmUsed).isFalse()
        assertThat(fallbackResult.fallback).isTrue()
        assertThat(fallbackResult.fallbackReason).isEqualTo("provider_unavailable")
    }
}
