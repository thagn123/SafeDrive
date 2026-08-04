import httpx
import pytest

from app.api.schemas.mobile import VehicleState
from app.mobile.emergency_reasoner import EmergencyLLMReasoner


def vehicle_state(**overrides: object) -> VehicleState:
    base = {
        "speedKmh": 0.0,
        "engineTemperatureC": 90.0,
        "cabinTemperatureC": 28.0,
        "energyPercent": 60,
        "continuousDrivingMinutes": 90,
        "steeringLastInteractionSeconds": 5,
        "driverSeatOccupied": True,
        "wearableConnected": False,
        "activeDtcs": [],
        "crashDetected": True,
        "passengerResponse": "NO_RESPONSE",
        "updatedAtMs": 1_000,
    }
    base.update(overrides)
    return VehicleState(**base)


def fake_post_returning(content: object) -> object:
    async def fake_post(self: httpx.AsyncClient, *args: object, **kwargs: object) -> httpx.Response:
        return httpx.Response(
            200,
            json={"message": {"content": content}},
            request=httpx.Request("POST", "http://test/api/chat"),
        )

    return fake_post


@pytest.mark.asyncio
async def test_accepts_a_valid_open_candidate_verdict(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(
        httpx.AsyncClient,
        "post",
        fake_post_returning(
            '{"open_candidate": true, "reasoning": "Va chạm và không có phản hồi từ người trong xe."}'
        ),
    )
    reasoner = EmergencyLLMReasoner("http://127.0.0.1:11434", "test-model", 1.0)

    judgment = await reasoner.assess_candidate(
        state=vehicle_state(), evidence_codes=["crash_detected", "occupant_no_response"]
    )

    assert judgment is not None
    assert judgment.open_candidate is True
    assert "không có phản hồi" in judgment.reasoning


@pytest.mark.asyncio
async def test_accepts_a_veto_verdict(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(
        httpx.AsyncClient,
        "post",
        fake_post_returning(
            '{"open_candidate": false, "reasoning": "Xe đang dừng, có thể là tín hiệu nhiễu."}'
        ),
    )
    reasoner = EmergencyLLMReasoner("http://127.0.0.1:11434", "test-model", 1.0)

    judgment = await reasoner.assess_escalation(
        state=vehicle_state(), evidence_codes=["crash_detected"]
    )

    assert judgment is not None
    assert judgment.open_candidate is False


@pytest.mark.asyncio
async def test_rejects_non_vietnamese_reasoning(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(
        httpx.AsyncClient,
        "post",
        fake_post_returning('{"open_candidate": true, "reasoning": "Crash detected, escalate now."}'),
    )
    reasoner = EmergencyLLMReasoner("http://127.0.0.1:11434", "test-model", 1.0)

    assert await reasoner.assess_candidate(state=vehicle_state(), evidence_codes=[]) is None


@pytest.mark.asyncio
async def test_rejects_cjk_reasoning(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(
        httpx.AsyncClient,
        "post",
        fake_post_returning('{"open_candidate": true, "reasoning": "我建议您休息一下。"}'),
    )
    reasoner = EmergencyLLMReasoner("http://127.0.0.1:11434", "test-model", 1.0)

    assert await reasoner.assess_candidate(state=vehicle_state(), evidence_codes=[]) is None


@pytest.mark.asyncio
async def test_rejects_malformed_json(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(httpx.AsyncClient, "post", fake_post_returning("not json at all"))
    reasoner = EmergencyLLMReasoner("http://127.0.0.1:11434", "test-model", 1.0)

    assert await reasoner.assess_candidate(state=vehicle_state(), evidence_codes=[]) is None


@pytest.mark.asyncio
async def test_rejects_missing_open_candidate_field(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(
        httpx.AsyncClient,
        "post",
        fake_post_returning('{"reasoning": "Tôi cần thêm thông tin xác nhận."}'),
    )
    reasoner = EmergencyLLMReasoner("http://127.0.0.1:11434", "test-model", 1.0)

    assert await reasoner.assess_candidate(state=vehicle_state(), evidence_codes=[]) is None


@pytest.mark.asyncio
async def test_returns_none_on_http_error(monkeypatch: pytest.MonkeyPatch) -> None:
    async def fake_post(self: httpx.AsyncClient, *args: object, **kwargs: object) -> httpx.Response:
        raise httpx.ConnectTimeout("no route to host")

    monkeypatch.setattr(httpx.AsyncClient, "post", fake_post)
    reasoner = EmergencyLLMReasoner("http://127.0.0.1:11434", "test-model", 1.0)

    assert await reasoner.assess_candidate(state=vehicle_state(), evidence_codes=[]) is None


@pytest.mark.asyncio
async def test_sends_structured_context_never_raw_state(monkeypatch: pytest.MonkeyPatch) -> None:
    captured: dict[str, object] = {}

    async def fake_post(self: httpx.AsyncClient, *args: object, **kwargs: object) -> httpx.Response:
        captured.update(kwargs["json"])  # type: ignore[arg-type]
        return httpx.Response(
            200,
            json={"message": {"content": '{"open_candidate": true, "reasoning": "Tôi xác nhận có rủi ro."}'}},
            request=httpx.Request("POST", "http://test/api/chat"),
        )

    monkeypatch.setattr(httpx.AsyncClient, "post", fake_post)
    reasoner = EmergencyLLMReasoner("http://127.0.0.1:11434", "test-model", 1.0)

    await reasoner.assess_candidate(
        state=vehicle_state(speedKmh=12.5), evidence_codes=["crash_detected"]
    )

    prompt = captured["messages"][1]["content"]  # type: ignore[index]
    assert '"speedKmh":12.5' in prompt
    assert '"crashDetected":true' in prompt
    assert '"evidence":["crash_detected"]' in prompt
    assert captured["format"] == "json"
