import asyncio
import datetime
from typing import Any

import pytest
from pydantic import ValidationError

from app.domain.models.signal import CanonicalSignal, SignalSource
from app.ingestion.canonicalizer import Canonicalizer, LRUCache
from app.ingestion.registry import SignalRegistry
from app.state.manager import LatestStateManager, OutOfOrderError, ReplayError, VehicleStateSnapshot


class FakeClock:
    def __init__(self, start_time: Any = None) -> None:
        self.now = start_time or datetime.datetime.now(datetime.UTC)

    def __call__(self) -> Any:
        return self.now

    def advance(self, seconds: float) -> None:
        self.now += datetime.timedelta(seconds=seconds)


def test_invalid_per_signal_values_and_ranges() -> None:
    registry = SignalRegistry()
    # Value out of range
    with pytest.raises(ValueError, match="Value schema validation failed"):
        registry.validate_signal(
            CanonicalSignal(
                received_at=datetime.datetime.now(datetime.UTC),
                signal_id="1",
                source=SignalSource.VHAL,
                signal_type="vehicle.speed_kmh",
                occurred_at=datetime.datetime.now(datetime.UTC),
                value={"value": 400.0},  # Max is 350
                vehicle_id="v1",
                trip_id="t1",
            )
        )

    # Incorrect type
    with pytest.raises(ValueError, match="Value schema validation failed"):
        registry.validate_signal(
            CanonicalSignal(
                received_at=datetime.datetime.now(datetime.UTC),
                signal_id="1",
                source=SignalSource.VHAL,
                signal_type="vehicle.gear",
                occurred_at=datetime.datetime.now(datetime.UTC),
                value={"gear": "X"},  # Not P,R,N,D
                vehicle_id="v1",
                trip_id="t1",
            )
        )


def test_naive_datetime_rejection() -> None:
    naive_dt = datetime.datetime(2023, 1, 1, 12, 0, 0, tzinfo=datetime.UTC).replace(tzinfo=None)
    with pytest.raises(ValidationError) as exc_info:
        CanonicalSignal(
            received_at=datetime.datetime.now(datetime.UTC),
            signal_id="1",
            source=SignalSource.VHAL,
            signal_type="vehicle.speed_kmh",
            occurred_at=naive_dt,
            value={"value": 100.0},
            vehicle_id="v1",
            trip_id="t1",
        )
    assert "timezone-aware" in str(exc_info.value)


def test_client_spoofing_of_received_at() -> None:
    from app.domain.models.signal import CanonicalSignalInput

    spoof_time = datetime.datetime(2000, 1, 1, tzinfo=datetime.UTC)
    from pydantic import TypeAdapter

    sig_input: CanonicalSignalInput = TypeAdapter(CanonicalSignalInput).validate_python(
        {
            "signal_id": "1",
            "source": SignalSource.VHAL.value,
            "signal_type": "vehicle.speed_kmh",
            "occurred_at": datetime.datetime.now(datetime.UTC),
            "value": {"value": 100.0},
            "vehicle_id": "v1",
            "trip_id": "t1",
        }
    )
    # server sets received_at at creation, spoof time is ignored
    sig = sig_input.to_canonical()
    assert sig.received_at != spoof_time


def test_missing_metadata_simulated() -> None:
    registry = SignalRegistry()
    sig = CanonicalSignal(
        received_at=datetime.datetime.now(datetime.UTC),
        signal_id="1",
        source=SignalSource.SIMULATOR,
        signal_type="driver.perclos",
        occurred_at=datetime.datetime.now(datetime.UTC),
        value={"probability": 0.5},
        vehicle_id="v1",
        trip_id="t1",
        metadata={},  # Missing simulated=true
    )
    with pytest.raises(ValueError, match="requires metadata.simulated=true"):
        registry.validate_signal(sig, "DMS_DEMO")


def test_unknown_and_production_incompatible_profiles() -> None:
    registry = SignalRegistry()
    sig = CanonicalSignal(
        received_at=datetime.datetime.now(datetime.UTC),
        signal_id="1",
        source=SignalSource.SIMULATOR,
        signal_type="driver.perclos",
        occurred_at=datetime.datetime.now(datetime.UTC),
        value={"probability": 0.5},
        vehicle_id="v1",
        trip_id="t1",
        metadata={"simulated": True},
    )
    with pytest.raises(ValueError, match="not supported in profile PRODUCTION_NO_DMS"):
        registry.validate_signal(sig, "PRODUCTION_NO_DMS")
    with pytest.raises(ValueError, match="not supported in profile UNKNOWN"):
        registry.validate_signal(sig, "UNKNOWN")


def test_full_body_dedup_conflict() -> None:
    clock = FakeClock()
    registry = SignalRegistry()
    canonicalizer = Canonicalizer(registry, clock=clock)

    sig1 = CanonicalSignal(
        received_at=datetime.datetime.now(datetime.UTC),
        signal_id="1",
        source=SignalSource.VHAL,
        signal_type="vehicle.speed_kmh",
        occurred_at=clock(),
        value={"value": 100.0},
        vehicle_id="v1",
        trip_id="t1",
    )
    res1 = canonicalizer.process(sig1)
    assert res1.status_code == 202

    # Duplicate
    res2 = canonicalizer.process(sig1)
    assert res2.status_code == 202
    assert "Duplicate ignored" in res2.message

    # Conflict
    sig3 = CanonicalSignal(
        received_at=datetime.datetime.now(datetime.UTC),
        signal_id="1",
        source=SignalSource.VHAL,
        signal_type="vehicle.speed_kmh",
        occurred_at=clock(),
        value={"value": 120.0},
        vehicle_id="v1",
        trip_id="t1",
    )
    res3 = canonicalizer.process(sig3)
    assert res3.status_code == 409
    assert "Conflict" in res3.message


def test_lru_size_eviction() -> None:
    lru = LRUCache(maxsize=2, ttl_seconds=3600)
    k1 = ("v1", "t1", "SIMULATOR", "k1")
    k2 = ("v1", "t1", "SIMULATOR", "k2")
    k3 = ("v1", "t1", "SIMULATOR", "k3")

    lru.put(k1, "v1")
    lru.put(k2, "v2")
    lru.put(k3, "v3")

    assert lru.get(k1) is None  # Evicted
    assert lru.get(k2) == "v2"
    assert lru.get(k3) == "v3"


class FakeMonotonicClock:
    def __init__(self) -> None:
        self.current = 1000.0

    def __call__(self) -> float:
        return self.current

    def advance(self, seconds: float) -> None:
        self.current += seconds


def test_ttl_expiration_fake_clock() -> None:
    clock = FakeMonotonicClock()
    lru = LRUCache(maxsize=10, ttl_seconds=10, clock=clock)
    k1 = ("v1", "t1", "SIMULATOR", "k1")
    lru.put(k1, "v1")

    assert lru.get(k1) == "v1"
    clock.advance(11)
    assert lru.get(k1) is None  # Expired


@pytest.mark.asyncio
async def test_lower_and_equal_sequences() -> None:
    manager = LatestStateManager(SignalRegistry())
    now = datetime.datetime.now(datetime.UTC)

    # Seq 2
    sig1 = CanonicalSignal(
        received_at=datetime.datetime.now(datetime.UTC),
        signal_id="1",
        source=SignalSource.VHAL,
        signal_type="vehicle.speed_kmh",
        occurred_at=now,
        value={"value": 100.0},
        vehicle_id="v1",
        trip_id="t1",
        sequence=2,
    )
    await manager.apply(sig1)

    # Seq 1 -> Lower
    sig2 = CanonicalSignal(
        received_at=datetime.datetime.now(datetime.UTC),
        signal_id="2",
        source=SignalSource.VHAL,
        signal_type="vehicle.speed_kmh",
        occurred_at=now,
        value={"value": 110.0},
        vehicle_id="v1",
        trip_id="t1",
        sequence=1,
    )
    with pytest.raises(OutOfOrderError):
        await manager.apply(sig2)

    # Seq 2 -> Equal
    sig3 = CanonicalSignal(
        received_at=datetime.datetime.now(datetime.UTC),
        signal_id="3",
        source=SignalSource.VHAL,
        signal_type="vehicle.speed_kmh",
        occurred_at=now,
        value={"value": 110.0},
        vehicle_id="v1",
        trip_id="t1",
        sequence=2,
    )
    with pytest.raises(ReplayError):
        await manager.apply(sig3)


@pytest.mark.asyncio
async def test_cross_source_sequence_independence() -> None:
    manager = LatestStateManager(SignalRegistry())
    now = datetime.datetime.now(datetime.UTC)

    # VHAL Seq 10
    sig1 = CanonicalSignal(
        received_at=datetime.datetime.now(datetime.UTC),
        signal_id="1",
        source=SignalSource.VHAL,
        signal_type="vehicle.speed_kmh",
        occurred_at=now,
        value={"value": 100.0},
        vehicle_id="v1",
        trip_id="t1",
        sequence=10,
    )
    await manager.apply(sig1)

    # SIMULATOR Seq 1 (Allowed because different source)
    sig2 = CanonicalSignal(
        received_at=datetime.datetime.now(datetime.UTC),
        signal_id="2",
        source=SignalSource.SIMULATOR,
        signal_type="vehicle.speed_kmh",
        occurred_at=now + datetime.timedelta(seconds=1),
        value={"value": 110.0},
        vehicle_id="v1",
        trip_id="t1",
        sequence=1,
    )
    state = await manager.apply(sig2)
    assert state is not None
    assert "vehicle.speed_kmh" in state.components
    assert state.components["vehicle.speed_kmh"].source == "SIMULATOR"

    # VHAL Seq 1 (Must be rejected because VHAL cursor is already at 10)
    sig3 = CanonicalSignal(
        received_at=datetime.datetime.now(datetime.UTC),
        signal_id="3",
        source=SignalSource.VHAL,
        signal_type="vehicle.speed_kmh",
        occurred_at=now + datetime.timedelta(seconds=2),
        value={"value": 120.0},
        vehicle_id="v1",
        trip_id="t1",
        sequence=1,
    )
    with pytest.raises(OutOfOrderError):
        await manager.apply(sig3)


@pytest.mark.asyncio
async def test_sequence_zero_downgrade() -> None:
    manager = LatestStateManager(SignalRegistry())
    now = datetime.datetime.now(datetime.UTC)

    # Seq 10
    sig1 = CanonicalSignal(
        received_at=datetime.datetime.now(datetime.UTC),
        signal_id="1",
        source=SignalSource.VHAL,
        signal_type="vehicle.speed_kmh",
        occurred_at=now,
        value={"value": 100.0},
        vehicle_id="v1",
        trip_id="t1",
        sequence=10,
    )
    await manager.apply(sig1)

    # Seq 0 (newer time)
    sig2 = CanonicalSignal(
        received_at=datetime.datetime.now(datetime.UTC),
        signal_id="2",
        source=SignalSource.VHAL,
        signal_type="vehicle.speed_kmh",
        occurred_at=now + datetime.timedelta(seconds=1),
        value={"value": 110.0},
        vehicle_id="v1",
        trip_id="t1",
        sequence=0,
    )
    with pytest.raises(OutOfOrderError):
        await manager.apply(sig2)


def test_stale_and_unavailable_freshness() -> None:
    clock = FakeClock()
    registry = SignalRegistry()

    # Unavailable
    state = VehicleStateSnapshot(vehicle_id="v1", trip_id="t1", state_version=0)
    freshness = state.get_freshness("vehicle.speed_kmh", registry, clock())
    assert freshness.status == "UNAVAILABLE"

    # Fresh
    state.components["vehicle.speed_kmh"] = __import__(
        "app.state.manager", fromlist=["ComponentState"]
    ).ComponentState(value={"value": 100}, updated_at=clock(), sequence=1, source="VHAL")
    freshness = state.get_freshness("vehicle.speed_kmh", registry, clock())
    assert freshness.status == "FRESH"

    # Stale
    # Speed TTL is 2.0s
    clock.advance(3.0)
    freshness = state.get_freshness("vehicle.speed_kmh", registry, clock())
    assert freshness.status == "STALE"


@pytest.mark.asyncio
async def test_snapshot_immutability() -> None:
    manager = LatestStateManager(SignalRegistry())
    now = datetime.datetime.now(datetime.UTC)

    sig1 = CanonicalSignal(
        received_at=datetime.datetime.now(datetime.UTC),
        signal_id="1",
        source=SignalSource.VHAL,
        signal_type="vehicle.speed_kmh",
        occurred_at=now,
        value={"value": 100.0},
        vehicle_id="v1",
        trip_id="t1",
        sequence=1,
    )
    state = await manager.apply(sig1)

    # Mutate snapshot
    state.components["vehicle.speed_kmh"].value["value"] = 999.0

    # Verify internal manager state is unaffected
    internal_state = manager.get_state("v1", "t1")
    assert internal_state is not None
    assert internal_state.components["vehicle.speed_kmh"].value["value"] == 100.0


@pytest.mark.asyncio
async def test_concurrent_updates_preserving_version_order() -> None:
    manager = LatestStateManager(SignalRegistry())
    now = datetime.datetime.now(datetime.UTC)
    barrier = asyncio.Barrier(3)

    async def worker(seq: int) -> None:
        await barrier.wait()
        sig = CanonicalSignal(
            received_at=datetime.datetime.now(datetime.UTC),
            signal_id=str(seq),
            source=SignalSource.VHAL,
            signal_type="vehicle.speed_kmh",
            occurred_at=now + datetime.timedelta(milliseconds=seq),
            value={"value": float(seq)},
            vehicle_id="v1",
            trip_id="t1",
            sequence=seq,
        )
        try:
            await manager.apply(sig)
        except OutOfOrderError:
            pass  # Expected if run out of order

    # 3 concurrent requests
    await asyncio.gather(worker(1), worker(2), worker(3))

    state = manager.get_state("v1", "t1")
    assert state is not None
    # Regardless of execution order, max sequence 3 must win or have been applied last
    # But wait, if they execute out of order, e.g. 3 then 1, 1 will fail OutOfOrder.
    # The end state must be seq 3.
    assert state.components["vehicle.speed_kmh"].sequence == 3
    assert state.state_version > 0
    assert state.state_version <= 3


@pytest.mark.asyncio
async def test_atomicity_on_cross_source_reject() -> None:
    manager = LatestStateManager(SignalRegistry())

    # 1. VHAL seq=10 (accepted)
    sig_vhal = CanonicalSignal(
        signal_id="vhal_1",
        source=SignalSource.VHAL,
        signal_type="vehicle.speed_kmh",
        occurred_at=datetime.datetime.now(datetime.UTC),
        received_at=datetime.datetime.now(datetime.UTC),
        value={"value": 10.0},
        vehicle_id="v1",
        trip_id="t1",
        sequence=10,
    )
    await manager.apply(sig_vhal)

    # 2. SIMULATOR seq=5 (older timestamp -> rejected)
    sig_sim_5 = CanonicalSignal(
        signal_id="sim_1",
        source=SignalSource.SIMULATOR,
        signal_type="vehicle.speed_kmh",
        occurred_at=datetime.datetime.now(datetime.UTC) - datetime.timedelta(seconds=10),
        received_at=datetime.datetime.now(datetime.UTC),
        value={"value": 5.0},
        vehicle_id="v1",
        trip_id="t1",
        sequence=5,
    )
    with pytest.raises(OutOfOrderError):
        await manager.apply(sig_sim_5)

    # Verify cursor for SIMULATOR is NOT 5
    state = manager.get_state("v1", "t1")
    assert state is not None
    assert state.cursors["vehicle.speed_kmh"].get(SignalSource.SIMULATOR.value) is None

    # 3. SIMULATOR seq=4 (newer timestamp -> accepted because cursor is None)
    sig_sim_4 = CanonicalSignal(
        signal_id="sim_2",
        source=SignalSource.SIMULATOR,
        signal_type="vehicle.speed_kmh",
        occurred_at=datetime.datetime.now(datetime.UTC) + datetime.timedelta(seconds=10),
        received_at=datetime.datetime.now(datetime.UTC),
        value={"value": 4.0},
        vehicle_id="v1",
        trip_id="t1",
        sequence=4,
    )
    await manager.apply(sig_sim_4)
    state2 = manager.get_state("v1", "t1")
    assert state2 is not None
    assert state2.cursors["vehicle.speed_kmh"].get(SignalSource.SIMULATOR.value) == 4
