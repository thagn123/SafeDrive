import datetime
from typing import Any

import pytest

from app.domain.models.signal import CanonicalSignal, SignalQuality, SignalSource
from app.ingestion.canonicalizer import Canonicalizer
from app.ingestion.registry import SignalRegistry


@pytest.fixture
def registry() -> SignalRegistry:
    return SignalRegistry("configs/signal_registry.yaml")


@pytest.fixture
def canonicalizer(registry: SignalRegistry) -> Canonicalizer:
    return Canonicalizer(registry)


def create_signal(
    signal_id: str,
    age_seconds: int = 0,
    quality: SignalQuality = SignalQuality.VALID,
    value_dict: dict[str, Any] | None = None,
    vehicle_id: str = "veh_01",
    trip_id: str = "trip_01",
) -> CanonicalSignal:
    if value_dict is None:
        value_dict = {"value": 62}
    now = datetime.datetime.now(datetime.UTC)
    return CanonicalSignal(
        received_at=datetime.datetime.now(datetime.UTC),
        signal_id=signal_id,
        source=SignalSource.SIMULATOR,
        signal_type="vehicle.speed_kmh",
        occurred_at=now - datetime.timedelta(seconds=age_seconds),
        value=value_dict,
        quality=quality,
        vehicle_id=vehicle_id,
        trip_id=trip_id,
    )


def test_canonicalizer_accepts_valid(canonicalizer: Canonicalizer) -> None:
    sig = create_signal("sig_1")
    res = canonicalizer.process(sig)
    assert res.status_code == 202
    assert res.message == "Accepted"
    assert res.signal is not None


def test_canonicalizer_quarantine_invalid_quality(canonicalizer: Canonicalizer) -> None:
    sig = create_signal("sig_2", quality=SignalQuality.INVALID)
    res = canonicalizer.process(sig)
    assert res.status_code == 422
    assert "INVALID quality" in res.message


def test_canonicalizer_quarantine_too_old(canonicalizer: Canonicalizer) -> None:
    sig = create_signal("sig_3", age_seconds=4)
    res = canonicalizer.process(sig)
    assert res.status_code == 422
    assert "too old" in res.message


def test_canonicalizer_quarantine_future(canonicalizer: Canonicalizer) -> None:
    sig = create_signal("sig_4", age_seconds=-35)
    res = canonicalizer.process(sig)
    assert res.status_code == 422
    assert "too far in future" in res.message


def test_canonicalizer_deduplication(canonicalizer: Canonicalizer) -> None:
    sig1 = create_signal("sig_5", value_dict={"value": 100})
    res1 = canonicalizer.process(sig1)
    assert res1.status_code == 202

    # Same id, same hash -> duplicate ignored
    res2 = canonicalizer.process(sig1)
    assert res2.status_code == 202
    assert res2.message == "Duplicate ignored"

    # Same id, different body -> 409 conflict
    sig2 = create_signal("sig_5", value_dict={"value": 105})
    res3 = canonicalizer.process(sig2)
    assert res3.status_code == 409
    assert "Conflict" in res3.message


def test_dedup_partition_colon_and_unicode_collision_independence(
    canonicalizer: Canonicalizer,
) -> None:
    now = datetime.datetime.now(datetime.UTC)

    # Partition 1: vehicle_id="veh:a", trip_id="trip"
    sig1 = CanonicalSignal(
        received_at=now,
        signal_id="sig_1",
        source=SignalSource.SIMULATOR,
        signal_type="vehicle.speed_kmh",
        occurred_at=now,
        value={"value": 50.0},
        quality=SignalQuality.VALID,
        vehicle_id="veh:a",
        trip_id="trip",
    )

    # Partition 2: vehicle_id="veh", trip_id="a:trip"
    sig2 = CanonicalSignal(
        received_at=now,
        signal_id="sig_1",
        source=SignalSource.SIMULATOR,
        signal_type="vehicle.speed_kmh",
        occurred_at=now,
        value={"value": 100.0},
        quality=SignalQuality.VALID,
        vehicle_id="veh",
        trip_id="a:trip",
    )

    # Partition 3: Unicode IDs vehicle_id="xe_🚗:01", trip_id="chuyen_🚀"
    sig3 = CanonicalSignal(
        received_at=now,
        signal_id="sig_1",
        source=SignalSource.SIMULATOR,
        signal_type="vehicle.speed_kmh",
        occurred_at=now,
        value={"value": 80.0},
        quality=SignalQuality.VALID,
        vehicle_id="xe_🚗:01",
        trip_id="chuyen_🚀",
    )

    res1 = canonicalizer.process(sig1)
    assert res1.status_code == 202
    assert res1.message == "Accepted"

    res2 = canonicalizer.process(sig2)
    assert res2.status_code == 202
    assert res2.message == "Accepted"

    res3 = canonicalizer.process(sig3)
    assert res3.status_code == 202
    assert res3.message == "Accepted"
