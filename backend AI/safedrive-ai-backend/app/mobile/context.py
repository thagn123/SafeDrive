"""Build compact, fresh context for SafeDrive reasoning.

The mobile API receives a bounded, structured cockpit snapshot. This module
keeps it structured and attaches freshness/missing-data metadata before any
assistant or safety logic uses it. It deliberately has no video, audio, CAN,
or free-form sensor payload fields.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Literal

from app.api.schemas.mobile import DriverSupportSignals, StateUpdateRequest, VehicleState

FreshnessStatus = Literal["FRESH", "STALE", "UNAVAILABLE"]


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


class MobileContextBuilder:
    """Creates compact state snapshots with bounded freshness semantics."""

    STATE_FRESHNESS_MS = 10_000
    WEARABLE_FRESHNESS_MS = 30_000
    FUTURE_TOLERANCE_MS = 5_000

    def build(
        self,
        request: StateUpdateRequest,
        *,
        state_version: int,
        now_ms: int,
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
