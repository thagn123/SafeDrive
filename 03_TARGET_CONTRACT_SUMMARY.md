# Target Contract Summary

Day la tom tat contract ma backend va app can khop.

## Health

Request:

```text
GET /health
```

Response:

```json
{
  "status": "NORMAL",
  "service": "SafeDrive AI Backend",
  "apiVersion": "1.0.0",
  "serverTimeMs": 1720000000000,
  "capabilities": {
    "assistant": true,
    "emergencySimulation": true,
    "cockpitStream": false
  }
}
```

## Session

Request:

```text
POST /api/v1/sessions/start
```

Response:

```json
{
  "sessionId": "session_demo_01",
  "expiresAtMs": 1720003600000,
  "serverTimeMs": 1720000000000,
  "contractVersion": "1.0.0",
  "realEmergencyDispatchEnabled": false
}
```

## State Update

Request:

```text
POST /api/v1/state/update
```

Backend receives app state and should return enriched state:

```json
{
  "state": {
    "speedKmh": 72,
    "engineTemperatureC": 88,
    "cabinTemperatureC": 31,
    "energyPercent": 24,
    "continuousDrivingMinutes": 245,
    "steeringLastInteractionSeconds": 8,
    "driverSeatOccupied": true,
    "wearableConnected": false,
    "activeDtcs": [],
    "crashDetected": false,
    "passengerResponse": "UNKNOWN",
    "updatedAtMs": 1720000000000
  },
  "driverSupportSignals": {
    "steeringSignalAvailable": true,
    "seatSensorAvailable": true,
    "wearableLastUpdateMs": null,
    "wearableHeartRateBpm": null,
    "userReportedFatigue": true,
    "availableSourceCount": 2,
    "totalSourceCount": 4
  },
  "riskAssessment": {
    "level": "HIGH",
    "title": "Nguy co met moi",
    "message": "Tai xe da lai lau va co dau hieu met moi.",
    "reasonCodes": ["driving_over_4_hours", "user_reported_fatigue"]
  },
  "restRecommendation": {
    "level": "RECOMMENDED",
    "title": "Nen dung nghi",
    "message": "Hay dung tai diem an toan gan nhat.",
    "confidence": "MEDIUM",
    "reasonCodes": ["long_drive", "fatigue_signal"],
    "updatedAtMs": 1720000000000
  },
  "stateVersion": 1,
  "acceptedAtMs": 1720000000000
}
```

## Assistant Query

Request:

```text
POST /api/v1/assistant/query
```

Response:

```json
{
  "requestId": "req_01",
  "message": {
    "id": "msg_01",
    "sender": "SAFEDRIVE",
    "text": "Ban da lai hon 4 tieng va cabin dang nong. Toi khuyen ban dung nghi o diem an toan gan nhat. Toi co the tang gio dieu hoa trong luc do.",
    "timestampMs": 1720000000000,
    "risk": {
      "level": "HIGH",
      "title": "Nguy co met moi",
      "message": "Long drive + fatigue context.",
      "reasonCodes": ["driving_over_4_hours", "hot_cabin"]
    },
    "actions": [
      {
        "id": "act_fan_01",
        "type": "INCREASE_FAN",
        "title": "Tang gio dieu hoa",
        "requiresConfirmation": true
      }
    ],
    "route": "safety.driver_fatigue",
    "latencyMs": 120
  },
  "serverTimeMs": 1720000000000,
  "serverProcessingMs": 120,
  "model": "deterministic-router",
  "finishReason": "STOP"
}
```

## Emergency Snapshot

Response:

```json
{
  "emergencyId": "emg_01",
  "state": "FINAL_COUNTDOWN",
  "deadlineMs": 1720000030000,
  "evidence": [
    {
      "code": "crash_detected",
      "label": "Phat hien va cham",
      "detectedAtMs": 1720000000000
    },
    {
      "code": "driver_no_response",
      "label": "Khong co phan hoi tu nguoi trong xe",
      "detectedAtMs": 1720000001000
    }
  ],
  "realEmergencyDispatchEnabled": false
}
```

## Rescue brief simulation

Backend co the luu noi bo hoac expose qua action result:

```json
{
  "dispatchMode": "SIMULATION_ONLY",
  "vehicleStatusSummary": "Crash detected, vehicle stopped, driver no response.",
  "locationSummary": "Last known GPS from simulator: 21.0285, 105.8542.",
  "riskLevel": "CRITICAL",
  "evidence": ["crash_detected", "driver_no_response"],
  "realEmergencyDispatchEnabled": false
}
```

