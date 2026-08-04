import datetime
from enum import Enum
from typing import Annotated, Any, Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator

SAFE_ID_PATTERN = r"^\S+$"


def normalize_utc(value: datetime.datetime) -> datetime.datetime:
    """Require a real zero UTC offset and normalize to datetime.UTC."""
    if value.tzinfo is None or value.utcoffset() is None:
        raise ValueError("timestamp must be timezone-aware")
    if value.utcoffset() != datetime.timedelta(0):
        raise ValueError("timestamp must have a zero UTC offset")
    return value.astimezone(datetime.UTC)


class SignalSource(str, Enum):
    VHAL = "VHAL"
    DMS = "DMS"
    DTC = "DTC"
    CABIN_CAMERA = "CABIN_CAMERA"
    USER = "USER"
    SYSTEM = "SYSTEM"
    GPS = "GPS"
    SIMULATOR = "SIMULATOR"


class SignalQuality(str, Enum):
    VALID = "VALID"
    DEGRADED = "DEGRADED"
    INVALID = "INVALID"


class BaseSignalValue(BaseModel):
    model_config = ConfigDict(extra="forbid")


class SpeedValue(BaseSignalValue):
    value: float = Field(..., ge=0.0, le=350.0)


class CrashValue(BaseSignalValue):
    severity: str


class SeatbeltValue(BaseSignalValue):
    engaged: bool


class BooleanStatusValue(BaseSignalValue):
    status: bool


class GearValue(BaseSignalValue):
    gear: Literal["P", "R", "N", "D"]


class SteeringAngleValue(BaseSignalValue):
    angle: float = Field(..., ge=-1080.0, le=1080.0)


class TirePressureValue(BaseSignalValue):
    pressure: float = Field(..., ge=0.0, le=100.0)


class GPSValue(BaseSignalValue):
    lat: float = Field(..., ge=-90.0, le=90.0)
    lon: float = Field(..., ge=-180.0, le=180.0)
    heading: float = Field(..., ge=0.0, le=360.0)
    speed: float = Field(..., ge=0.0, le=350.0)


class HVACValue(BaseSignalValue):
    temperature: float = Field(..., ge=10.0, le=35.0)


class HVACFanValue(BaseSignalValue):
    speed: int = Field(..., ge=0, le=10)


class DTCValue(BaseSignalValue):
    code: str


class DMSProbabilityValue(BaseSignalValue):
    probability: float = Field(..., ge=0.0, le=1.0)


class PedalValue(BaseSignalValue):
    position: float = Field(..., ge=0.0, le=100.0)


class StringValue(BaseSignalValue):
    value: str


class BaseCanonicalSignalInput(BaseModel):
    model_config = ConfigDict(extra="forbid")

    signal_id: str = Field(..., min_length=1, max_length=64, pattern=SAFE_ID_PATTERN)
    source: SignalSource
    occurred_at: datetime.datetime
    unit: str | None = None
    confidence: float = Field(default=1.0, ge=0.0, le=1.0)
    quality: SignalQuality = SignalQuality.VALID
    vehicle_id: str = Field(..., min_length=1, max_length=64, pattern=SAFE_ID_PATTERN)
    trip_id: str = Field(..., min_length=1, max_length=64, pattern=SAFE_ID_PATTERN)
    sequence: int = Field(default=0, ge=0)
    metadata: dict[str, Any] = Field(default_factory=dict, max_length=20)

    @field_validator("occurred_at")
    @classmethod
    def validate_occurred_at(cls, value: datetime.datetime) -> datetime.datetime:
        return normalize_utc(value)

    def to_canonical(
        self,
        received_at: datetime.datetime | None = None,
    ) -> "CanonicalSignal":
        data = self.model_dump()
        server_received_at = received_at or datetime.datetime.now(datetime.UTC)
        return CanonicalSignal(**data, received_at=server_received_at)


# 1
class SpeedSignalInput(BaseCanonicalSignalInput):
    signal_type: Literal["vehicle.speed_kmh"]
    value: SpeedValue


# 2
class CrashSignalInput(BaseCanonicalSignalInput):
    signal_type: Literal["vehicle.crash"]
    value: CrashValue


# 3
class SeatbeltSignalInput(BaseCanonicalSignalInput):
    signal_type: Literal["vehicle.seatbelt"]
    value: SeatbeltValue


# 4
class ParkingBrakeSignalInput(BaseCanonicalSignalInput):
    signal_type: Literal["vehicle.parking_brake"]
    value: BooleanStatusValue


# 5
class DoorOpenSignalInput(BaseCanonicalSignalInput):
    signal_type: Literal["vehicle.door_open"]
    value: BooleanStatusValue


# 6
class WindowOpenSignalInput(BaseCanonicalSignalInput):
    signal_type: Literal["vehicle.window_open"]
    value: BooleanStatusValue


# 7
class GearSignalInput(BaseCanonicalSignalInput):
    signal_type: Literal["vehicle.gear"]
    value: GearValue


# 8
class SteeringAngleSignalInput(BaseCanonicalSignalInput):
    signal_type: Literal["vehicle.steering_angle"]
    value: SteeringAngleValue


# 9
class TirePressureSignalInput(BaseCanonicalSignalInput):
    signal_type: Literal["vehicle.tire_pressure"]
    value: TirePressureValue


# 10
class BrakePedalSignalInput(BaseCanonicalSignalInput):
    signal_type: Literal["vehicle.brake_pedal"]
    value: PedalValue


# 11
class AcceleratorPedalSignalInput(BaseCanonicalSignalInput):
    signal_type: Literal["vehicle.accelerator_pedal"]
    value: PedalValue


# 12
class GPSSignalInput(BaseCanonicalSignalInput):
    signal_type: Literal["vehicle.gps"]
    value: GPSValue


# 13
class HVACTemperatureSignalInput(BaseCanonicalSignalInput):
    signal_type: Literal["hvac.temperature"]
    value: HVACValue


# 14
class HVACFanSpeedSignalInput(BaseCanonicalSignalInput):
    signal_type: Literal["hvac.fan_speed"]
    value: HVACFanValue


# 15
class HVACACStatusSignalInput(BaseCanonicalSignalInput):
    signal_type: Literal["hvac.ac_status"]
    value: BooleanStatusValue


# 16
class DTCCodeSignalInput(BaseCanonicalSignalInput):
    signal_type: Literal["dtc.code"]
    value: DTCValue


# 17
class DriverPerclosSignalInput(BaseCanonicalSignalInput):
    signal_type: Literal["driver.perclos"]
    value: DMSProbabilityValue


# 18
class DriverEyeClosureSignalInput(BaseCanonicalSignalInput):
    signal_type: Literal["driver.eye_closure"]
    value: DMSProbabilityValue


# 19
class DriverYawningSignalInput(BaseCanonicalSignalInput):
    signal_type: Literal["driver.yawning"]
    value: DMSProbabilityValue


# 20
class DriverHeadPoseSignalInput(BaseCanonicalSignalInput):
    signal_type: Literal["driver.head_pose"]
    value: DMSProbabilityValue


# 21
class DriverGazeSignalInput(BaseCanonicalSignalInput):
    signal_type: Literal["driver.gaze"]
    value: DMSProbabilityValue


# 22
class PassengerOccupancySignalInput(BaseCanonicalSignalInput):
    signal_type: Literal["passenger.occupancy"]
    value: BooleanStatusValue


# 23
class PassengerMotionSignalInput(BaseCanonicalSignalInput):
    signal_type: Literal["passenger.motion"]
    value: BooleanStatusValue


# 24
class PassengerPostureSignalInput(BaseCanonicalSignalInput):
    signal_type: Literal["passenger.posture"]
    value: StringValue


# 25
class PassengerHeadPositionSignalInput(BaseCanonicalSignalInput):
    signal_type: Literal["passenger.head_position"]
    value: StringValue


CanonicalSignalInput = Annotated[
    SpeedSignalInput
    | CrashSignalInput
    | SeatbeltSignalInput
    | ParkingBrakeSignalInput
    | DoorOpenSignalInput
    | WindowOpenSignalInput
    | GearSignalInput
    | SteeringAngleSignalInput
    | TirePressureSignalInput
    | BrakePedalSignalInput
    | AcceleratorPedalSignalInput
    | GPSSignalInput
    | HVACTemperatureSignalInput
    | HVACFanSpeedSignalInput
    | HVACACStatusSignalInput
    | DTCCodeSignalInput
    | DriverPerclosSignalInput
    | DriverEyeClosureSignalInput
    | DriverYawningSignalInput
    | DriverHeadPoseSignalInput
    | DriverGazeSignalInput
    | PassengerOccupancySignalInput
    | PassengerMotionSignalInput
    | PassengerPostureSignalInput
    | PassengerHeadPositionSignalInput,
    Field(discriminator="signal_type"),
]


class CanonicalSignal(BaseModel):
    """Server domain model with received_at"""

    model_config = ConfigDict(extra="forbid")

    signal_id: str = Field(min_length=1, max_length=64, pattern=SAFE_ID_PATTERN)
    source: SignalSource
    signal_type: str
    occurred_at: datetime.datetime
    value: dict[str, Any]
    unit: str | None = None
    confidence: float = Field(default=1.0, ge=0.0, le=1.0)
    quality: SignalQuality = SignalQuality.VALID
    vehicle_id: str = Field(min_length=1, max_length=64, pattern=SAFE_ID_PATTERN)
    trip_id: str = Field(min_length=1, max_length=64, pattern=SAFE_ID_PATTERN)
    sequence: int = Field(default=0, ge=0)
    metadata: dict[str, Any] = Field(default_factory=dict, max_length=20)
    received_at: datetime.datetime

    @field_validator("occurred_at", "received_at")
    @classmethod
    def validate_timestamps(cls, value: datetime.datetime) -> datetime.datetime:
        return normalize_utc(value)
