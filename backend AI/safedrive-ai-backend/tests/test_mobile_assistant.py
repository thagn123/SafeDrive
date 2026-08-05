"""Response-quality tests for ContextAwareAssistant's deterministic templates.

These tests exercise ContextAwareAssistant.build_reply directly, with no LLM
involved at all -- so they check response quality (no raw reason codes, no
unsafe echoed client text, grounded numbers) independent of narration. Most
of these routes (vehicle.fault_concern, safety.driver_fatigue,
assistant.vehicle_status, HVAC) are now also narratable via the LLM when risk
is LOW/MEDIUM (see _NARRATABLE_ROUTES in app/mobile/session_store.py) -- when
that happens, this module's text is what the narrator receives as
APPROVED_REPLY/DETERMINISTIC_FALLBACK and what its guardrail (including
required_narration_snippets) validates against, and it remains the exact
fallback text whenever Ollama is unavailable or rejects the LLM's output.
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


# --- DTC-code-shaped token: specific-code lookup against the active-DTC catalog ---


def test_known_dtc_code_question_preserves_exact_code_and_uses_catalog_severity() -> None:
    text, _ = plan_reply("Ma U0100 nghia la gi?", dtc_severity="MEDIUM")

    assert "U0100" in text
    assert "Controller communication fault" in text
    assert "thận trọng" in text


def test_known_dtc_code_question_never_weakens_high_severity_guidance() -> None:
    text, _ = plan_reply("Ma U0100 nghia la gi?", dtc_severity="HIGH")

    assert UNSAFE_CLIENT_RECOMMENDATION not in text
    assert "Không nên tiếp tục hành trình dài" in text
    assert "U0100" in text


def test_known_but_inactive_dtc_code_gives_catalog_meaning_and_says_not_active() -> None:
    # P0300 is in the small static reference catalog (app/mobile/dtc_catalog.py) but
    # not currently active on this vehicle (no active_dtcs at all).
    text, _ = plan_reply("Ma P0300 nghia la gi?")

    assert "P0300" in text
    assert "bỏ máy" in text  # catalog-owned generic meaning is present
    assert "KHÔNG" in text  # explicitly framed as not currently active
    assert "đang hoạt động" in text


def test_known_but_inactive_dtc_code_never_creates_an_active_risk() -> None:
    resolution, _snapshot, safety = resolve_snapshot_safety("Ma P0300 nghia la gi?")

    assert resolution.mentioned_dtc_code == "P0300"
    assert safety.risk.level == "LOW"


def test_unknown_to_catalog_valid_shaped_dtc_code_is_not_hallucinated() -> None:
    # P0130 is a real, valid-shaped OBD-II code but deliberately not included in this
    # system's small static catalog, and not currently active either.
    text, _ = plan_reply("Ma P0130 nghia la gi?")

    assert "P0130" in text
    assert "danh mục tham khảo đáng tin cậy" in text
    assert "cảm biến oxy" not in text
    assert "Oxygen" not in text


def test_unknown_to_catalog_dtc_code_question_still_surfaces_a_real_active_dtc_if_one_exists() -> None:
    # Vehicle actually has U0100 active; driver asks about the unrelated, uncataloged P0130.
    text, _ = plan_reply("Ma P0130 nghia la gi?", dtc_severity="LOW")

    assert "P0130" in text
    assert "danh mục tham khảo đáng tin cậy" in text
    assert "U0100" in text


def test_non_dtc_token_falls_through_to_general_open_answer_path_not_fault_concern() -> None:
    resolution, _, _ = resolve_snapshot_safety("XYZ123 nghia la gi?")

    assert resolution.route == "assistant.general"


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


# --- assistant.general vs assistant.clarify: distinct templates, distinct routes ---


def test_general_catchall_and_clarify_produce_different_text() -> None:
    """Regression for the bug the user reported: before the split, IntentResolver's
    true catch-all (nothing matched any known category, e.g. an off-topic question)
    and a genuinely ambiguous fatigue/cabin/vehicle-concern message collapsed into the
    exact same disambiguation string. They must now be distinguishable."""

    general_text, _ = plan_reply("Hom nay troi dep nhi")
    clarify_text, _ = plan_reply("Toi thay hoi kho chiu")

    assert general_text != clarify_text
    assert "nằm ngoài phạm vi hỗ trợ" in general_text
    assert "mệt, khó chịu trong cabin, hay lo về tình trạng xe" not in general_text
    assert "mệt, khó chịu trong cabin, hay lo về tình trạng xe" in clarify_text
    assert "nằm ngoài phạm vi hỗ trợ" not in clarify_text


def test_general_catchall_never_pretends_to_know_what_was_asked() -> None:
    text, _ = plan_reply("1+1 bang may")

    assert "nằm ngoài phạm vi hỗ trợ" in text
    assert "mệt" not in text


def test_general_catchall_is_a_contextual_fallback_not_a_bare_refusal() -> None:
    """The catch-all's deterministic text doubles as OllamaNarrator.answer_open_query's
    fallback whenever the LLM is unavailable or rejects both attempts. It must not be a
    content-free refusal in that case -- the driver should still get real, current
    vehicle facts, since many assistant.general hits are genuinely vehicle-related
    questions no keyword happened to match (e.g. "xe cua toi the nao")."""

    text, _ = plan_reply(
        "xe cua toi the nao", speed_kmh=60.0, cabin_temperature=25.0
    )

    assert "60" in text
    assert "25" in text
    assert "km/h" in text


# --- ContextAwareAssistant.required_narration_snippets: mirrors _message_and_actions ---


def resolve_snapshot_safety(text: str, **kwargs: object) -> tuple[object, object, object]:
    request = make_request(**kwargs)  # type: ignore[arg-type]
    now = int(time.time() * 1_000)
    snapshot = MobileContextBuilder().build(request, state_version=1, now_ms=now)
    safety = SafetyRiskEngine().evaluate(snapshot, now_ms=now)
    resolution = IntentResolver().resolve(text, snapshot, safety)
    return resolution, snapshot, safety


def test_required_narration_snippets_include_dtc_code_and_guidance() -> None:
    resolution, snapshot, safety = resolve_snapshot_safety("Bao loi gi vay", dtc_severity="MEDIUM")

    snippets = ContextAwareAssistant.required_narration_snippets(resolution, snapshot, safety)

    assert "U0100" in snippets
    assert any("thận trọng" in snippet for snippet in snippets)


def test_required_narration_snippets_include_rest_stop_directive_for_fatigue() -> None:
    resolution, snapshot, safety = resolve_snapshot_safety(
        "Toi hoi buon ngu", driving_minutes=30, fatigue=True, cabin_temperature=24.0
    )

    snippets = ContextAwareAssistant.required_narration_snippets(resolution, snapshot, safety)

    assert any("dừng ở vị trí an toàn" in snippet for snippet in snippets)


def test_required_narration_snippets_empty_for_hvac_and_low_risk_status() -> None:
    hvac_resolution, hvac_snapshot, hvac_safety = resolve_snapshot_safety("Bat dieu hoa")
    assert ContextAwareAssistant.required_narration_snippets(hvac_resolution, hvac_snapshot, hvac_safety) == ()

    status_resolution, status_snapshot, status_safety = resolve_snapshot_safety("Tinh trang xe the nao")
    assert (
        ContextAwareAssistant.required_narration_snippets(status_resolution, status_snapshot, status_safety) == ()
    )


def test_required_narration_snippets_include_risk_title_for_abnormal_status() -> None:
    resolution, snapshot, safety = resolve_snapshot_safety("Tinh trang xe the nao", engine_temperature=118.0)

    snippets = ContextAwareAssistant.required_narration_snippets(resolution, snapshot, safety)

    assert safety.risk.title in snippets
