import logging
import tempfile
from importlib.resources import files
from pathlib import Path
from typing import Literal, cast

import pytest
from httpx import ASGITransport, AsyncClient
from pydantic import SecretStr, ValidationError

from app.core.config import Settings, get_settings
from app.core.logging import get_safedrive_owned_handlers
from app.core.services import ApplicationServices
from app.main import create_app


def test_packaged_default_signal_registry_resource_exists() -> None:
    registry_resource = files("configs").joinpath("signal_registry.yaml")
    assert registry_resource.is_file()


def test_valid_production_configuration_with_explicit_api_key() -> None:
    settings = Settings(
        environment="production",
        active_profile="PRODUCTION_NO_DMS",
        safedrive_api_key=SecretStr("super-secret-key-12345"),
    )
    assert settings.environment == "production"
    assert settings.active_profile == "PRODUCTION_NO_DMS"
    assert settings.safedrive_api_key is not None
    assert settings.safedrive_api_key.get_secret_value() == "super-secret-key-12345"


def test_production_missing_api_key_fails() -> None:
    with pytest.raises(ValidationError) as exc_info:
        Settings(
            environment="production",
            active_profile="PRODUCTION_NO_DMS",
            safedrive_api_key=None,
        )
    assert "safedrive_api_key" in str(exc_info.value)


def test_production_known_placeholder_api_key_fails() -> None:
    for placeholder in [
        "your_api_key_here",
        "change_this_to_a_secure_api_key_in_production",
    ]:
        with pytest.raises(ValidationError) as exc_info:
            Settings(
                environment="production",
                active_profile="PRODUCTION_NO_DMS",
                safedrive_api_key=SecretStr(placeholder),
            )
        err_msg = str(exc_info.value)
        assert "safedrive_api_key" in err_msg
        # Assert raw placeholder secret string does NOT leak in ValidationError output
        assert placeholder not in err_msg


def test_valid_explicit_development_test_configuration() -> None:
    for env in ["development", "test"]:
        typed_env = cast(Literal["development", "test"], env)
        settings = Settings(
            environment=typed_env,
            active_profile="DMS_DEMO",
            safedrive_api_key=None,
        )
        assert settings.environment == env
        assert settings.active_profile == "DMS_DEMO"


def test_production_with_dms_demo_fails() -> None:
    with pytest.raises(ValidationError) as exc_info:
        Settings(
            environment="production",
            active_profile="DMS_DEMO",
            safedrive_api_key=SecretStr("super-secret-key-12345"),
        )
    assert "DMS_DEMO profile cannot be used in production environment" in str(exc_info.value)


def test_invalid_environment_or_profile_value_fails() -> None:
    with pytest.raises(ValidationError):
        Settings.model_validate({"environment": "staging"})

    with pytest.raises(ValidationError):
        Settings.model_validate({"active_profile": "UNKNOWN_PROFILE"})


def test_invalid_registry_path_fails() -> None:
    with pytest.raises(ValidationError) as exc_info:
        Settings(
            environment="development",
            signal_registry_path=Path("non_existent_file.yaml"),
        )
    assert "signal_registry_path" in str(exc_info.value)


def test_default_registry_path_works_outside_repository_cwd(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    original_cwd = Path.cwd()
    with tempfile.TemporaryDirectory() as tmp_dir:
        monkeypatch.chdir(tmp_dir)
        try:
            settings = get_settings()
            assert settings.signal_registry_path.exists()
            assert settings.signal_registry_path.name == "signal_registry.yaml"
        finally:
            monkeypatch.chdir(original_cwd)


def test_environment_variable_loading(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("ENVIRONMENT", "development")
    monkeypatch.setenv("ACTIVE_PROFILE", "PRODUCTION_NO_DMS")
    monkeypatch.setenv("PORT", "9090")
    monkeypatch.setenv("LOG_LEVEL", "DEBUG")

    settings = Settings()
    assert settings.environment == "development"
    assert settings.active_profile == "PRODUCTION_NO_DMS"
    assert settings.port == 9090
    assert settings.log_level == "DEBUG"


@pytest.mark.asyncio
async def test_explicit_settings_passed_to_create_app_published_during_lifespan() -> None:
    custom_settings = Settings(
        environment="development",
        active_profile="PRODUCTION_NO_DMS",
        port=8081,
    )
    app = create_app(settings=custom_settings)

    async with app.router.lifespan_context(app):
        assert app.state.settings is custom_settings
        assert app.state.settings.port == 8081
        assert app.state.ready is True


@pytest.mark.asyncio
async def test_two_app_instances_remain_isolated() -> None:
    settings_a = Settings(environment="development", port=8001)
    settings_b = Settings(environment="development", port=8002)

    app_a = create_app(settings=settings_a)
    app_b = create_app(settings=settings_b)

    async with (
        app_a.router.lifespan_context(app_a),
        app_b.router.lifespan_context(app_b),
    ):
        assert app_a.state.settings.port == 8001
        assert app_b.state.settings.port == 8002


def test_secret_redacted_from_repr_and_serialization() -> None:
    secret_value = "super-confidential-token-999"
    settings = Settings(
        environment="development",
        safedrive_api_key=SecretStr(secret_value),
    )

    repr_str = repr(settings)
    assert secret_value not in repr_str
    assert "**********" in repr_str

    dump = settings.model_dump()
    assert secret_value not in str(dump["safedrive_api_key"])
    assert settings.safedrive_api_key is not None
    assert settings.safedrive_api_key.get_secret_value() == secret_value


@pytest.mark.asyncio
async def test_secret_bearing_startup_exception_absent_from_logs_and_http(
    caplog: pytest.LogCaptureFixture,
) -> None:
    secret_marker = "TOP_SECRET_SHOULD_NOT_BE_LOGGED"

    def failing_initializer() -> ApplicationServices:
        raise RuntimeError(f"Database error with token {secret_marker}")

    app = create_app(service_initializer=failing_initializer)

    async with (
        app.router.lifespan_context(app),
        AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client,
    ):
        ready_resp = await client.get("/ready")

        assert ready_resp.status_code == 503
        assert ready_resp.json()["status"] == "not_ready"
        assert ready_resp.json()["schema_version"] == "1.0"
        assert secret_marker not in ready_resp.text
        assert secret_marker not in caplog.text
        assert app.state.startup_error == "RuntimeError"
        assert secret_marker not in app.state.startup_error


@pytest.mark.asyncio
async def test_health_200_and_ready_503_on_configuration_failure() -> None:
    def failing_settings_initializer() -> ApplicationServices:
        raise ValueError("Invalid registry config file")

    app = create_app(service_initializer=failing_settings_initializer)

    async with (
        app.router.lifespan_context(app),
        AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client,
    ):
        health_resp = await client.get("/health")
        ready_resp = await client.get("/ready")

        assert health_resp.status_code == 200
        assert health_resp.json()["status"] == "ok"
        assert health_resp.json()["schema_version"] == "1.0"

        assert ready_resp.status_code == 503
        assert ready_resp.json()["status"] == "not_ready"
        assert ready_resp.json()["schema_version"] == "1.0"
        assert "Invalid registry config file" not in ready_resp.text
        assert app.state.startup_error == "ValueError"


@pytest.mark.asyncio
async def test_environment_log_level_applied_during_lifespan(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setenv("ENVIRONMENT", "development")
    monkeypatch.setenv("LOG_LEVEL", "DEBUG")

    app = create_app()

    async with app.router.lifespan_context(app):
        assert app.state.settings.log_level == "DEBUG"
        assert logging.getLogger("app").level == logging.DEBUG
        assert all(handler.level == logging.DEBUG for handler in get_safedrive_owned_handlers())
