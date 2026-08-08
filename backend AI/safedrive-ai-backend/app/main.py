import asyncio
import contextlib
import logging
from collections.abc import AsyncIterator, Callable
from contextlib import AbstractAsyncContextManager, asynccontextmanager
from pathlib import Path

from fastapi import FastAPI

from app.api.errors import install_exception_handlers
from app.api.router import api_router
from app.api.routes.system import router as system_router
from app.core import services as core_services
from app.core.config import Settings, get_settings
from app.core.logging import configure_logging
from app.core.request_context import RequestContextMiddleware
from app.ingestion.canonicalizer import Canonicalizer
from app.ingestion.registry import SignalRegistry
from app.mobile.emergency_reasoner import EmergencyLLMReasoner
from app.mobile.llm import GeminiNarrator, OllamaIntentClassifier, OllamaNarrator, VertexAINarrator
from app.mobile.memory import FirestoreContextMemory, InMemoryContextMemory
from app.mobile.session_store import MobileSessionStore
from app.mobile.state_bridge import MobileStateBridge
from app.services.signal_ingestion import SignalIngestionService
from app.state.manager import LatestStateManager
from app.state.rolling_window import RollingWindowManager

logger = logging.getLogger("app.main")

PROJECT_ROOT = Path(__file__).resolve().parents[1]

ServiceInitializer = Callable[[], core_services.ApplicationServices]
Lifespan = Callable[[FastAPI], AbstractAsyncContextManager[None]]


async def _rolling_window_prune_loop(
    rolling_window_manager: RollingWindowManager, interval_seconds: float
) -> None:
    while True:
        await asyncio.sleep(interval_seconds)
        try:
            await rolling_window_manager.prune_all()
        except Exception as exc:  # noqa: BLE001 -- background task error boundary
            logger.error(
                "Rolling window pruning execution failed: %s",
                type(exc).__name__,
                extra={
                    "event": "rolling_window_prune_failed",
                    "exception_type": type(exc).__name__,
                },
            )


def initialize_application_services(
    settings: Settings | None = None,
) -> core_services.ApplicationServices:
    """Build shared services from explicit settings or load default Settings."""
    cfg = settings if settings is not None else get_settings()

    signal_registry = SignalRegistry(config_path=str(cfg.signal_registry_path))

    if cfg.active_profile not in signal_registry.profiles:
        raise ValueError(
            f"Active profile '{cfg.active_profile}' is not defined in registry profiles"
        )

    canonicalizer = Canonicalizer(signal_registry)
    latest_state_manager = LatestStateManager(signal_registry)
    rolling_window_manager = RollingWindowManager(signal_registry)
    ingestion_service = SignalIngestionService(
        registry=signal_registry,
        canonicalizer=canonicalizer,
        latest_state_manager=latest_state_manager,
        rolling_window_manager=rolling_window_manager,
    )

    return core_services.ApplicationServices(
        settings=cfg,
        signal_registry=signal_registry,
        canonicalizer=canonicalizer,
        latest_state_manager=latest_state_manager,
        rolling_window_manager=rolling_window_manager,
        signal_ingestion_service=ingestion_service,
    )


def _reset_application_state(app: FastAPI) -> None:
    app.state.ready = False
    app.state.startup_error = None
    app.state.services = None
    app.state.settings = None
    app.state.signal_registry = None
    app.state.registry = None
    app.state.canonicalizer = None
    app.state.latest_state_manager = None
    app.state.state_manager = None
    app.state.rolling_window_manager = None
    app.state.signal_ingestion_service = None
    app.state.mobile_session_store = None


def _publish_services(app: FastAPI, services: core_services.ApplicationServices) -> None:
    app.state.services = services
    app.state.settings = services.settings
    app.state.signal_registry = services.signal_registry
    app.state.registry = services.signal_registry
    app.state.canonicalizer = services.canonicalizer
    app.state.latest_state_manager = services.latest_state_manager
    app.state.state_manager = services.latest_state_manager
    app.state.rolling_window_manager = services.rolling_window_manager
    app.state.signal_ingestion_service = services.signal_ingestion_service
    narrator = None
    classifier = None
    reasoner = None
    if services.settings.llm_provider == "ollama":
        narrator = OllamaNarrator(
            base_url=services.settings.llm_base_url,
            model=services.settings.llm_model,
            timeout_seconds=services.settings.llm_timeout_seconds,
        )
        classifier = OllamaIntentClassifier(
            base_url=services.settings.llm_base_url,
            model=services.settings.llm_model,
            timeout_seconds=services.settings.llm_timeout_seconds,
        )
        reasoner = EmergencyLLMReasoner(
            base_url=services.settings.llm_base_url,
            model=services.settings.llm_model,
            timeout_seconds=min(services.settings.llm_timeout_seconds, 3.0),
        )
    elif services.settings.llm_provider == "gemini":
        narrator = GeminiNarrator(
            api_key=services.settings.llm_api_key,
            model=services.settings.llm_model,
            timeout_seconds=services.settings.llm_timeout_seconds,
            project_id=services.settings.gcp_project_id,
            region=services.settings.gcp_region,
        )
    elif services.settings.llm_provider == "vertex_ai":
        narrator = VertexAINarrator(
            api_key=services.settings.llm_api_key,
            model=services.settings.llm_model,
            timeout_seconds=services.settings.llm_timeout_seconds,
            project_id=services.settings.gcp_project_id or "gen-lang-client-0307536353",
            region=services.settings.gcp_region,
        )
    memory = (
        FirestoreContextMemory(
            project_id=services.settings.gcp_project_id or "gen-lang-client-0307536353",
            database_id=services.settings.firestore_database_id,
        )
        if services.settings.memory_backend == "firestore"
        else InMemoryContextMemory()
    )
    app.state.mobile_session_store = MobileSessionStore(
        state_bridge=MobileStateBridge(services),
        narrator=narrator,
        classifier=classifier,
        reasoner=reasoner,
        memory=memory,
    )
    app.state.startup_error = None
    app.state.ready = True


def build_lifespan(service_initializer: ServiceInitializer) -> Lifespan:
    @asynccontextmanager
    async def lifespan(app: FastAPI) -> AsyncIterator[None]:
        _reset_application_state(app)
        prune_task: asyncio.Task[None] | None = None
        try:
            services = service_initializer()
            configure_logging(services.settings.log_level)
            _publish_services(app, services)
            prune_task = asyncio.create_task(
                _rolling_window_prune_loop(
                    services.rolling_window_manager,
                    services.settings.rolling_window_prune_interval_seconds,
                )
            )
        except Exception as exc:  # noqa: BLE001 -- intentional application startup boundary
            app.state.startup_error = type(exc).__name__
            logger.error(
                "Application service initialization failed: %s",
                type(exc).__name__,
                extra={
                    "event": "startup_initialization_failed",
                    "exception_type": type(exc).__name__,
                },
            )

        try:
            yield
        finally:
            if prune_task is not None:
                prune_task.cancel()
                with contextlib.suppress(asyncio.CancelledError):
                    await prune_task
            _reset_application_state(app)

    return lifespan


def create_app(
    settings: Settings | None = None,
    service_initializer: ServiceInitializer | None = None,
) -> FastAPI:
    """Create an isolated FastAPI application instance."""
    log_level = settings.log_level if settings is not None else "INFO"
    configure_logging(log_level)

    def _initializer() -> core_services.ApplicationServices:
        if service_initializer is not None:
            return service_initializer()
        return initialize_application_services(settings)

    application = FastAPI(
        title="SafeDrive AI Backend",
        version="0.1.0",
        lifespan=build_lifespan(_initializer),
    )
    _reset_application_state(application)
    application.add_middleware(RequestContextMiddleware)
    install_exception_handlers(application)
    application.include_router(system_router)
    application.include_router(api_router)
    return application


# ASGI entrypoint for `uvicorn app.main:app`; tests and embedding should use create_app().
app = create_app()
