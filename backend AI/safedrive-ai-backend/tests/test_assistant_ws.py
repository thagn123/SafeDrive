"""Tests for the assistant WebSocket transport (app/api/routes/assistant_ws.py).

These deliberately do not re-test routing/risk/narration/guardrail behavior --
that's already covered end-to-end by tests/test_mobile_compatibility.py against the
REST route, and assistant_ws.py calls the exact same MobileSessionStore.answer_assistant.
This file only covers the transport-specific pieces: session gating at handshake,
the final-frame shape matching the REST response, heartbeat frames during a slow
call, and the error-frame shape for a malformed query.
"""

import time

import httpx
import pytest
from pydantic import SecretStr
from starlette.testclient import TestClient

from app.core.config import Settings
from app.main import create_app


def mobile_settings(*, with_llm: bool = False) -> Settings:
    kwargs: dict[str, object] = {
        "environment": "test",
        "active_profile": "DMS_DEMO",
        "safedrive_api_key": SecretStr("canonical-api-key"),
    }
    if with_llm:
        kwargs["llm_provider"] = "ollama"
    return Settings(**kwargs)


def session_payload() -> dict[str, object]:
    return {
        "deviceId": "android-demo-device",
        "appVersion": "1.0.0",
        "platform": "ANDROID",
        "mode": "REMOTE",
        "clientTimeMs": int(time.time() * 1_000),
    }


def state_payload(session_id: str) -> dict[str, object]:
    timestamp = int(time.time() * 1_000)
    return {
        "sessionId": session_id,
        "state": {
            "speedKmh": 72.0,
            "engineTemperatureC": 88.0,
            "cabinTemperatureC": 25.0,
            "energyPercent": 74,
            "continuousDrivingMinutes": 20,
            "steeringLastInteractionSeconds": 8,
            "driverSeatOccupied": True,
            "wearableConnected": True,
            "activeDtcs": [],
            "crashDetected": False,
            "passengerResponse": "UNKNOWN",
            "updatedAtMs": timestamp,
        },
        "driverSupportSignals": {
            "steeringSignalAvailable": True,
            "seatSensorAvailable": True,
            "wearableLastUpdateMs": None,
            "wearableHeartRateBpm": None,
            "userReportedFatigue": False,
            "availableSourceCount": 2,
            "totalSourceCount": 4,
        },
        "source": "PHONE_SIMULATOR",
        "clientEventId": f"event-{timestamp}",
    }


def _patch_ollama_chat(
    monkeypatch: pytest.MonkeyPatch, content: str | None, *, delay_seconds: float = 0.0
) -> None:
    """Same technique as tests/test_mobile_compatibility.py's helper of the same
    name: intercepts only the narrator's Ollama call, passes everything else
    through to the real implementation."""

    original_post = httpx.AsyncClient.post

    async def fake_post(
        self: httpx.AsyncClient, url: object, *args: object, **kwargs: object
    ) -> httpx.Response:
        if str(url).endswith("/api/chat"):
            if delay_seconds:
                import anyio

                await anyio.sleep(delay_seconds)
            return httpx.Response(
                200,
                json={"message": {"content": content}},
                request=httpx.Request("POST", str(url)),
            )
        return await original_post(self, url, *args, **kwargs)

    monkeypatch.setattr(httpx.AsyncClient, "post", fake_post)


def _start_session_and_push_low_risk_state(client: TestClient) -> str:
    started = client.post("/api/v1/sessions/start", json=session_payload())
    assert started.status_code == 200
    session_id = started.json()["sessionId"]
    updated = client.post("/api/v1/state/update", json=state_payload(session_id))
    assert updated.status_code == 200
    assert updated.json()["riskAssessment"]["level"] == "LOW"
    return session_id


def test_websocket_rejects_missing_session_id() -> None:
    app = create_app(settings=mobile_settings())
    with (
        TestClient(app) as client,
        pytest.raises(Exception) as exc_info,
        client.websocket_connect("/api/v1/ws/assistant") as ws,
    ):
        ws.receive_text()
    assert getattr(exc_info.value, "code", None) == 4401


def test_websocket_rejects_unknown_session_id() -> None:
    app = create_app(settings=mobile_settings())
    with (
        TestClient(app) as client,
        pytest.raises(Exception) as exc_info,
        client.websocket_connect("/api/v1/ws/assistant?sessionId=does-not-exist") as ws,
    ):
        ws.receive_text()
    assert getattr(exc_info.value, "code", None) == 4401
    assert getattr(exc_info.value, "reason", None) == "session_not_found_or_expired"


def test_websocket_final_frame_matches_rest_response_for_deterministic_route() -> None:
    """Parity check: identical input over WS vs REST for a route that never calls
    the LLM (status) must produce the same message text/route/llmUsed/fallback."""
    app = create_app(settings=mobile_settings())
    with TestClient(app) as client:
        session_id = _start_session_and_push_low_risk_state(client)

        rest_response = client.post(
            "/api/v1/assistant/query",
            json={
                "sessionId": session_id,
                "requestId": "rest-status-1",
                "text": "Tinh trang xe the nao",
                "source": "TEXT",
                "locale": "vi-VN",
                "clientAttemptOf": None,
                "context": {"stateVersion": 1, "screen": "assistant"},
            },
        )
        assert rest_response.status_code == 200
        rest_body = rest_response.json()

        with client.websocket_connect(
            f"/api/v1/ws/assistant?sessionId={session_id}"
        ) as ws:
            ws.send_json(
                {
                    "sessionId": session_id,
                    "requestId": "ws-status-1",
                    "text": "Tinh trang xe the nao",
                    "source": "TEXT",
                    "locale": "vi-VN",
                    "clientAttemptOf": None,
                    "context": {"stateVersion": 1, "screen": "assistant"},
                }
            )
            frame = ws.receive_json()

        assert frame["type"] == "final"
        assert frame["requestId"] == "ws-status-1"
        assert frame["message"]["text"] == rest_body["message"]["text"]
        assert frame["message"]["risk"]["level"] == rest_body["message"]["risk"]["level"]
        assert frame["llmUsed"] is rest_body["llmUsed"] is False
        assert frame["fallback"] is rest_body["fallback"] is False


def test_websocket_final_frame_reports_real_llm_narration(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    _patch_ollama_chat(
        monkeypatch,
        "Tôi luôn ở đây cùng bạn. Xe đang chạy 72 km/h, cabin 25 độ, bạn đã lái được 20 phút rồi.",
    )
    app = create_app(settings=mobile_settings(with_llm=True))
    with TestClient(app) as client:
        session_id = _start_session_and_push_low_risk_state(client)
        with client.websocket_connect(
            f"/api/v1/ws/assistant?sessionId={session_id}"
        ) as ws:
            ws.send_json(
                {
                    "sessionId": session_id,
                    "requestId": "ws-chat-1",
                    "text": "Noi chuyen voi toi mot chut",
                    "source": "TEXT",
                    "locale": "vi-VN",
                    "clientAttemptOf": None,
                    "context": {"stateVersion": 1, "screen": "assistant"},
                }
            )
            frame = ws.receive_json()

    assert frame["type"] == "final"
    assert frame["llmUsed"] is True
    assert frame["fallback"] is False
    assert frame["model"] == "ollama/qwen2.5:7b-instruct-q4_K_M"
    assert frame["message"]["text"] == (
        "Tôi luôn ở đây cùng bạn. Xe đang chạy 72 km/h, cabin 25 độ, bạn đã lái được 20 phút rồi."
    )


def test_websocket_sends_heartbeats_during_a_slow_narration_call(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """This is the entire point of the WS transport: a real (possibly multi-second)
    Ollama call no longer needs a client-side fixed timeout guess, because the
    socket stays demonstrably alive via heartbeat frames while it's in flight."""
    _patch_ollama_chat(
        monkeypatch,
        "Tôi luôn ở đây cùng bạn. Xe đang chạy 72 km/h, cabin 25 độ, bạn đã lái được 20 phút rồi.",
        delay_seconds=4.5,
    )
    app = create_app(settings=mobile_settings(with_llm=True))
    with TestClient(app) as client:
        session_id = _start_session_and_push_low_risk_state(client)
        with client.websocket_connect(
            f"/api/v1/ws/assistant?sessionId={session_id}"
        ) as ws:
            ws.send_json(
                {
                    "sessionId": session_id,
                    "requestId": "ws-chat-slow-1",
                    "text": "Noi chuyen voi toi mot chut",
                    "source": "TEXT",
                    "locale": "vi-VN",
                    "clientAttemptOf": None,
                    "context": {"stateVersion": 1, "screen": "assistant"},
                }
            )
            frames = []
            frame = ws.receive_json()
            frames.append(frame)
            while frame["type"] == "heartbeat":
                frame = ws.receive_json()
                frames.append(frame)

    heartbeat_count = sum(1 for f in frames if f["type"] == "heartbeat")
    assert heartbeat_count >= 1
    assert frames[-1]["type"] == "final"
    assert frames[-1]["llmUsed"] is True


def test_websocket_sends_error_frame_for_malformed_query_and_stays_open() -> None:
    app = create_app(settings=mobile_settings())
    with TestClient(app) as client:
        session_id = _start_session_and_push_low_risk_state(client)
        with client.websocket_connect(
            f"/api/v1/ws/assistant?sessionId={session_id}"
        ) as ws:
            ws.send_json({"sessionId": session_id, "text": ""})  # missing requestId/context, empty text
            error_frame = ws.receive_json()
            assert error_frame["type"] == "error"
            assert error_frame["code"] == "VALIDATION"

            # Connection must still be usable after an error frame.
            ws.send_json(
                {
                    "sessionId": session_id,
                    "requestId": "ws-after-error-1",
                    "text": "Tinh trang xe the nao",
                    "source": "TEXT",
                    "locale": "vi-VN",
                    "clientAttemptOf": None,
                    "context": {"stateVersion": 1, "screen": "assistant"},
                }
            )
            final_frame = ws.receive_json()
            assert final_frame["type"] == "final"
            assert final_frame["requestId"] == "ws-after-error-1"
