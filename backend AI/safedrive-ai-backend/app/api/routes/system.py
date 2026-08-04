import datetime
from http import HTTPStatus
from typing import Any, Literal

from fastapi import APIRouter, Request, Response
from pydantic import BaseModel

from app.core.request_context import get_request_id

router = APIRouter(tags=["system"])


def utc_now() -> datetime.datetime:
    return datetime.datetime.now(datetime.UTC)


class HealthResponse(BaseModel):
    status: Literal["ok"]
    request_id: str
    timestamp: datetime.datetime
    schema_version: Literal["1.0"]
    service: str
    apiVersion: Literal["1.0.0"]
    serverTimeMs: int
    capabilities: dict[str, bool]


class ReadinessResponse(BaseModel):
    status: Literal["ready", "not_ready"]
    request_id: str
    timestamp: datetime.datetime
    schema_version: Literal["1.0"]


SYSTEM_RESPONSE_HEADERS: dict[str, Any] = {"X-Request-ID": {"schema": {"type": "string"}}}


@router.get(
    "/health",
    response_model=HealthResponse,
    responses={
        200: {
            "model": HealthResponse,
            "headers": SYSTEM_RESPONSE_HEADERS,
        }
    },
)
async def health() -> HealthResponse:
    """Report process liveness with request correlation metadata."""
    req_id = get_request_id() or "unknown"
    timestamp = utc_now()
    return HealthResponse(
        status="ok",
        request_id=req_id,
        timestamp=timestamp,
        schema_version="1.0",
        service="SafeDrive AI Backend",
        apiVersion="1.0.0",
        serverTimeMs=int(timestamp.timestamp() * 1_000),
        capabilities={
            "assistant": True,
            "emergencySimulation": True,
            "cockpitStream": False,
        },
    )


@router.get(
    "/ready",
    response_model=ReadinessResponse,
    responses={
        200: {
            "model": ReadinessResponse,
            "headers": SYSTEM_RESPONSE_HEADERS,
        },
        HTTPStatus.SERVICE_UNAVAILABLE: {
            "model": ReadinessResponse,
            "headers": SYSTEM_RESPONSE_HEADERS,
        },
    },
)
async def ready(request: Request, response: Response) -> ReadinessResponse:
    """Report whether all shared application services initialized successfully."""
    req_id = get_request_id() or "unknown"
    if not bool(getattr(request.app.state, "ready", False)):
        response.status_code = HTTPStatus.SERVICE_UNAVAILABLE
        return ReadinessResponse(
            status="not_ready",
            request_id=req_id,
            timestamp=utc_now(),
            schema_version="1.0",
        )

    return ReadinessResponse(
        status="ready",
        request_id=req_id,
        timestamp=utc_now(),
        schema_version="1.0",
    )
