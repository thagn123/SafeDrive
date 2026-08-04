import time

import httpx
import pytest
from httpx import ASGITransport, AsyncClient
from pydantic import SecretStr

from app.core.config import Settings
from app.main import create_app


def mobile_settings() -> Settings:
    return Settings(
        environment="test",
        active_profile="DMS_DEMO",
        safedrive_api_key=SecretStr("canonical-api-key"),
    )


def session_payload() -> dict[str, object]:
    return {
        "deviceId": "android-demo-device",
        "appVersion": "1.0.0",
        "platform": "ANDROID",
        "mode": "REMOTE",
        "clientTimeMs": int(time.time() * 1_000),
    }


def state_payload(
    session_id: str, *, crash: bool = False, passenger: str = "UNKNOWN"
) -> dict[str, object]:
    timestamp = int(time.time() * 1_000)
    return {
        "sessionId": session_id,
        "state": {
            "speedKmh": 72.0,
            "engineTemperatureC": 88.0,
            "cabinTemperatureC": 32.0,
            "energyPercent": 24,
            "continuousDrivingMinutes": 245,
            "steeringLastInteractionSeconds": 8,
            "driverSeatOccupied": True,
            "wearableConnected": False,
            "activeDtcs": [],
            "crashDetected": crash,
            "passengerResponse": passenger,
            "updatedAtMs": timestamp,
        },
        "driverSupportSignals": {
            "steeringSignalAvailable": True,
            "seatSensorAvailable": True,
            "wearableLastUpdateMs": None,
            "wearableHeartRateBpm": None,
            "userReportedFatigue": True,
            "availableSourceCount": 2,
            "totalSourceCount": 4,
        },
        "source": "PHONE_SIMULATOR",
        "clientEventId": f"event-{timestamp}",
    }


@pytest.mark.asyncio
async def test_mobile_contract_supports_session_state_assistant_and_emergency() -> None:
    app = create_app(settings=mobile_settings())
    async with (
        app.router.lifespan_context(app),
        AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client,
    ):
        health = await client.get("/health")
        assert health.status_code == 200
        assert health.json()["status"] == "ok"
        assert health.json()["service"] == "SafeDrive AI Backend"
        assert health.json()["capabilities"] == {
            "assistant": True,
            "emergencySimulation": True,
            "cockpitStream": False,
        }

        started = await client.post("/api/v1/sessions/start", json=session_payload())
        assert started.status_code == 200
        session = started.json()
        assert session["realEmergencyDispatchEnabled"] is False
        # Must match openapi/safedrive-v1.yaml's SessionInfo.contractVersion example ("v1") exactly:
        # Android's SessionCoordinator rejects any other value as CONTRACT_VERSION_INCOMPATIBLE.
        assert session["contractVersion"] == "v1"
        session_id = session["sessionId"]

        updated = await client.post("/api/v1/state/update", json=state_payload(session_id))
        assert updated.status_code == 200
        envelope = updated.json()
        assert envelope["stateVersion"] == 1
        assert envelope["riskAssessment"]["level"] == "HIGH"
        assert "driving_over_4_hours" in envelope["riskAssessment"]["reasonCodes"]

        canonical_states = list(app.state.services.latest_state_manager.states.values())
        assert len(canonical_states) == 1
        assert canonical_states[0].components["vehicle.speed_kmh"].value["value"] == 72.0
        assert canonical_states[0].components["hvac.temperature"].value["temperature"] == 32.0

        restored = await client.get("/api/v1/state", params={"sessionId": session_id})
        assert restored.status_code == 200
        assert restored.json()["stateVersion"] == 1

        assistant = await client.post(
            "/api/v1/assistant/query",
            json={
                "sessionId": session_id,
                "requestId": "request-fatigue-1",
                "text": "Toi hoi buon ngu",
                "source": "VOICE",
                "locale": "vi-VN",
                "clientAttemptOf": None,
                "context": {"stateVersion": 1, "screen": "assistant"},
            },
        )
        assert assistant.status_code == 200
        message = assistant.json()["message"]
        assert message["route"] == "safety.driver_fatigue"
        assert message["actions"][0]["type"] == "SUGGEST_REST_STOP"
        rest_action = message["actions"][0]

        confirmed = await client.post(
            "/api/v1/actions/confirm",
            json={
                "sessionId": session_id,
                "actionId": rest_action["id"],
                "actionType": rest_action["type"],
                "confirmed": True,
                "confirmationId": "confirm-rest-1",
                "contextVersion": 1,
            },
        )
        assert confirmed.status_code == 200
        assert confirmed.json()["accepted"] is True
        assert confirmed.json()["actionResult"] == "SIMULATED_SUGGEST_REST_STOP"

        accepted = await client.post(
            "/api/v1/events",
            json={
                "sessionId": session_id,
                "eventId": "fatigue-event-1",
                "type": "USER_REPORTED_FATIGUE",
                "occurredAtMs": int(time.time() * 1_000),
            },
        )
        assert accepted.status_code == 200
        assert accepted.json()["accepted"] is True

        crash_payload = state_payload(session_id, crash=True, passenger="NO_RESPONSE")
        crash_payload["state"]["location"] = {
            "latitude": 21.0285,
            "longitude": 105.8542,
            "source": "SIMULATOR",
            "capturedAtMs": int(time.time() * 1_000),
        }
        crashed = await client.post(
            "/api/v1/state/update",
            json=crash_payload,
        )
        assert crashed.status_code == 200
        assert crashed.json()["riskAssessment"]["level"] == "CRITICAL"
        assert crashed.json()["emergency"]["state"] == "VERIFYING_EVIDENCE"
        assert crashed.json()["emergency"]["deadlineMs"] is not None
        assert crashed.json()["emergency"]["rescueBrief"]["dispatchMode"] == "SIMULATION_ONLY"

        emergency_id = app.state.mobile_session_store._sessions[session_id].emergency.emergencyId
        emergency = await client.get(
            f"/api/v1/emergency/{emergency_id}", params={"sessionId": session_id}
        )
        assert emergency.status_code == 200
        assert emergency.json()["state"] == "VERIFYING_EVIDENCE"
        assert emergency.json()["realEmergencyDispatchEnabled"] is False
        rescue_brief = emergency.json()["rescueBrief"]
        assert rescue_brief["dispatchMode"] == "SIMULATION_ONLY"
        assert rescue_brief["eventType"] == "CRASH_AND_NO_RESPONSE"
        assert rescue_brief["lastKnownLocation"]["latitude"] == 21.0285
        assert rescue_brief["locationStatus"] == "FRESH"
        assert "Human verification is required." in rescue_brief["vehicleStatusSummary"]
        assert rescue_brief["realEmergencyDispatchEnabled"] is False

        no_response = await client.post(
            f"/api/v1/emergency/{emergency_id}/respond",
            json={
                "sessionId": session_id,
                "responseId": "response-no-response-1",
                "response": "NO_RESPONSE",
                "clientTimeMs": int(time.time() * 1_000),
            },
        )
        assert no_response.status_code == 200
        assert no_response.json()["state"] == "FINAL_COUNTDOWN"
        assert no_response.json()["realEmergencyDispatchEnabled"] is False

        # The gateway is intentionally mocked. Expiring the countdown in the
        # in-memory simulator proves the brief is sent only after that boundary.
        store = app.state.mobile_session_store
        current = store._sessions[session_id].emergency
        store._sessions[session_id].emergency = current.model_copy(
            update={"deadlineMs": int(time.time() * 1_000) - 1}
        )
        sent = await client.get(
            f"/api/v1/emergency/{emergency_id}", params={"sessionId": session_id}
        )
        assert sent.status_code == 200
        assert sent.json()["state"] == "SOS_SIMULATED_SENT"
        assert sent.json()["rescueDispatch"] == {
            "provider": "MOCK_ROADSIDE_ASSISTANCE_GATEWAY",
            "endpoint": "mock://safedrive-rescue-gateway/v1/events",
            "outcome": "SIMULATED_ACCEPTED",
            "referenceId": sent.json()["rescueDispatch"]["referenceId"],
            "receivedAtMs": sent.json()["rescueDispatch"]["receivedAtMs"],
        }


@pytest.mark.asyncio
async def test_cancelled_emergency_cannot_be_reopened_by_a_stray_no_response() -> None:
    app = create_app(settings=mobile_settings())
    async with (
        app.router.lifespan_context(app),
        AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client,
    ):
        started = await client.post("/api/v1/sessions/start", json=session_payload())
        session_id = started.json()["sessionId"]

        crashed = await client.post(
            "/api/v1/state/update",
            json=state_payload(session_id, crash=True, passenger="NO_RESPONSE"),
        )
        emergency_id = crashed.json()["emergency"]["emergencyId"]

        cancelled = await client.post(
            f"/api/v1/emergency/{emergency_id}/respond",
            json={
                "sessionId": session_id,
                "responseId": "user-ok-1",
                "response": "USER_OK",
                "clientTimeMs": int(time.time() * 1_000),
            },
        )
        assert cancelled.status_code == 200
        assert cancelled.json()["state"] == "CANCELLED"

        # A stray or duplicate NO_RESPONSE arriving after the user already
        # said they're OK must never reopen the emergency.
        reopen_attempt = await client.post(
            f"/api/v1/emergency/{emergency_id}/respond",
            json={
                "sessionId": session_id,
                "responseId": "late-no-response-1",
                "response": "NO_RESPONSE",
                "clientTimeMs": int(time.time() * 1_000),
            },
        )
        assert reopen_attempt.status_code == 200
        assert reopen_attempt.json()["state"] == "CANCELLED"
        assert reopen_attempt.json()["deadlineMs"] is None

        confirmed_terminal = await client.get(
            f"/api/v1/emergency/{emergency_id}", params={"sessionId": session_id}
        )
        assert confirmed_terminal.json()["state"] == "CANCELLED"


@pytest.mark.asyncio
async def test_sos_simulated_sent_is_terminal_and_ignores_further_responses() -> None:
    app = create_app(settings=mobile_settings())
    async with (
        app.router.lifespan_context(app),
        AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client,
    ):
        started = await client.post("/api/v1/sessions/start", json=session_payload())
        session_id = started.json()["sessionId"]

        crashed = await client.post(
            "/api/v1/state/update",
            json=state_payload(session_id, crash=True, passenger="NO_RESPONSE"),
        )
        emergency_id = crashed.json()["emergency"]["emergencyId"]

        await client.post(
            f"/api/v1/emergency/{emergency_id}/respond",
            json={
                "sessionId": session_id,
                "responseId": "no-response-1",
                "response": "NO_RESPONSE",
                "clientTimeMs": int(time.time() * 1_000),
            },
        )

        store = app.state.mobile_session_store
        current = store._sessions[session_id].emergency
        store._sessions[session_id].emergency = current.model_copy(
            update={"deadlineMs": int(time.time() * 1_000) - 1}
        )
        sent = await client.get(
            f"/api/v1/emergency/{emergency_id}", params={"sessionId": session_id}
        )
        assert sent.json()["state"] == "SOS_SIMULATED_SENT"
        first_receipt = sent.json()["rescueDispatch"]

        # A duplicate/late NO_RESPONSE after the simulated dispatch already
        # "sent" must not push the state back into FINAL_COUNTDOWN.
        duplicate = await client.post(
            f"/api/v1/emergency/{emergency_id}/respond",
            json={
                "sessionId": session_id,
                "responseId": "no-response-2-late-duplicate",
                "response": "NO_RESPONSE",
                "clientTimeMs": int(time.time() * 1_000),
            },
        )
        assert duplicate.status_code == 200
        assert duplicate.json()["state"] == "SOS_SIMULATED_SENT"
        assert duplicate.json()["rescueDispatch"] == first_receipt


@pytest.mark.asyncio
async def test_mobile_route_errors_use_flat_envelope_shape_android_expects() -> None:
    app = create_app(settings=mobile_settings())
    async with (
        app.router.lifespan_context(app),
        AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client,
    ):
        response = await client.get("/api/v1/state", params={"sessionId": "no-such-session"})
        assert response.status_code == 404
        body = response.json()
        # openapi/safedrive-v1.yaml's ErrorEnvelope is flat, not nested under "error" like the
        # canonical ApiError shape — Android's ErrorEnvelopeDto requires exactly this shape.
        assert set(body.keys()) == {"code", "message", "requestId", "retryable", "serverTimeMs"}
        assert body["code"] == "UNSUPPORTED"
        assert body["retryable"] is False
        assert isinstance(body["serverTimeMs"], int)


@pytest.mark.asyncio
async def test_canonical_state_query_remains_key_protected_when_mobile_query_is_absent() -> None:
    app = create_app(settings=mobile_settings())
    async with (
        app.router.lifespan_context(app),
        AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client,
    ):
        response = await client.get(
            "/api/v1/state", params={"vehicle_id": "vehicle-1", "trip_id": "trip-1"}
        )
        assert response.status_code == 401
        assert response.json()["error"]["code"] == "AUTHENTICATION_REQUIRED"


@pytest.mark.asyncio
async def test_ambiguous_user_discomfort_is_grounded_by_fatigue_context() -> None:
    app = create_app(settings=mobile_settings())
    async with (
        app.router.lifespan_context(app),
        AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client,
    ):
        started = await client.post("/api/v1/sessions/start", json=session_payload())
        session_id = started.json()["sessionId"]
        updated = await client.post("/api/v1/state/update", json=state_payload(session_id))
        assert updated.status_code == 200

        response = await client.post(
            "/api/v1/assistant/query",
            json={
                "sessionId": session_id,
                "requestId": "request-ambiguous-1",
                "text": "Tôi thấy không ổn, có nên dừng không?",
                "source": "TEXT",
                "locale": "vi-VN",
                "clientAttemptOf": None,
                "context": {"stateVersion": 1, "screen": "assistant"},
            },
        )

        assert response.status_code == 200
        message = response.json()["message"]
        assert message["route"] == "safety.user_discomfort_check"
        assert "driving_over_4_hours" in message["risk"]["reasonCodes"]
        assert any(action["type"] == "SUGGEST_REST_STOP" for action in message["actions"])


@pytest.mark.asyncio
async def test_generic_hvac_command_is_context_checked_confirmed_and_published_as_simulated_state() -> (
    None
):
    app = create_app(settings=mobile_settings())
    async with (
        app.router.lifespan_context(app),
        AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client,
    ):
        started = await client.post("/api/v1/sessions/start", json=session_payload())
        session_id = started.json()["sessionId"]
        initial = await client.post("/api/v1/state/update", json=state_payload(session_id))
        assert initial.status_code == 200

        assistant = await client.post(
            "/api/v1/assistant/query",
            json={
                "sessionId": session_id,
                "requestId": "request-hvac-1",
                "text": "Bat dieu hoa",
                "source": "VOICE",
                "locale": "vi-VN",
                "clientAttemptOf": None,
                "context": {"stateVersion": 1, "screen": "assistant"},
            },
        )
        assert assistant.status_code == 200
        action = assistant.json()["message"]["actions"][0]
        assert action["type"] == "SET_HVAC_TEMPERATURE"
        assert action["hvacTargetTemperatureC"] == 22.0
        assert action["requiresConfirmation"] is True

        altered = await client.post(
            "/api/v1/actions/confirm",
            json={
                "sessionId": session_id,
                "actionId": action["id"],
                "actionType": "SET_HVAC_TEMPERATURE",
                "hvacTargetTemperatureC": 16.0,
                "confirmed": True,
                "confirmationId": "confirm-hvac-altered",
                "contextVersion": 1,
            },
        )
        assert altered.status_code == 200
        assert altered.json()["accepted"] is False
        assert "details do not match" in altered.json()["message"].lower()

        confirmed = await client.post(
            "/api/v1/actions/confirm",
            json={
                "sessionId": session_id,
                "actionId": action["id"],
                "actionType": "SET_HVAC_TEMPERATURE",
                "hvacTargetTemperatureC": 22.0,
                "confirmed": True,
                "confirmationId": "confirm-hvac-1",
                "contextVersion": 1,
            },
        )
        assert confirmed.status_code == 200
        assert confirmed.json()["actionResult"] == "SIMULATED_SET_HVAC_TEMPERATURE_22C"

        refreshed = await client.get("/api/v1/state", params={"sessionId": session_id})
        assert refreshed.status_code == 200
        assert refreshed.json()["stateVersion"] == 2
        assert refreshed.json()["state"]["hvacTargetTemperatureC"] == 22.0
        canonical_states = list(app.state.services.latest_state_manager.states.values())
        assert canonical_states[0].components["hvac.temperature"].value["temperature"] == 22.0


@pytest.mark.asyncio
async def test_action_confirmation_rejects_a_forged_or_unissued_plan() -> None:
    app = create_app(settings=mobile_settings())
    async with (
        app.router.lifespan_context(app),
        AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client,
    ):
        started = await client.post("/api/v1/sessions/start", json=session_payload())
        session_id = started.json()["sessionId"]
        updated = await client.post("/api/v1/state/update", json=state_payload(session_id))
        assert updated.status_code == 200

        forged = await client.post(
            "/api/v1/actions/confirm",
            json={
                "sessionId": session_id,
                "actionId": "act_not_issued",
                "actionType": "SET_HVAC_TEMPERATURE",
                "hvacTargetTemperatureC": 16.0,
                "confirmed": True,
                "confirmationId": "confirm-forged",
                "contextVersion": 1,
            },
        )

        assert forged.status_code == 200
        assert forged.json()["accepted"] is False
        assert "not issued" in forged.json()["message"].lower()
        current = await client.get("/api/v1/state", params={"sessionId": session_id})
        assert current.json()["stateVersion"] == 1
        assert current.json()["state"]["hvacTargetTemperatureC"] is None


@pytest.mark.asyncio
async def test_hvac_confirmation_survives_unrelated_fresh_telemetry() -> None:
    app = create_app(settings=mobile_settings())
    async with (
        app.router.lifespan_context(app),
        AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client,
    ):
        started = await client.post("/api/v1/sessions/start", json=session_payload())
        session_id = started.json()["sessionId"]
        initial = await client.post("/api/v1/state/update", json=state_payload(session_id))
        assert initial.json()["stateVersion"] == 1

        assistant = await client.post(
            "/api/v1/assistant/query",
            json={
                "sessionId": session_id,
                "requestId": "request-hvac-telemetry",
                "text": "Bật điều hòa",
                "source": "VOICE",
                "locale": "vi-VN",
                "clientAttemptOf": None,
                "context": {"stateVersion": 1, "screen": "assistant"},
            },
        )
        action = assistant.json()["message"]["actions"][0]

        # Speed and position may update every remote polling cycle. They are
        # not decision inputs for this typed HVAC plan.
        telemetry = state_payload(session_id)
        telemetry["state"]["speedKmh"] = 83.0
        telemetry["state"]["location"] = {
            "latitude": 21.0285,
            "longitude": 105.8542,
            "source": "SIMULATOR",
            "capturedAtMs": int(time.time() * 1_000),
        }
        updated = await client.post("/api/v1/state/update", json=telemetry)
        assert updated.json()["stateVersion"] == 2

        confirmed = await client.post(
            "/api/v1/actions/confirm",
            json={
                "sessionId": session_id,
                "actionId": action["id"],
                "actionType": "SET_HVAC_TEMPERATURE",
                "hvacTargetTemperatureC": action["hvacTargetTemperatureC"],
                "confirmed": True,
                "confirmationId": "confirm-hvac-after-telemetry",
                "contextVersion": 2,
            },
        )
        assert confirmed.json()["accepted"] is True


@pytest.mark.asyncio
async def test_hvac_confirmation_is_invalidated_when_a_decision_input_changes() -> None:
    app = create_app(settings=mobile_settings())
    async with (
        app.router.lifespan_context(app),
        AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client,
    ):
        started = await client.post("/api/v1/sessions/start", json=session_payload())
        session_id = started.json()["sessionId"]
        await client.post("/api/v1/state/update", json=state_payload(session_id))
        assistant = await client.post(
            "/api/v1/assistant/query",
            json={
                "sessionId": session_id,
                "requestId": "request-hvac-energy-change",
                "text": "Bật điều hòa",
                "source": "VOICE",
                "locale": "vi-VN",
                "clientAttemptOf": None,
                "context": {"stateVersion": 1, "screen": "assistant"},
            },
        )
        action = assistant.json()["message"]["actions"][0]

        changed = state_payload(session_id)
        changed["state"]["energyPercent"] = 18
        updated = await client.post("/api/v1/state/update", json=changed)
        assert updated.json()["stateVersion"] == 2

        rejected = await client.post(
            "/api/v1/actions/confirm",
            json={
                "sessionId": session_id,
                "actionId": action["id"],
                "actionType": "SET_HVAC_TEMPERATURE",
                "hvacTargetTemperatureC": action["hvacTargetTemperatureC"],
                "confirmed": True,
                "confirmationId": "confirm-hvac-after-energy-change",
                "contextVersion": 2,
            },
        )
        assert rejected.json()["accepted"] is False
        assert "not issued" in rejected.json()["message"].lower()


@pytest.mark.asyncio
async def test_hvac_and_status_routes_never_call_the_llm_even_when_ollama_is_configured(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """HVAC control, vehicle status and fault explanations must stay fully
    deterministic with no LLM call at all -- not merely LLM-narrated-but-guarded --
    even when an Ollama narrator is configured and reachable. Only routes with no
    grounded vehicle fact or action (general/clarify/companion chit-chat) may be
    narrated (see `_NARRATABLE_ROUTES` in app/mobile/session_store.py)."""

    # The app's own test client is also an httpx.AsyncClient (over ASGITransport), so the
    # fake must only intercept the narrator's Ollama call and pass every other request
    # (session start, state update, ...) through to the real implementation.
    original_post = httpx.AsyncClient.post

    # The companion.conversation template now weaves in real grounded facts (speed,
    # cabin temperature, driving duration) so the narrator has genuine context to work
    # with instead of a fact-free line -- this fake response must preserve those same
    # numbers (matching the fresh_state set up below) or the narrator's own
    # number-preservation guard would correctly reject it and fall back to the
    # deterministic reply, same as it would for a real model that dropped a fact.
    async def fake_post(self: httpx.AsyncClient, url: object, *args: object, **kwargs: object) -> httpx.Response:
        if str(url).endswith("/api/chat"):
            return httpx.Response(
                200,
                json={"message": {"content": "Tôi luôn ở đây cùng bạn. Xe đang chạy 72 km/h, cabin 25 độ, bạn đã lái được 20 phút rồi."}},
                request=httpx.Request("POST", str(url)),
            )
        return await original_post(self, url, *args, **kwargs)

    monkeypatch.setattr(httpx.AsyncClient, "post", fake_post)

    settings = Settings(
        environment="test",
        active_profile="DMS_DEMO",
        safedrive_api_key=SecretStr("canonical-api-key"),
        llm_provider="ollama",
    )
    app = create_app(settings=settings)
    async with (
        app.router.lifespan_context(app),
        AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client,
    ):
        started = await client.post("/api/v1/sessions/start", json=session_payload())
        session_id = started.json()["sessionId"]
        fresh_state = state_payload(session_id)
        fresh_state["state"].update(
            {"cabinTemperatureC": 25.0, "continuousDrivingMinutes": 20, "energyPercent": 74}
        )
        fresh_state["driverSupportSignals"]["userReportedFatigue"] = False
        assert (await client.post("/api/v1/state/update", json=fresh_state)).status_code == 200

        hvac = await client.post(
            "/api/v1/assistant/query",
            json={
                "sessionId": session_id,
                "requestId": "request-hvac-no-llm",
                "text": "Bat dieu hoa",
                "source": "VOICE",
                "locale": "vi-VN",
                "clientAttemptOf": None,
                "context": {"stateVersion": 1, "screen": "assistant"},
            },
        )
        assert hvac.status_code == 200
        assert not (hvac.json()["model"] or "").startswith("ollama/")
        assert "cùng bạn" not in hvac.json()["message"]["text"]

        status = await client.post(
            "/api/v1/assistant/query",
            json={
                "sessionId": session_id,
                "requestId": "request-status-no-llm",
                "text": "Tinh trang xe the nao",
                "source": "VOICE",
                "locale": "vi-VN",
                "clientAttemptOf": None,
                "context": {"stateVersion": 1, "screen": "assistant"},
            },
        )
        assert status.status_code == 200
        assert not (status.json()["model"] or "").startswith("ollama/")
        assert "cùng bạn" not in status.json()["message"]["text"]

        # Companion chit-chat has no confirmable action to preserve (unlike HVAC/status),
        # so it remains narratable -- proves the exclusion above is route-specific, not a
        # blanket "narrator never fires" wiring failure.
        chat = await client.post(
            "/api/v1/assistant/query",
            json={
                "sessionId": session_id,
                "requestId": "request-chat-llm-ok",
                "text": "Noi chuyen voi toi mot chut",
                "source": "VOICE",
                "locale": "vi-VN",
                "clientAttemptOf": None,
                "context": {"stateVersion": 1, "screen": "assistant"},
            },
        )
        assert chat.status_code == 200
        assert chat.json()["model"] == "ollama/qwen2.5:7b-instruct-q4_K_M"
        assert chat.json()["message"]["text"] == (
            "Tôi luôn ở đây cùng bạn. Xe đang chạy 72 km/h, cabin 25 độ, bạn đã lái được 20 phút rồi."
        )


@pytest.mark.asyncio
async def test_response_envelope_reports_llm_used_and_fallback_metadata(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """Competition MVP observability requirement: the client must be able to tell apart
    three distinct cases from the response alone -- (1) a route that never calls an LLM
    at all, (2) a real successful LLM narration, (3) an LLM attempt that failed/was
    rejected and fell back to the deterministic reply."""

    original_post = httpx.AsyncClient.post

    async def fake_post(self: httpx.AsyncClient, url: object, *args: object, **kwargs: object) -> httpx.Response:
        if str(url).endswith("/api/chat"):
            return httpx.Response(
                200,
                json={"message": {"content": "Tôi luôn ở đây cùng bạn. Xe đang chạy 72 km/h, cabin 25 độ, bạn đã lái được 20 phút rồi."}},
                request=httpx.Request("POST", str(url)),
            )
        return await original_post(self, url, *args, **kwargs)

    monkeypatch.setattr(httpx.AsyncClient, "post", fake_post)

    settings = Settings(
        environment="test",
        active_profile="DMS_DEMO",
        safedrive_api_key=SecretStr("canonical-api-key"),
        llm_provider="ollama",
    )
    app = create_app(settings=settings)
    async with (
        app.router.lifespan_context(app),
        AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client,
    ):
        started = await client.post("/api/v1/sessions/start", json=session_payload())
        session_id = started.json()["sessionId"]
        fresh_state = state_payload(session_id)
        fresh_state["state"].update(
            {"cabinTemperatureC": 25.0, "continuousDrivingMinutes": 20, "energyPercent": 74}
        )
        fresh_state["driverSupportSignals"]["userReportedFatigue"] = False
        assert (await client.post("/api/v1/state/update", json=fresh_state)).status_code == 200

        # Case 1: never-attempted -- HVAC/status stay deterministic by design.
        status = await client.post(
            "/api/v1/assistant/query",
            json={
                "sessionId": session_id,
                "requestId": "meta-never-attempted",
                "text": "Tinh trang xe the nao",
                "source": "VOICE",
                "locale": "vi-VN",
                "clientAttemptOf": None,
                "context": {"stateVersion": 1, "screen": "assistant"},
            },
        )
        body = status.json()
        assert body["llmUsed"] is False
        assert body["fallback"] is False
        assert body["fallbackReason"] is None

        # Case 2: real successful narration.
        chat_ok = await client.post(
            "/api/v1/assistant/query",
            json={
                "sessionId": session_id,
                "requestId": "meta-success",
                "text": "Noi chuyen voi toi mot chut",
                "source": "VOICE",
                "locale": "vi-VN",
                "clientAttemptOf": None,
                "context": {"stateVersion": 1, "screen": "assistant"},
            },
        )
        body = chat_ok.json()
        assert body["llmUsed"] is True
        assert body["fallback"] is False
        assert body["fallbackReason"] is None


@pytest.mark.asyncio
async def test_response_envelope_reports_fallback_when_ollama_is_unreachable(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    original_post = httpx.AsyncClient.post

    async def fake_post(self: httpx.AsyncClient, url: object, *args: object, **kwargs: object) -> httpx.Response:
        if str(url).endswith("/api/chat"):
            raise httpx.ConnectTimeout("no route to host")
        return await original_post(self, url, *args, **kwargs)

    monkeypatch.setattr(httpx.AsyncClient, "post", fake_post)

    settings = Settings(
        environment="test",
        active_profile="DMS_DEMO",
        safedrive_api_key=SecretStr("canonical-api-key"),
        llm_provider="ollama",
    )
    app = create_app(settings=settings)
    async with (
        app.router.lifespan_context(app),
        AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client,
    ):
        started = await client.post("/api/v1/sessions/start", json=session_payload())
        session_id = started.json()["sessionId"]
        # Must be a LOW-risk, narratable state -- state_payload()'s own defaults
        # (fatigue=True, 245 min driving) are HIGH risk, which _can_narrate excludes
        # from narration entirely, and this test needs an LLM attempt to actually
        # happen so it can prove that a *failed* attempt reports fallback=True.
        fresh_state = state_payload(session_id)
        fresh_state["state"].update(
            {"cabinTemperatureC": 25.0, "continuousDrivingMinutes": 20, "energyPercent": 74}
        )
        fresh_state["driverSupportSignals"]["userReportedFatigue"] = False
        assert (await client.post("/api/v1/state/update", json=fresh_state)).status_code == 200

        chat = await client.post(
            "/api/v1/assistant/query",
            json={
                "sessionId": session_id,
                "requestId": "meta-fallback",
                "text": "Noi chuyen voi toi mot chut",
                "source": "VOICE",
                "locale": "vi-VN",
                "clientAttemptOf": None,
                "context": {"stateVersion": 1, "screen": "assistant"},
            },
        )
        body = chat.json()
        assert body["llmUsed"] is False
        assert body["fallback"] is True
        assert body["fallbackReason"] == "provider_unavailable"


@pytest.mark.asyncio
async def test_narratable_route_falls_back_to_the_deterministic_reply_when_ollama_times_out(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """Phase 3: there is no separate 'fallback builder' component -- the deterministic
    reply that ContextAwareAssistant already built (and that answer_assistant would have
    returned anyway if no narrator were configured) IS the fallback. This proves that
    path end-to-end for a route that IS eligible for narration, once the Ollama call
    itself fails outright (timeout)."""

    original_post = httpx.AsyncClient.post

    async def fake_post(self: httpx.AsyncClient, url: object, *args: object, **kwargs: object) -> httpx.Response:
        if str(url).endswith("/api/chat"):
            raise httpx.ConnectTimeout("no route to host")
        return await original_post(self, url, *args, **kwargs)

    monkeypatch.setattr(httpx.AsyncClient, "post", fake_post)

    settings = Settings(
        environment="test",
        active_profile="DMS_DEMO",
        safedrive_api_key=SecretStr("canonical-api-key"),
        llm_provider="ollama",
    )
    app = create_app(settings=settings)
    async with (
        app.router.lifespan_context(app),
        AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client,
    ):
        started = await client.post("/api/v1/sessions/start", json=session_payload())
        session_id = started.json()["sessionId"]
        fresh_state = state_payload(session_id)
        fresh_state["state"].update(
            {"cabinTemperatureC": 25.0, "continuousDrivingMinutes": 20, "energyPercent": 74}
        )
        fresh_state["driverSupportSignals"]["userReportedFatigue"] = False
        assert (await client.post("/api/v1/state/update", json=fresh_state)).status_code == 200

        chat = await client.post(
            "/api/v1/assistant/query",
            json={
                "sessionId": session_id,
                "requestId": "request-chat-llm-timeout",
                "text": "Noi chuyen voi toi mot chut",
                "source": "VOICE",
                "locale": "vi-VN",
                "clientAttemptOf": None,
                "context": {"stateVersion": 1, "screen": "assistant"},
            },
        )

    assert chat.status_code == 200
    body = chat.json()
    assert not (body["model"] or "").startswith("ollama/")
    assert body["message"]["text"] == (
        "Tôi ở đây cùng bạn. Xe đang chạy 72 km/h, cabin 25 độ C. Bạn đã lái khoảng 20 phút rồi. "
        "Nếu bạn bắt đầu mệt, hãy nói với tôi hoặc dừng ở vị trí an toàn khi có thể."
    )


def _ollama_settings() -> Settings:
    return Settings(
        environment="test",
        active_profile="DMS_DEMO",
        safedrive_api_key=SecretStr("canonical-api-key"),
        llm_provider="ollama",
    )


def _patch_ollama_chat(monkeypatch: pytest.MonkeyPatch, content: str | None, *, raises: bool = False) -> None:
    """Intercepts only the reasoner/narrator's Ollama call; every other
    request (session start, state update, ...) passes through to the real
    ASGI app, same technique as test_hvac_and_status_routes_never_call_the_llm."""

    original_post = httpx.AsyncClient.post

    async def fake_post(self: httpx.AsyncClient, url: object, *args: object, **kwargs: object) -> httpx.Response:
        if str(url).endswith("/api/chat"):
            if raises:
                raise httpx.ConnectTimeout("no route to host")
            return httpx.Response(
                200,
                json={"message": {"content": content}},
                request=httpx.Request("POST", str(url)),
            )
        return await original_post(self, url, *args, **kwargs)

    monkeypatch.setattr(httpx.AsyncClient, "post", fake_post)


def _fake_chat_by_system_prompt(monkeypatch: pytest.MonkeyPatch, *, classify_label: str) -> list[str]:
    """Distinguishes the classifier's call (system prompt starts with "Classify the
    driver's message") from the narrator's call (starts with "You are SafeDrive AI
    Companion") on the same intercepted /api/chat endpoint, since a reclassified route
    that also happens to be narratable would otherwise trigger both in one request.
    Returns the list of system prompts actually seen, for call-count assertions."""

    original_post = httpx.AsyncClient.post
    seen_system_prompts: list[str] = []

    async def fake_post(self: httpx.AsyncClient, url: object, *args: object, **kwargs: object) -> httpx.Response:
        if str(url).endswith("/api/chat"):
            system_prompt = kwargs["json"]["messages"][0]["content"]  # type: ignore[index]
            seen_system_prompts.append(system_prompt)
            if system_prompt.startswith("Classify the driver's message"):
                return httpx.Response(
                    200,
                    json={"message": {"content": classify_label}},
                    request=httpx.Request("POST", str(url)),
                )
            return httpx.Response(
                200,
                json={"message": {"content": "Tôi luôn ở đây để trò chuyện cùng bạn."}},
                request=httpx.Request("POST", str(url)),
            )
        return await original_post(self, url, *args, **kwargs)

    monkeypatch.setattr(httpx.AsyncClient, "post", fake_post)
    return seen_system_prompts


@pytest.mark.asyncio
async def test_unmatched_text_can_be_reclassified_by_advisory_llm_into_a_fixed_template(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """A message that matches no deterministic keyword at all lands on
    IntentResolver's `assistant.general` fallback (needs_clarification=True). The
    advisory OllamaIntentClassifier may reclassify it into a different label, but only
    ever by selecting an existing ContextAwareAssistant template -- proving the label
    can't invent wording or an action (app/mobile/llm.py's OllamaIntentClassifier
    docstring, and MobileSessionStore._can_classify's gate)."""

    seen_prompts = _fake_chat_by_system_prompt(monkeypatch, classify_label="assistant.vehicle_status")

    app = create_app(settings=_ollama_settings())
    async with (
        app.router.lifespan_context(app),
        AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client,
    ):
        started = await client.post("/api/v1/sessions/start", json=session_payload())
        session_id = started.json()["sessionId"]
        fresh_state = state_payload(session_id)
        fresh_state["state"].update(
            {"speedKmh": 60.0, "cabinTemperatureC": 25.0, "continuousDrivingMinutes": 20, "energyPercent": 74}
        )
        fresh_state["driverSupportSignals"]["userReportedFatigue"] = False
        assert (await client.post("/api/v1/state/update", json=fresh_state)).status_code == 200

        result = await client.post(
            "/api/v1/assistant/query",
            json={
                "sessionId": session_id,
                "requestId": "request-reclassify",
                "text": "Ban co the hat cho toi nghe mot bai khong",
                "source": "VOICE",
                "locale": "vi-VN",
                "clientAttemptOf": None,
                "context": {"stateVersion": 1, "screen": "assistant"},
            },
        )
        body = result.json()

    assert body["model"] == "ollama-intent/qwen2.5:7b-instruct-q4_K_M"
    assert body["message"]["route"] == "assistant.vehicle_status"
    assert "60 km/h" in body["message"]["text"]
    assert "20 phút" in body["message"]["text"]
    # assistant.vehicle_status is not in _NARRATABLE_ROUTES, so only the classifier's
    # call should have run -- proves this doesn't silently chain into a second,
    # separate narrator call once the route changes.
    assert len(seen_prompts) == 1


@pytest.mark.asyncio
async def test_reclassification_never_runs_during_an_active_emergency(monkeypatch: pytest.MonkeyPatch) -> None:
    """MobileSessionStore._can_classify must refuse to run the classifier while a
    crash/no-response emergency is open, mirroring _can_narrate's own gate -- a
    reclassification must never discard the deterministic emergency-aware text that
    `_message_and_actions` already produced regardless of route."""

    seen_prompts = _fake_chat_by_system_prompt(monkeypatch, classify_label="companion.conversation")

    app = create_app(settings=_ollama_settings())
    async with (
        app.router.lifespan_context(app),
        AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client,
    ):
        started = await client.post("/api/v1/sessions/start", json=session_payload())
        session_id = started.json()["sessionId"]
        crash_state = state_payload(session_id, crash=True, passenger="NO_RESPONSE")
        assert (await client.post("/api/v1/state/update", json=crash_state)).status_code == 200

        result = await client.post(
            "/api/v1/assistant/query",
            json={
                "sessionId": session_id,
                "requestId": "request-emergency-no-reclassify",
                "text": "Ban co the hat cho toi nghe mot bai khong",
                "source": "VOICE",
                "locale": "vi-VN",
                "clientAttemptOf": None,
                "context": {"stateVersion": 1, "screen": "assistant"},
            },
        )
        body = result.json()

    assert "va chạm" in body["message"]["text"] or "SOS" in body["message"]["text"]
    assert not (body["model"] or "").startswith("ollama-intent/")
    # The emergency's own EmergencyLLMReasoner background task independently calls
    # /api/chat too (a different, unrelated system prompt) -- assert specifically that
    # no *classifier*-shaped prompt ever ran, not that zero LLM calls happened at all.
    assert not any(prompt.startswith("Classify the driver's message") for prompt in seen_prompts)


async def _poll_until_emergency(
    client: AsyncClient,
    session_id: str,
    emergency_id: str,
    *,
    is_done: object,
    attempts: int = 60,
    delay_seconds: float = 0.02,
) -> dict[str, object]:
    """Polls GET /api/v1/emergency until `is_done(snapshot_json)` is true or
    attempts run out, returning the last-seen snapshot either way. The
    reasoner's verdict is applied by a fire-and-forget background task
    (session_store.py's _run_candidate_reasoning/_run_escalation_reasoning),
    so tests must poll for it rather than assume it landed synchronously."""

    import asyncio

    snapshot: dict[str, object] = {}
    for _ in range(attempts):
        response = await client.get(
            f"/api/v1/emergency/{emergency_id}", params={"sessionId": session_id}
        )
        snapshot = response.json()
        if is_done(snapshot):
            return snapshot
        await asyncio.sleep(delay_seconds)
    return snapshot


@pytest.mark.asyncio
async def test_llm_confirming_a_crash_candidate_attaches_reasoning_without_changing_state(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    _patch_ollama_chat(
        monkeypatch,
        '{"open_candidate": true, "reasoning": "Va chạm và không có phản hồi, cần theo dõi sát."}',
    )
    app = create_app(settings=_ollama_settings())
    async with (
        app.router.lifespan_context(app),
        AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client,
    ):
        started = await client.post("/api/v1/sessions/start", json=session_payload())
        session_id = started.json()["sessionId"]

        crashed = await client.post(
            "/api/v1/state/update",
            json=state_payload(session_id, crash=True, passenger="NO_RESPONSE"),
        )
        # The deterministic safety net opens the candidate immediately,
        # synchronously, regardless of the LLM -- this must never regress.
        assert crashed.json()["emergency"]["state"] == "VERIFYING_EVIDENCE"
        emergency_id = crashed.json()["emergency"]["emergencyId"]

        snapshot = await _poll_until_emergency(
            client, session_id, emergency_id, is_done=lambda s: s["reasoningSummary"] is not None
        )
        assert snapshot["reasoningSummary"] == "Va chạm và không có phản hồi, cần theo dõi sát."
        # Confirming verdict never changes the deterministic state/timing.
        assert snapshot["state"] == "VERIFYING_EVIDENCE"
        assert snapshot["deadlineMs"] is not None


@pytest.mark.asyncio
async def test_llm_vetoing_a_crash_candidate_cancels_it_back_to_idle(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    _patch_ollama_chat(
        monkeypatch,
        '{"open_candidate": false, "reasoning": "Xe đang dừng hẳn, có thể là tín hiệu nhiễu."}',
    )
    app = create_app(settings=_ollama_settings())
    async with (
        app.router.lifespan_context(app),
        AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client,
    ):
        started = await client.post("/api/v1/sessions/start", json=session_payload())
        session_id = started.json()["sessionId"]

        crashed = await client.post(
            "/api/v1/state/update",
            json=state_payload(session_id, crash=True, passenger="NO_RESPONSE"),
        )
        # Still opens synchronously/deterministically first, exactly as today.
        assert crashed.json()["emergency"]["state"] == "VERIFYING_EVIDENCE"
        emergency_id = crashed.json()["emergency"]["emergencyId"]

        snapshot = await _poll_until_emergency(
            client, session_id, emergency_id, is_done=lambda s: s["state"] != "VERIFYING_EVIDENCE"
        )
        assert snapshot["state"] == "IDLE"
        assert snapshot["deadlineMs"] is None
        assert snapshot["reasoningSummary"] == "Xe đang dừng hẳn, có thể là tín hiệu nhiễu."
        assert snapshot["evidence"] == []
        assert snapshot["rescueBrief"] is None


@pytest.mark.asyncio
async def test_llm_unreachable_leaves_the_deterministic_crash_candidate_exactly_as_is(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """The user's own approved fallback: with no working LLM connection at
    all (matching their current environment), the emergency feature must
    behave byte-for-byte like the llm_provider="mock" path -- this is the
    safety net the whole feature was built to never compromise."""

    _patch_ollama_chat(monkeypatch, None, raises=True)
    app = create_app(settings=_ollama_settings())
    async with (
        app.router.lifespan_context(app),
        AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client,
    ):
        started = await client.post("/api/v1/sessions/start", json=session_payload())
        session_id = started.json()["sessionId"]

        crashed = await client.post(
            "/api/v1/state/update",
            json=state_payload(session_id, crash=True, passenger="NO_RESPONSE"),
        )
        assert crashed.json()["emergency"]["state"] == "VERIFYING_EVIDENCE"
        emergency_id = crashed.json()["emergency"]["emergencyId"]

        # Give the failing background task every chance to (wrongly) mutate
        # state before asserting nothing changed.
        import asyncio

        await asyncio.sleep(0.1)
        snapshot = await client.get(
            f"/api/v1/emergency/{emergency_id}", params={"sessionId": session_id}
        )
        assert snapshot.json()["state"] == "VERIFYING_EVIDENCE"
        assert snapshot.json()["reasoningSummary"] is None

