"""Live SafeDrive hackathon demo matrix.

Runs only synthetic sessions against the configured backend. It proves the
driver-facing demo flows and the Brain/Armor boundary without changing service
configuration or invoking any real vehicle/rescue integration.
"""

from __future__ import annotations

import argparse
import asyncio
import json
import time
import uuid
from typing import Any

import httpx
import websockets


def now_ms() -> int:
    return int(time.time() * 1_000)


def unique(prefix: str) -> str:
    return f"{prefix}-{uuid.uuid4().hex[:10]}"


class DemoMatrix:
    def __init__(self, base_url: str) -> None:
        self.base_url = base_url.rstrip("/")
        self.client = httpx.Client(base_url=self.base_url, timeout=40.0)
        self.results: list[dict[str, Any]] = []

    def close(self) -> None:
        self.client.close()

    def check(self, usecase: str, condition: bool, **evidence: Any) -> None:
        result = {"usecase": usecase, "pass": bool(condition), **evidence}
        self.results.append(result)
        print(json.dumps(result, ensure_ascii=False))
        if not condition:
            raise AssertionError(f"{usecase}: {evidence}")

    def start_session(self) -> str:
        response = self.client.post(
            "/api/v1/sessions/start",
            json={
                "deviceId": unique("matrix-device"),
                "appVersion": "0.1.0-mvp",
                "platform": "ANDROID",
                "mode": "REMOTE",
                "clientTimeMs": now_ms(),
            },
        )
        response.raise_for_status()
        return str(response.json()["sessionId"])

    @staticmethod
    def state_payload(
        session_id: str,
        *,
        speed: float = 45,
        engine: float = 90,
        cabin: float = 25,
        energy: int = 74,
        drive: int = 35,
        fatigue: bool = False,
        dtcs: list[dict[str, Any]] | None = None,
        crash: bool = False,
        passenger: str = "RESPONSIVE",
        occupied: bool = True,
        hvac: float | None = None,
        location: bool = False,
    ) -> dict[str, Any]:
        timestamp = now_ms()
        vehicle_state: dict[str, Any] = {
            "speedKmh": speed,
            "engineTemperatureC": engine,
            "cabinTemperatureC": cabin,
            "energyPercent": energy,
            "continuousDrivingMinutes": drive,
            "steeringLastInteractionSeconds": 4,
            "driverSeatOccupied": occupied,
            "wearableConnected": False,
            "activeDtcs": dtcs or [],
            "crashDetected": crash,
            "passengerResponse": passenger,
            "updatedAtMs": timestamp,
            "hvacTargetTemperatureC": hvac,
        }
        if location:
            vehicle_state["location"] = {
                "latitude": 21.0285,
                "longitude": 105.8542,
                "source": "SIMULATOR",
                "capturedAtMs": timestamp,
            }
        return {
            "sessionId": session_id,
            "state": vehicle_state,
            "driverSupportSignals": {
                "steeringSignalAvailable": True,
                "seatSensorAvailable": True,
                "wearableLastUpdateMs": None,
                "wearableHeartRateBpm": None,
                "userReportedFatigue": fatigue,
                "availableSourceCount": 2,
                "totalSourceCount": 4,
            },
            "source": "PHONE_SIMULATOR",
            "clientEventId": unique("event"),
        }

    def update_state(self, payload: dict[str, Any]) -> dict[str, Any]:
        response = self.client.post("/api/v1/state/update", json=payload)
        response.raise_for_status()
        return dict(response.json())

    def ask(self, session_id: str, version: int, text: str) -> dict[str, Any]:
        response = self.client.post(
            "/api/v1/assistant/query",
            json={
                "sessionId": session_id,
                "requestId": unique("request"),
                "text": text,
                "source": "TEXT",
                "locale": "vi-VN",
                "clientAttemptOf": None,
                "context": {"stateVersion": version, "screen": "assistant"},
            },
        )
        response.raise_for_status()
        return dict(response.json())

    def confirm(
        self, session_id: str, action: dict[str, Any], version: int
    ) -> dict[str, Any]:
        payload: dict[str, Any] = {
            "sessionId": session_id,
            "actionId": action["id"],
            "actionType": action["type"],
            "confirmed": True,
            "confirmationId": unique("confirm"),
            "contextVersion": version,
        }
        if action["type"] == "SET_HVAC_TEMPERATURE":
            payload["hvacTargetTemperatureC"] = action["hvacTargetTemperatureC"]
        response = self.client.post("/api/v1/actions/confirm", json=payload)
        response.raise_for_status()
        return dict(response.json())

    def run_rest_matrix(self) -> tuple[str, int]:
        session = self.start_session()
        state = self.update_state(self.state_payload(session))
        reply = self.ask(session, state["stateVersion"], "Xe của tôi hiện tại thế nào?")
        self.check(
            "UC02 vehicle status + Vertex narration",
            reply["message"]["route"] == "assistant.vehicle_status"
            and reply["llmUsed"] is True
            and str(reply.get("model", "")).startswith("vertex_ai/"),
            route=reply["message"]["route"],
            llmUsed=reply["llmUsed"],
            model=reply.get("model"),
        )

        session = self.start_session()
        state = self.update_state(self.state_payload(session, cabin=18))
        reply = self.ask(session, state["stateVersion"], "Lạnh quá")
        actions = reply["message"]["actions"]
        action = actions[0] if actions else None
        self.check(
            "UC01 HVAC proposal through Armor",
            action is not None
            and reply["message"]["route"] == "comfort.too_cold"
            and action["type"] == "SET_HVAC_TEMPERATURE"
            and action["requiresConfirmation"] is True,
            route=reply["message"]["route"],
            target=action and action.get("hvacTargetTemperatureC"),
            llmUsed=reply["llmUsed"],
        )
        assert action is not None
        accepted = self.confirm(session, action, state["stateVersion"])
        restored = self.client.get("/api/v1/state", params={"sessionId": session}).json()
        replay = self.confirm(session, action, state["stateVersion"])
        self.check(
            "UC01 confirm once + replay reject",
            accepted["accepted"] is True
            and restored["state"]["hvacTargetTemperatureC"]
            == action["hvacTargetTemperatureC"]
            and replay["accepted"] is False,
            accepted=accepted["accepted"],
            hvac=restored["state"]["hvacTargetTemperatureC"],
            replayAccepted=replay["accepted"],
        )

        session = self.start_session()
        before = self.update_state(self.state_payload(session, cabin=18, engine=90))
        reply = self.ask(session, before["stateVersion"], "Lạnh quá")
        old_action = reply["message"]["actions"][0]
        critical = self.update_state(self.state_payload(session, cabin=18, engine=118))
        rejected = self.confirm(session, old_action, before["stateVersion"])
        restored = self.client.get("/api/v1/state", params={"sessionId": session}).json()
        self.check(
            "Armor stale action vs CRITICAL",
            critical["riskAssessment"]["level"] == "CRITICAL"
            and rejected["accepted"] is False
            and restored["state"]["hvacTargetTemperatureC"] is None,
            risk=critical["riskAssessment"]["level"],
            accepted=rejected["accepted"],
            hvac=restored["state"]["hvacTargetTemperatureC"],
        )

        dtc = {
            "code": "U0100",
            "title": "Mất liên lạc ECM/PCM",
            "description": "Mất liên lạc với bộ điều khiển động cơ",
            "severity": "HIGH",
            "recommendation": "Dừng xe an toàn và kiểm tra kỹ thuật.",
            "updatedAtMs": now_ms(),
        }
        session = self.start_session()
        state = self.update_state(self.state_payload(session, dtcs=[dtc]))
        reply = self.ask(session, state["stateVersion"], "Mã U0100 nghĩa là gì?")
        self.check(
            "UC03 active DTC grounded",
            reply["message"]["route"] == "vehicle.fault_concern"
            and "U0100" in reply["message"]["text"]
            and reply["message"]["risk"]["level"] == "HIGH",
            route=reply["message"]["route"],
            risk=reply["message"]["risk"]["level"],
            llmUsed=reply["llmUsed"],
        )

        session = self.start_session()
        state = self.update_state(self.state_payload(session))
        reply = self.ask(session, state["stateVersion"], "Mã P0130 nghĩa là gì?")
        unknown_text = reply["message"]["text"].casefold()
        self.check(
            "UC03 unknown DTC honest",
            reply["message"]["route"] == "vehicle.fault_concern"
            and "P0130" in reply["message"]["text"]
            and (
                "không có trong danh mục" in unknown_text
                or "không thể xác nhận" in unknown_text
            ),
            route=reply["message"]["route"],
            llmUsed=reply["llmUsed"],
            text=reply["message"]["text"],
        )

        session = self.start_session()
        state = self.update_state(self.state_payload(session, drive=260, fatigue=True))
        reply = self.ask(session, state["stateVersion"], "Tôi hơi buồn ngủ")
        action_types = [action["type"] for action in reply["message"]["actions"]]
        self.check(
            "UC04 driver fatigue",
            reply["message"]["route"] == "safety.driver_fatigue"
            and reply["message"]["risk"]["level"] == "HIGH"
            and "SUGGEST_REST_STOP" in action_types,
            route=reply["message"]["route"],
            risk=reply["message"]["risk"]["level"],
            actions=action_types,
        )

        session = self.start_session()
        state = self.update_state(
            self.state_payload(session, crash=True, passenger="RESPONSIVE", occupied=False)
        )
        emergency = state.get("emergency")
        self.check(
            "UC05 single noisy evidence gated",
            state["riskAssessment"]["level"] == "HIGH"
            and (not emergency or emergency.get("state") in {"IDLE", "CANCELLED"}),
            risk=state["riskAssessment"]["level"],
            emergencyState=emergency and emergency.get("state"),
        )

        session = self.start_session()
        state = self.update_state(
            self.state_payload(
                session, crash=True, passenger="NO_RESPONSE", location=True
            )
        )
        emergency = state["emergency"]
        self.check(
            "UC06 crash starts deterministic SOS",
            state["riskAssessment"]["level"] == "CRITICAL"
            and emergency["state"] == "VERIFYING_EVIDENCE"
            and emergency["realEmergencyDispatchEnabled"] is False,
            risk=state["riskAssessment"]["level"],
            state=emergency["state"],
            realDispatch=emergency["realEmergencyDispatchEnabled"],
        )
        brief = emergency["rescueBrief"]
        self.check(
            "UC07 rescue brief simulation only",
            brief["dispatchMode"] == "SIMULATION_ONLY"
            and brief["lastKnownLocation"]["latitude"] == 21.0285
            and brief["realEmergencyDispatchEnabled"] is False,
            dispatchMode=brief["dispatchMode"],
            locationStatus=brief["locationStatus"],
        )
        response = self.client.post(
            f"/api/v1/emergency/{emergency['emergencyId']}/respond",
            json={
                "sessionId": session,
                "responseId": unique("response"),
                "response": "USER_OK",
                "clientTimeMs": now_ms(),
            },
        )
        response.raise_for_status()
        cancelled = response.json()
        self.check(
            "UC06 user cancel is authoritative",
            cancelled["state"] == "CANCELLED" and cancelled["deadlineMs"] is None,
            state=cancelled["state"],
        )

        session = self.start_session()
        state = self.update_state(self.state_payload(session))
        reply = self.ask(
            session,
            state["stateVersion"],
            "Tôi nên chú ý điều gì để chuyến đi thoải mái và an toàn hơn?",
        )
        self.check(
            "UC08 complex query guarded LLM",
            reply["message"]["route"] == "assistant.general"
            and reply["llmUsed"] is True
            and not reply["message"]["actions"],
            route=reply["message"]["route"],
            llmUsed=reply["llmUsed"],
            model=reply.get("model"),
            actions=len(reply["message"]["actions"]),
            fallback=reply["fallback"],
        )

        websocket_session = self.start_session()
        websocket_state = self.update_state(self.state_payload(websocket_session))
        return websocket_session, websocket_state["stateVersion"]

    async def run_websocket(self, session_id: str, state_version: int) -> None:
        scheme = "wss" if self.base_url.startswith("https://") else "ws"
        host = self.base_url.split("://", 1)[1]
        uri = f"{scheme}://{host}/api/v1/ws/assistant?sessionId={session_id}"
        async with websockets.connect(uri, open_timeout=20, close_timeout=5) as socket:
            await socket.send(
                json.dumps(
                    {
                        "sessionId": session_id,
                        "requestId": unique("ws-request"),
                        "text": "Xe của tôi hiện tại thế nào?",
                        "source": "TEXT",
                        "locale": "vi-VN",
                        "clientAttemptOf": None,
                        "context": {
                            "stateVersion": state_version,
                            "screen": "assistant",
                        },
                    },
                    ensure_ascii=False,
                )
            )
            while True:
                frame = json.loads(await asyncio.wait_for(socket.recv(), timeout=40))
                if frame.get("type") == "error":
                    raise AssertionError(frame)
                if frame.get("type") == "final":
                    message = frame["message"]
                    self.check(
                        "UC09 WebSocket parity",
                        message["route"] == "assistant.vehicle_status"
                        and frame["llmUsed"] is True,
                        type=frame["type"],
                        route=message["route"],
                        llmUsed=frame["llmUsed"],
                        model=frame.get("model"),
                    )
                    return


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--base-url",
        default="https://safedrive-backend-165374511912.asia-southeast1.run.app",
    )
    args = parser.parse_args()
    matrix = DemoMatrix(args.base_url)
    try:
        session_id, state_version = matrix.run_rest_matrix()
        asyncio.run(matrix.run_websocket(session_id, state_version))
    finally:
        matrix.close()
    passed = sum(1 for result in matrix.results if result["pass"])
    print(f"LIVE_MATRIX_SUMMARY={passed}/{len(matrix.results)} PASS")


if __name__ == "__main__":
    main()
