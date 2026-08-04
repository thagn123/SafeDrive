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
import vn.edu.haui.hvs.safedrive.core.model.ChatSender
import vn.edu.haui.hvs.safedrive.core.model.StartSessionRequest
import vn.edu.haui.hvs.safedrive.core.model.StateSource
import vn.edu.haui.hvs.safedrive.core.model.StateUpdateRequest
import vn.edu.haui.hvs.safedrive.domain.repository.SafeDriveGateway

/**
 * Shared contract per docs/android-mvp-plan/02-android-architecture.md ("MockSafeDriveGateway và
 * RemoteSafeDriveGateway phải pass cùng contract tests"). Subclasses only provide a
 * [SafeDriveGateway] instance and vehicle-state fixtures — every assertion here must hold for both
 * Demo and Remote Mode.
 */
abstract class SafeDriveGatewayContractTest {

    abstract fun buildGateway(): SafeDriveGateway
    abstract fun sampleVehicleState(): vn.edu.haui.hvs.safedrive.core.model.VehicleState
    abstract fun sampleDriverSupportSignals(): vn.edu.haui.hvs.safedrive.core.model.DriverSupportSignals

    @Test
    fun `checkHealth succeeds and reports assistant capability`() = runTest {
        val result = buildGateway().checkHealth()
        check(result is GatewayResult.Success)
        assertThat(result.data.capabilities.assistant).isTrue()
    }

    @Test
    fun `startSession never enables real emergency dispatch`() = runTest {
        val result = buildGateway().startSession(
            StartSessionRequest("device_1", "0.1.0", "android", BackendMode.DEMO, 1_000L),
        )
        check(result is GatewayResult.Success)
        assertThat(result.data.realEmergencyDispatchEnabled).isFalse()
        assertThat(result.data.sessionId).isNotEmpty()
    }

    @Test
    fun `updateVehicleState returns a state envelope with a stateVersion`() = runTest {
        val result = buildGateway().updateVehicleState(
            StateUpdateRequest("sess_1", sampleVehicleState(), sampleDriverSupportSignals(), StateSource.PHONE_SIMULATOR, "evt_1"),
        )
        check(result is GatewayResult.Success)
        assertThat(result.data.stateVersion).isAtLeast(0L)
        assertThat(result.data.riskAssessment.title).isNotEmpty()
    }

    @Test
    fun `queryAssistant returns a non-empty SAFEDRIVE reply for the same requestId`() = kotlinx.coroutines.runBlocking {
        // runBlocking, not runTest: RemoteSafeDriveGateway's implementation now does real
        // WebSocket I/O internally, which kotlinx-coroutines-test's virtual-time scheduler
        // doesn't reliably wake up for (real OkHttp callback threads vs. a StandardTestDispatcher).
        val result = buildGateway().queryAssistant(
            AssistantQueryRequest("sess_1", "req_1", "Xin chào", context = AssistantContext(0L, "assistant")),
        )
        check(result is GatewayResult.Success)
        assertThat(result.data.requestId).isEqualTo("req_1")
        assertThat(result.data.message.sender).isEqualTo(ChatSender.SAFEDRIVE)
        assertThat(result.data.message.text).isNotEmpty()
    }

    @Test
    fun `confirmAction reflects the confirmed flag in accepted`() = runTest {
        val result = buildGateway().confirmAction(
            ActionConfirmRequest("sess_1", "act_1", ActionType.SUGGEST_REST_STOP, confirmed = true, "conf_1", 0L),
        )
        check(result is GatewayResult.Success)
        assertThat(result.data.accepted).isTrue()
    }
}
