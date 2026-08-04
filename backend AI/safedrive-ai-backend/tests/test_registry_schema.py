from copy import deepcopy
from pathlib import Path
from typing import Any, cast

import pytest
import yaml
from httpx import ASGITransport, AsyncClient
from pydantic import SecretStr, ValidationError

from app.core.config import Settings
from app.ingestion.registry import RegistrySchema, SignalRegistry
from app.main import create_app


def registry_data() -> dict[str, Any]:
    with Path("configs/signal_registry.yaml").open("r", encoding="utf-8") as file:
        return cast(dict[str, Any], yaml.safe_load(file))


def write_registry(tmp_path: Path, data: dict[str, Any]) -> Path:
    path = tmp_path / "signal_registry.yaml"
    path.write_text(yaml.safe_dump(data, sort_keys=False), encoding="utf-8")
    return path


def test_production_registry_schema_and_consistency() -> None:
    registry = SignalRegistry()
    assert "PRODUCTION_NO_DMS" in registry.profiles
    assert "DMS_DEMO" in registry.profiles
    assert registry.profiles["DMS_DEMO"].inherits == "PRODUCTION_NO_DMS"
    assert all(
        not capability.startswith(("driver.", "passenger."))
        for capability in registry.profiles["PRODUCTION_NO_DMS"].capabilities
    )
    assert all(
        definition.simulated_required
        for signal_type, definition in registry.signals.items()
        if signal_type.startswith(("driver.", "passenger."))
    )


@pytest.mark.parametrize(
    ("mutate", "message"),
    [
        (
            lambda data: data["signals"]["vehicle.speed_kmh"].update({"ttl_seconds": 0}),
            "greater than 0",
        ),
        (
            lambda data: data["signals"]["vehicle.speed_kmh"].update(
                {"allowed_lateness_seconds": -1}
            ),
            "greater than or equal to 0",
        ),
        (
            lambda data: data["signals"]["vehicle.speed_kmh"].pop("window_max_length"),
            "configured together",
        ),
        (
            lambda data: data["signals"]["vehicle.speed_kmh"].pop("window_ttl_seconds"),
            "configured together",
        ),
        (
            lambda data: data["signals"]["vehicle.speed_kmh"].update({"window_max_length": 0}),
            "greater than 0",
        ),
        (
            lambda data: data["profiles"]["PRODUCTION_NO_DMS"]["capabilities"].append(
                "unknown.signal"
            ),
            "unknown signal capability",
        ),
        (
            lambda data: data["profiles"]["DMS_DEMO"].update({"inherits": "MISSING_PROFILE"}),
            "inheritance target is invalid",
        ),
        (
            lambda data: data["signals"]["vehicle.speed_kmh"].update(
                {"source_allowed": ["NOT_A_SOURCE"]}
            ),
            "Input should be",
        ),
    ],
)
def test_malformed_registry_is_rejected(
    mutate: Any,
    message: str,
) -> None:
    data = deepcopy(registry_data())
    mutate(data)
    with pytest.raises(ValidationError, match=message):
        RegistrySchema.model_validate(data)


def test_missing_value_schema_mapping_is_rejected() -> None:
    data = registry_data()
    data["signals"]["unknown.signal"] = deepcopy(data["signals"]["vehicle.speed_kmh"])
    with pytest.raises(ValidationError, match="implemented value schemas must match"):
        RegistrySchema.model_validate(data)


def test_duplicate_yaml_signal_key_is_rejected(tmp_path: Path) -> None:
    original = Path("configs/signal_registry.yaml").read_text(encoding="utf-8")
    duplicate = original.replace(
        "signals:\n",
        (
            "signals:\n"
            "  vehicle.speed_kmh:\n"
            "    owner: VHAL\n"
            "    source_allowed: [VHAL]\n"
            "    ttl_seconds: 1\n"
            "    allowed_lateness_seconds: 0\n"
            "    simulated_required: false\n"
        ),
        1,
    )
    path = tmp_path / "duplicate.yaml"
    path.write_text(duplicate, encoding="utf-8")
    with pytest.raises(ValueError, match="duplicate mapping key"):
        SignalRegistry(path)


@pytest.mark.asyncio
async def test_invalid_registry_fails_readiness_but_not_liveness(
    tmp_path: Path,
) -> None:
    data = registry_data()
    data["signals"]["vehicle.speed_kmh"]["ttl_seconds"] = 0
    invalid_path = write_registry(tmp_path, data)
    settings = Settings(
        environment="test",
        safedrive_api_key=SecretStr("registry-test-key"),
        signal_registry_path=invalid_path,
    )
    app = create_app(settings=settings)

    async with (
        app.router.lifespan_context(app),
        AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client,
    ):
        health = await client.get("/health")
        ready = await client.get("/ready")

    assert health.status_code == 200
    assert ready.status_code == 503
    assert ready.json()["status"] == "not_ready"
    assert str(invalid_path) not in ready.text
