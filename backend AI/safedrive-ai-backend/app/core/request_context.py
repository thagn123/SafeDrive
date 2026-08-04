import re
import time
import uuid
from collections.abc import MutableMapping
from contextvars import ContextVar
from typing import Any

from starlette.types import ASGIApp, Receive, Scope, Send

_request_id_ctx_var: ContextVar[str | None] = ContextVar("request_id", default=None)

# Strict request ID pattern: 1 to 128 characters, no whitespace/tabs/CRLF permitted.
VALID_REQUEST_ID_PATTERN = re.compile(r"^[A-Za-z0-9._:-]{1,128}$")


def get_request_id() -> str | None:
    """Return the request ID for the current async execution context."""
    return _request_id_ctx_var.get()


def require_request_id() -> str:
    """Return the current request ID or raise RuntimeError if uninitialized."""
    req_id = get_request_id()
    if not req_id:
        raise RuntimeError("Request ID context is not set")
    return req_id


def generate_request_id() -> str:
    """Generate a unique request ID string."""
    return f"req_{uuid.uuid4().hex}"


def extract_authoritative_request_id(headers: list[tuple[bytes, bytes]]) -> str:
    """Extract and validate request ID from headers with strict ASCII decoding and exact pattern matching."""
    matching_headers = [v for k, v in headers if k.lower() == b"x-request-id"]
    if len(matching_headers) != 1:
        # 0 headers or multiple ambiguous headers -> generate new ID
        return generate_request_id()

    raw_id = matching_headers[0]
    try:
        decoded_id = raw_id.decode("ascii")
    except UnicodeDecodeError:
        return generate_request_id()

    # Exact fullmatch without trimming or stripping
    if VALID_REQUEST_ID_PATTERN.fullmatch(decoded_id):
        return decoded_id

    return generate_request_id()


class RequestContextMiddleware:
    """Pure ASGI middleware managing request correlation IDs, contextvar scope, and completion logging."""

    def __init__(self, app: ASGIApp) -> None:
        self.app = app

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        if scope["type"] != "http":
            await self.app(scope, receive, send)
            return

        headers = list(scope.get("headers", []))
        request_id = extract_authoritative_request_id(headers)
        token = _request_id_ctx_var.set(request_id)

        if "state" not in scope:
            scope["state"] = {}
        scope["state"]["request_id"] = request_id

        start_time = time.perf_counter()
        status_code_container: list[int] = [500]

        async def send_wrapper(message: MutableMapping[str, Any]) -> None:
            if message["type"] == "http.response.start":
                status_code_container[0] = int(message.get("status", 500))
                # Strip all downstream X-Request-ID headers and append exactly one authoritative header
                filtered_headers = [
                    (k, v) for k, v in message.get("headers", []) if k.lower() != b"x-request-id"
                ]
                filtered_headers.append((b"x-request-id", request_id.encode("ascii")))
                message["headers"] = filtered_headers
            await send(message)

        try:
            await self.app(scope, receive, send_wrapper)
        finally:
            _request_id_ctx_var.reset(token)
            try:
                duration_ms = round((time.perf_counter() - start_time) * 1000, 2)
                path_only = str(scope.get("path", "/"))

                from app.core.logging import get_application_logger

                logger = get_application_logger()
                logger.info(
                    "request_completed",
                    extra={
                        "event": "request_completed",
                        "request_id": request_id,
                        "method": str(scope.get("method", "GET")),
                        "path": path_only,
                        "status_code": status_code_container[0],
                        "duration_ms": duration_ms,
                    },
                )
            except Exception:  # noqa: BLE001, S110 -- logging completion attempt failure must not propagate
                pass
