from __future__ import annotations

from pathlib import Path
from typing import Any

import yaml
from pydantic import BaseModel, ConfigDict, Field, ValidationError, field_validator, model_validator

from app.domain.models.signal import (
    BooleanStatusValue,
    CanonicalSignal,
    CrashValue,
    DMSProbabilityValue,
    DTCValue,
    GearValue,
    GPSValue,
    HVACFanValue,
    HVACValue,
    PedalValue,
    SeatbeltValue,
    SignalSource,
    SpeedValue,
    SteeringAngleValue,
    StringValue,
    TirePressureValue,
)

SIGNAL_VALUE_SCHEMAS: dict[str, type[BaseModel]] = {
    "vehicle.speed_kmh": SpeedValue,
    "vehicle.crash": CrashValue,
    "vehicle.seatbelt": SeatbeltValue,
    "vehicle.parking_brake": BooleanStatusValue,
    "vehicle.door_open": BooleanStatusValue,
    "vehicle.window_open": BooleanStatusValue,
    "vehicle.gear": GearValue,
    "vehicle.steering_angle": SteeringAngleValue,
    "vehicle.tire_pressure": TirePressureValue,
    "vehicle.brake_pedal": PedalValue,
    "vehicle.accelerator_pedal": PedalValue,
    "vehicle.gps": GPSValue,
    "hvac.temperature": HVACValue,
    "hvac.fan_speed": HVACFanValue,
    "hvac.ac_status": BooleanStatusValue,
    "dtc.code": DTCValue,
    "driver.perclos": DMSProbabilityValue,
    "driver.eye_closure": DMSProbabilityValue,
    "driver.yawning": DMSProbabilityValue,
    "driver.head_pose": DMSProbabilityValue,
    "driver.gaze": DMSProbabilityValue,
    "passenger.occupancy": BooleanStatusValue,
    "passenger.motion": BooleanStatusValue,
    "passenger.posture": StringValue,
    "passenger.head_position": StringValue,
}


class UniqueKeyLoader(yaml.SafeLoader):
    """Safe YAML loader that rejects duplicate mapping keys."""


def _construct_unique_mapping(
    loader: UniqueKeyLoader,
    node: yaml.MappingNode,
    deep: bool = False,
) -> dict[Any, Any]:
    mapping: dict[Any, Any] = {}
    for key_node, value_node in node.value:
        key = loader.construct_object(key_node, deep=deep)
        if key in mapping:
            raise ValueError("Registry contains a duplicate mapping key")
        mapping[key] = loader.construct_object(value_node, deep=deep)
    return mapping


UniqueKeyLoader.add_constructor(
    yaml.resolver.BaseResolver.DEFAULT_MAPPING_TAG,
    _construct_unique_mapping,
)


class ProfileDefinition(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    description: str = Field(min_length=1)
    capabilities: list[str]
    inherits: str | None = None

    @field_validator("capabilities")
    @classmethod
    def validate_unique_capabilities(cls, value: list[str]) -> list[str]:
        if len(value) != len(set(value)):
            raise ValueError("Profile capabilities must be unique")
        return value


class SignalDefinition(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    owner: str = Field(min_length=1)
    source_allowed: list[SignalSource] = Field(min_length=1)
    ttl_seconds: float = Field(gt=0)
    allowed_lateness_seconds: float = Field(ge=0)
    simulated_required: bool
    window_ttl_seconds: float | None = Field(default=None, gt=0)
    window_max_length: int | None = Field(default=None, gt=0)

    @model_validator(mode="after")
    def validate_window_policy_pair(self) -> SignalDefinition:
        if (self.window_ttl_seconds is None) != (self.window_max_length is None):
            raise ValueError("window_ttl_seconds and window_max_length must be configured together")
        return self


class RegistrySchema(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    profiles: dict[str, ProfileDefinition]
    signals: dict[str, SignalDefinition]

    @model_validator(mode="after")
    def validate_references(self) -> RegistrySchema:
        configured = set(self.signals)
        implemented = set(SIGNAL_VALUE_SCHEMAS)
        if configured != implemented:
            raise ValueError("Registry signals and implemented value schemas must match")

        for profile_name, profile in self.profiles.items():
            if profile.inherits is not None and (
                profile.inherits == profile_name or profile.inherits not in self.profiles
            ):
                raise ValueError("Profile inheritance target is invalid")
            unknown_capabilities = set(profile.capabilities) - configured
            if unknown_capabilities:
                raise ValueError("Profile references an unknown signal capability")
        return self


class SignalRegistry:
    def __init__(self, config_path: str | Path = "configs/signal_registry.yaml") -> None:
        self.config_path = Path(config_path)
        registry = self._load_registry()
        self.profiles = registry.profiles
        self.signals = registry.signals

    def _load_registry(self) -> RegistrySchema:
        if not self.config_path.is_file():
            raise FileNotFoundError("Signal registry configuration is unavailable")
        with self.config_path.open("r", encoding="utf-8") as registry_file:
            raw_data = yaml.load(registry_file, Loader=UniqueKeyLoader)
        return RegistrySchema.model_validate(raw_data)

    def capabilities_for(self, active_profile: str) -> frozenset[str]:
        if active_profile not in self.profiles:
            raise ValueError("Active profile is not defined")

        capabilities: set[str] = set()
        visited: set[str] = set()
        profile_name: str | None = active_profile
        while profile_name is not None:
            if profile_name in visited:
                raise ValueError("Profile inheritance cycle detected")
            visited.add(profile_name)
            profile = self.profiles[profile_name]
            capabilities.update(profile.capabilities)
            profile_name = profile.inherits
        return frozenset(capabilities)

    def validate_signal(
        self,
        signal: CanonicalSignal,
        active_profile: str = "PRODUCTION_NO_DMS",
    ) -> bool:
        signal_definition = self.signals.get(signal.signal_type)
        schema_cls = SIGNAL_VALUE_SCHEMAS.get(signal.signal_type)
        if signal_definition is None or schema_cls is None:
            raise ValueError("Unknown signal type")

        try:
            schema_cls.model_validate(signal.value)
        except ValidationError as exc:
            raise ValueError("Value schema validation failed") from exc

        if signal.source not in signal_definition.source_allowed:
            raise ValueError(f"Source {signal.source.value} not allowed for {signal.signal_type}")

        if active_profile not in self.profiles:
            raise ValueError(
                f"Signal {signal.signal_type} not supported in profile {active_profile}"
            )
        if signal.signal_type not in self.capabilities_for(active_profile):
            raise ValueError(
                f"Signal {signal.signal_type} not supported in profile {active_profile}"
            )

        if signal_definition.simulated_required and signal.metadata.get("simulated") is not True:
            raise ValueError(f"Signal {signal.signal_type} requires metadata.simulated=true")
        return True
