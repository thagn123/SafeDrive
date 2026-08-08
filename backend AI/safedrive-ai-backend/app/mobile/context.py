"""Build compact, fresh context for SafeDrive reasoning.

The mobile API receives a bounded, structured cockpit snapshot. This module
keeps it structured and attaches freshness/missing-data metadata before any
assistant or safety logic uses it. It deliberately has no video, audio, CAN,
or free-form sensor payload fields.
"""

from __future__ import annotations

from collections.abc import Sequence
from dataclasses import dataclass
from typing import Literal

from app.api.schemas.mobile import DriverSupportSignals, StateUpdateRequest, VehicleState

FreshnessStatus = Literal["FRESH", "STALE", "UNAVAILABLE"]
TrendDirection = Literal["rising", "stable", "falling"]


@dataclass(frozen=True, slots=True)
class ContextValue:
    """A single safe-to-reason-over value with its quality metadata."""

    name: str
    value: object | None
    source: str
    age_ms: int | None
    status: FreshnessStatus


@dataclass(frozen=True, slots=True)
class ContextSnapshot:
    """Current mobile state plus compact quality information for Safety Core."""

    state: VehicleState
    driver_support: DriverSupportSignals
    source: str
    state_version: int
    values: dict[str, ContextValue]
    missing_context: tuple[str, ...]

    @property
    def state_is_fresh(self) -> bool:
        return self.values["vehicle.state"].status == "FRESH"


@dataclass(frozen=True, slots=True)
class ContextPack:
    """LLM-safe context contract for constrained companion narration.

    The provider may receive this bounded representation only after the
    deterministic system has produced an approved plan. It never receives a
    raw mobile request by convenience.
    """

    state_version: int
    values: tuple[ContextValue, ...]
    missing_context: tuple[str, ...]
    constraints: tuple[str, ...]


@dataclass(frozen=True, slots=True)
class EngineTemperatureTrend:
    """A derived fact only -- see SAFEDRIVE_AGENT_ARMOR_PLAN.md Slice 4. Computed from recent
    engine-temperature samples already flowing through MobileSession; never read by
    SafetyRiskEngine (which only ever reads ContextSnapshot.state/.driver_support, never
    .values), and never itself a safety judgment."""

    current_c: float
    delta_c: float
    window_seconds: int
    direction: TrendDirection


def derive_engine_temperature_trend(
    samples: Sequence[tuple[int, float]],
    *,
    min_window_ms: int,
    stable_threshold_c: float,
) -> EngineTemperatureTrend | None:
    """Smallest deterministic classifier for a short-term engine-temperature trend.

    ``samples`` is expected to already be in (timestamp_ms, value_c) order, oldest first, and
    already trimmed to the caller's retention window (see MobileSession.engine_temperature_samples
    in app/mobile/session_store.py) -- this function does not own retention policy. It does,
    independently, defend against out-of-order/non-increasing timestamps itself (dropping any
    sample that does not strictly advance the clock versus the last kept one), so it is safe and
    correctly testable even when called directly with adversarial input, not only through the
    trusted append path.

    Returns ``None`` ("unavailable") whenever the evidence is not trustworthy enough to name a
    direction: fewer than two usable samples, or too little elapsed time between the oldest and
    newest usable sample to distinguish a real trend from noise. This is a derived fact only --
    it never computes or implies a risk/safety level.
    """

    cleaned: list[tuple[int, float]] = []
    for timestamp_ms, value_c in samples:
        if cleaned and timestamp_ms <= cleaned[-1][0]:
            continue
        cleaned.append((timestamp_ms, value_c))

    if len(cleaned) < 2:
        return None

    earliest_ts, earliest_value = cleaned[0]
    latest_ts, latest_value = cleaned[-1]
    window_ms = latest_ts - earliest_ts
    if window_ms < min_window_ms:
        return None

    delta_c = latest_value - earliest_value
    direction: TrendDirection
    if abs(delta_c) < stable_threshold_c:
        direction = "stable"
    elif delta_c > 0:
        direction = "rising"
    else:
        direction = "falling"

    return EngineTemperatureTrend(
        current_c=latest_value,
        delta_c=delta_c,
        window_seconds=window_ms // 1000,
        direction=direction,
    )


class MobileContextBuilder:
    """Creates compact state snapshots with bounded freshness semantics."""

    STATE_FRESHNESS_MS = 10_000
    WEARABLE_FRESHNESS_MS = 30_000
    FUTURE_TOLERANCE_MS = 5_000
    # Engine-temperature trend (SAFEDRIVE_AGENT_ARMOR_PLAN.md Slice 4). Deliberately separate
    # constants from STATE_FRESHNESS_MS above: that 10s window governs whether the single latest
    # reading is current, and is far too short to ever observe a multi-minute trend. These three
    # constants govern a different question -- how much recent history is worth keeping and when
    # it is old/thin enough to be untrustworthy -- and have no existing project precedent to reuse.
    ENGINE_TREND_WINDOW_MS = 5 * 60 * 1_000
    ENGINE_TREND_MIN_WINDOW_MS = 30_000
    ENGINE_TREND_STABLE_THRESHOLD_C = 1.0

    def build(
        self,
        request: StateUpdateRequest,
        *,
        state_version: int,
        now_ms: int,
        engine_temperature_samples: Sequence[tuple[int, float]] = (),
    ) -> ContextSnapshot:
        state = request.state
        support = request.driverSupportSignals
        state_value = self._timestamped(
            "vehicle.state",
            value=True,
            source=request.source,
            timestamp_ms=state.updatedAtMs,
            now_ms=now_ms,
            freshness_ms=self.STATE_FRESHNESS_MS,
        )
        wearable_value = self._timestamped(
            "driver.wearable",
            value=support.wearableHeartRateBpm,
            source=request.source,
            timestamp_ms=support.wearableLastUpdateMs,
            now_ms=now_ms,
            freshness_ms=self.WEARABLE_FRESHNESS_MS,
        )
        engine_trend = derive_engine_temperature_trend(
            engine_temperature_samples,
            min_window_ms=self.ENGINE_TREND_MIN_WINDOW_MS,
            stable_threshold_c=self.ENGINE_TREND_STABLE_THRESHOLD_C,
        )
        # Derived fact only -- see EngineTemperatureTrend's docstring. Uses the exact same
        # FRESH/UNAVAILABLE ContextValue pattern as every other optional field above (e.g.
        # driver.wearable): "no trend" is represented as UNAVAILABLE, not omitted, so it
        # participates in `missing_context` identically to any other absent optional value.
        # SafetyRiskEngine never reads this dict, so this can never change risk/severity.
        trend_source = "derived"
        if engine_trend is None:
            trend_direction_value = ContextValue(
                "vehicle.engine_temperature_trend_direction", None, trend_source, None, "UNAVAILABLE"
            )
            trend_delta_value = ContextValue(
                "vehicle.engine_temperature_trend_delta_c", None, trend_source, None, "UNAVAILABLE"
            )
            trend_window_value = ContextValue(
                "vehicle.engine_temperature_trend_window_seconds", None, trend_source, None, "UNAVAILABLE"
            )
        else:
            trend_direction_value = ContextValue(
                "vehicle.engine_temperature_trend_direction",
                engine_trend.direction,
                trend_source,
                0,
                "FRESH",
            )
            trend_delta_value = ContextValue(
                "vehicle.engine_temperature_trend_delta_c", engine_trend.delta_c, trend_source, 0, "FRESH"
            )
            trend_window_value = ContextValue(
                "vehicle.engine_temperature_trend_window_seconds",
                engine_trend.window_seconds,
                trend_source,
                0,
                "FRESH",
            )
        values = {
            "vehicle.state": state_value,
            "vehicle.speed_kmh": self._from_state(
                "vehicle.speed_kmh", state.speedKmh, request.source, state_value
            ),
            "vehicle.engine_temperature_c": self._from_state(
                "vehicle.engine_temperature_c",
                state.engineTemperatureC,
                request.source,
                state_value,
            ),
            "vehicle.cabin_temperature_c": self._from_state(
                "vehicle.cabin_temperature_c", state.cabinTemperatureC, request.source, state_value
            ),
            "hvac.target_temperature_c": self._from_state(
                "hvac.target_temperature_c",
                state.hvacTargetTemperatureC,
                request.source,
                state_value,
            ),
            "vehicle.energy_percent": self._from_state(
                "vehicle.energy_percent", state.energyPercent, request.source, state_value
            ),
            "trip.continuous_driving_minutes": self._from_state(
                "trip.continuous_driving_minutes",
                state.continuousDrivingMinutes,
                request.source,
                state_value,
            ),
            "driver.steering_last_interaction_seconds": self._from_state(
                "driver.steering_last_interaction_seconds",
                state.steeringLastInteractionSeconds,
                request.source,
                state_value,
            ),
            "driver.seat_occupied": self._from_state(
                "driver.seat_occupied",
                state.driverSeatOccupied,
                request.source,
                state_value,
            ),
            "vehicle.crash_detected": self._from_state(
                "vehicle.crash_detected", state.crashDetected, request.source, state_value
            ),
            "passenger.response": self._from_state(
                "passenger.response", state.passengerResponse, request.source, state_value
            ),
            "vehicle.active_dtcs": self._from_state(
                "vehicle.active_dtcs", state.activeDtcs, request.source, state_value
            ),
            "vehicle.location_available": self._from_state(
                "vehicle.location_available",
                state.location is not None,
                request.source,
                state_value,
            ),
            "driver.user_reported_fatigue": self._from_state(
                "driver.user_reported_fatigue",
                support.userReportedFatigue,
                request.source,
                state_value,
            ),
            "driver.wearable": wearable_value,
            "vehicle.engine_temperature_trend_direction": trend_direction_value,
            "vehicle.engine_temperature_trend_delta_c": trend_delta_value,
            "vehicle.engine_temperature_trend_window_seconds": trend_window_value,
        }
        missing = tuple(name for name, value in values.items() if value.status == "UNAVAILABLE")
        return ContextSnapshot(
            state=state,
            driver_support=support,
            source=request.source,
            state_version=state_version,
            values=values,
            missing_context=missing,
        )

    @staticmethod
    def to_context_pack(snapshot: ContextSnapshot) -> ContextPack:
        return ContextPack(
            state_version=snapshot.state_version,
            values=tuple(snapshot.values.values()),
            missing_context=snapshot.missing_context,
            constraints=(
                "Use only supplied structured values.",
                "Do not infer missing safety signals.",
                "Do not choose emergency escalation; deterministic policy owns it.",
                "Do not receive raw video, audio, CAN, or sensor streams.",
            ),
        )

    def _timestamped(
        self,
        name: str,
        *,
        value: object | None,
        source: str,
        timestamp_ms: int | None,
        now_ms: int,
        freshness_ms: int,
    ) -> ContextValue:
        if timestamp_ms is None or value is None:
            return ContextValue(name, None, source, None, "UNAVAILABLE")
        if timestamp_ms > now_ms + self.FUTURE_TOLERANCE_MS:
            return ContextValue(name, value, source, None, "UNAVAILABLE")
        age_ms = max(0, now_ms - timestamp_ms)
        status: FreshnessStatus = "FRESH" if age_ms <= freshness_ms else "STALE"
        return ContextValue(name, value, source, age_ms, status)

    @staticmethod
    def _from_state(
        name: str,
        value: object | None,
        source: str,
        state_value: ContextValue,
    ) -> ContextValue:
        if value is None:
            return ContextValue(name, None, source, state_value.age_ms, "UNAVAILABLE")
        return ContextValue(name, value, source, state_value.age_ms, state_value.status)
