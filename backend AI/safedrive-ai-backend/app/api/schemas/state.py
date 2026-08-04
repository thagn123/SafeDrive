import datetime
from typing import Any, Literal

from pydantic import BaseModel, ConfigDict, Field

from app.state.manager import Freshness


class ComponentStateResponse(BaseModel):
    signal_type: str
    value: dict[str, Any]
    updated_at: datetime.datetime
    sequence: int
    source: str
    freshness: Freshness


class StateResponse(BaseModel):
    model_config = ConfigDict(extra="forbid")

    request_id: str
    timestamp: datetime.datetime
    schema_version: Literal["1.0"]
    vehicle_id: str
    trip_id: str
    state_version: int = Field(..., ge=0)
    components: dict[str, ComponentStateResponse]
