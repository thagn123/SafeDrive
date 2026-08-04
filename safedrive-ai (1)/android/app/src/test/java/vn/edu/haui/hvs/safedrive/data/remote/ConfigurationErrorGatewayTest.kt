package vn.edu.haui.hvs.safedrive.data.remote

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import vn.edu.haui.hvs.safedrive.core.common.GatewayResult
import vn.edu.haui.hvs.safedrive.core.model.ActionConfirmRequest
import vn.edu.haui.hvs.safedrive.core.model.ActionType
import vn.edu.haui.hvs.safedrive.core.model.AssistantContext
import vn.edu.haui.hvs.safedrive.core.model.AssistantQueryRequest
import vn.edu.haui.hvs.safedrive.core.model.BackendMode
import vn.edu.haui.hvs.safedrive.core.model.EmergencyResponseRequest
import vn.edu.haui.hvs.safedrive.core.model.EmergencyResponseType
import vn.edu.haui.hvs.safedrive.core.model.SafeDriveEvent
import vn.edu.haui.hvs.safedrive.core.model.SafeDriveEventType
import vn.edu.haui.hvs.safedrive.core.model.StartSessionRequest
import vn.edu.haui.hvs.safedrive.core.model.StateSource
import vn.edu.haui.hvs.safedrive.core.model.StateUpdateRequest
import vn.edu.haui.hvs.safedrive.core.model.EventPayload
import vn.edu.haui.hvs.safedrive.core.testing.driverSupportSignalsFixture
import vn.edu.haui.hvs.safedrive.core.testing.vehicleStateFixture
import vn.edu.haui.hvs.safedrive.core.common.GatewayError

/**
 * Every method must fail fast with `GatewayError.Configuration`, never make a network call and never
 * behave like Mock (docs/android-mvp-plan/12 W5.6) — this is the gateway used when Remote Mode is
 * selected with no BASE_URL configured.
 */
class ConfigurationErrorGatewayTest {

    private val gateway = ConfigurationErrorGateway("REMOTE_BASE_URL_MISSING")

    private fun <T> assertConfigurationFailure(result: GatewayResult<T>) {
        assertThat(result).isInstanceOf(GatewayResult.Failure::class.java)
        val error = (result as GatewayResult.Failure).error
        assertThat(error).isInstanceOf(GatewayError.Configuration::class.java)
        assertThat((error as GatewayError.Configuration).reasonCode).isEqualTo("REMOTE_BASE_URL_MISSING")
    }

    @Test
    fun `checkHealth fails with Configuration`() = runTest {
        assertConfigurationFailure(gateway.checkHealth())
    }

    @Test
    fun `startSession fails with Configuration and never fabricates a session`() = runTest {
        assertConfigurationFailure(
            gateway.startSession(StartSessionRequest("device", "1.0", "android", BackendMode.REMOTE, 0L)),
        )
    }

    @Test
    fun `updateVehicleState fails with Configuration`() = runTest {
        assertConfigurationFailure(
            gateway.updateVehicleState(
                StateUpdateRequest(
                    sessionId = "sess",
                    vehicleState = vehicleStateFixture(),
                    driverSupportSignals = driverSupportSignalsFixture(),
                    source = StateSource.PHONE_SIMULATOR,
                    clientEventId = "evt",
                ),
            ),
        )
    }

    @Test
    fun `getVehicleState fails with Configuration`() = runTest {
        assertConfigurationFailure(gateway.getVehicleState("sess", null))
    }

    @Test
    fun `queryAssistant fails with Configuration`() = runTest {
        assertConfigurationFailure(
            gateway.queryAssistant(AssistantQueryRequest("sess", "req", "hello", context = AssistantContext(0L, "assistant"))),
        )
    }

    @Test
    fun `sendEvent fails with Configuration`() = runTest {
        assertConfigurationFailure(
            gateway.sendEvent(SafeDriveEvent("sess", "evt", SafeDriveEventType.SCENARIO_APPLIED, 0L, EventPayload.ScenarioApplied("x"))),
        )
    }

    @Test
    fun `confirmAction fails with Configuration`() = runTest {
        assertConfigurationFailure(
            gateway.confirmAction(ActionConfirmRequest("sess", "act", ActionType.NONE, true, "conf", 0L)),
        )
    }

    @Test
    fun `getEmergency fails with Configuration`() = runTest {
        assertConfigurationFailure(gateway.getEmergency("emg", "sess"))
    }

    @Test
    fun `respondEmergency fails with Configuration`() = runTest {
        assertConfigurationFailure(
            gateway.respondEmergency(EmergencyResponseRequest("emg", "sess", "resp", EmergencyResponseType.USER_OK, 0L)),
        )
    }
}
