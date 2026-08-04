import datetime
import logging
from typing import Any, Literal

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from pydantic import BaseModel, Field
from starlette.exceptions import HTTPException as StarletteHTTPException

from app.core.request_context import get_request_id

logger = logging.getLogger("app.errors")


def utc_now() -> datetime.datetime:
    return datetime.datetime.now(datetime.UTC)


def get_current_request_id(request: Request | None = None) -> str:
    """Retrieve active request ID from ContextVar or request state."""
    req_id = get_request_id()
    if not req_id and request is not None:
        req_id = getattr(request.state, "request_id", None)
    return req_id or "unknown"


class ErrorDetail(BaseModel):
    code: str
    message: str
    details: dict[str, Any] = Field(default_factory=dict)
    request_id: str
    timestamp: datetime.datetime
    schema_version: Literal["1.0"]


class ErrorEnvelope(BaseModel):
    error: ErrorDetail


class ApiError(Exception):
    """Typed application exception carrying safe error information."""

    def __init__(
        self,
        status_code: int,
        code: str,
        safe_message: str,
        details: dict[str, Any] | None = None,
    ) -> None:
        super().__init__(safe_message)
        self.status_code = status_code
        self.code = code
        self.safe_message = safe_message
        self.details = details or {}


class MobileErrorEnvelope(BaseModel):
    code: str
    message: str
    requestId: str | None = None
    retryable: bool = False
    serverTimeMs: int


class MobileApiError(ApiError):
    """Typed error for the app-facing mobile-compatibility routes.

    ApiError's handler emits the canonical nested envelope
    (`{"error": {...snake_case fields...}}`), which does not match
    `openapi/safedrive-v1.yaml`'s flat `ErrorEnvelope`
    (code/message/requestId/retryable/serverTimeMs) that Android's
    ErrorEnvelopeDto requires — a mismatch would make Android silently fall
    back to blunt HTTP-status-only error mapping for every mobile-route
    error. `code` must be one of the closed enum Android's GatewayError
    understands: TIMEOUT, OFFLINE, UNAUTHORIZED, UNSUPPORTED, CONFLICT,
    VALIDATION, SERVER, PROTOCOL.
    """

    def __init__(
        self, status_code: int, code: str, safe_message: str, *, retryable: bool = False
    ) -> None:
        super().__init__(status_code, code, safe_message)
        self.retryable = retryable


def install_exception_handlers(app: FastAPI) -> None:
    """Register unified error handlers for ApiError, validation errors, Starlette HTTP errors, and unhandled exceptions."""

    @app.exception_handler(MobileApiError)
    async def mobile_api_error_handler(request: Request, exc: MobileApiError) -> JSONResponse:
        req_id = get_current_request_id(request)
        envelope = MobileErrorEnvelope(
            code=exc.code,
            message=exc.safe_message,
            requestId=req_id,
            retryable=exc.retryable,
            serverTimeMs=int(utc_now().timestamp() * 1000),
        )
        return JSONResponse(
            status_code=exc.status_code,
            content=envelope.model_dump(mode="json"),
            headers={"X-Request-ID": req_id},
        )

    @app.exception_handler(ApiError)
    async def api_error_handler(request: Request, exc: ApiError) -> JSONResponse:
        req_id = get_current_request_id(request)
        envelope = ErrorEnvelope(
            error=ErrorDetail(
                code=exc.code,
                message=exc.safe_message,
                details=exc.details,
                request_id=req_id,
                timestamp=utc_now(),
                schema_version="1.0",
            )
        )
        return JSONResponse(
            status_code=exc.status_code,
            content=envelope.model_dump(mode="json"),
            headers={"X-Request-ID": req_id},
        )

    @app.exception_handler(RequestValidationError)
    async def validation_error_handler(
        request: Request, exc: RequestValidationError
    ) -> JSONResponse:
        req_id = get_current_request_id(request)
        sanitized_errors = []
        for err in exc.errors():
            sanitized_errors.append(
                {
                    "location": [str(loc) for loc in err.get("loc", [])],
                    "type": str(err.get("type", "invalid")),
                }
            )
        envelope = ErrorEnvelope(
            error=ErrorDetail(
                code="VALIDATION_ERROR",
                message="Request validation failed.",
                details={"errors": sanitized_errors},
                request_id=req_id,
                timestamp=utc_now(),
                schema_version="1.0",
            )
        )
        return JSONResponse(
            status_code=422,
            content=envelope.model_dump(mode="json"),
            headers={"X-Request-ID": req_id},
        )

    @app.exception_handler(StarletteHTTPException)
    async def http_exception_handler(request: Request, exc: StarletteHTTPException) -> JSONResponse:
        req_id = get_current_request_id(request)
        if exc.status_code == 404:
            code = "NOT_FOUND"
            msg = "The requested resource was not found."
        elif exc.status_code == 405:
            code = "METHOD_NOT_ALLOWED"
            msg = "The HTTP method is not allowed for this endpoint."
        else:
            code = "HTTP_ERROR"
            msg = "An HTTP error occurred."

        envelope = ErrorEnvelope(
            error=ErrorDetail(
                code=code,
                message=msg,
                details={},
                request_id=req_id,
                timestamp=utc_now(),
                schema_version="1.0",
            )
        )
        return JSONResponse(
            status_code=exc.status_code,
            content=envelope.model_dump(mode="json"),
            headers={"X-Request-ID": req_id},
        )

    @app.exception_handler(Exception)
    async def unhandled_exception_handler(request: Request, exc: Exception) -> JSONResponse:
        req_id = get_current_request_id(request)
        logger.error(
            "Unhandled server exception: %s",
            type(exc).__name__,
            extra={
                "event": "unhandled_server_exception",
                "exception_type": type(exc).__name__,
                "request_id": req_id,
            },
        )
        envelope = ErrorEnvelope(
            error=ErrorDetail(
                code="INTERNAL_SERVER_ERROR",
                message="An internal server error occurred.",
                details={},
                request_id=req_id,
                timestamp=utc_now(),
                schema_version="1.0",
            )
        )
        return JSONResponse(
            status_code=500,
            content=envelope.model_dump(mode="json"),
            headers={"X-Request-ID": req_id},
        )
