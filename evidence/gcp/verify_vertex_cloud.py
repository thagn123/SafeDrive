import httpx
import time
import json
import uuid
import sys
import io

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8")

BASE = "https://safedrive-backend-165374511912.asia-southeast1.run.app"


def main():
    with httpx.Client(base_url=BASE, timeout=30.0) as c:
        r = c.post("/api/v1/sessions/start", json={
            "deviceId": "device_verify_vertex",
            "appVersion": "1.0.0",
            "platform": "android",
            "mode": "REMOTE",
            "clientTimeMs": int(time.time() * 1000),
        })
        r.raise_for_status()
        session = r.json()
        session_id = session["sessionId"]
        print("session:", session_id)

        r = c.post("/api/v1/state/update", json={
            "sessionId": session_id,
            "state": {
                "speedKmh": 50.0,
                "engineTemperatureC": 90.0,
                "cabinTemperatureC": 24.0,
                "energyPercent": 65,
                "continuousDrivingMinutes": 40,
                "steeringLastInteractionSeconds": 5,
                "driverSeatOccupied": True,
                "wearableConnected": False,
                "activeDtcs": [],
                "crashDetected": False,
                "passengerResponse": "RESPONSIVE",
                "updatedAtMs": int(time.time() * 1000),
            },
            "driverSupportSignals": {
                "steeringSignalAvailable": True,
                "seatSensorAvailable": True,
                "wearableLastUpdateMs": None,
                "wearableHeartRateBpm": None,
                "userReportedFatigue": False,
                "availableSourceCount": 3,
                "totalSourceCount": 4,
            },
            "source": "PHONE_SIMULATOR",
            "clientEventId": f"evt_{uuid.uuid4().hex[:8]}",
        })
        r.raise_for_status()
        state_version = r.json().get("stateVersion")
        print("stateVersion:", state_version)

        results = []
        for i in range(10):
            req_id = f"req_verify_{uuid.uuid4().hex[:8]}"
            r = c.post("/api/v1/assistant/query", json={
                "sessionId": session_id,
                "requestId": req_id,
                "text": "Xe của tôi hiện tại thế nào?",
                "source": "TEXT",
                "locale": "vi-VN",
                "clientAttemptOf": None,
                "context": {"stateVersion": state_version, "screen": "cockpit"},
            })
            r.raise_for_status()
            data = r.json()
            results.append(data)
            print(f"\n=== attempt {i} ===")
            print(json.dumps({
                k: data.get(k) for k in
                ["llmUsed", "fallback", "fallbackReason", "provider", "model", "route", "serverProcessingMs"]
            }, ensure_ascii=False))
            print("text:", data.get("text") or data.get("reply") or data.get("message"))

        llm_used_count = sum(1 for d in results if d.get("llmUsed") is True)
        print(f"\n\nSUMMARY: llmUsed=true in {llm_used_count}/10 attempts")
        with open("vertex_verify_results.json", "w", encoding="utf-8") as f:
            json.dump(results, f, ensure_ascii=False, indent=2)


if __name__ == "__main__":
    main()
