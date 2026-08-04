"""Generate contract fixtures into an explicit, non-destructive destination."""

import argparse
import json
from pathlib import Path
from typing import Any

FIXTURES: dict[str, dict[str, Any]] = {
    "signals_request.json": {
        "signals": [
            {
                "signal_id": "sig-001",
                "source": "VHAL",
                "signal_type": "vehicle.speed_kmh",
                "occurred_at": "2026-07-29T10:00:00Z",
                "value": {"value": 120.5},
                "quality": "VALID",
                "vehicle_id": "veh-123",
                "trip_id": "trip-456",
            }
        ]
    },
    "signals_response.json": {
        "request_id": "req_01",
        "timestamp": "2026-07-29T10:00:00Z",
        "schema_version": "1.0",
        "accepted": 1,
        "duplicate": 0,
        "quarantined": 0,
        "state_version": 1,
    },
    "state_snapshot.json": {
        "request_id": "req_01",
        "timestamp": "2026-07-29T10:00:00Z",
        "schema_version": "1.0",
        "vehicle_id": "v1",
        "trip_id": "t1",
        "state_version": 1,
        "components": {
            "vehicle.speed_kmh": {
                "signal_type": "vehicle.speed_kmh",
                "value": {"value": 100.5},
                "updated_at": "2026-07-29T09:59:59Z",
                "sequence": 1,
                "source": "VHAL",
                "freshness": {
                    "age_ms": 100,
                    "status": "FRESH",
                    "source": "VHAL",
                },
            }
        },
    },
    "error_response.json": {
        "error": {
            "code": "STALE_REQUIRED_STATE",
            "message": "Data is stale.",
            "details": {},
            "request_id": "req-123",
            "timestamp": "2026-07-29T10:00:00Z",
            "schema_version": "1.0",
        }
    },
    "safety_response.json": {
        "request_id": "req-123",
        "message": "Safe driving!",
        "intent": "GREETING",
        "requires_confirmation": False,
        "fallback_used": False,
        "state_version": 42,
    },
    "safety_response_null.json": {
        "request_id": "req-123",
        "message": "No risk detected",
        "intent": "GREETING",
        "requires_confirmation": False,
        "fallback_used": False,
        "state_version": 1,
    },
    "tool_call.json": {
        "tool_call_id": "tool-123",
        "tool_name": "SOS_trigger",
        "arguments": {"reason": "Crash detected"},
        "request_id": "req-123",
        "idempotency_key": "idemp-123",
    },
    "tool_result.json": {
        "tool_call_id": "tool-123",
        "status": "SUCCEEDED",
        "output": {"message": "SOS confirmed"},
        "executed_at": "2026-07-29T10:00:00Z",
        "latency_ms": 150,
    },
    "sos_status.json": {
        "status": "WAITING_FOR_CONFIRMATION",
        "updated_at": "2026-07-29T10:00:00Z",
        "remaining_ms": 10000,
    },
}


def generate_fixtures(output_dir: Path, *, overwrite: bool = False) -> list[Path]:
    output_dir.mkdir(parents=True, exist_ok=True)
    targets = [output_dir / filename for filename in FIXTURES]
    existing = [path for path in targets if path.exists()]
    if existing and not overwrite:
        raise FileExistsError("Fixture generation refused because target files already exist")

    for path, fixture in zip(targets, FIXTURES.values(), strict=True):
        path.write_text(
            json.dumps(fixture, indent=2, ensure_ascii=False) + "\n",
            encoding="utf-8",
        )
    return targets


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--output-dir",
        type=Path,
        required=True,
        help="Destination directory; existing fixture files are preserved by default.",
    )
    parser.add_argument(
        "--overwrite",
        action="store_true",
        help="Explicitly allow replacement of generated fixture files.",
    )
    args = parser.parse_args()
    generate_fixtures(args.output_dir, overwrite=args.overwrite)


if __name__ == "__main__":
    main()
