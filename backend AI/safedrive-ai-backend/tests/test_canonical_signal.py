import datetime

import pytest
from pydantic import ValidationError

from app.domain.models.signal import CanonicalSignal, SignalQuality, SignalSource
from app.ingestion.registry import SignalRegistry


def test_canonical_signal_valid() -> None:
    signal = CanonicalSignal(
        received_at=datetime.datetime.now(datetime.UTC),
        signal_id="sig_101",
        source=SignalSource.SIMULATOR,
        signal_type="vehicle.speed_kmh",
        occurred_at=datetime.datetime.now(datetime.UTC),
        value={"value": 62},
        quality=SignalQuality.VALID,
        vehicle_id="veh_01",
        trip_id="trip_01",
        metadata={"simulated": True},
    )
    assert signal.signal_id == "sig_101"
    assert signal.signal_type == "vehicle.speed_kmh"


def test_canonical_signal_invalid_range() -> None:
    with pytest.raises(ValidationError):
        CanonicalSignal(
            received_at=datetime.datetime.now(datetime.UTC),
            signal_id="sig_101",
            source=SignalSource.SIMULATOR,
            signal_type="vehicle.speed_kmh",
            occurred_at=datetime.datetime.now(datetime.UTC),
            value={"value": 62},
            confidence=1.5,  # invalid, > 1.0
            quality=SignalQuality.VALID,
            vehicle_id="veh_01",
            trip_id="trip_01",
        )


def test_registry_validation() -> None:
    registry = SignalRegistry(config_path="configs/signal_registry.yaml")

    # Valid signal in DMS_DEMO
    sig1 = CanonicalSignal(
        received_at=datetime.datetime.now(datetime.UTC),
        signal_id="sig_1",
        source=SignalSource.SIMULATOR,
        signal_type="driver.perclos",
        occurred_at=datetime.datetime.now(datetime.UTC),
        vehicle_id="veh",
        trip_id="trip",
        metadata={"simulated": True},
        value={"probability": 0.5},
    )
    assert registry.validate_signal(sig1, "DMS_DEMO") is True

    # Same signal in PRODUCTION_NO_DMS should fail
    with pytest.raises(ValueError, match="not supported in profile"):
        registry.validate_signal(sig1, "PRODUCTION_NO_DMS")

    # Valid simulated required check
    sig2 = CanonicalSignal(
        received_at=datetime.datetime.now(datetime.UTC),
        signal_id="sig_2",
        source=SignalSource.VHAL,  # VHAL not allowed for driver.perclos
        signal_type="driver.perclos",
        occurred_at=datetime.datetime.now(datetime.UTC),
        vehicle_id="veh",
        trip_id="trip",
        value={"probability": 0.5},
    )
    with pytest.raises(ValueError, match="not allowed for"):
        registry.validate_signal(sig2, "DMS_DEMO")
