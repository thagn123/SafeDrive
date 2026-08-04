"""Response-quality tests for ContextAwareAssistant's deterministic templates.

These routes (vehicle.fault_concern, safety.driver_fatigue,
assistant.vehicle_status) are never narrated by the LLM -- see
_NARRATABLE_ROUTES in app/mobile/session_store.py -- so the text built here by
ContextAwareAssistant IS the final user-facing reply. That makes this module
the right place to test response quality (no raw reason codes, no unsafe
echoed client text, grounded numbers) independent of any LLM.
"""

from __future__ import annotations

import time

from app.api.schemas.mobile import StateUpdateRequest
from app.mobile.assistant import ContextAwareAssistant
from app.mobile.context import MobileContextBuilder
from app.mobile.intent import IntentResolver
from app.mobile.safety import SafetyRiskEngine

UNSAFE_CLIENT_RECOMMENDATION = "Ban co the tiep tuc lai xe binh thuong."


def make_request(
    *,
    driving_minutes: int | None = 30,
    fatigue: bool | None = False,
    cabin_temperature: float = 24.0,
    dtc_severity: str | None = None,
    speed_kmh: float = 72.0,
    updated_at_ms: int | None = None,
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
                "recommendation": UNSAFE_CLIENT_RECOMMENDATION,
                "updatedAtMs": timestamp,
            }
        )
    return StateUpdateRequest.model_validate(
        {
            "sessionId": "session_test",
            "state": {
                "speedKmh": speed_kmh,
                "engineTemperatureC": engine_temperature,
                "cabinTemperatureC": cabin_temperature,
                "energyPercent": 24,
                "continuousDrivingMinutes": driving_minutes,
                "steeringLastInteractionSeconds": 8,
                "driverSeatOccupied": True,
                "wearableConnected": False,
                "activeDtcs": active_dtcs,
                "crashDetected": False,
                "passengerResponse": "UNKNOWN",
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
            "clientEventId": "mobile-assistant-test-event",
        }
    )


def plan_reply(text: str, *, now_ms: int | None = None, **kwargs: object) -> tuple[str, list[object]]:
    request = make_request(**kwargs)  # type: ignore[arg-type]
    now = now_ms if now_ms is not None else int(time.time() * 1_000)
    snapshot = MobileContextBuilder().build(request, state_version=1, now_ms=now)
    safety = SafetyRiskEngine().evaluate(snapshot, now_ms=now)
    resolution = IntentResolver().resolve(text, snapshot, safety)
    return ContextAwareAssistant().build_reply(resolution, snapshot, safety, "req_test")


# --- Gap 3: DTC severity-aware backend policy, not a client-recommendation echo ---


def test_high_dtc_never_echoes_client_recommendation_and_overrides_with_backend_policy() -> None:
    text, _ = plan_reply("Bao loi gi vay", dtc_severity="HIGH")

    assert UNSAFE_CLIENT_RECOMMENDATION not in text
    assert "Không nên tiếp tục hành trình dài" in text
    assert "chưa phải chẩn đoán kỹ thuật cuối cùng" in text
    assert "U0100" in text


def test_critical_dtc_never_echoes_client_recommendation_and_overrides_with_backend_policy() -> None:
    text, _ = plan_reply("Bao loi gi vay", dtc_severity="CRITICAL")

    assert UNSAFE_CLIENT_RECOMMENDATION not in text
    assert "Không nên tiếp tục hành trình dài" in text
    assert "chưa phải chẩn đoán kỹ thuật cuối cùng" in text


def test_medium_dtc_allows_cautious_continuation_without_alarm_language() -> None:
    text, _ = plan_reply("Bao loi gi vay", dtc_severity="MEDIUM")

    assert UNSAFE_CLIENT_RECOMMENDATION not in text
    assert "thận trọng" in text
    assert "Không nên tiếp tục hành trình dài" not in text


def test_low_dtc_recommends_checking_without_unnecessary_alarm() -> None:
    text, _ = plan_reply("Bao loi gi vay", dtc_severity="LOW")

    assert UNSAFE_CLIENT_RECOMMENDATION not in text
    assert "kiểm tra" in text
    assert "Không nên tiếp tục hành trình dài" not in text
    assert "nghiêm trọng cao" not in text


def test_dtc_route_never_speaks_the_client_recommendation_field_for_any_severity() -> None:
    for severity in ("LOW", "MEDIUM", "HIGH", "CRITICAL"):
        text, _ = plan_reply("Bao loi gi vay", dtc_severity=severity)
        assert UNSAFE_CLIENT_RECOMMENDATION not in text, severity


# --- Case A: fatigue response must not leak raw internal reason codes ---


def test_fatigue_reply_never_leaks_raw_reason_codes() -> None:
    text, _ = plan_reply("Toi hoi buon ngu", driving_minutes=245, fatigue=True, cabin_temperature=32.0)

    assert "user_reported_fatigue" not in text
    assert "driving_over_4_hours" not in text
    assert "245" in text


def test_fatigue_reply_handles_missing_driving_duration_gracefully() -> None:
    text, _ = plan_reply("Toi hoi buon ngu", driving_minutes=None, fatigue=True)

    assert "Bạn báo đang mệt." in text
    assert "None" not in text


def test_fatigue_reply_grounds_long_drive_without_a_user_report() -> None:
    text, _ = plan_reply(
        "Toi khong on, co nen dung khong", driving_minutes=245, fatigue=False, cabin_temperature=24.0
    )

    assert "245" in text
    assert "user_reported_fatigue" not in text
    assert "driving_over_4_hours" not in text


# --- Case C: vehicle-status response prioritizes the abnormal condition, not a field dump ---


def test_vehicle_status_leads_with_risk_headline_when_abnormal() -> None:
    text, _ = plan_reply("Tinh trang xe the nao", cabin_temperature=32.0)

    assert text.startswith("Cabin đang nóng.")


def test_vehicle_status_uses_calm_headline_when_risk_is_low() -> None:
    text, _ = plan_reply("Tinh trang xe the nao")

    assert "vận hành bình thường" in text


def test_vehicle_status_still_grounds_speed_and_duration_numbers() -> None:
    text, _ = plan_reply("Tinh trang xe the nao", speed_kmh=60.0, driving_minutes=20)

    assert "60 km/h" in text
    assert "20 phút" in text


def test_vehicle_status_leads_with_engine_overheat_headline() -> None:
    text, _ = plan_reply("Tinh trang xe the nao", engine_temperature=118.0)

    assert text.startswith("Động cơ quá nhiệt nghiêm trọng.")
    assert "118" in text


def test_stale_state_never_claims_a_confident_status_and_asks_for_a_refresh() -> None:
    now = int(time.time() * 1_000)
    stale_timestamp = now - MobileContextBuilder.STATE_FRESHNESS_MS - 1

    text, _ = plan_reply("Tinh trang xe the nao", updated_at_ms=stale_timestamp, now_ms=now)

    assert "cập nhật" in text
    assert "km/h" not in text
