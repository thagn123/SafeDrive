import logging
from typing import Annotated, Any

from fastapi import APIRouter, Depends, Query, Request
from fastapi.responses import JSONResponse

from app.api.auth import verify_api_key
from app.api.errors import ApiError, ErrorEnvelope, get_current_request_id
from app.api.routes.dependencies import get_application_services
from app.api.routes.mobile import get_mobile_state
from app.api.schemas.state import ComponentStateResponse, StateResponse
from app.core.services import ApplicationServices

logger = logging.getLogger("app.routes.state")

router = APIRouter(tags=["state"])
SAFE_ID_PATTERN = r"^\S+$"
ERROR_RESPONSES: dict[int | str, dict[str, Any]] = {
    200: {
        "model": StateResponse,
        "headers": {"X-Request-ID": {"schema": {"type": "string"}}},
    },
    401: {
        "model": ErrorEnvelope,
        "headers": {"X-Request-ID": {"schema": {"type": "string"}}},
    },
    404: {
        "model": ErrorEnvelope,
        "headers": {"X-Request-ID": {"schema": {"type": "string"}}},
    },
    422: {
        "model": ErrorEnvelope,
        "headers": {"X-Request-ID": {"schema": {"type": "string"}}},
    },
    500: {
        "model": ErrorEnvelope,
        "headers": {"X-Request-ID": {"schema": {"type": "string"}}},
    },
    503: {
        "model": ErrorEnvelope,
        "headers": {"X-Request-ID": {"schema": {"type": "string"}}},
    },
}


@router.get(
    "/state",
    response_model=StateResponse,
    status_code=200,
    responses=ERROR_RESPONSES,
    summary="Get latest state snapshot for vehicle and trip",
)
async def get_state(
    request: Request,
    services: Annotated[ApplicationServices, Depends(get_application_services)],
    vehicle_id: Annotated[
        str,  # noqa: RUF013 - optional query default must preserve the v1 OpenAPI string schema.
        Query(min_length=1, max_length=64, pattern=SAFE_ID_PATTERN),
    ] = None,
    trip_id: Annotated[
        str,  # noqa: RUF013 - optional query default must preserve the v1 OpenAPI string schema.
        Query(min_length=1, max_length=64, pattern=SAFE_ID_PATTERN),
    ] = None,
    session_id: Annotated[
        str | None,
        Query(alias="sessionId", min_length=1, max_length=128),
    ] = None,
    since_version: Annotated[int | None, Query(alias="sinceVersion", ge=0)] = None,
) -> JSONResponse:
    if session_id is not None:
        if vehicle_id is not None or trip_id is not None:
            raise ApiError(
                status_code=422,
                code="INVALID_STATE_QUERY",
                safe_message="Use either sessionId or vehicle_id/trip_id, not both.",
            )
        mobile_state = await get_mobile_state(request, session_id, since_version)
        return JSONResponse(status_code=200, content=mobile_state.model_dump(mode="json"))

    if vehicle_id is None or trip_id is None:
        raise ApiError(
            status_code=422,
            code="INVALID_STATE_QUERY",
            safe_message="vehicle_id and trip_id are required for canonical state queries.",
        )

    await verify_api_key(request, request.headers.get("X-SafeDrive-Key"))
    projection = services.latest_state_manager.project_state(vehicle_id, trip_id)
    if projection is None:
        raise ApiError(
            status_code=404,
            code="STATE_NOT_FOUND",
            safe_message="State was not found.",
        )

    request_id = get_current_request_id(request)
    components = {
        component_name: ComponentStateResponse(
            signal_type=component_name,
            value=component.value,
            updated_at=component.updated_at,
            sequence=component.sequence,
            source=component.source,
            freshness=component.freshness,
        )
        for component_name, component in projection.components.items()
    }
    response_state = StateResponse(
        request_id=request_id,
        timestamp=projection.projected_at,
        schema_version="1.0",
        vehicle_id=vehicle_id,
        trip_id=trip_id,
        state_version=projection.state_version,
        components=components,
    )
    logger.info(
        "State snapshot returned",
        extra={
            "event": "state_snapshot_returned",
            "state_version": projection.state_version,
            "request_id": request_id,
        },
    )
    return JSONResponse(
        status_code=200,
        content=response_state.model_dump(mode="json"),
        headers={"X-Request-ID": request_id},
    )
