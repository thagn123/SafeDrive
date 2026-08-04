import datetime
from typing import Any

import pytest
from httpx import ASGITransport, AsyncClient
from pydantic import SecretStr

from app.core.config import Settings
from app.main import create_app

TEST_SECRET = "test-secret-api-key-12345"


def get_test_settings() -> Settings:
    return Settings(
        environment="test",
        active_profile="PRODUCTION_NO_DMS",
        safedrive_api_key=SecretStr(TEST_SECRET),
    )


def make_speed_signal(
    sig_id: str = "sig-speed-01",
    v_id: str = "veh-100",
    t_id: str = "trip-200",
    speed: float = 65.0,
    seq: int = 1,
    occurred_at: str | None = None,
) -> dict[str, Any]:
    if occurred_at is None:
        occurred_at = datetime.datetime.now(datetime.UTC).isoformat()
    return {
        "signal_id": sig_id,
        "source": "VHAL",
        "signal_type": "vehicle.speed_kmh",
        "occurred_at": occurred_at,
        "value": {"value": speed},
        "quality": "VALID",
        "vehicle_id": v_id,
        "trip_id": t_id,
        "sequence": seq,
    }


@pytest.mark.asyncio
async def test_post_signals_and_get_state_happy_path() -> None:
    app = create_app(settings=get_test_settings())
    headers = {
        "X-SafeDrive-Key": TEST_SECRET,
        "Idempotency-Key": "idem-happy-path-001",
    }
    sig = make_speed_signal()

    async with (
        app.router.lifespan_context(app),
        AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client,
    ):
        # 1. Post signals -> 202 Accepted
        res_post = await client.post("/api/v1/signals", headers=headers, json={"signals": [sig]})
        assert res_post.status_code == 202
        body_post = res_post.json()
        assert body_post["accepted"] == 1
        assert body_post["duplicate"] == 0
        assert body_post["quarantined"] == 0
        assert body_post["state_version"] == 1
        assert body_post["schema_version"] == "1.0"
        assert body_post["request_id"] == res_post.headers.get("X-Request-ID")

        # 2. Get state -> 200 OK
        res_state = await client.get(
            "/api/v1/state",
            headers={"X-SafeDrive-Key": TEST_SECRET},
            params={"vehicle_id": "veh-100", "trip_id": "trip-200"},
        )
        assert res_state.status_code == 200
        body_state = res_state.json()
        assert body_state["vehicle_id"] == "veh-100"
        assert body_state["trip_id"] == "trip-200"
        assert body_state["state_version"] == 1
        assert "cursors" not in body_state

        comp = body_state["components"]["vehicle.speed_kmh"]
        assert comp["value"]["value"] == 65.0
        assert comp["freshness"]["status"] in ("FRESH", "STALE")
        assert comp["freshness"]["source"] == "VHAL"


@pytest.mark.asyncio
async def test_authentication_scenarios() -> None:
    app = create_app(settings=get_test_settings())
    headers_no_key = {"Idempotency-Key": "idem-auth-01"}
    sig = make_speed_signal()

    async with (
        app.router.lifespan_context(app),
        AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client,
    ):
        # Missing key -> 401
        res1 = await client.post("/api/v1/signals", headers=headers_no_key, json={"signals": [sig]})
        assert res1.status_code == 401
        assert res1.json()["error"]["code"] == "AUTHENTICATION_REQUIRED"

        # Wrong key -> 401
        res2 = await client.post(
            "/api/v1/signals",
            headers={"X-SafeDrive-Key": "wrong-key", "Idempotency-Key": "idem-auth-01"},
            json={"signals": [sig]},
        )
        assert res2.status_code == 401
        assert res2.json()["error"]["code"] == "INVALID_API_KEY"

    # Server without safedrive_api_key -> 503
    no_auth_settings = Settings(
        environment="test",
        active_profile="PRODUCTION_NO_DMS",
        safedrive_api_key=None,
    )
    app_no_auth = create_app(settings=no_auth_settings)
    async with (
        app_no_auth.router.lifespan_context(app_no_auth),
        AsyncClient(
            transport=ASGITransport(app=app_no_auth), base_url="http://test"
        ) as client_no_auth,
    ):
        res3 = await client_no_auth.get(
            "/api/v1/state",
            headers={"X-SafeDrive-Key": "any-key"},
            params={"vehicle_id": "v1", "trip_id": "t1"},
        )
        assert res3.status_code == 503
        assert res3.json()["error"]["code"] == "AUTH_NOT_CONFIGURED"


@pytest.mark.asyncio
async def test_validation_and_partition_errors() -> None:
    app = create_app(settings=get_test_settings())
    headers = {
        "X-SafeDrive-Key": TEST_SECRET,
        "Idempotency-Key": "idem-valid-01",
    }

    async with (
        app.router.lifespan_context(app),
        AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client,
    ):
        # Empty signals batch -> 422
        res_empty = await client.post("/api/v1/signals", headers=headers, json={"signals": []})
        assert res_empty.status_code == 422

        # Mixed vehicle/trip partition -> 400 MIXED_SIGNAL_PARTITIONS
        sig1 = make_speed_signal(sig_id="sig-1", v_id="veh-A", t_id="trip-A")
        sig2 = make_speed_signal(sig_id="sig-2", v_id="veh-B", t_id="trip-A")
        res_mixed = await client.post(
            "/api/v1/signals", headers=headers, json={"signals": [sig1, sig2]}
        )
        assert res_mixed.status_code == 400
        assert res_mixed.json()["error"]["code"] == "MIXED_SIGNAL_PARTITIONS"

        # State not found -> 404
        res_404 = await client.get(
            "/api/v1/state",
            headers={"X-SafeDrive-Key": TEST_SECRET},
            params={"vehicle_id": "nonexistent-v", "trip_id": "nonexistent-t"},
        )
        assert res_404.status_code == 404
        assert res_404.json()["error"]["code"] == "STATE_NOT_FOUND"


@pytest.mark.asyncio
async def test_payload_too_large_rejection() -> None:
    app = create_app(settings=get_test_settings())
    headers = {
        "X-SafeDrive-Key": TEST_SECRET,
        "Idempotency-Key": "idem-payload-large-01",
    }

    # Generate oversized payload > 1 MiB via extra metadata
    big_metadata = {"padding": "x" * (1024 * 1024 + 100)}
    sig = make_speed_signal()
    sig["metadata"] = big_metadata

    async with (
        app.router.lifespan_context(app),
        AsyncClient(
            transport=ASGITransport(app=app, raise_app_exceptions=False),
            base_url="http://test",
        ) as client,
    ):
        res = await client.post("/api/v1/signals", headers=headers, json={"signals": [sig]})
        assert res.status_code == 413
        assert res.json()["error"]["code"] == "PAYLOAD_TOO_LARGE"
