package vn.edu.haui.hvs.safedrive.data.mock

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import vn.edu.haui.hvs.safedrive.core.common.GatewayResult
import vn.edu.haui.hvs.safedrive.core.common.UuidIdGenerator
import vn.edu.haui.hvs.safedrive.core.model.AssistantContext
import vn.edu.haui.hvs.safedrive.core.model.AssistantQueryRequest
import vn.edu.haui.hvs.safedrive.core.model.BackendMode
import vn.edu.haui.hvs.safedrive.core.model.SimulatedLatencyProfile
import vn.edu.haui.hvs.safedrive.core.model.StartSessionRequest
import vn.edu.haui.hvs.safedrive.core.model.StateSource
import vn.edu.haui.hvs.safedrive.core.model.StateUpdateRequest
import vn.edu.haui.hvs.safedrive.core.testing.FakeClock

class MockSafeDriveGatewayTest {

    private val clock = FakeClock(initialMs = 1_000_000L)
    private val fixtures = MockFixtures(clock)
    private val gateway = MockSafeDriveGateway(clock, UuidIdGenerator(), fixtures, MockPolicyEvaluator(clock))

    @Test
    fun `checkHealth reports NORMAL with assistant and emergencySimulation capabilities`() = runTest {
        val result = gateway.checkHealth()
        check(result is GatewayResult.Success)
        assertThat(result.data.status.name).isEqualTo("NORMAL")
        assertThat(result.data.capabilities.assistant).isTrue()
        assertThat(result.data.capabilities.emergencySimulation).isTrue()
    }

    @Test
    fun `startSession never enables real emergency dispatch`() = runTest {
        val result = gateway.startSession(
            StartSessionRequest("device_1", "0.1.0", "android", BackendMode.DEMO, clock.nowMs()),
        )
        check(result is GatewayResult.Success)
        assertThat(result.data.realEmergencyDispatchEnabled).isFalse()
        assertThat(result.data.sessionId).isNotEmpty()
    }

    @Test
    fun `updateVehicleState returns a strictly increasing stateVersion`() = runTest {
        val state = fixtures.defaultVehicleState()
        val signals = fixtures.defaultDriverSupportSignals()
        val first = gateway.updateVehicleState(StateUpdateRequest("sess", state, signals, StateSource.MOCK, "evt_1"))
        val second = gateway.updateVehicleState(StateUpdateRequest("sess", state, signals, StateSource.MOCK, "evt_2"))
        check(first is GatewayResult.Success)
        check(second is GatewayResult.Success)
        assertThat(second.data.stateVersion).isGreaterThan(first.data.stateVersion)
    }

    @Test
    fun `getVehicleState returns the last envelope pushed by updateVehicleState`() = runTest {
        val overheat = fixtures.scenarioPresets().first { it.id == "overheat" }
        gateway.updateVehicleState(
            StateUpdateRequest("sess", overheat.vehicleState, overheat.driverSupportSignals, StateSource.PHONE_SIMULATOR, "evt_overheat"),
        )
        val result = gateway.getVehicleState("sess")
        check(result is GatewayResult.Success)
        assertThat(result.data.riskAssessment.level.name).isEqualTo("HIGH")
    }

    @Test
    fun `queryAssistant answers engine temperature question using current vehicle state context`() = runTest {
        gateway.updateVehicleState(
            StateUpdateRequest(
                "sess",
                fixtures.defaultVehicleState().copy(engineTemperatureC = 95f),
                fixtures.defaultDriverSupportSignals(),
                StateSource.MOCK,
                "evt_temp",
            ),
        )
        val result = gateway.queryAssistant(
            AssistantQueryRequest("sess", "req_1", "Nhiệt độ động cơ hiện tại là bao nhiêu?", context = AssistantContext(1, "assistant")),
        )
        check(result is GatewayResult.Success)
        assertThat(result.data.message.text).contains("95")
        assertThat(result.data.requestId).isEqualTo("req_1")
    }

    @Test
    fun `queryAssistant returns unknown-text fallback without crashing on unmatched input`() = runTest {
        val result = gateway.queryAssistant(
            AssistantQueryRequest("sess", "req_2", "asdkjaslkdjalksjd", context = AssistantContext(1, "assistant")),
        )
        check(result is GatewayResult.Success)
        assertThat(result.data.message.text).isNotEmpty()
    }

    @Test
    fun `emergency endpoints are unsupported on the mock gateway since Demo Mode owns emergency locally`() = runTest {
        val getResult = gateway.getEmergency("emg_1", "sess")
        assertThat(getResult).isInstanceOf(GatewayResult.Failure::class.java)
    }

    // --- Developer Mode simulated latency profiles (docs/android-mvp-plan/12 W4.3/W4.4/W8.8) ---

    private fun requestFor(id: String) =
        AssistantQueryRequest("sess", id, "Xin chào", context = AssistantContext(1, "assistant"))

    @Test
    fun `NONE profile (Demo Mode default) adds no delay`() = runTest {
        val g = MockSafeDriveGateway(clock, UuidIdGenerator(), fixtures, MockPolicyEvaluator(clock)) { SimulatedLatencyProfile.NONE }
        g.queryAssistant(requestFor("req_none"))
        assertThat(testScheduler.currentTime).isEqualTo(0L)
    }

    @Test
    fun `MS_500 profile delays by exactly 500ms`() = runTest {
        val g = MockSafeDriveGateway(clock, UuidIdGenerator(), fixtures, MockPolicyEvaluator(clock)) { SimulatedLatencyProfile.MS_500 }
        g.queryAssistant(requestFor("req_500"))
        assertThat(testScheduler.currentTime).isEqualTo(500L)
    }

    @Test
    fun `TIMEOUT profile delays long enough for a 10s caller deadline to fire first`() = runTest {
        val g = MockSafeDriveGateway(clock, UuidIdGenerator(), fixtures, MockPolicyEvaluator(clock)) { SimulatedLatencyProfile.TIMEOUT }
        val result = kotlinx.coroutines.withTimeoutOrNull(10_000L) { g.queryAssistant(requestFor("req_timeout")) }
        assertThat(result).isNull() // the 10s caller-side deadline wins — the mock never resolves within it
    }

    @Test
    fun `each successful reply reports latencyMs matching the active profile`() = runTest {
        val g = MockSafeDriveGateway(clock, UuidIdGenerator(), fixtures, MockPolicyEvaluator(clock)) { SimulatedLatencyProfile.MS_100 }
        val result = g.queryAssistant(requestFor("req_100"))
        check(result is GatewayResult.Success)
        assertThat(result.data.message.latencyMs).isEqualTo(100L)
        assertThat(result.data.serverProcessingMs).isEqualTo(100L)
    }
}
