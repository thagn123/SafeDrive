from typing import Any

import pytest
from pydantic import TypeAdapter, ValidationError

from app.domain.models.signal import CanonicalSignalInput
from app.ingestion.registry import SignalRegistry


def get_base_payload(
    signal_type: str,
    value: dict[str, Any] | Any,
    source: str = "SIMULATOR",
    metadata: dict[str, Any] | None = None,
) -> dict[str, Any]:
    return {
        "signal_id": "123",
        "source": source,
        "signal_type": signal_type,
        "occurred_at": "2023-01-01T12:00:00Z",
        "value": value,
        "quality": "VALID",
        "vehicle_id": "v1",
        "trip_id": "t1",
        "metadata": metadata if metadata is not None else {},
    }


VALID_SIGNAL_CASES: list[tuple[str, dict[str, Any], str]] = [
    ("vehicle.speed_kmh", {"value": 100.5}, "VHAL"),
    ("vehicle.crash", {"severity": "HIGH"}, "VHAL"),
    ("vehicle.seatbelt", {"engaged": True}, "VHAL"),
    ("vehicle.parking_brake", {"status": False}, "VHAL"),
    ("vehicle.door_open", {"status": True}, "VHAL"),
    ("vehicle.window_open", {"status": False}, "VHAL"),
    ("vehicle.gear", {"gear": "D"}, "VHAL"),
    ("vehicle.steering_angle", {"angle": 45.0}, "VHAL"),
    ("vehicle.tire_pressure", {"pressure": 32.5}, "VHAL"),
    ("vehicle.brake_pedal", {"position": 50.0}, "VHAL"),
    ("vehicle.accelerator_pedal", {"position": 10.0}, "VHAL"),
    ("vehicle.gps", {"lat": 10.0, "lon": 20.0, "heading": 180.0, "speed": 100.0}, "GPS"),
    ("hvac.temperature", {"temperature": 22.5}, "VHAL"),
    ("hvac.fan_speed", {"speed": 5}, "VHAL"),
    ("hvac.ac_status", {"status": True}, "VHAL"),
    ("dtc.code", {"code": "P0101"}, "DTC"),
    ("driver.perclos", {"probability": 0.5}, "SIMULATOR"),
    ("driver.eye_closure", {"probability": 0.1}, "SIMULATOR"),
    ("driver.yawning", {"probability": 0.0}, "SIMULATOR"),
    ("driver.head_pose", {"probability": 1.0}, "SIMULATOR"),
    ("driver.gaze", {"probability": 0.8}, "SIMULATOR"),
    ("passenger.occupancy", {"status": True}, "SIMULATOR"),
    ("passenger.motion", {"status": False}, "SIMULATOR"),
    ("passenger.posture", {"value": "NORMAL"}, "SIMULATOR"),
    ("passenger.head_position", {"value": "CENTER"}, "SIMULATOR"),
]


def test_speed_signal_input_valid() -> None:
    payload = get_base_payload("vehicle.speed_kmh", {"value": 100.5})
    ta: TypeAdapter[Any] = TypeAdapter(CanonicalSignalInput)
    model = ta.validate_python(payload)
    assert model.signal_type == "vehicle.speed_kmh"
    assert model.value.value == 100.5


def test_speed_signal_input_invalid_range() -> None:
    payload = get_base_payload("vehicle.speed_kmh", {"value": 99999.0})
    ta: TypeAdapter[Any] = TypeAdapter(CanonicalSignalInput)
    with pytest.raises(ValidationError) as exc:
        ta.validate_python(payload)
    assert "Input should be less than or equal to 350" in str(exc.value)


def test_unexpected_field_rejected() -> None:
    payload = get_base_payload("vehicle.speed_kmh", {"value": 100.5})
    payload["unexpected_field"] = "bad"
    ta: TypeAdapter[Any] = TypeAdapter(CanonicalSignalInput)
    with pytest.raises(ValidationError) as exc:
        ta.validate_python(payload)
    assert "Extra inputs are not permitted" in str(exc.value)


def test_received_at_rejected_in_input() -> None:
    payload = get_base_payload("vehicle.speed_kmh", {"value": 100.5})
    payload["received_at"] = "2023-01-01T12:00:00Z"
    ta: TypeAdapter[Any] = TypeAdapter(CanonicalSignalInput)
    with pytest.raises(ValidationError) as exc:
        ta.validate_python(payload)
    assert "Extra inputs are not permitted" in str(exc.value)


def test_all_25_signal_types_end_to_end_validation() -> None:
    registry = SignalRegistry()
    ta: TypeAdapter[Any] = TypeAdapter(CanonicalSignalInput)

    assert len(VALID_SIGNAL_CASES) == 25, "Must cover all 25 registered signal types"

    for sig_type, val, source in VALID_SIGNAL_CASES:
        meta = {"simulated": True} if sig_type.startswith(("driver.", "passenger.")) else {}
        payload = get_base_payload(sig_type, val, source=source, metadata=meta)

        input_model = ta.validate_python(payload)
        canonical = input_model.to_canonical()

        profile = (
            "DMS_DEMO" if sig_type.startswith(("driver.", "passenger.")) else "PRODUCTION_NO_DMS"
        )
        assert registry.validate_signal(canonical, active_profile=profile) is True


def test_pedal_values_enforce_range_0_100() -> None:
    ta: TypeAdapter[Any] = TypeAdapter(CanonicalSignalInput)
    for sig_type in ("vehicle.brake_pedal", "vehicle.accelerator_pedal"):
        payload_invalid = get_base_payload(sig_type, {"position": 150.0})
        with pytest.raises(ValidationError):
            ta.validate_python(payload_invalid)

        payload_invalid_neg = get_base_payload(sig_type, {"position": -10.0})
        with pytest.raises(ValidationError):
            ta.validate_python(payload_invalid_neg)


def test_passenger_posture_and_head_position_use_string_schema() -> None:
    registry = SignalRegistry()
    ta: TypeAdapter[Any] = TypeAdapter(CanonicalSignalInput)

    for sig_type in ("passenger.posture", "passenger.head_position"):
        payload_invalid = get_base_payload(sig_type, {"status": True}, metadata={"simulated": True})
        with pytest.raises(ValidationError):
            ta.validate_python(payload_invalid)

        payload_valid = get_base_payload(
            sig_type, {"value": "NORMAL"}, metadata={"simulated": True}
        )
        input_model = ta.validate_python(payload_valid)
        canonical = input_model.to_canonical()
        assert registry.validate_signal(canonical, active_profile="DMS_DEMO") is True


def test_dms_and_passenger_signals_require_dms_demo_profile() -> None:
    registry = SignalRegistry()
    ta: TypeAdapter[Any] = TypeAdapter(CanonicalSignalInput)

    payload = get_base_payload("driver.perclos", {"probability": 0.5}, metadata={"simulated": True})
    canonical = ta.validate_python(payload).to_canonical()

    with pytest.raises(ValueError, match="not supported in profile PRODUCTION_NO_DMS"):
        registry.validate_signal(canonical, active_profile="PRODUCTION_NO_DMS")

    assert registry.validate_signal(canonical, active_profile="DMS_DEMO") is True


def test_simulated_dms_passenger_signals_require_simulated_true() -> None:
    registry = SignalRegistry()
    ta: TypeAdapter[Any] = TypeAdapter(CanonicalSignalInput)

    payload_missing_sim = get_base_payload("driver.perclos", {"probability": 0.5}, metadata={})
    canonical_missing = ta.validate_python(payload_missing_sim).to_canonical()

    with pytest.raises(ValueError, match="requires metadata.simulated=true"):
        registry.validate_signal(canonical_missing, active_profile="DMS_DEMO")
