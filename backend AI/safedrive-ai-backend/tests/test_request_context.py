import asyncio
import io
import json
import logging
import re

import pytest
from fastapi import APIRouter, FastAPI, Response
from httpx import ASGITransport, AsyncClient
from pydantic import BaseModel

from app.core.logging import JSONFormatter
from app.core.request_context import (
    extract_authoritative_request_id,
    get_request_id,
)
from app.core.services import ApplicationServices
from app.main import create_app

request_context_router = APIRouter()


class ValidationPayload(BaseModel):
    required_value: str


@request_context_router.post("/test/validation-dummy")
async def validation_dummy_endpoint(payload: ValidationPayload) -> dict[str, str]:
    return {"status": payload.required_value}


@request_context_router.get("/test/unhandled-exception")
async def unhandled_exception_endpoint() -> None:
    raise RuntimeError("Simulated unhandled exception for testing")


@request_context_router.get("/test/downstream-header-conflict")
async def downstream_header_conflict_endpoint(response: Response) -> dict[str, str]:
    response.headers["X-Request-ID"] = "downstream-wrong"
    return {"status": "ok"}


@request_context_router.get("/test/async-overlap")
async def async_overlap_endpoint() -> dict[str, str]:
    req_id = get_request_id() or "missing"
    await asyncio.sleep(0.01)
    return {"status": "ok", "captured_request_id": req_id}


def create_test_app() -> FastAPI:
    app = create_app()
    app.include_router(request_context_router)
    return app


def test_strict_incoming_request_id_extraction_unit() -> None:
    # 1. Non-ASCII bytes -> generated ID
    non_ascii_headers = [(b"x-request-id", "id_nön_ascii".encode())]
    res1 = extract_authoritative_request_id(non_ascii_headers)
    assert res1.startswith("req_")
    assert re.match(r"^[A-Za-z0-9._:-]{1,128}$", res1)

    # 2. Duplicate headers -> generated ID (ambiguous)
    dup_headers = [
        (b"x-request-id", b"valid-id-1"),
        (b"x-request-id", b"valid-id-2"),
    ]
    res2 = extract_authoritative_request_id(dup_headers)
    assert res2.startswith("req_")

    # 3. Leading/trailing whitespace -> generated ID (NOT trimmed!)
    ws_headers = [(b"x-request-id", b" valid_req-01 ")]
    res3 = extract_authoritative_request_id(ws_headers)
    assert res3.startswith("req_")
    assert res3 != "valid_req-01"

    # 4. Tab, CRLF, spaces, oversized
    for bad_bytes in [
        b"\tvalid_id",
        b"valid\r\nid",
        b"bad id spaces",
        b"a" * 129,
        b"<script>",
    ]:
        res = extract_authoritative_request_id([(b"x-request-id", bad_bytes)])
        assert res.startswith("req_")
        assert res != bad_bytes.decode("ascii", errors="ignore")

    # 5. Single valid ASCII ID preserved strictly
    valid_headers = [(b"x-request-id", b"exact-valid_id.123:XYZ")]
    assert extract_authoritative_request_id(valid_headers) == "exact-valid_id.123:XYZ"


@pytest.mark.asyncio
async def test_no_x_request_id_header_generates_matching_header_and_body() -> None:
    app = create_app()
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client:
        response = await client.get("/health")
        assert response.status_code == 200

        header_id = response.headers.get("X-Request-ID")
        assert header_id is not None
        assert header_id.startswith("req_")

        body = response.json()
        assert body["request_id"] == header_id
        assert body["schema_version"] == "1.0"
        assert "timestamp" in body

        # Context cleared outside request
        assert get_request_id() is None


@pytest.mark.asyncio
async def test_valid_incoming_caller_id_preserved_header_and_body() -> None:
    app = create_app()
    caller_id = "custom-client-trace-12345"
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client:
        response = await client.get("/health", headers={"X-Request-ID": caller_id})
        assert response.status_code == 200
        assert response.headers.get("X-Request-ID") == caller_id
        assert response.json()["request_id"] == caller_id
        assert get_request_id() is None


@pytest.mark.asyncio
async def test_invalid_caller_ids_via_http_transport_replaced() -> None:
    app = create_app()
    invalid_ids = [
        " valid_req-01 ",
        "\tvalid_id",
        "bad id with spaces",
        "a" * 130,
        "valid\r\nheader-injection",
    ]
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client:
        for bad_id in invalid_ids:
            response = await client.get("/health", headers={"X-Request-ID": bad_id})
            assert response.status_code == 200
            res_id = response.headers.get("X-Request-ID")
            assert res_id is not None
            assert res_id != bad_id
            assert res_id.startswith("req_")
            assert response.json()["request_id"] == res_id
            assert get_request_id() is None


@pytest.mark.asyncio
async def test_context_var_cleared_after_all_response_types() -> None:
    app = create_test_app()
    async with AsyncClient(
        transport=ASGITransport(app=app, raise_app_exceptions=False),
        base_url="http://test",
    ) as client:
        # 1. Success 200
        res200 = await client.get("/health")
        assert res200.status_code == 200
        assert get_request_id() is None

        # 2. 404 Error
        res404 = await client.get("/nonexistent-route-xyz")
        assert res404.status_code == 404
        assert get_request_id() is None

        # 3. 405 Error
        res405 = await client.post("/health")
        assert res405.status_code == 405
        assert get_request_id() is None

        # 4. 422 Validation Error
        res422 = await client.post("/test/validation-dummy", json={})
        assert res422.status_code == 422
        assert get_request_id() is None

        # 5. 500 Unhandled Exception Error
        res500 = await client.get("/test/unhandled-exception")
        assert res500.status_code == 500
        assert get_request_id() is None

    # 6. 503 Readiness Degradation Error (under failing lifespan)
    def failing_initializer() -> ApplicationServices:
        raise ValueError("Degraded state")

    app_failing = create_app(service_initializer=failing_initializer)
    async with (
        app_failing.router.lifespan_context(app_failing),
        AsyncClient(
            transport=ASGITransport(app=app_failing), base_url="http://test"
        ) as client_failing,
    ):
        res503 = await client_failing.get("/ready")
        assert res503.status_code == 503
        assert get_request_id() is None


@pytest.mark.asyncio
async def test_authoritative_x_request_id_overrides_downstream() -> None:
    app = create_test_app()
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client:
        response = await client.get(
            "/test/downstream-header-conflict",
            headers={"X-Request-ID": "upstream-authoritative-id"},
        )
        assert response.status_code == 200

        # Assert downstream-wrong was stripped and replaced with context request ID
        header_vals = [v for k, v in response.headers.raw if k.lower() == b"x-request-id"]
        assert len(header_vals) == 1
        assert header_vals[0].decode("ascii") == "upstream-authoritative-id"
        assert "downstream-wrong" not in response.headers.get("X-Request-ID", "")
        assert get_request_id() is None


@pytest.mark.asyncio
async def test_all_status_codes_include_single_authoritative_x_request_id_header_and_body_correlation() -> (
    None
):
    app = create_test_app()
    async with AsyncClient(
        transport=ASGITransport(app=app, raise_app_exceptions=False),
        base_url="http://test",
    ) as client:
        # 1. 200 OK
        r200 = await client.get("/health")
        assert r200.status_code == 200
        h200 = [v for k, v in r200.headers.raw if k.lower() == b"x-request-id"]
        assert len(h200) == 1
        h200_id = h200[0].decode("ascii")
        assert r200.json()["request_id"] == h200_id

        # 2. 404 Not Found
        r404 = await client.get("/nonexistent-route-xyz")
        assert r404.status_code == 404
        h404 = [v for k, v in r404.headers.raw if k.lower() == b"x-request-id"]
        assert len(h404) == 1
        h404_id = h404[0].decode("ascii")
        assert r404.json()["error"]["request_id"] == h404_id

        # 3. 405 Method Not Allowed
        r405 = await client.post("/health")
        assert r405.status_code == 405
        h405 = [v for k, v in r405.headers.raw if k.lower() == b"x-request-id"]
        assert len(h405) == 1
        h405_id = h405[0].decode("ascii")
        assert r405.json()["error"]["request_id"] == h405_id

        # 4. 422 Validation Error
        r422 = await client.post("/test/validation-dummy", json={})
        assert r422.status_code == 422
        h422 = [v for k, v in r422.headers.raw if k.lower() == b"x-request-id"]
        assert len(h422) == 1
        h422_id = h422[0].decode("ascii")
        assert r422.json()["error"]["request_id"] == h422_id

        # 5. 500 Internal Server Error
        r500 = await client.get("/test/unhandled-exception")
        assert r500.status_code == 500
        h500 = [v for k, v in r500.headers.raw if k.lower() == b"x-request-id"]
        assert len(h500) == 1
        h500_id = h500[0].decode("ascii")
        assert r500.json()["error"]["request_id"] == h500_id

    # 6. 503 Service Unavailable (Readiness Degradation)
    def failing_initializer() -> ApplicationServices:
        raise RuntimeError("Initialization failure")

    app_failing = create_app(service_initializer=failing_initializer)
    async with (
        app_failing.router.lifespan_context(app_failing),
        AsyncClient(
            transport=ASGITransport(app=app_failing), base_url="http://test"
        ) as client_failing,
    ):
        r503 = await client_failing.get("/ready")
        assert r503.status_code == 503
        h503 = [v for k, v in r503.headers.raw if k.lower() == b"x-request-id"]
        assert len(h503) == 1
        h503_id = h503[0].decode("ascii")
        assert r503.json()["request_id"] == h503_id


@pytest.mark.asyncio
async def test_strong_concurrency_isolation_and_completion_logs() -> None:
    app_logger = logging.getLogger("app")
    log_output = io.StringIO()
    stream_handler = logging.StreamHandler(log_output)
    stream_handler.setFormatter(JSONFormatter())
    app_logger.addHandler(stream_handler)

    try:
        app = create_test_app()

        async def make_concurrent_request(
            client: AsyncClient, idx: int
        ) -> tuple[str, str, str, str]:
            req_id = f"concurrent-trace-{idx:03d}"
            res = await client.get("/test/async-overlap", headers={"X-Request-ID": req_id})
            body = res.json()
            header_id = res.headers.get("X-Request-ID", "")
            captured_body_id = body.get("captured_request_id", "")
            return req_id, header_id, captured_body_id, res.text

        async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client:
            tasks = [make_concurrent_request(client, i) for i in range(35)]
            results = await asyncio.gather(*tasks)

        seen_ids: set[str] = set()
        for expected_id, header_id, captured_body_id, _ in results:
            assert expected_id == header_id
            assert expected_id == captured_body_id
            assert expected_id not in seen_ids
            seen_ids.add(expected_id)

        raw_log_lines = [line for line in log_output.getvalue().splitlines() if line.strip()]
        completion_records = [
            json.loads(line)
            for line in raw_log_lines
            if json.loads(line).get("event") == "request_completed"
            and json.loads(line).get("path") == "/test/async-overlap"
        ]

        assert len(completion_records) == 35
        log_req_ids = {rec["request_id"] for rec in completion_records}
        assert log_req_ids == seen_ids

    finally:
        app_logger.removeHandler(stream_handler)
