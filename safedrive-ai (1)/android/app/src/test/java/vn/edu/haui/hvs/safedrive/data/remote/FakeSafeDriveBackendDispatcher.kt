package vn.edu.haui.hvs.safedrive.data.remote

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest

/**
 * Tiny fake backend for [RemoteSafeDriveGatewayContractTest]: routes by request path so the shared
 * [SafeDriveGatewayContractTest] methods work unmodified against a real HTTP round-trip (real JSON
 * serialization, real OkHttp/Retrofit call), not just a unit-level mapper test.
 */
class FakeSafeDriveBackendDispatcher : Dispatcher() {
    override fun dispatch(request: RecordedRequest): MockResponse {
        val path = request.path.orEmpty().substringBefore('?')
        val body = when {
            path == "/health" -> HEALTH_JSON
            path == "/ready" -> READY_JSON
            path == "/api/v1/sessions/start" -> SESSION_JSON
            path == "/api/v1/state/update" -> STATE_ENVELOPE_JSON
            path == "/api/v1/state" -> STATE_ENVELOPE_JSON
            path == "/api/v1/assistant/query" -> ASSISTANT_JSON
            path == "/api/v1/events" -> EVENT_ACCEPTED_JSON
            path == "/api/v1/actions/confirm" -> ACTION_CONFIRM_JSON
            path.startsWith("/api/v1/emergency/") -> EMERGENCY_JSON
            else -> return MockResponse().setResponseCode(404)
        }
        return MockResponse().setResponseCode(200).setBody(body).setHeader("Content-Type", "application/json")
    }

    companion object {
        const val HEALTH_JSON = """
            {"status":"NORMAL","service":"safedrive-fake","apiVersion":"v1","serverTimeMs":1000,
             "capabilities":{"assistant":true,"emergencySimulation":true,"cockpitStream":false}}
        """

        const val READY_JSON = """{"status":"ready"}"""

        const val SESSION_JSON = """
            {"sessionId":"sess_fake","expiresAtMs":999999,"serverTimeMs":1000,"contractVersion":"v1",
             "realEmergencyDispatchEnabled":false}
        """

        const val STATE_ENVELOPE_JSON = """
            {
              "state": {
                "speedKmh": 60.0, "engineTemperatureC": 90.0, "cabinTemperatureC": 24.0,
                "energyPercent": 70, "continuousDrivingMinutes": 30, "steeringLastInteractionSeconds": 5,
                "driverSeatOccupied": true, "wearableConnected": false, "activeDtcs": [],
                "crashDetected": false, "passengerResponse": "RESPONSIVE", "updatedAtMs": 1000
              },
              "driverSupportSignals": {
                "steeringSignalAvailable": true, "seatSensorAvailable": true,
                "wearableLastUpdateMs": null, "wearableHeartRateBpm": null,
                "userReportedFatigue": false, "availableSourceCount": 3, "totalSourceCount": 4
              },
              "riskAssessment": {"level":"LOW","title":"On","message":"Nominal","reasonCodes":["system_nominal"]},
              "restRecommendation": {"level":"NORMAL","title":"On","message":"Nominal","confidence":"MEDIUM","reasonCodes":[],"updatedAtMs":1000},
              "stateVersion": 1,
              "acceptedAtMs": 1000
            }
        """

        const val ASSISTANT_JSON = """
            {"requestId":"req_1","message":{"id":"msg_1","sender":"SAFEDRIVE","text":"Xin chào, tôi có thể giúp gì?",
             "timestampMs":1000,"risk":null,"actions":[],"route":"safety_fast_path","latencyMs":100},"serverTimeMs":1000}
        """

        const val EVENT_ACCEPTED_JSON = """{"eventId":"evt_1","accepted":true,"acceptedAtMs":1000,"stateVersion":1}"""

        const val ACTION_CONFIRM_JSON = """{"accepted":true,"actionResult":"ACKNOWLEDGED","message":null,"serverTimeMs":1000}"""

        const val EMERGENCY_JSON = """{"emergencyId":"emg_1","state":"IDLE","deadlineMs":null,"evidence":[],"realEmergencyDispatchEnabled":false}"""
    }
}
