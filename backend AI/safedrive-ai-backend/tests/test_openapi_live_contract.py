import datetime
from typing import Any, cast

import pytest
import yaml
from httpx import ASGITransport, AsyncClient, Response
from openapi_schema_validator import OAS30Validator
from pydantic import SecretStr
from referencing import Registry, Resource
from referencing.jsonschema import DRAFT202012

from app.core.config import Settings
from app.main import create_app

API_KEY = "live-contract-test-key"


def load_spec() -> dict[str, Any]:
    with open("contracts/openapi.yaml", "r", encoding="utf-8") as file:
        return cast(dict[str, Any], yaml.safe_load(file))


def validate_live_response(
    spec: dict[str, Any],
    path: str,
    method: str,
    response: Response,
) -> None:
    response_contract = spec["paths"][path][method]["responses"][str(response.status_code)]
    schema = response_contract["content"]["application/json"]["schema"]
    resource = Resource.from_contents(spec, default_specification=DRAFT202012)
    registry = Registry().with_resource("urn:openapi", resource)
    if "$ref" in schema:
        schema = {"$ref": f"urn:openapi{schema['$ref']}"}
    OAS30Validator(schema, registry=registry).validate(response.json())
    assert response.headers["X-Request-ID"]
    assert "X-Request-ID" in response_contract["headers"]


def speed_signal(
    signal_id: str = "live-speed-1",
    speed: float = 64.0,
    vehicle_id: str = "live-vehicle",
) -> dict[str, Any]:
    return {
        "signal_id": signal_id,
        "source": "VHAL",
        "signal_type": "vehicle.speed_kmh",
        "occurred_at": datetime.datetime.now(datetime.UTC).isoformat(),
        "value": {"value": speed},
        "quality": "VALID",
        "vehicle_id": vehicle_id,
        "trip_id": "live-trip",
        "sequence": 1,
    }


@pytest.mark.asyncio
async def test_live_http_bodies_validate_against_pinned_openapi() -> None:
    spec = load_spec()
    settings = Settings(
        environment="test",
        safedrive_api_key=SecretStr(API_KEY),
    )
    app = create_app(settings=settings)
    transport = ASGITransport(app=app, raise_app_exceptions=False)

    async with (
        app.router.lifespan_context(app),
        AsyncClient(transport=transport, base_url="http://test") as client,
    ):
        health = await client.get("/health")
        ready = await client.get("/ready")
        validate_live_response(spec, "/health", "get", health)
        validate_live_response(spec, "/ready", "get", ready)

        headers = {
            "X-SafeDrive-Key": API_KEY,
            "Idempotency-Key": "live-operation-1",
            "X-Request-ID": "live-request-1",
        }
        request_body = {"signals": [speed_signal()]}
        first = await client.post("/api/v1/signals", headers=headers, json=request_body)
        validate_live_response(spec, "/api/v1/signals", "post", first)

        next_response_time = datetime.datetime.now(datetime.UTC) + datetime.timedelta(seconds=1)
        app.state.services.signal_ingestion_service.clock = lambda: next_response_time
        replay_headers = dict(headers)
        replay_headers["X-Request-ID"] = "live-request-2"
        replay = await client.post(
            "/api/v1/signals",
            headers=replay_headers,
            json=request_body,
        )
        validate_live_response(spec, "/api/v1/signals", "post", replay)
        assert replay.json()["request_id"] != first.json()["request_id"]
        assert replay.json()["timestamp"] != first.json()["timestamp"]
        for business_field in (
            "accepted",
            "duplicate",
            "quarantined",
            "state_version",
        ):
            assert replay.json()[business_field] == first.json()[business_field]

        state = await client.get(
            "/api/v1/state",
            headers={"X-SafeDrive-Key": API_KEY},
            params={"vehicle_id": "live-vehicle", "trip_id": "live-trip"},
        )
        validate_live_response(spec, "/api/v1/state", "get", state)

        not_found = await client.get(
            "/api/v1/state",
            headers={"X-SafeDrive-Key": API_KEY},
            params={"vehicle_id": "missing", "trip_id": "missing"},
        )
        validate_live_response(spec, "/api/v1/state", "get", not_found)

        unauthorized = await client.get(
            "/api/v1/state",
            params={"vehicle_id": "live-vehicle", "trip_id": "live-trip"},
        )
        validate_live_response(spec, "/api/v1/state", "get", unauthorized)

        invalid_header = await client.post(
            "/api/v1/signals",
            headers={"X-SafeDrive-Key": API_KEY},
            json=request_body,
        )
        validate_live_response(spec, "/api/v1/signals", "post", invalid_header)

        mixed = await client.post(
            "/api/v1/signals",
            headers={
                "X-SafeDrive-Key": API_KEY,
                "Idempotency-Key": "live-mixed",
            },
            json={
                "signals": [
                    speed_signal("mixed-1"),
                    speed_signal("mixed-2", vehicle_id="other-vehicle"),
                ]
            },
        )
        validate_live_response(spec, "/api/v1/signals", "post", mixed)

        conflict = await client.post(
            "/api/v1/signals",
            headers=replay_headers,
            json={"signals": [speed_signal(speed=99.0)]},
        )
        validate_live_response(spec, "/api/v1/signals", "post", conflict)

        oversized_signal = speed_signal("oversized")
        oversized_signal["metadata"] = {"padding": "x" * (1024 * 1024 + 100)}
        oversized = await client.post(
            "/api/v1/signals",
            headers={
                "X-SafeDrive-Key": API_KEY,
                "Idempotency-Key": "live-oversized",
            },
            json={"signals": [oversized_signal]},
        )
        validate_live_response(spec, "/api/v1/signals", "post", oversized)


def parameter_contract(
    operation: dict[str, Any],
    name: str,
) -> dict[str, Any]:
    return next(
        parameter for parameter in operation.get("parameters", []) if parameter["name"] == name
    )


def test_runtime_openapi_matches_manual_implemented_route_surface() -> None:
    manual = load_spec()
    runtime = create_app(
        settings=Settings(
            environment="test",
            safedrive_api_key=SecretStr(API_KEY),
        )
    ).openapi()
    implemented = {
        "/health": "get",
        "/ready": "get",
        "/api/v1/signals": "post",
        "/api/v1/state": "get",
    }
    for path, method in implemented.items():
        manual_operation = manual["paths"][path][method]
        runtime_operation = runtime["paths"][path][method]
        assert set(manual_operation["responses"]) == set(runtime_operation["responses"])
        for response in runtime_operation["responses"].values():
            assert "X-Request-ID" in response.get("headers", {})

    manual_idempotency = parameter_contract(
        manual["paths"]["/api/v1/signals"]["post"],
        "Idempotency-Key",
    )
    runtime_idempotency = parameter_contract(
        runtime["paths"]["/api/v1/signals"]["post"],
        "Idempotency-Key",
    )
    for key in ("minLength", "maxLength", "pattern"):
        assert manual_idempotency["schema"][key] == runtime_idempotency["schema"][key]
    assert manual_idempotency["required"] is runtime_idempotency["required"] is True

    for name in ("vehicle_id", "trip_id"):
        manual_query = parameter_contract(
            manual["paths"]["/api/v1/state"]["get"],
            name,
        )
        runtime_query = parameter_contract(
            runtime["paths"]["/api/v1/state"]["get"],
            name,
        )
        for key in ("minLength", "maxLength", "pattern"):
            assert manual_query["schema"][key] == runtime_query["schema"][key]

    assert (
        manual["components"]["securitySchemes"]["ApiKeyAuth"]
        == runtime["components"]["securitySchemes"]["ApiKeyAuth"]
    )
    assert (
        manual["components"]["schemas"]["CanonicalSignalInputBase"]["properties"]["metadata"][
            "maxProperties"
        ]
        == runtime["components"]["schemas"]["SpeedSignalInput"]["properties"]["metadata"][
            "maxProperties"
        ]
        == 20
    )
