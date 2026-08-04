import asyncio
import datetime
from typing import Any

import pytest
from pydantic import TypeAdapter

from app.domain.models.signal import CanonicalSignal, CanonicalSignalInput, SignalSource
from app.ingestion.registry import SignalRegistry
from app.state.rolling_window import RollingWindow, RollingWindowManager


class FakeClock:
    def __init__(self) -> None:
        self.current = datetime.datetime(2026, 1, 1, 12, 0, 0, tzinfo=datetime.UTC)

    def __call__(self) -> datetime.datetime:
        return self.current

    def advance(self, seconds: float) -> None:
        self.current += datetime.timedelta(seconds=seconds)


def create_signal(
    occurred_at: datetime.datetime,
    signal_type: str = "vehicle.speed_kmh",
    vehicle_id: str = "v1",
    trip_id: str = "t1",
    value: Any = None,
    sequence: int = 1,
) -> CanonicalSignal:
    if value is None:
        if signal_type == "vehicle.crash":
            value = {"severity": "CRITICAL"}
        elif signal_type in (
            "hvac.ac_status",
            "vehicle.parking_brake",
            "vehicle.door_open",
            "vehicle.window_open",
            "vehicle.brake_pedal",
            "vehicle.accelerator_pedal",
            "passenger.occupancy",
            "passenger.motion",
            "passenger.posture",
            "passenger.head_position",
        ):
            value = {"status": True}
        elif signal_type == "vehicle.gear":
            value = {"gear": "D"}
        else:
            value = {"value": 100.0}

    sig_input: CanonicalSignalInput = TypeAdapter(CanonicalSignalInput).validate_python(
        {
            "signal_id": f"sig-{sequence}",
            "source": SignalSource.VHAL.value,
            "signal_type": signal_type,
            "occurred_at": occurred_at,
            "value": value,
            "vehicle_id": vehicle_id,
            "trip_id": trip_id,
        }
    )
    sig = sig_input.to_canonical()
    sig.sequence = sequence
    return sig


def test_rolling_window_chronological_order_late_arrival() -> None:
    clock = FakeClock()
    rw = RollingWindow(max_length=10, ttl_seconds=60.0)

    clock.advance(100)
    now = clock()
    sig_new = create_signal(now, sequence=100)
    rw.append(sig_new, now)

    sig_late = create_signal(now - datetime.timedelta(seconds=0.5), sequence=99)
    rw.append(sig_late, now)

    win = rw.get_window(10.0, now)
    assert len(win) == 2
    assert win[0].sequence == 99
    assert win[1].sequence == 100


def test_rolling_window_stale_late_event_purged() -> None:
    clock = FakeClock()
    rw = RollingWindow(max_length=10, ttl_seconds=10.0)

    clock.advance(100)
    now = clock()
    sig_new = create_signal(now, sequence=100)
    rw.append(sig_new, now)

    sig_late = create_signal(now - datetime.timedelta(seconds=11), sequence=89)
    rw.append(sig_late, now)

    win = rw.get_window(20.0, now)
    assert len(win) == 1
    assert win[0].sequence == 100


def test_rolling_window_maxlen_retains_newest_event_time() -> None:
    clock = FakeClock()
    rw = RollingWindow(max_length=3, ttl_seconds=60.0)

    t0 = clock()
    sig1 = create_signal(t0 + datetime.timedelta(seconds=9.8), sequence=1)
    sig2 = create_signal(t0 + datetime.timedelta(seconds=9.9), sequence=2)
    sig3 = create_signal(t0 + datetime.timedelta(seconds=10.0), sequence=3)

    sig_late = create_signal(t0 + datetime.timedelta(seconds=9.0), sequence=0)

    rw.append(sig1, clock())
    rw.append(sig2, clock())
    rw.append(sig3, clock())
    rw.append(sig_late, clock())

    assert len(rw._items) == 3
    assert rw._items[0].sequence == 1
    assert rw._items[1].sequence == 2
    assert rw._items[2].sequence == 3


def test_rolling_window_deterministic_equal_timestamp_order() -> None:
    clock = FakeClock()
    rw = RollingWindow(max_length=10, ttl_seconds=60.0)
    now = clock()

    sig2 = create_signal(now, sequence=2)
    sig1 = create_signal(now, sequence=1)

    rw.append(sig2, now)
    rw.append(sig1, now)

    win = rw.get_window(10.0, now)
    assert len(win) == 2
    assert win[0].sequence == 1
    assert win[1].sequence == 2


@pytest.mark.asyncio
async def test_manager_missing_get_window_leaves_entries_zero() -> None:
    registry = SignalRegistry()
    manager = RollingWindowManager(registry=registry)

    for i in range(1000):
        res = await manager.get_window(f"v_{i}", "t1", "vehicle.speed_kmh", 10.0)
        assert res == []

    assert len(manager.entries) == 0


@pytest.mark.asyncio
async def test_invalid_duration_rejected_before_missing_entry_lookup() -> None:
    manager = RollingWindowManager(registry=SignalRegistry())

    with pytest.raises(ValueError, match="duration_seconds must be positive"):
        await manager.get_window("missing", "missing", "vehicle.speed_kmh", 0)
    with pytest.raises(ValueError, match="duration_seconds must be positive"):
        await manager.get_window("missing", "missing", "vehicle.speed_kmh", -1)
    assert manager.entries == {}


@pytest.mark.asyncio
async def test_manager_unconfigured_signals_skipped_normally() -> None:
    registry = SignalRegistry()
    manager = RollingWindowManager(registry=registry)

    now = datetime.datetime.now(datetime.UTC)
    sig_crash = create_signal(now, signal_type="vehicle.crash")
    sig_hvac = create_signal(now, signal_type="hvac.ac_status")
    sig_gear = create_signal(now, signal_type="vehicle.gear")

    assert await manager.append_if_configured(sig_crash) is False
    assert await manager.append_if_configured(sig_hvac) is False
    assert await manager.append_if_configured(sig_gear) is False

    assert len(manager.entries) == 0


@pytest.mark.asyncio
async def test_manager_expired_empty_windows_reclaimed() -> None:
    clock = FakeClock()
    registry = SignalRegistry()
    manager = RollingWindowManager(registry=registry, clock=clock)

    sig = create_signal(clock(), signal_type="vehicle.speed_kmh", sequence=1)
    res = await manager.append_if_configured(sig)
    assert res is True
    assert len(manager.entries) == 1

    # Advance clock beyond speed_kmh window_ttl_seconds (120s)
    clock.advance(130)

    await manager.prune_all()
    assert len(manager.entries) == 0


@pytest.mark.asyncio
async def test_manager_trip_a_blocked_trip_b_parallel() -> None:
    clock = FakeClock()
    registry = SignalRegistry()
    manager = RollingWindowManager(registry=registry, clock=clock)

    sig_a = create_signal(clock(), vehicle_id="v1", trip_id="trip_A", sequence=1)
    sig_b = create_signal(clock(), vehicle_id="v1", trip_id="trip_B", sequence=2)

    await manager.append(sig_a)

    key_a = ("v1", "trip_A", "vehicle.speed_kmh")
    entry_a = manager.entries[key_a]

    # Acquire trip A entry lock manually to block trip A operations
    await entry_a.lock.acquire()

    try:
        # Append for trip B must complete immediately without waiting for trip A lock
        append_b_task = asyncio.create_task(manager.append(sig_b))
        await asyncio.wait_for(append_b_task, timeout=0.5)

        win_b = await manager.get_window("v1", "trip_B", "vehicle.speed_kmh", 10.0)
        assert len(win_b) == 1
        assert win_b[0].sequence == 2
    finally:
        entry_a.lock.release()


@pytest.mark.asyncio
async def test_manager_cleanup_cannot_remove_entry_with_active_users() -> None:
    clock = FakeClock()
    registry = SignalRegistry()
    manager = RollingWindowManager(registry=registry, clock=clock)

    sig = create_signal(clock(), vehicle_id="v1", trip_id="t1", sequence=1)
    await manager.append(sig)

    key = ("v1", "t1", "vehicle.speed_kmh")
    entry = manager.entries[key]

    # Advance clock beyond TTL
    clock.advance(130)

    # Manually hold entry lock and increment active_users to simulate an active operation
    async with manager._index_lock:
        entry.active_users += 1

    await entry.lock.acquire()

    try:
        # Prune_all executes while user is active
        asyncio.create_task(manager.prune_all())
        await asyncio.sleep(0.05)

        # Entry must NOT be removed from manager.entries while active_users > 0
        assert key in manager.entries
    finally:
        entry.lock.release()
        async with manager._index_lock:
            entry.active_users -= 1

    # Cleanup after active user finishes
    await manager.prune_all()
    assert key not in manager.entries


@pytest.mark.asyncio
async def test_input_signal_mutation_protected() -> None:
    clock = FakeClock()
    registry = SignalRegistry()
    manager = RollingWindowManager(registry=registry, clock=clock)

    sig = create_signal(
        clock(), signal_type="vehicle.speed_kmh", value={"value": 100.0}, sequence=1
    )
    await manager.append(sig)

    sig.value = {"value": 999.0}
    sig.occurred_at = clock() + datetime.timedelta(days=1)

    win = await manager.get_window("v1", "t1", "vehicle.speed_kmh", 10.0)
    assert len(win) == 1
    assert win[0].value == {"value": 100.0}
    assert win[0].occurred_at == clock()
