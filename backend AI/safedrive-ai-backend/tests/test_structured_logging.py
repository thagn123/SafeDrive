import io
import json
import logging

import pytest
from fastapi import APIRouter, FastAPI
from httpx import ASGITransport, AsyncClient

from app.core.logging import (
    JSONFormatter,
    configure_logging,
    get_safedrive_owned_handlers,
)
from app.main import create_app

security_router = APIRouter()


@security_router.get("/test/security-probe")
async def security_probe_endpoint() -> None:
    secret_marker = "PRIVATE_EXCEPTION_MARKER_888"
    raise RuntimeError(f"Database connection failed with {secret_marker}")


def create_security_test_app() -> FastAPI:
    app = create_app()
    app.include_router(security_router)
    return app


def test_logging_level_idempotency_and_handler_updates() -> None:
    app_logger = logging.getLogger("app")

    configure_logging("INFO")
    initial_safedrive_handlers = get_safedrive_owned_handlers()
    initial_count = len(initial_safedrive_handlers)

    assert app_logger.level == logging.INFO
    assert all(h.level == logging.INFO for h in initial_safedrive_handlers)

    # Reconfiguring to DEBUG must update app logger and all existing SafeDrive handlers
    configure_logging("DEBUG")
    after_debug_handlers = get_safedrive_owned_handlers()

    assert len(after_debug_handlers) == initial_count
    assert app_logger.level == logging.DEBUG
    assert all(h.level == logging.DEBUG for h in after_debug_handlers)

    # Reset back to INFO
    configure_logging("INFO")
    assert app_logger.level == logging.INFO


def test_invalid_log_level_falls_back_to_info() -> None:
    try:
        configure_logging("NOT_A_REAL_LEVEL")

        app_logger = logging.getLogger("app")
        assert app_logger.level == logging.INFO
        assert all(handler.level == logging.INFO for handler in get_safedrive_owned_handlers())
    finally:
        configure_logging("INFO")


def test_configure_logging_does_not_modify_external_handler_level() -> None:
    app_logger = logging.getLogger("app")
    external_handler = logging.StreamHandler()
    external_handler.setLevel(logging.WARNING)
    app_logger.addHandler(external_handler)

    try:
        configure_logging("DEBUG")

        assert external_handler.level == logging.WARNING
        assert all(handler.level == logging.DEBUG for handler in get_safedrive_owned_handlers())
    finally:
        app_logger.removeHandler(external_handler)


def test_raw_log_message_not_leaked_as_event() -> None:
    app_logger = logging.getLogger("app.test_probe")
    log_output = io.StringIO()
    handler = logging.StreamHandler(log_output)
    handler.setFormatter(JSONFormatter())
    app_logger.addHandler(handler)

    try:
        raw_secret = "RAW_MESSAGE_SECRET_CONTAINER_123"
        app_logger.error("Failed to authenticate user with key=%s", raw_secret)

        emitted_text = log_output.getvalue()
        assert raw_secret not in emitted_text

        json_log = json.loads(emitted_text.strip())
        assert json_log["event"] == "unstructured_application_log"
        assert json_log["logger"] == "app.test_probe"
    finally:
        app_logger.removeHandler(handler)


@pytest.mark.asyncio
async def test_actual_emitted_output_security_redaction() -> None:
    app_logger = logging.getLogger("app")
    log_output = io.StringIO()
    handler = logging.StreamHandler(log_output)
    handler.setFormatter(JSONFormatter())
    app_logger.addHandler(handler)

    try:
        app = create_security_test_app()

        query_secret = "PRIVATE_QUERY_MARKER_111"
        api_key_secret = "PRIVATE_API_KEY_222"
        token_secret = "PRIVATE_TOKEN_333"
        exception_secret = "PRIVATE_EXCEPTION_MARKER_888"

        headers = {
            "X-Request-ID": "req-security-test-999",
            "X-SafeDrive-Key": api_key_secret,
            "Authorization": f"Bearer {token_secret}",
        }

        async with AsyncClient(
            transport=ASGITransport(app=app, raise_app_exceptions=False),
            base_url="http://test",
        ) as client:
            res = await client.get(f"/test/security-probe?token={query_secret}", headers=headers)
            assert res.status_code == 500

        # Direct counterexample probe call
        probe_logger = logging.getLogger("app.security_probe")
        probe_secret = "PROBE_DIRECT_SECRET_444"
        probe_logger.error("database failed token=%s", probe_secret)

        emitted_output = log_output.getvalue()

        # Counterexample assertions: NONE of the private markers must appear in emitted JSON output
        assert query_secret not in emitted_output
        assert api_key_secret not in emitted_output
        assert token_secret not in emitted_output
        assert exception_secret not in emitted_output
        assert probe_secret not in emitted_output
        assert "httpx" not in emitted_output

        # Confirm emitted lines are valid JSON and include stable events
        for line in emitted_output.splitlines():
            if line.strip():
                record = json.loads(line)
                assert "timestamp" in record
                assert "level" in record
                assert "logger" in record
                assert "event" in record

    finally:
        app_logger.removeHandler(handler)
