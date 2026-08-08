"""Tests for SAFEDRIVE_AGENT_ARMOR_PLAN.md Slice 4 (engine-temperature trend).

Two layers, matching where this feature's logic actually lives:
  - the pure function ``derive_engine_temperature_trend`` (app/mobile/context.py) -- cases A-D
    plus an explicit out-of-order-timestamp case and a below-minimum-window case;
  - the full session/context/assistant pipeline (``MobileSessionStore``/``MobileContextBuilder``/
    ``ContextAwareAssistant``) -- case E (a real gap is trimmed away by ``MobileSession``'s
    append-time window, not a separate staleness clock), case F (``SafetyRiskEngine`` isolation),
    and case G (``assistant.vehicle_status`` integration).
"""

from __future__ import annotations

import pytest

from app.api.schemas.mobile import StartSessionRequest
from app.mobile.assistant import ContextAwareAssistant
from app.mobile.context import MobileContextBuilder, derive_engine_temperature_trend
from app.mobile.intent import IntentResolver
from app.mobile.safety import SafetyRiskEngine
from app.mobile.session_store import MobileSessionStore
from tests.test_mobile_safety_core import make_request

MIN_WINDOW_MS = MobileContextBuilder.ENGINE_TREND_MIN_WINDOW_MS
STABLE_THRESHOLD_C = MobileContextBuilder.ENGINE_TREND_STABLE_THRESHOLD_C

# A status-check phrase already proven (throughout tests/test_mobile_assistant.py) to route
# deterministically to assistant.vehicle_status -- see IntentResolver's "tinh trang xe" keyword.
VEHICLE_STATUS_QUERY = "Tinh trang xe the nao"


def _derive(samples: list[tuple[int, float]]):
    return derive_engine_temperature_trend(
        samples, min_window_ms=MIN_WINDOW_MS, stable_threshold_c=STABLE_THRESHOLD_C
    )


# --- A/B/C/D + defensive cases: pure-function classification ---


def test_case_a_rising_sequence_is_classified_rising() -> None:
    samples = [(0, 101.0), (60_000, 104.0), (120_000, 107.0), (180_000, 109.0)]
    trend = _derive(samples)

    assert trend is not None
    assert trend.direction == "rising"
    assert trend.delta_c == pytest.approx(8.0)
    assert trend.window_seconds == 180
    assert trend.current_c == 109.0


def test_case_b_fluctuating_small_delta_is_classified_stable() -> None:
    samples = [(0, 109.0), (60_000, 109.0), (120_000, 110.0), (180_000, 109.0)]
    trend = _derive(samples)

    assert trend is not None
    assert trend.direction == "stable"


def test_case_c_falling_sequence_is_classified_falling() -> None:
    samples = [(0, 109.0), (90_000, 106.0), (180_000, 103.0)]
    trend = _derive(samples)

    assert trend is not None
    assert trend.direction == "falling"
    assert trend.delta_c == pytest.approx(-6.0)


def test_case_d_single_sample_is_unavailable() -> None:
    assert _derive([(0, 101.0)]) is None


def test_no_samples_at_all_is_unavailable() -> None:
    assert _derive([]) is None


def test_window_below_minimum_span_is_unavailable_not_a_wild_rate() -> None:
    # Two real values only 5s apart -- ENGINE_TREND_MIN_WINDOW_MS (30s) rejects this as noise
    # rather than reporting a technically-correct but meaningless instantaneous rate.
    samples = [(0, 101.0), (5_000, 109.0)]
    assert _derive(samples) is None


def test_out_of_order_or_non_increasing_timestamp_is_dropped_not_trusted() -> None:
    # The third sample's timestamp does not advance past the second's -- must be ignored
    # entirely, not cause an exception and not silently reorder into a wrong classification.
    samples = [(0, 101.0), (120_000, 109.0), (60_000, 999.0)]
    trend = _derive(samples)

    assert trend is not None
    assert trend.direction == "rising"
    assert trend.delta_c == pytest.approx(8.0)
    assert trend.window_seconds == 120


# --- E: a real gap is trimmed by MobileSession's append-time window, not a separate clock ---


@pytest.mark.asyncio
async def test_case_e_a_gap_past_the_retention_window_leaves_the_trend_unavailable() -> None:
    clock_ms = [0]
    store = MobileSessionStore(clock=lambda: clock_ms[0])
    start_response = await store.start(
        StartSessionRequest(
            deviceId="dev1", appVersion="1.0.0", platform="android", mode="REMOTE", clientTimeMs=0
        )
    )
    session_id = start_response.sessionId

    first_update = make_request(updated_at_ms=clock_ms[0], engine_temperature=101.0).model_copy(
        update={"sessionId": session_id, "clientEventId": "evt-1"}
    )
    await store.update_state(first_update)

    # Jump past ENGINE_TREND_WINDOW_MS (5 min) -- e.g. the app was backgrounded and telemetry
    # resumed later. The first sample must not survive to be averaged against the new one.
    clock_ms[0] = MobileContextBuilder.ENGINE_TREND_WINDOW_MS + 60_000
    second_update = make_request(updated_at_ms=clock_ms[0], engine_temperature=109.0).model_copy(
        update={"sessionId": session_id, "clientEventId": "evt-2"}
    )
    await store.update_state(second_update)

    session = store._sessions[session_id]  # intentional white-box check
    assert len(session.engine_temperature_samples) == 1
    assert _derive(session.engine_temperature_samples) is None


# --- F: SafetyRiskEngine is structurally isolated from trend ---


def test_case_f_critical_engine_temperature_is_unaffected_by_any_trend() -> None:
    request = make_request(engine_temperature=116.0, updated_at_ms=200_000)
    builder = MobileContextBuilder()
    safety_engine = SafetyRiskEngine()

    trend_scenarios: list[list[tuple[int, float]]] = [
        [],  # unavailable
        [(20_000, 101.0), (80_000, 104.0), (140_000, 107.0), (200_000, 109.0)],  # rising
        [(20_000, 109.0), (80_000, 109.0), (140_000, 110.0), (200_000, 109.0)],  # stable
        [(20_000, 109.0), (110_000, 106.0), (200_000, 103.0)],  # falling
    ]
    results = []
    for samples in trend_scenarios:
        snapshot = builder.build(
            request, state_version=1, now_ms=200_000, engine_temperature_samples=samples
        )
        evaluation = safety_engine.evaluate(snapshot, now_ms=200_000)
        results.append((evaluation.risk.level, tuple(evaluation.risk.reasonCodes)))

    assert all(level == "CRITICAL" for level, _ in results)
    assert len({reason_codes for _, reason_codes in results}) == 1  # identical reason codes too


# --- G: assistant.vehicle_status integration ---


def test_case_g_valid_trend_appears_in_vehicle_status_reply() -> None:
    request = make_request(engine_temperature=95.0, updated_at_ms=300_000, cabin_temperature=24.0)
    builder = MobileContextBuilder()
    safety_engine = SafetyRiskEngine()
    assistant = ContextAwareAssistant()
    resolver = IntentResolver()

    rising_samples = [(120_000, 87.0), (180_000, 90.0), (240_000, 93.0), (300_000, 95.0)]
    snapshot = builder.build(
        request, state_version=1, now_ms=300_000, engine_temperature_samples=rising_samples
    )
    safety = safety_engine.evaluate(snapshot, now_ms=300_000)
    # 95C is below ENGINE_WARNING_C (105C): risk stays LOW, isolating the trend clause from the
    # separate, already-existing overheat clause.
    assert safety.risk.level == "LOW"

    resolution = resolver.resolve(VEHICLE_STATUS_QUERY, snapshot, safety)
    assert resolution.route == "assistant.vehicle_status"

    text, _actions = assistant.build_reply(resolution, snapshot, safety, "req-g")

    assert "tăng" in text
    assert "8" in text  # grounded delta: 95 - 87 = 8
    assert "3 phút" in text  # grounded window: 180s = 3 minutes


def test_case_g_unavailable_trend_is_omitted_and_reply_is_unchanged() -> None:
    request = make_request(engine_temperature=95.0, updated_at_ms=300_000, cabin_temperature=24.0)
    builder = MobileContextBuilder()
    safety_engine = SafetyRiskEngine()
    assistant = ContextAwareAssistant()
    resolver = IntentResolver()

    snapshot_no_history = builder.build(request, state_version=1, now_ms=300_000)
    snapshot_default_arg = builder.build(
        request, state_version=1, now_ms=300_000, engine_temperature_samples=()
    )
    safety = safety_engine.evaluate(snapshot_no_history, now_ms=300_000)
    resolution = resolver.resolve(VEHICLE_STATUS_QUERY, snapshot_no_history, safety)

    text_no_arg, _ = assistant.build_reply(resolution, snapshot_no_history, safety, "req-g2a")
    text_default_arg, _ = assistant.build_reply(resolution, snapshot_default_arg, safety, "req-g2b")

    assert "tăng" not in text_no_arg
    assert text_no_arg == text_default_arg  # calling build() with no samples arg at all changes nothing
