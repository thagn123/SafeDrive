import asyncio

import pytest
from httpx import ASGITransport, AsyncClient

from app.api.router import api_router
from app.api.v1.router import v1_router
from app.core.services import ApplicationServices
from app.ingestion.canonicalizer import Canonicalizer
from app.ingestion.registry import SignalRegistry
from app.main import create_app
from app.state.manager import LatestStateManager
from app.state.rolling_window import RollingWindowManager


class ThirdPartyStartupError(Exception):
    pass


@pytest.mark.asyncio
async def test_lifespan_initializes_and_releases_shared_services() -> None:
    application = create_app()
    assert application.state.ready is False

    async with application.router.lifespan_context(application):
        assert application.state.ready is True
        assert isinstance(application.state.signal_registry, SignalRegistry)
        assert isinstance(application.state.canonicalizer, Canonicalizer)
        assert isinstance(application.state.latest_state_manager, LatestStateManager)
        assert isinstance(application.state.rolling_window_manager, RollingWindowManager)
        assert application.state.canonicalizer.registry is application.state.signal_registry
        assert application.state.latest_state_manager.registry is application.state.signal_registry
        assert (
            application.state.rolling_window_manager.registry is application.state.signal_registry
        )

    assert application.state.ready is False
    assert application.state.services is None
    assert application.state.signal_registry is None
    assert application.state.canonicalizer is None
    assert application.state.latest_state_manager is None
    assert application.state.rolling_window_manager is None


@pytest.mark.asyncio
async def test_startup_failure_does_not_expose_internal_detail() -> None:
    def fail_initialization() -> ApplicationServices:
        raise RuntimeError("sensitive initialization detail")

    application = create_app(service_initializer=fail_initialization)

    async with (
        application.router.lifespan_context(application),
        AsyncClient(transport=ASGITransport(app=application), base_url="http://test") as client,
    ):
        health_response = await client.get("/health")
        ready_response = await client.get("/ready")

        assert health_response.status_code == 200
        assert ready_response.status_code == 503
        assert ready_response.json()["status"] == "not_ready"
        assert ready_response.json()["schema_version"] == "1.0"
        assert "sensitive" not in ready_response.text
        assert application.state.startup_error == "RuntimeError"
        assert application.state.services is None

    assert application.state.startup_error is None


@pytest.mark.parametrize(
    "exception_type",
    [TypeError, ThirdPartyStartupError],
)
@pytest.mark.asyncio
async def test_startup_exception_types_degrade_readiness_safely(
    exception_type: type[Exception], caplog: pytest.LogCaptureFixture
) -> None:
    secret_marker = "TOP_SECRET_MARKER_98765"

    def fail_initialization() -> ApplicationServices:
        raise exception_type(f"failed with {secret_marker}")

    app = create_app(service_initializer=fail_initialization)

    async with (
        app.router.lifespan_context(app),
        AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client,
    ):
        assert app.state.ready is False
        assert app.state.startup_error == exception_type.__name__

        health = await client.get("/health")
        ready = await client.get("/ready")

        assert health.status_code == 200
        assert ready.status_code == 503
        assert ready.json()["status"] == "not_ready"
        assert ready.json()["schema_version"] == "1.0"
        assert secret_marker not in caplog.text
        assert secret_marker not in ready.text
        assert secret_marker not in app.state.startup_error


@pytest.mark.asyncio
async def test_factory_instances_have_isolated_lifecycle_state() -> None:
    first = create_app()
    second = create_app()

    async with first.router.lifespan_context(first):
        assert first.state.ready is True
        assert second.state.ready is False
        assert first.state.services is not second.state.services

    assert first.state.ready is False
    assert second.state.ready is False


@pytest.mark.asyncio
async def test_lifespan_does_not_leak_background_tasks() -> None:
    application = create_app()
    current_task = asyncio.current_task()
    tasks_before = {
        task for task in asyncio.all_tasks() if task is not current_task and not task.done()
    }

    async with application.router.lifespan_context(application):
        await asyncio.sleep(0)

    await asyncio.sleep(0)
    tasks_after = {
        task for task in asyncio.all_tasks() if task is not current_task and not task.done()
    }
    assert tasks_after == tasks_before


def test_api_v1_router_has_sd_0205_signal_and_state_routes() -> None:
    openapi_paths = create_app().openapi()["paths"]

    assert api_router.prefix == "/api"
    assert v1_router.prefix == "/v1"
    assert "/health" in openapi_paths
    assert "/ready" in openapi_paths
    assert any(path.startswith("/api/v1/signals") for path in openapi_paths)
    assert any(path.startswith("/api/v1/state") for path in openapi_paths)
