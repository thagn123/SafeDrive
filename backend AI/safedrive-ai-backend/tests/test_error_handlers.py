import datetime

import pytest
from fastapi import APIRouter, FastAPI
from httpx import ASGITransport, AsyncClient
from pydantic import BaseModel, Field

from app.api.errors import ApiError
from app.main import create_app


class DummyPayload(BaseModel):
    name: str = Field(min_length=3)
    age: int = Field(gt=0)


dummy_router = APIRouter()


@dummy_router.post("/test/dummy")
async def dummy_endpoint(payload: DummyPayload) -> dict[str, str]:
    return {"status": "ok"}


@dummy_router.get("/test/custom-error")
async def custom_error_endpoint() -> None:
    raise ApiError(
        status_code=409,
        code="RESOURCE_CONFLICT",
        safe_message="Resource conflict detected.",
        details={"resource_id": "res_123"},
    )


@dummy_router.get("/test/unhandled-exception")
async def unhandled_exception_endpoint() -> None:
    secret_marker = "PRIVATE_EXCEPTION_VALUE_SECRET_999"
    raise RuntimeError(f"Internal database crash with {secret_marker}")


def create_test_app() -> FastAPI:
    app = create_app()
    app.include_router(dummy_router)
    return app


@pytest.mark.asyncio
async def test_api_error_handler() -> None:
    app = create_test_app()
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client:
        res = await client.get("/test/custom-error", headers={"X-Request-ID": "req-custom-01"})
        assert res.status_code == 409
        assert res.headers["X-Request-ID"] == "req-custom-01"

        data = res.json()
        assert "error" in data
        err = data["error"]
        assert err["code"] == "RESOURCE_CONFLICT"
        assert err["message"] == "Resource conflict detected."
        assert err["details"] == {"resource_id": "res_123"}
        assert err["request_id"] == "req-custom-01"
        assert err["schema_version"] == "1.0"
        dt = datetime.datetime.fromisoformat(err["timestamp"])
        assert dt.tzinfo is not None


@pytest.mark.asyncio
async def test_validation_error_handler_sanitizes_details() -> None:
    app = create_test_app()
    secret_body_value = "PRIVATE_BODY_VALUE_SECRET_777"
    invalid_payload = {"name": "a", "age": -5, "secret": secret_body_value}

    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client:
        res = await client.post(
            "/test/dummy",
            json=invalid_payload,
            headers={"X-Request-ID": "req-val-02"},
        )
        assert res.status_code == 422
        assert res.headers["X-Request-ID"] == "req-val-02"

        data = res.json()
        err = data["error"]
        assert err["code"] == "VALIDATION_ERROR"
        assert err["message"] == "Request validation failed."
        assert err["request_id"] == "req-val-02"
        assert err["schema_version"] == "1.0"

        # Assert no raw input or secret body values leaked in error envelope
        assert secret_body_value not in res.text
        assert "input" not in err["details"]
        assert isinstance(err["details"]["errors"], list)
        assert len(err["details"]["errors"]) > 0


@pytest.mark.asyncio
async def test_starlette_http_exception_handlers_404_and_405() -> None:
    app = create_app()
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client:
        # 404 Not Found
        r404 = await client.get("/non_existent_route_xyz", headers={"X-Request-ID": "req-404"})
        assert r404.status_code == 404
        assert r404.headers["X-Request-ID"] == "req-404"
        err404 = r404.json()["error"]
        assert err404["code"] == "NOT_FOUND"
        assert err404["message"] == "The requested resource was not found."

        # 405 Method Not Allowed
        r405 = await client.post("/health", headers={"X-Request-ID": "req-405"})
        assert r405.status_code == 405
        assert r405.headers["X-Request-ID"] == "req-405"
        err405 = r405.json()["error"]
        assert err405["code"] == "METHOD_NOT_ALLOWED"
        assert err405["message"] == "The HTTP method is not allowed for this endpoint."


@pytest.mark.asyncio
async def test_unhandled_exception_handler_returns_500_without_leaking_exception() -> None:
    app = create_test_app()
    secret_marker = "PRIVATE_EXCEPTION_VALUE_SECRET_999"

    async with AsyncClient(
        transport=ASGITransport(app=app, raise_app_exceptions=False),
        base_url="http://test",
    ) as client:
        res = await client.get("/test/unhandled-exception", headers={"X-Request-ID": "req-500"})
        assert res.status_code == 500
        assert res.headers["X-Request-ID"] == "req-500"

        err = res.json()["error"]
        assert err["code"] == "INTERNAL_SERVER_ERROR"
        assert err["message"] == "An internal server error occurred."
        assert err["details"] == {}
        assert secret_marker not in res.text
