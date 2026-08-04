import asyncio
import datetime
from collections import defaultdict
from collections.abc import Callable
from typing import Any, Literal

from pydantic import BaseModel, ConfigDict, Field

from app.domain.models.signal import CanonicalSignal
from app.ingestion.registry import SignalRegistry

StateKey = tuple[str, str]


class Freshness(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    age_ms: int = Field(ge=0)
    status: Literal["FRESH", "STALE", "UNAVAILABLE"]
    source: str


class ComponentState(BaseModel):
    value: Any
    updated_at: datetime.datetime
    sequence: int
    source: str


class VehicleStateSnapshot(BaseModel):
    vehicle_id: str
    trip_id: str
    state_version: int
    components: dict[str, ComponentState] = Field(default_factory=dict)
    # Cursor map: component -> (source -> max sequence)
    cursors: dict[str, dict[str, int]] = Field(default_factory=dict)

    def get_freshness(
        self,
        component_name: str,
        registry: SignalRegistry,
        now: datetime.datetime,
    ) -> Freshness:
        """Project freshness for this detached snapshot without wall-clock access."""
        component = self.components.get(component_name)
        if component is None:
            return Freshness(age_ms=0, status="UNAVAILABLE", source="")
        age_ms = max(0, int((now - component.updated_at).total_seconds() * 1000))
        ttl_ms = int(registry.signals[component_name].ttl_seconds * 1000)
        status: Literal["FRESH", "STALE"] = "FRESH" if age_ms <= ttl_ms else "STALE"
        return Freshness(age_ms=age_ms, status=status, source=component.source)


class ProjectedComponentState(BaseModel):
    value: Any
    updated_at: datetime.datetime
    sequence: int
    source: str
    freshness: Freshness


class StateProjection(BaseModel):
    vehicle_id: str
    trip_id: str
    state_version: int
    projected_at: datetime.datetime
    components: dict[str, ProjectedComponentState]


class OutOfOrderError(ValueError):
    pass


class ReplayError(ValueError):
    pass


class LatestStateManager:
    def __init__(
        self,
        registry: SignalRegistry,
        clock: Callable[[], datetime.datetime] | None = None,
    ) -> None:
        self.registry = registry
        self.clock = clock
        self.states: dict[StateKey, VehicleStateSnapshot] = {}
        self.locks: dict[StateKey, asyncio.Lock] = defaultdict(asyncio.Lock)

    def current_time(self) -> datetime.datetime:
        if self.clock:
            return self.clock()
        return datetime.datetime.now(datetime.UTC)

    @staticmethod
    def _apply_to_snapshot(
        state: VehicleStateSnapshot,
        signal: CanonicalSignal,
    ) -> None:
        component_key = signal.signal_type
        source_key = signal.source.value
        source_cursors = state.cursors.setdefault(component_key, {})
        existing_seq = source_cursors.get(source_key, 0)

        if existing_seq > 0:
            if signal.sequence > 0:
                if signal.sequence < existing_seq:
                    raise OutOfOrderError("OUT_OF_ORDER_DROPPED")
                if signal.sequence == existing_seq:
                    raise ReplayError("REPLAY_DROPPED")
            else:
                raise OutOfOrderError(
                    "OUT_OF_ORDER_DROPPED: Zero sequence cannot overwrite positive sequence"
                )
        elif signal.sequence == 0 and component_key in state.components:
            component = state.components[component_key]
            if component.source == source_key:
                if signal.occurred_at < component.updated_at:
                    raise OutOfOrderError("OUT_OF_ORDER_DROPPED")
                if signal.occurred_at == component.updated_at:
                    raise ReplayError("REPLAY_DROPPED")

        if component_key in state.components:
            component = state.components[component_key]
            if component.source != source_key:
                if signal.occurred_at < component.updated_at:
                    raise OutOfOrderError("OUT_OF_ORDER_DROPPED")
                if signal.occurred_at == component.updated_at:
                    raise ReplayError("REPLAY_DROPPED")

        if signal.sequence > 0:
            source_cursors[source_key] = signal.sequence

        state.components[component_key] = ComponentState(
            value=signal.value,
            updated_at=signal.occurred_at,
            sequence=signal.sequence,
            source=source_key,
        )
        state.state_version += 1

    def stage_signal(
        self,
        signal: CanonicalSignal,
        snapshot: VehicleStateSnapshot | None = None,
    ) -> VehicleStateSnapshot:
        """Apply ordering rules to a detached snapshot without live mutation."""
        if snapshot is None:
            staged = VehicleStateSnapshot(
                vehicle_id=signal.vehicle_id,
                trip_id=signal.trip_id,
                state_version=0,
            )
        else:
            if snapshot.vehicle_id != signal.vehicle_id or snapshot.trip_id != signal.trip_id:
                raise ValueError("Signal partition does not match state snapshot")
            staged = snapshot.model_copy(deep=True)
        self._apply_to_snapshot(staged, signal)
        return staged

    def swap_state(
        self,
        staged: VehicleStateSnapshot,
    ) -> VehicleStateSnapshot | None:
        """Atomically replace one partition and return the prior live object."""
        key = (staged.vehicle_id, staged.trip_id)
        previous = self.states.get(key)
        self.states[key] = staged
        return previous

    def restore_state(
        self,
        key: StateKey,
        previous: VehicleStateSnapshot | None,
    ) -> None:
        if previous is None:
            self.states.pop(key, None)
        else:
            self.states[key] = previous

    async def apply(self, signal: CanonicalSignal) -> VehicleStateSnapshot:
        key: StateKey = (signal.vehicle_id, signal.trip_id)
        async with self.locks[key]:
            staged = self.stage_signal(signal, self.states.get(key))
            self.states[key] = staged
            return staged.model_copy(deep=True)

    def get_state(self, vehicle_id: str, trip_id: str) -> VehicleStateSnapshot | None:
        state = self.states.get((vehicle_id, trip_id))
        return state.model_copy(deep=True) if state is not None else None

    def component_freshness(
        self,
        vehicle_id: str,
        trip_id: str,
        component_name: str,
    ) -> Freshness:
        state = self.states.get((vehicle_id, trip_id))
        if state is None or component_name not in state.components:
            return Freshness(age_ms=0, status="UNAVAILABLE", source="")

        component = state.components[component_name]
        age_ms = max(
            0,
            int((self.current_time() - component.updated_at).total_seconds() * 1000),
        )
        ttl_ms = int(self.registry.signals[component_name].ttl_seconds * 1000)
        status: Literal["FRESH", "STALE"] = "FRESH" if age_ms <= ttl_ms else "STALE"
        return Freshness(age_ms=age_ms, status=status, source=component.source)

    def project_state(self, vehicle_id: str, trip_id: str) -> StateProjection | None:
        """Return a public, clock-consistent state projection for API consumers."""
        state = self.states.get((vehicle_id, trip_id))
        if state is None:
            return None

        projected_at = self.current_time()
        projected_components: dict[str, ProjectedComponentState] = {}
        for component_name, component in state.components.items():
            age_ms = max(
                0,
                int((projected_at - component.updated_at).total_seconds() * 1000),
            )
            ttl_ms = int(self.registry.signals[component_name].ttl_seconds * 1000)
            freshness_status: Literal["FRESH", "STALE"] = "FRESH" if age_ms <= ttl_ms else "STALE"
            projected_components[component_name] = ProjectedComponentState(
                value=component.value,
                updated_at=component.updated_at,
                sequence=component.sequence,
                source=component.source,
                freshness=Freshness(
                    age_ms=age_ms,
                    status=freshness_status,
                    source=component.source,
                ),
            )

        return StateProjection(
            vehicle_id=state.vehicle_id,
            trip_id=state.trip_id,
            state_version=state.state_version,
            projected_at=projected_at,
            components=projected_components,
        )
