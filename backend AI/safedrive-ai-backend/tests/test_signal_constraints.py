import datetime
from collections.abc import Mapping

import pytest
from pydantic import ValidationError

from app.domain.models.signal import SpeedSignalInput
from app.ingestion.registry import SignalRegistry
from app.state.manager import LatestStateManager


class MisnamedUtc(datetime.tzinfo):
    def utcoffset(self, dt: datetime.datetime | None) -> datetime.timedelta:
        return datetime.timedelta(hours=1)

    def dst(self, dt: datetime.datetime | None) -> datetime.timedelta:
        return datetime.timedelta(0)

    def tzname(self, dt: datetime.datetime | None) -> str:
        return "UTC"


def payload(
    timestamp: datetime.datetime,
    metadata: Mapping[str, object],
) -> dict[str, object]:
    return {
        "signal_id": "constraint-signal",
        "source": "VHAL",
        "signal_type": "vehicle.speed_kmh",
        "occurred_at": timestamp,
        "value": {"value": 30.0},
        "vehicle_id": "constraint-vehicle",
        "trip_id": "constraint-trip",
        "sequence": 1,
        "metadata": dict(metadata),
    }


def test_utc_offset_is_enforced_and_normalized() -> None:
    spoofed = datetime.datetime(2026, 7, 29, 12, 0, tzinfo=MisnamedUtc())
    with pytest.raises(ValidationError, match="zero UTC offset"):
        SpeedSignalInput.model_validate(payload(spoofed, {}))

    plus_zero = datetime.datetime.fromisoformat("2026-07-29T12:00:00+00:00")
    signal = SpeedSignalInput.model_validate(payload(plus_zero, {}))
    assert signal.occurred_at.tzinfo is datetime.UTC


def test_metadata_twenty_keys_pass_and_twenty_one_fail() -> None:
    timestamp = datetime.datetime.now(datetime.UTC)
    twenty = {f"k{index}": index for index in range(20)}
    SpeedSignalInput.model_validate(payload(timestamp, twenty))

    twenty_one = {f"k{index}": index for index in range(21)}
    with pytest.raises(ValidationError):
        SpeedSignalInput.model_validate(payload(timestamp, twenty_one))


@pytest.mark.asyncio
async def test_state_projection_uses_injected_clock_exactly() -> None:
    now = datetime.datetime(2026, 7, 29, 12, 0, tzinfo=datetime.UTC)

    def clock() -> datetime.datetime:
        return now

    manager = LatestStateManager(SignalRegistry(), clock=clock)
    signal = SpeedSignalInput.model_validate(payload(now, {})).to_canonical(now)
    await manager.apply(signal)

    projection = manager.project_state("constraint-vehicle", "constraint-trip")
    assert projection is not None
    assert projection.components["vehicle.speed_kmh"].freshness.age_ms == 0
    assert projection.components["vehicle.speed_kmh"].freshness.status == "FRESH"
    assert (
        manager.component_freshness(
            "constraint-vehicle",
            "constraint-trip",
            "vehicle.crash",
        ).status
        == "UNAVAILABLE"
    )

    now += datetime.timedelta(milliseconds=1999)
    fresh_projection = manager.project_state(
        "constraint-vehicle",
        "constraint-trip",
    )
    assert fresh_projection is not None
    assert fresh_projection.components["vehicle.speed_kmh"].freshness.status == "FRESH"
    now += datetime.timedelta(milliseconds=2)
    stale_projection = manager.project_state(
        "constraint-vehicle",
        "constraint-trip",
    )
    assert stale_projection is not None
    assert stale_projection.components["vehicle.speed_kmh"].freshness.status == "STALE"
