import secrets
from typing import Annotated

from fastapi import Depends, Request
from fastapi.security import APIKeyHeader

from app.api.errors import ApiError

_api_key_header = APIKeyHeader(
    name="X-SafeDrive-Key",
    scheme_name="ApiKeyAuth",
    auto_error=False,
)


async def verify_api_key(
    request: Request,
    x_safedrive_key: Annotated[str | None, Depends(_api_key_header)],
) -> None:
    """Verify X-SafeDrive-Key while publishing a required OpenAPI security scheme."""
    services = getattr(request.app.state, "services", None)
    if services is None or services.settings is None:
        raise ApiError(
            status_code=503,
            code="SERVICE_NOT_READY",
            safe_message="Service is not ready.",
        )

    configured_secret = services.settings.safedrive_api_key
    if configured_secret is None:
        raise ApiError(
            status_code=503,
            code="AUTH_NOT_CONFIGURED",
            safe_message="Authentication is not configured on the server.",
        )
    if not x_safedrive_key:
        raise ApiError(
            status_code=401,
            code="AUTHENTICATION_REQUIRED",
            safe_message="X-SafeDrive-Key header is required.",
        )

    expected_key = configured_secret.get_secret_value()
    if not secrets.compare_digest(x_safedrive_key, expected_key):
        raise ApiError(
            status_code=401,
            code="INVALID_API_KEY",
            safe_message="Invalid API Key.",
        )
