import asyncio
import datetime

import pytest

from app.domain.models.signal import CanonicalSignal, SignalQuality, SignalSource
from app.ingestion.registry import SignalRegistry
from app.state.manager import LatestStateManager, OutOfOrderError, ReplayError


def create_signal(signal_id: str, age_seconds: int = 0, sequence: int = 0) -> CanonicalSignal:
    now = datetime.datetime.now(datetime.UTC)
    return CanonicalSignal(
        received_at=datetime.datetime.now(datetime.UTC),
        signal_id=signal_id,
        source=SignalSource.SIMULATOR,
        signal_type="vehicle.speed_kmh",
        occurred_at=now - datetime.timedelta(seconds=age_seconds),
        value={"value": 62},
        quality=SignalQuality.VALID,
        vehicle_id="veh_01",
        trip_id="trip_01",
        sequence=sequence,
    )


@pytest.mark.asyncio
async def test_state_manager_atomic_updates() -> None:
    manager = LatestStateManager(SignalRegistry())
    sig1 = create_signal("sig_1", sequence=1)

    state = await manager.apply(sig1)
    assert state.state_version == 1
    assert "vehicle.speed_kmh" in state.components
    assert state.components["vehicle.speed_kmh"].sequence == 1

    # Update with new sequence
    sig2 = create_signal("sig_2", sequence=2)
    state2 = await manager.apply(sig2)
    assert state2.state_version == 2
    assert state2.components["vehicle.speed_kmh"].sequence == 2


@pytest.mark.asyncio
async def test_state_manager_out_of_order() -> None:
    manager = LatestStateManager(SignalRegistry())
    sig1 = create_signal("sig_1", sequence=2)
    await manager.apply(sig1)

    # Try to apply older sequence
    sig_old = create_signal("sig_0", sequence=1)
    with pytest.raises(OutOfOrderError):
        await manager.apply(sig_old)


@pytest.mark.asyncio
async def test_state_manager_replay() -> None:
    manager = LatestStateManager(SignalRegistry())
    sig1 = create_signal("sig_1", sequence=1)
    await manager.apply(sig1)

    # Replay same sequence and same occurred_at
    with pytest.raises(ReplayError):
        await manager.apply(sig1)


@pytest.mark.asyncio
async def test_state_manager_freshness() -> None:
    current_time = datetime.datetime(2023, 1, 1, 12, 0, 0, tzinfo=datetime.UTC)

    def fake_clock() -> datetime.datetime:
        return current_time

    manager = LatestStateManager(SignalRegistry(), clock=fake_clock)

    sig1 = CanonicalSignal(
        received_at=current_time,
        signal_id="sig_1",
        source=SignalSource.SIMULATOR,
        signal_type="vehicle.speed_kmh",
        occurred_at=current_time,
        value={"value": 62},
        quality=SignalQuality.VALID,
        vehicle_id="veh_01",
        trip_id="trip_01",
        sequence=1,
    )
    state = await manager.apply(sig1)

    current_time = current_time + datetime.timedelta(milliseconds=1999)
    freshness = state.get_freshness("vehicle.speed_kmh", manager.registry, fake_clock())
    assert freshness.age_ms == 1999
    assert freshness.status == "FRESH"

    current_time = current_time + datetime.timedelta(milliseconds=1)
    freshness = state.get_freshness("vehicle.speed_kmh", manager.registry, fake_clock())
    assert freshness.age_ms == 2000
    assert freshness.status == "FRESH"

    current_time = current_time + datetime.timedelta(milliseconds=1)
    freshness = state.get_freshness("vehicle.speed_kmh", manager.registry, fake_clock())
    assert freshness.age_ms == 2001
    assert freshness.status == "STALE"


@pytest.mark.asyncio
async def test_state_manager_concurrent_updates() -> None:
    manager = LatestStateManager(SignalRegistry())

    async def apply_signal(seq: int) -> None:
        sig = create_signal(f"sig_{seq}", sequence=seq)
        try:
            await manager.apply(sig)
        except (OutOfOrderError, ReplayError):
            pass

    await asyncio.gather(apply_signal(1), apply_signal(2), apply_signal(3), apply_signal(4))

    state = manager.get_state("veh_01", "trip_01")
    assert state is not None
    assert state.state_version == 4
    assert state.components["vehicle.speed_kmh"].sequence == 4


@pytest.mark.asyncio
async def test_state_manager_colon_partition_collision_independence() -> None:
    manager = LatestStateManager(SignalRegistry())
    now = datetime.datetime.now(datetime.UTC)

    # State A: ("veh:a", "trip")
    sig_a = CanonicalSignal(
        received_at=now,
        signal_id="sig_a",
        source=SignalSource.VHAL,
        signal_type="vehicle.speed_kmh",
        occurred_at=now,
        value={"value": 60.0},
        quality=SignalQuality.VALID,
        vehicle_id="veh:a",
        trip_id="trip",
        sequence=10,
    )

    # State B: ("veh", "a:trip")
    sig_b = CanonicalSignal(
        received_at=now,
        signal_id="sig_b",
        source=SignalSource.VHAL,
        signal_type="vehicle.speed_kmh",
        occurred_at=now,
        value={"value": 120.0},
        quality=SignalQuality.VALID,
        vehicle_id="veh",
        trip_id="a:trip",
        sequence=5,
    )

    state_a = await manager.apply(sig_a)
    state_b = await manager.apply(sig_b)

    assert state_a.vehicle_id == "veh:a"
    assert state_a.trip_id == "trip"
    assert state_a.state_version == 1
    assert state_a.components["vehicle.speed_kmh"].value == {"value": 60.0}

    assert state_b.vehicle_id == "veh"
    assert state_b.trip_id == "a:trip"
    assert state_b.state_version == 1
    assert state_b.components["vehicle.speed_kmh"].value == {"value": 120.0}

    lookup_a = manager.get_state("veh:a", "trip")
    lookup_b = manager.get_state("veh", "a:trip")

    assert lookup_a is not None and lookup_b is not None
    assert lookup_a.state_version == 1
    assert lookup_b.state_version == 1
    assert lookup_a.components["vehicle.speed_kmh"].value == {"value": 60.0}
    assert lookup_b.components["vehicle.speed_kmh"].value == {"value": 120.0}
