import json
import typing
from pathlib import Path

import pytest
import yaml
from jsonschema.exceptions import ValidationError
from openapi_schema_validator import OAS30Validator
from referencing import Registry, Resource
from referencing.jsonschema import DRAFT202012

from app.ingestion.registry import UniqueKeyLoader
from create_fixtures import FIXTURES, generate_fixtures


def validate_with_refs(instance: typing.Any, schema_name: str, spec: typing.Any) -> None:
    schema = {"$ref": f"urn:openapi#/components/schemas/{schema_name}"}
    resource = Resource.from_contents(spec, default_specification=DRAFT202012)
    registry = Registry().with_resource("urn:openapi", resource)
    validator = OAS30Validator(schema, registry=registry)
    validator.validate(instance)


def test_fixtures() -> None:
    with open("contracts/openapi.yaml", "r", encoding="utf-8") as f:
        spec = yaml.safe_load(f)

    with open("contracts/examples/signals_request.json", "r", encoding="utf-8") as f:
        req = json.load(f)
    validate_with_refs(req, "SignalBatchRequest", spec)

    with open("contracts/examples/signals_response.json", "r", encoding="utf-8") as f:
        sig_resp = json.load(f)
    validate_with_refs(sig_resp, "SignalBatchResult", spec)

    with open("contracts/examples/safety_response.json", "r", encoding="utf-8") as f:
        resp = json.load(f)
    validate_with_refs(resp, "SafetyResponse", spec)

    with open("contracts/examples/safety_response_null.json", "r", encoding="utf-8") as f:
        resp_null = json.load(f)
    validate_with_refs(resp_null, "SafetyResponse", spec)

    with open("contracts/examples/error_response.json", "r", encoding="utf-8") as f:
        err = json.load(f)
    validate_with_refs(err, "ErrorEnvelope", spec)

    with open("contracts/examples/tool_call.json", "r", encoding="utf-8") as f:
        tool_call = json.load(f)
    validate_with_refs(tool_call, "ToolCall", spec)

    with open("contracts/examples/tool_result.json", "r", encoding="utf-8") as f:
        tool_result = json.load(f)
    validate_with_refs(tool_result, "ToolResult", spec)

    with open("contracts/examples/sos_status.json", "r", encoding="utf-8") as f:
        sos = json.load(f)
    validate_with_refs(sos, "SOSStatus", spec)

    with open("contracts/examples/state_snapshot.json", "r", encoding="utf-8") as f:
        state = json.load(f)
    validate_with_refs(state, "StateSnapshot", spec)


def test_openapi_yaml_has_no_duplicate_mapping_keys() -> None:
    with open("contracts/openapi.yaml", "r", encoding="utf-8") as file:
        spec = yaml.load(file, Loader=UniqueKeyLoader)
    assert spec["openapi"] == "3.0.3"


def test_range_validation_rejection() -> None:
    with open("contracts/openapi.yaml", "r", encoding="utf-8") as f:
        spec = yaml.safe_load(f)

    invalid_req = {
        "signals": [
            {
                "signal_id": "1",
                "source": "VHAL",
                "signal_type": "vehicle.speed_kmh",
                "occurred_at": "2023-01-01T00:00:00Z",
                "value": {"value": 99999.0},  # Invalid speed > 350
                "quality": "VALID",
                "vehicle_id": "v1",
                "trip_id": "t1",
            }
        ]
    }

    with pytest.raises(ValidationError):
        validate_with_refs(invalid_req, "SignalBatchRequest", spec)


def test_fixture_generator_is_non_destructive_and_validates(
    tmp_path: Path,
) -> None:
    generated = generate_fixtures(tmp_path)
    assert {path.name for path in generated} == set(FIXTURES)

    with open("contracts/openapi.yaml", "r", encoding="utf-8") as file:
        spec = yaml.safe_load(file)
    schema_by_fixture = {
        "signals_request.json": "SignalBatchRequest",
        "signals_response.json": "SignalBatchResult",
        "state_snapshot.json": "StateSnapshot",
        "error_response.json": "ErrorEnvelope",
        "safety_response.json": "SafetyResponse",
        "safety_response_null.json": "SafetyResponse",
        "tool_call.json": "ToolCall",
        "tool_result.json": "ToolResult",
        "sos_status.json": "SOSStatus",
    }
    for fixture_name, schema_name in schema_by_fixture.items():
        fixture = json.loads((tmp_path / fixture_name).read_text(encoding="utf-8"))
        validate_with_refs(fixture, schema_name, spec)

    with pytest.raises(FileExistsError, match="target files already exist"):
        generate_fixtures(tmp_path)
