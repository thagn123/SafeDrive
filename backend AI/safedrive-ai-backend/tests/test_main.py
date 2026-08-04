import pytest
from httpx import ASGITransport, AsyncClient

from app.core.services import ApplicationServices
from app.main import create_app


def failing_initializer() -> ApplicationServices:
    raise RuntimeError("Simulated startup failure")


@pytest.mark.asyncio
async def test_health_endpoint() -> None:
    app = create_app()
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client:
        response = await client.get("/health")
        assert response.status_code == 200
        data = response.json()
        assert data["status"] == "ok"
        assert "request_id" in data
        assert "timestamp" in data
        assert data["schema_version"] == "1.0"


@pytest.mark.asyncio
async def test_ready_endpoint_success() -> None:
    app = create_app()
    async with (
        app.router.lifespan_context(app),
        AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client,
    ):
        response = await client.get("/ready")
        assert response.status_code == 200
        data = response.json()
        assert data["status"] == "ready"
        assert "request_id" in data
        assert "timestamp" in data
        assert data["schema_version"] == "1.0"


@pytest.mark.asyncio
async def test_ready_endpoint_initialization_failure() -> None:
    app = create_app(service_initializer=failing_initializer)
    async with (
        app.router.lifespan_context(app),
        AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client,
    ):
        response = await client.get("/ready")
        assert response.status_code == 503
        data = response.json()
        assert data["status"] == "not_ready"
        assert "request_id" in data
        assert "timestamp" in data
        assert data["schema_version"] == "1.0"


@pytest.mark.asyncio
async def test_app_state_services_initialized() -> None:
    app = create_app()
    async with app.router.lifespan_context(app):
        assert hasattr(app.state, "signal_registry")
        assert hasattr(app.state, "canonicalizer")
        assert hasattr(app.state, "latest_state_manager")
        assert hasattr(app.state, "rolling_window_manager")
        assert app.state.ready is True
