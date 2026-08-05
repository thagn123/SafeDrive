import time

import pytest

from app.api.schemas.mobile import StartSessionRequest, StateUpdateRequest
from app.mobile.context import MobileContextBuilder
from app.mobile.safety import SafetyRiskEngine
from app.mobile.session_store import MobileSessionStore


def make_request(
    *,
    updated_at_ms: int | None = None,
    driving_minutes: int | None = 30,
    fatigue: bool | None = False,
    crash: bool = False,
    passenger: str = "RESPONSIVE",
    cabin_temperature: float = 24.0,
    dtc_severity: str | None = None,
    engine_temperature: float = 88.0,
) -> StateUpdateRequest:
    timestamp = updated_at_ms if updated_at_ms is not None else int(time.time() * 1_000)
    active_dtcs = []
    if dtc_severity is not None:
        active_dtcs.append(
            {
                "code": "U0100",
                "title": "Controller communication fault",
                "description": "A simulated active diagnostic fault.",
                "severity": dtc_severity,
                "recommendation": "Review the vehicle before continuing.",
                "updatedAtMs": timestamp,
            }
        )
    return StateUpdateRequest.model_validate(
        {
            "sessionId": "session_test",
            "state": {
                "speedKmh": 72.0,
                "engineTemperatureC": engine_temperature,
                "cabinTemperatureC": cabin_temperature,
                "energyPercent": 24,
                "continuousDrivingMinutes": driving_minutes,
                "steeringLastInteractionSeconds": 8,
                "driverSeatOccupied": True,
                "wearableConnected": False,
                "activeDtcs": active_dtcs,
                "crashDetected": crash,
                "passengerResponse": passenger,
                "updatedAtMs": timestamp,
            },
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
            "clientEventId": "mobile-safety-test-event",
        }
    )


def evaluate(request: StateUpdateRequest, *, now: int | None = None):
    now_ms = int(time.time() * 1_000) if now is None else now
    builder = MobileContextBuilder()
    snapshot = builder.build(request, state_version=1, now_ms=now_ms)
    return snapshot, SafetyRiskEngine().evaluate(snapshot, now_ms=now_ms)


def test_fresh_crash_and_no_response_is_critical_and_can_start_sos_simulation() -> None:
    snapshot, result = evaluate(make_request(crash=True, passenger="NO_RESPONSE"))

    assert snapshot.state_is_fresh is True
    assert result.risk.level == "CRITICAL"
    assert result.risk.reasonCodes == ["crash_detected", "occupant_no_response"]
    assert result.emergency_candidate is True
    assert result.allowed_action_types == ("SHOW_WARNING", "START_SOS_COUNTDOWN")


def test_stale_crash_signal_cannot_automatically_escalate_sos() -> None:
    now = int(time.time() * 1_000)
    request = make_request(
        updated_at_ms=now - MobileContextBuilder.STATE_FRESHNESS_MS - 1,
        crash=True,
        passenger="NO_RESPONSE",
    )
    snapshot, result = evaluate(request, now=now)

    assert snapshot.state_is_fresh is False
    assert result.risk.level == "MEDIUM"
    assert result.risk.reasonCodes == ["vehicle_state_not_fresh"]
    assert result.emergency_candidate is False


def test_fatigue_and_long_driving_recommend_rest_without_claiming_hvac_makes_it_safe() -> None:
    _, result = evaluate(make_request(driving_minutes=245, fatigue=True, cabin_temperature=32.0))

    assert result.risk.level == "HIGH"
    assert set(result.risk.reasonCodes) == {"user_reported_fatigue", "driving_over_4_hours"}
    assert result.rest.level == "REST_RECOMMENDED"
    assert "không làm việc lái xe khi mệt trở nên an toàn" in result.rest.message
    assert result.allowed_action_types == ("SHOW_WARNING", "SUGGEST_REST_STOP")


def test_engine_temperature_below_warning_threshold_is_not_flagged() -> None:
    _, result = evaluate(make_request(engine_temperature=104.9))

    assert result.risk.level == "LOW"
    assert result.risk.reasonCodes == []


def test_engine_temperature_at_warning_threshold_is_high_risk() -> None:
    _, result = evaluate(make_request(engine_temperature=108.0))

    assert result.risk.level == "HIGH"
    assert result.risk.reasonCodes == ["engine_overheat_warning"]
    assert "108" in result.risk.message
    assert result.allowed_action_types == ("SHOW_WARNING",)
    assert "SUGGEST_REST_STOP" not in result.allowed_action_types


def test_engine_temperature_at_critical_threshold_is_critical_but_not_an_emergency_candidate() -> None:
    _, result = evaluate(make_request(engine_temperature=118.0))

    assert result.risk.level == "CRITICAL"
    assert result.risk.reasonCodes == ["engine_overheat_critical"]
    assert "118" in result.risk.message
    # Overheat is urgent driver guidance, not a crash -- it must never enter the SOS/rescue
    # state machine, which is keyed off crashDetected alone (see MobileSessionStore._refresh_emergency).
    assert result.emergency_candidate is False


def test_engine_overheat_takes_priority_over_a_lower_severity_dtc() -> None:
    _, result = evaluate(make_request(engine_temperature=118.0, dtc_severity="LOW"))

    assert result.risk.level == "CRITICAL"
    assert result.risk.reasonCodes == ["engine_overheat_critical"]


def test_high_severity_dtc_prioritizes_diagnostics() -> None:
    _, result = evaluate(make_request(dtc_severity="CRITICAL"))

    assert result.risk.level == "HIGH"
    assert result.risk.reasonCodes == ["high_severity_dtc", "U0100"]
    assert result.allowed_action_types == ("SHOW_WARNING", "OPEN_DIAGNOSTICS")


def test_context_pack_is_compact_and_contains_no_raw_sensor_payload() -> None:
    request = make_request()
    now = int(time.time() * 1_000)
    snapshot = MobileContextBuilder().build(request, state_version=7, now_ms=now)
    pack = MobileContextBuilder.to_context_pack(snapshot)
    serialized_names = {value.name for value in pack.values}

    assert pack.state_version == 7
    assert "vehicle.speed_kmh" in serialized_names
    assert "vehicle.cabin_temperature_c" in serialized_names
    assert "raw_video" not in serialized_names
    assert "raw_audio" not in serialized_names
    assert "raw_can" not in serialized_names
    assert "Do not receive raw video, audio, CAN, or sensor streams." in pack.constraints


@pytest.mark.asyncio
async def test_remote_emergency_workflow_advances_from_verification_to_simulated_dispatch() -> None:
    """The backend, not the Android UI, is authoritative for every SOS transition."""

    current_time = 1_000_000
    store = MobileSessionStore(clock=lambda: current_time)
    session = await store.start(
        StartSessionRequest.model_validate(
            {
                "deviceId": "android-test-device",
                "appVersion": "test",
                "platform": "ANDROID",
                "mode": "REMOTE",
                "clientTimeMs": current_time,
            }
        )
    )
    request = make_request(
        updated_at_ms=current_time,
        crash=True,
        passenger="NO_RESPONSE",
    ).model_copy(update={"sessionId": session.sessionId})

    envelope = await store.update_state(request)
    initial = envelope.emergency
    assert initial is not None
    assert initial.state == "VERIFYING_EVIDENCE"
    assert initial.deadlineMs == current_time + 5_000
    assert initial.rescueBrief is not None

    current_time += 5_000
    awaiting = await store.get_emergency(session.sessionId, initial.emergencyId)
    assert awaiting.state == "AWAITING_USER_RESPONSE"
    assert awaiting.deadlineMs == 1_020_000

    current_time += 15_000
    final = await store.get_emergency(session.sessionId, initial.emergencyId)
    assert final.state == "FINAL_COUNTDOWN"
    assert final.deadlineMs == 1_030_000

    current_time += 10_000
    sent = await store.get_emergency(session.sessionId, initial.emergencyId)
    assert sent.state == "SOS_SIMULATED_SENT"
    assert sent.realEmergencyDispatchEnabled is False
    assert sent.rescueDispatch is not None
    assert sent.rescueDispatch.outcome == "SIMULATED_ACCEPTED"


@pytest.mark.asyncio
async def test_start_purges_expired_sessions_from_memory() -> None:
    current_time = 1_000_000
    store = MobileSessionStore(clock=lambda: current_time)
    payload = {
        "deviceId": "android-test-device",
        "appVersion": "test",
        "platform": "ANDROID",
        "mode": "REMOTE",
        "clientTimeMs": current_time,
    }
    first = await store.start(StartSessionRequest.model_validate(payload))

    current_time += 60 * 60 * 1_000 + 1
    second = await store.start(StartSessionRequest.model_validate(payload))

    assert first.sessionId not in store._sessions
    assert second.sessionId in store._sessions
