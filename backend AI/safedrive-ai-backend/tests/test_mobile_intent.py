from app.mobile.assistant import ContextAwareAssistant
from app.mobile.context import MobileContextBuilder
from app.mobile.intent import IntentResolver
from app.mobile.safety import SafetyRiskEngine
from tests.test_mobile_safety_core import make_request


def test_ambiguous_discomfort_without_supporting_context_requests_clarification() -> None:
    request = make_request(driving_minutes=25, fatigue=False, cabin_temperature=24.0)
    snapshot = MobileContextBuilder().build(
        request, state_version=1, now_ms=request.state.updatedAtMs
    )
    safety = SafetyRiskEngine().evaluate(snapshot, now_ms=request.state.updatedAtMs)

    result = IntentResolver().resolve("Tôi thấy hơi khó chịu", snapshot, safety)

    assert result.route == "assistant.clarify"
    assert result.needs_clarification is True
    assert len(result.hypotheses) == 4


def test_high_severity_dtc_grounds_ambiguous_concern_in_fault_context() -> None:
    request = make_request(dtc_severity="HIGH")
    snapshot = MobileContextBuilder().build(
        request, state_version=1, now_ms=request.state.updatedAtMs
    )
    safety = SafetyRiskEngine().evaluate(snapshot, now_ms=request.state.updatedAtMs)

    result = IntentResolver().resolve("Xe có gì lạ", snapshot, safety)

    assert result.route == "vehicle.fault_concern"
    assert result.confidence >= 0.8


def test_explicit_hvac_temperature_command_extracts_a_typed_target() -> None:
    request = make_request(cabin_temperature=31.0)
    snapshot = MobileContextBuilder().build(
        request, state_version=1, now_ms=request.state.updatedAtMs
    )
    safety = SafetyRiskEngine().evaluate(snapshot, now_ms=request.state.updatedAtMs)

    result = IntentResolver().resolve("Đặt điều hòa 23 độ C", snapshot, safety)

    assert result.route == "climate.set_temperature"
    assert result.confidence == 0.97
    assert result.hvac_target_temperature_c == 23.0


def test_explicit_decimal_hvac_temperature_command_extracts_typed_target() -> None:
    request = make_request(cabin_temperature=28.5)
    snapshot = MobileContextBuilder().build(
        request, state_version=1, now_ms=request.state.updatedAtMs
    )
    safety = SafetyRiskEngine().evaluate(snapshot, now_ms=request.state.updatedAtMs)

    result = IntentResolver().resolve("Bật điều hòa 23.5 độ C", snapshot, safety)

    assert result.route == "climate.set_temperature"
    assert result.hvac_target_temperature_c == 23.5

    plan = ContextAwareAssistant().answer(
        type("Req", (), {"text": "Bật điều hòa 23.5 độ C", "requestId": "test-req-1"})(),
        snapshot,
        safety,
        started_at_ms=1000,
        completed_at_ms=1050,
    )
    assert "23.5 độ C" in plan.response.message.text
    assert plan.response.message.actions[0].hvacTargetTemperatureC == 23.5
    assert "23.5°C" in plan.response.message.actions[0].title


def test_generic_hvac_command_uses_an_energy_aware_default_and_requires_confirmation() -> None:
    request = make_request(cabin_temperature=29.0)
    snapshot = MobileContextBuilder().build(
        request, state_version=1, now_ms=request.state.updatedAtMs
    )
    safety = SafetyRiskEngine().evaluate(snapshot, now_ms=request.state.updatedAtMs)

    plan = ContextAwareAssistant().answer(
        type("Req", (), {"text": "Tôi muốn bật điều hòa", "requestId": "generic-hvac"})(),
        snapshot,
        safety,
        started_at_ms=1_000,
        completed_at_ms=1_050,
    )

    assert plan.resolution.route == "climate.enable_default"
    assert plan.resolution.hvac_target_temperature_c == 22.0
    assert "chưa nêu nhiệt độ" in plan.response.message.text
    assert plan.response.message.actions[0].type == "SET_HVAC_TEMPERATURE"
    assert plan.response.message.actions[0].hvacTargetTemperatureC == 22.0
    assert plan.response.message.actions[0].requiresConfirmation is True


def test_generic_hvac_command_preserves_energy_for_low_energy_state() -> None:
    request = make_request(cabin_temperature=29.0)
    low_energy_state = request.state.model_copy(update={"energyPercent": 18})
    request = request.model_copy(update={"state": low_energy_state})
    snapshot = MobileContextBuilder().build(
        request, state_version=1, now_ms=request.state.updatedAtMs
    )
    safety = SafetyRiskEngine().evaluate(snapshot, now_ms=request.state.updatedAtMs)

    result = IntentResolver().resolve("Bật máy lạnh", snapshot, safety)

    assert result.route == "climate.enable_default"
    assert result.hvac_target_temperature_c == 24.0


def test_compound_hot_cabin_and_enable_ac_uses_context_aware_comfort_route() -> None:
    request = make_request(cabin_temperature=33.0)
    snapshot = MobileContextBuilder().build(
        request, state_version=1, now_ms=request.state.updatedAtMs
    )
    safety = SafetyRiskEngine().evaluate(snapshot, now_ms=request.state.updatedAtMs)

    result = IntentResolver().resolve("Nóng quá, bật điều hòa lên", snapshot, safety)

    assert result.route == "comfort.too_hot"


def test_cold_cabin_complaint_uses_context_aware_comfort_route() -> None:
    # Symmetric to test_compound_hot_cabin_and_enable_ac_uses_context_aware_comfort_route:
    # cold-discomfort phrasing must not fall through to assistant.general, which can
    # never create an HVAC action (see app/mobile/intent.py's comfort.too_cold branch).
    request = make_request(cabin_temperature=16.0)
    snapshot = MobileContextBuilder().build(
        request, state_version=1, now_ms=request.state.updatedAtMs
    )
    safety = SafetyRiskEngine().evaluate(snapshot, now_ms=request.state.updatedAtMs)

    result = IntentResolver().resolve("Lạnh quá", snapshot, safety)

    assert result.route == "comfort.too_cold"
    assert result.confidence == 0.92


def test_cold_keyword_ret_also_routes_to_comfort_too_cold() -> None:
    request = make_request(cabin_temperature=16.0)
    snapshot = MobileContextBuilder().build(
        request, state_version=1, now_ms=request.state.updatedAtMs
    )
    safety = SafetyRiskEngine().evaluate(snapshot, now_ms=request.state.updatedAtMs)

    result = IntentResolver().resolve("Tôi thấy rét quá", snapshot, safety)

    assert result.route == "comfort.too_cold"


def test_natural_fault_question_is_recognized_without_exact_demo_keyword() -> None:
    request = make_request(dtc_severity="HIGH")
    snapshot = MobileContextBuilder().build(
        request, state_version=1, now_ms=request.state.updatedAtMs
    )
    safety = SafetyRiskEngine().evaluate(snapshot, now_ms=request.state.updatedAtMs)

    result = IntentResolver().resolve("Xe có vấn đề gì không?", snapshot, safety)

    assert result.route == "vehicle.fault_concern"


def test_user_saying_not_well_is_grounded_or_clarified_instead_of_general_fallback() -> None:
    request = make_request(driving_minutes=25, fatigue=False, cabin_temperature=24.0)
    snapshot = MobileContextBuilder().build(
        request, state_version=1, now_ms=request.state.updatedAtMs
    )
    safety = SafetyRiskEngine().evaluate(snapshot, now_ms=request.state.updatedAtMs)

    result = IntentResolver().resolve("Tôi không khỏe", snapshot, safety)

    assert result.route == "assistant.clarify"
    assert result.needs_clarification is True


def test_accented_companion_request_routes_to_llm_eligible_conversation() -> None:
    request = make_request(driving_minutes=25, fatigue=False, cabin_temperature=24.0)
    snapshot = MobileContextBuilder().build(
        request, state_version=1, now_ms=request.state.updatedAtMs
    )
    safety = SafetyRiskEngine().evaluate(snapshot, now_ms=request.state.updatedAtMs)

    result = IntentResolver().resolve(
        "N\u00f3i chuy\u1ec7n v\u1edbi t\u00f4i m\u1ed9t ch\u00fat, t\u00f4i \u0111ang th\u1ea5y c\u0103ng th\u1eb3ng.",
        snapshot,
        safety,
    )

    assert result.route == "companion.conversation"
    assert result.confidence >= 0.8


def test_fatigue_keyword_during_active_crash_surfaces_the_emergency_not_fatigue_text() -> None:
    # A fatigue-sounding query ("Toi hoi buon ngu") would normally route to
    # safety.driver_fatigue, but with crash_detected + occupant_no_response
    # both active, the reply must surface the live emergency rather than
    # relabeling crash evidence as unrelated "fatigue risk" (a real bug found
    # by adversarial testing: the old text said "context shows fatigue risk
    # (crash_detected, occupant_no_response)").
    request = make_request(crash=True, passenger="NO_RESPONSE")
    snapshot = MobileContextBuilder().build(
        request, state_version=1, now_ms=request.state.updatedAtMs
    )
    safety = SafetyRiskEngine().evaluate(snapshot, now_ms=request.state.updatedAtMs)
    assert safety.emergency_candidate is True

    plan = ContextAwareAssistant().answer(
        type("Req", (), {"text": "Toi hoi buon ngu", "requestId": "crash-fatigue-race"})(),
        snapshot,
        safety,
        started_at_ms=1_000,
        completed_at_ms=1_050,
    )

    assert "mệt mỏi" not in plan.response.message.text.lower()
    assert "va chạm" in plan.response.message.text or "SOS" in plan.response.message.text
    assert any(
        action.type == "START_SOS_COUNTDOWN" for action in plan.response.message.actions
    )


def test_negative_hvac_temperature_is_parsed_as_negative_not_silently_flipped_positive() -> None:
    request = make_request(cabin_temperature=27.0)
    snapshot = MobileContextBuilder().build(
        request, state_version=1, now_ms=request.state.updatedAtMs
    )
    safety = SafetyRiskEngine().evaluate(snapshot, now_ms=request.state.updatedAtMs)

    # "am 20 do C" (Vietnamese "âm" = negative, tone-stripped by normalize_text)
    # must resolve to -20, not be silently misread as +20 (which is in-range
    # and would otherwise be silently accepted as the opposite of what the
    # user asked for).
    result = IntentResolver().resolve("Đặt điều hòa âm 20 độ C", snapshot, safety)

    assert result.route == "climate.invalid_temperature"
    assert result.requested_temperature_c == -20.0
    assert result.hvac_target_temperature_c is None


def test_invalid_hvac_temperature_explains_the_safe_range_without_creating_an_action() -> None:
    request = make_request(cabin_temperature=27.0)
    snapshot = MobileContextBuilder().build(
        request, state_version=1, now_ms=request.state.updatedAtMs
    )
    safety = SafetyRiskEngine().evaluate(snapshot, now_ms=request.state.updatedAtMs)

    plan = ContextAwareAssistant().answer(
        type("Req", (), {"text": "Đặt điều hòa 31°C", "requestId": "invalid-hvac"})(),
        snapshot,
        safety,
        started_at_ms=1_000,
        completed_at_ms=1_050,
    )

    assert plan.resolution.route == "climate.invalid_temperature"
    assert plan.resolution.requested_temperature_c == 31.0
    assert "16 đến 30 độ C" in plan.response.message.text
    assert plan.response.message.actions == []


# --- DTC-code-shaped token recognition (competition-audit regression) ---
# "Ma U0100 nghia la gi?" previously fell through to assistant.general's catch-all
# because it matches none of vehicle.fault_concern's natural-language keywords
# ("bao loi", "ma loi", "dtc", ...). A driver naming a specific code is an
# unambiguous signal regardless of phrasing, so IntentResolver now recognizes the
# standard 5-character OBD-II shape directly.


def test_dtc_shaped_code_routes_to_fault_concern_even_without_a_keyword() -> None:
    request = make_request()
    snapshot = MobileContextBuilder().build(
        request, state_version=1, now_ms=request.state.updatedAtMs
    )
    safety = SafetyRiskEngine().evaluate(snapshot, now_ms=request.state.updatedAtMs)

    result = IntentResolver().resolve("Ma U0100 nghia la gi?", snapshot, safety)

    assert result.route == "vehicle.fault_concern"
    assert result.mentioned_dtc_code == "U0100"


def test_dtc_shaped_code_p0300_routes_to_fault_concern() -> None:
    request = make_request()
    snapshot = MobileContextBuilder().build(
        request, state_version=1, now_ms=request.state.updatedAtMs
    )
    safety = SafetyRiskEngine().evaluate(snapshot, now_ms=request.state.updatedAtMs)

    result = IntentResolver().resolve("Xe bao loi P0300", snapshot, safety)

    assert result.route == "vehicle.fault_concern"
    assert result.mentioned_dtc_code == "P0300"


def test_dtc_shaped_code_b1234_routes_to_fault_concern() -> None:
    request = make_request()
    snapshot = MobileContextBuilder().build(
        request, state_version=1, now_ms=request.state.updatedAtMs
    )
    safety = SafetyRiskEngine().evaluate(snapshot, now_ms=request.state.updatedAtMs)

    result = IntentResolver().resolve("Ma B1234 la loi gi?", snapshot, safety)

    assert result.route == "vehicle.fault_concern"
    assert result.mentioned_dtc_code == "B1234"


def test_non_dtc_shaped_token_is_not_treated_as_a_confirmed_dtc() -> None:
    request = make_request()
    snapshot = MobileContextBuilder().build(
        request, state_version=1, now_ms=request.state.updatedAtMs
    )
    safety = SafetyRiskEngine().evaluate(snapshot, now_ms=request.state.updatedAtMs)

    result = IntentResolver().resolve("XYZ123 nghia la gi?", snapshot, safety)

    assert result.mentioned_dtc_code is None
    assert result.route == "assistant.general"


def test_sos_keyword_still_wins_over_a_dtc_shaped_code_in_the_same_message() -> None:
    request = make_request()
    snapshot = MobileContextBuilder().build(
        request, state_version=1, now_ms=request.state.updatedAtMs
    )
    safety = SafetyRiskEngine().evaluate(snapshot, now_ms=request.state.updatedAtMs)

    result = IntentResolver().resolve("SOS xe bao loi U0100 giup toi", snapshot, safety)

    assert result.route == "safety.emergency_request"
