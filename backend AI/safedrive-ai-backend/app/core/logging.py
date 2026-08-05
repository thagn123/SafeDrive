import datetime
import json
import logging
import sys
from typing import Any

from app.core.request_context import get_request_id

SAFE_DRIVE_HANDLER_ATTRIBUTE = "_safedrive_owned"

# Allowlisted log fields permitted in JSON records
ALLOWLISTED_LOG_FIELDS = {
    "timestamp",
    "level",
    "logger",
    "event",
    "request_id",
    "method",
    "path",
    "status_code",
    "duration_ms",
    "exception_type",
    "language_ok",
    "retried",
    "fallback_used",
}


class JSONFormatter(logging.Formatter):
    """Formats application log records as single-line JSON strings without raw log messages."""

    def format(self, record: logging.LogRecord) -> str:
        req_id = getattr(record, "request_id", None) or get_request_id()

        # Strict event extraction: extra={"event": "..."} mandatory for custom events
        event = getattr(record, "event", None)
        if not event or not isinstance(event, str):
            event = "unstructured_application_log"

        dt = datetime.datetime.fromtimestamp(record.created, datetime.UTC)
        timestamp_str = dt.isoformat()

        data: dict[str, Any] = {
            "timestamp": timestamp_str,
            "level": record.levelname,
            "logger": record.name,
            "event": event,
            "request_id": req_id,
        }

        # Include optional allowlisted extra attributes
        for field in (
            "method",
            "path",
            "status_code",
            "duration_ms",
            "exception_type",
            "language_ok",
            "retried",
            "fallback_used",
        ):
            val = getattr(record, field, None)
            if val is not None:
                data[field] = val

        return json.dumps(data, ensure_ascii=False)


def get_safedrive_owned_handlers() -> list[logging.Handler]:
    """Return all StreamHandlers attached to 'app' logger marked as owned by SafeDrive."""
    app_logger = logging.getLogger("app")
    return [
        h for h in app_logger.handlers if getattr(h, SAFE_DRIVE_HANDLER_ATTRIBUTE, False) is True
    ]


def configure_logging(log_level: str = "INFO") -> None:
    """Idempotently configure structured JSON logging on the dedicated 'app' logger namespace."""
    app_logger = logging.getLogger("app")
    app_logger.propagate = False

    level_name = log_level.upper() if isinstance(log_level, str) else "INFO"
    numeric_level = getattr(logging, level_name, None)
    if not isinstance(numeric_level, int):
        numeric_level = logging.INFO

    app_logger.setLevel(numeric_level)

    safedrive_handlers = get_safedrive_owned_handlers()

    if not safedrive_handlers:
        new_handler: logging.Handler = logging.StreamHandler(sys.stdout)
        new_handler.setFormatter(JSONFormatter())
        setattr(new_handler, SAFE_DRIVE_HANDLER_ATTRIBUTE, True)
        new_handler.setLevel(numeric_level)
        app_logger.addHandler(new_handler)
    else:
        for handler in safedrive_handlers:
            handler.setLevel(numeric_level)


def get_application_logger() -> logging.Logger:
    """Return application request logger instance."""
    return logging.getLogger("app.request")
