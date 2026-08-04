import datetime
from typing import Literal

from pydantic import BaseModel, ConfigDict, Field

from app.domain.models.signal import CanonicalSignalInput


class SignalBatchRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    signals: list[CanonicalSignalInput] = Field(
        ...,
        min_length=1,
        max_length=100,
        description="Batch of canonical input signals (1..100 items)",
    )


class SignalBatchResult(BaseModel):
    model_config = ConfigDict(extra="forbid")

    request_id: str
    timestamp: datetime.datetime
    schema_version: Literal["1.0"]
    accepted: int = Field(..., ge=0)
    duplicate: int = Field(..., ge=0)
    quarantined: int = Field(..., ge=0)
    state_version: int = Field(..., ge=0)
