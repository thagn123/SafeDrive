package vn.edu.haui.hvs.safedrive.data.mock

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import vn.edu.haui.hvs.safedrive.core.model.RestRecommendationLevel
import vn.edu.haui.hvs.safedrive.core.model.Severity
import vn.edu.haui.hvs.safedrive.core.testing.FakeClock

class MockPolicyEvaluatorTest {

    private val clock = FakeClock(initialMs = 1_000_000L)
    private val fixtures = MockFixtures(clock)
    private val evaluator = MockPolicyEvaluator(clock)

    private fun restLevelFor(scenarioId: String): RestRecommendationLevel {
        val preset = fixtures.scenarioPresets().first { it.id == scenarioId }
        return evaluator.evaluateRestRecommendation(preset.vehicleState, preset.driverSupportSignals).level
    }

    @Test
    fun `new_trip maps to NORMAL, never the prototype's NO_IMMEDIATE_INDICATION`() {
        assertThat(restLevelFor("new_trip")).isEqualTo(RestRecommendationLevel.NORMAL)
    }

    @Test
    fun `over_2h maps to MONITOR`() {
        assertThat(restLevelFor("over_2h")).isEqualTo(RestRecommendationLevel.MONITOR)
    }

    @Test
    fun `consider_rest maps to CONSIDER_REST`() {
        assertThat(restLevelFor("consider_rest")).isEqualTo(RestRecommendationLevel.CONSIDER_REST)
    }

    @Test
    fun `rest_recommended maps to REST_RECOMMENDED`() {
        assertThat(restLevelFor("rest_recommended")).isEqualTo(RestRecommendationLevel.REST_RECOMMENDED)
    }

    @Test
    fun `insufficient_data maps to INSUFFICIENT_DATA`() {
        assertThat(restLevelFor("insufficient_data")).isEqualTo(RestRecommendationLevel.INSUFFICIENT_DATA)
    }

    @Test
    fun `user_reported_fatigue maps to REST_RECOMMENDED regardless of driving duration`() {
        assertThat(restLevelFor("user_reported_fatigue")).isEqualTo(RestRecommendationLevel.REST_RECOMMENDED)
    }

    @Test
    fun `overheat scenario risk is HIGH`() {
        val preset = fixtures.scenarioPresets().first { it.id == "overheat" }
        val rest = evaluator.evaluateRestRecommendation(preset.vehicleState, preset.driverSupportSignals)
        val risk = evaluator.evaluateRisk(preset.vehicleState, rest)
        assertThat(risk.level).isEqualTo(Severity.HIGH)
    }

    @Test
    fun `crash scenario risk is CRITICAL and message reflects no passenger response`() {
        val preset = fixtures.scenarioPresets().first { it.id == "crash" }
        val rest = evaluator.evaluateRestRecommendation(preset.vehicleState, preset.driverSupportSignals)
        val risk = evaluator.evaluateRisk(preset.vehicleState, rest)
        assertThat(risk.level).isEqualTo(Severity.CRITICAL)
        assertThat(risk.reasonCodes).containsAtLeast("crash_detected", "passenger_no_response")
    }

    @Test
    fun `new_trip scenario has LOW risk and system_nominal reason code`() {
        val preset = fixtures.scenarioPresets().first { it.id == "new_trip" }
        val rest = evaluator.evaluateRestRecommendation(preset.vehicleState, preset.driverSupportSignals)
        val risk = evaluator.evaluateRisk(preset.vehicleState, rest)
        assertThat(rest.level).isEqualTo(RestRecommendationLevel.NORMAL)
        assertThat(risk.level).isEqualTo(Severity.LOW)
        assertThat(risk.reasonCodes).contains("system_nominal")
    }

    private fun riskForEngineTemperature(tempC: Float): Severity {
        val state = fixtures.defaultVehicleState().copy(engineTemperatureC = tempC)
        val signals = fixtures.defaultDriverSupportSignals()
        val rest = evaluator.evaluateRestRecommendation(state, signals)
        return evaluator.evaluateRisk(state, rest).level
    }

    // Boundary tests for the Demo-only fallback policy -- must stay numerically identical
    // to the backend's authoritative ENGINE_WARNING_C=105/ENGINE_CRITICAL_C=115
    // (app/mobile/safety.py) so Demo Mode never contradicts a real backend response.
    @Test
    fun `104_9C is below the warning threshold and is not flagged as engine overheat`() {
        assertThat(riskForEngineTemperature(104.9f)).isNotEqualTo(Severity.HIGH)
        assertThat(riskForEngineTemperature(104.9f)).isNotEqualTo(Severity.CRITICAL)
    }

    @Test
    fun `105C is exactly the warning threshold and is HIGH`() {
        assertThat(riskForEngineTemperature(105.0f)).isEqualTo(Severity.HIGH)
    }

    @Test
    fun `114_9C is still HIGH, not yet CRITICAL`() {
        assertThat(riskForEngineTemperature(114.9f)).isEqualTo(Severity.HIGH)
    }

    @Test
    fun `115C is exactly the critical threshold and is CRITICAL`() {
        assertThat(riskForEngineTemperature(115.0f)).isEqualTo(Severity.CRITICAL)
    }

    @Test
    fun `default vehicle state mirrors over_2h and evaluates to MONITOR MEDIUM risk`() {
        val state = fixtures.defaultVehicleState()
        val signals = fixtures.defaultDriverSupportSignals()
        val rest = evaluator.evaluateRestRecommendation(state, signals)
        val risk = evaluator.evaluateRisk(state, rest)
        assertThat(rest.level).isEqualTo(RestRecommendationLevel.MONITOR)
        assertThat(risk.level).isEqualTo(Severity.MEDIUM)
    }
}
