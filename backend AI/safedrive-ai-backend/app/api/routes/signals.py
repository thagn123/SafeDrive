import logging
from typing import Annotated, Any

from fastapi import APIRouter, Depends, Header, Request
from fastapi.responses import JSONResponse

from app.api.auth import verify_api_key
from app.api.errors import ApiError, ErrorEnvelope, get_current_request_id
from app.api.routes.dependencies import get_application_services
from app.api.schemas.signals import SignalBatchRequest, SignalBatchResult
from app.core.services import ApplicationServices
from app.services.signal_ingestion import IngestionError

logger = logging.getLogger("app.routes.signals")

router = APIRouter(tags=["signals"])

MAX_PAYLOAD_BYTES = 1024 * 1024
IDEMPOTENCY_KEY_PATTERN = r"^[A-Za-z0-9._:-]{1,128}$"
ERROR_RESPONSES: dict[int | str, dict[str, Any]] = {
    202: {
        "model": SignalBatchResult,
        "headers": {"X-Request-ID": {"schema": {"type": "string"}}},
    },
    400: {
        "model": ErrorEnvelope,
        "headers": {"X-Request-ID": {"schema": {"type": "string"}}},
    },
    401: {
        "model": ErrorEnvelope,
        "headers": {"X-Request-ID": {"schema": {"type": "string"}}},
    },
    409: {
        "model": ErrorEnvelope,
        "headers": {"X-Request-ID": {"schema": {"type": "string"}}},
    },
    413: {
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


@router.post(
    "/signals",
    response_model=SignalBatchResult,
    status_code=202,
    responses=ERROR_RESPONSES,
    summary="Ingest batch of canonical signals",
    description="Ingest a batch of 1..100 signals for one vehicle/trip partition.",
)
async def post_signals(
    request: Request,
    batch: SignalBatchRequest,
    idempotency_key: Annotated[
        str,
        Header(
            alias="Idempotency-Key",
            min_length=1,
            max_length=128,
            pattern=IDEMPOTENCY_KEY_PATTERN,
        ),
    ],
    services: Annotated[ApplicationServices, Depends(get_application_services)],
    _authenticated: Annotated[None, Depends(verify_api_key)],
) -> JSONResponse:
    content_length_value = request.headers.get("content-length")
    if content_length_value is not None:
        try:
            if int(content_length_value) > MAX_PAYLOAD_BYTES:
                raise ApiError(
                    status_code=413,
                    code="PAYLOAD_TOO_LARGE",
                    safe_message="Payload exceeds maximum size limit of 1 MiB.",
                )
        except ValueError:
            pass

    if len(batch.model_dump_json().encode("utf-8")) > MAX_PAYLOAD_BYTES:
        raise ApiError(
            status_code=413,
            code="PAYLOAD_TOO_LARGE",
            safe_message="Payload exceeds maximum size limit of 1 MiB.",
        )

    try:
        result = await services.signal_ingestion_service.process_batch(
            request=batch,
            idempotency_key=idempotency_key,
            active_profile=services.settings.active_profile,
        )
    except IngestionError as exc:
        status_code = 400 if exc.code == "MIXED_SIGNAL_PARTITIONS" else 422
        if exc.code in {"IDEMPOTENCY_KEY_REUSED", "SIGNAL_ID_CONFLICT"}:
            status_code = 409
        raise ApiError(
            status_code=status_code,
            code=exc.code,
            safe_message=exc.safe_message,
        ) from exc

    request_id = get_current_request_id(request)
    response_result = SignalBatchResult(
        request_id=request_id,
        timestamp=services.signal_ingestion_service.current_time(),
        schema_version="1.0",
        accepted=result.accepted,
        duplicate=result.duplicate,
        quarantined=result.quarantined,
        state_version=result.state_version,
    )
    logger.info(
        "Signal batch processed",
        extra={
            "event": "signal_batch_processed",
            "accepted": result.accepted,
            "duplicate": result.duplicate,
            "quarantined": result.quarantined,
            "state_version": result.state_version,
            "request_id": request_id,
        },
    )
    return JSONResponse(
        status_code=202,
        content=response_result.model_dump(mode="json"),
        headers={"X-Request-ID": request_id},
    )
