from fastapi import Request

from app.api.errors import ApiError
from app.core.services import ApplicationServices


def get_application_services(request: Request) -> ApplicationServices:
    """Retrieve initialized ApplicationServices from request state."""
    ready = getattr(request.app.state, "ready", False)
    services: ApplicationServices | None = getattr(request.app.state, "services", None)

    if not ready or services is None:
        raise ApiError(
            status_code=503,
            code="SERVICE_NOT_READY",
            safe_message="Service is not ready.",
        )

    return services
