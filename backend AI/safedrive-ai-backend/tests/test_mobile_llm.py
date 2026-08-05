import httpx
import pytest

from app.mobile.context import ContextPack, ContextValue
from app.mobile.llm import OllamaIntentClassifier, OllamaNarrator


def context_pack() -> ContextPack:
    return ContextPack(
        state_version=3,
        values=(
            ContextValue("vehicle.cabin_temperature_c", 31.0, "PHONE_SIMULATOR", 12, "FRESH"),
            ContextValue("vehicle.energy_percent", 18, "PHONE_SIMULATOR", 12, "FRESH"),
            ContextValue("trip.continuous_driving_minutes", 245, "PHONE_SIMULATOR", 12, "FRESH"),
        ),
        missing_context=("driver.wearable",),
        constraints=("Use only supplied structured values.",),
    )


def narration_kwargs() -> dict[str, object]:
    return {
        "user_text": "N\u00f3i chuy\u1ec7n v\u1edbi t\u00f4i",
        "approved_reply": "Cabin hi\u1ec7n \u1edf 31 \u0111\u1ed9 C, n\u0103ng l\u01b0\u1ee3ng 18%.",
        "context_pack": context_pack(),
        "risk_level": "MEDIUM",
        "risk_reasons": ("hot_cabin",),
        "allowed_actions": [
            {
                "type": "SET_HVAC_TEMPERATURE",
                "title": "\u0110\u1eb7t HVAC 24 \u0111\u1ed9 C",
                "requiresConfirmation": True,
                "hvacTargetTemperatureC": 24.0,
            }
        ],
    }


@pytest.mark.asyncio
async def test_ollama_narrator_accepts_short_vietnamese_rewrite(monkeypatch: pytest.MonkeyPatch) -> None:
    response = httpx.Response(
        200,
        json={
            "message": {
                "content": "Cabin \u0111ang 31 \u0111\u1ed9 C v\u00e0 n\u0103ng l\u01b0\u1ee3ng c\u00f2n 18%."
            }
        },
        request=httpx.Request("POST", "http://test/api/chat"),
    )

    async def fake_post(self: httpx.AsyncClient, *args: object, **kwargs: object) -> httpx.Response:
        return response

    monkeypatch.setattr(httpx.AsyncClient, "post", fake_post)
    narrator = OllamaNarrator("http://127.0.0.1:11434", "test-model", 1.0)

    assert await narrator.rewrite_grounded_reply(**narration_kwargs()) == (
        "Cabin \u0111ang 31 \u0111\u1ed9 C v\u00e0 n\u0103ng l\u01b0\u1ee3ng c\u00f2n 18%."
    )


@pytest.mark.asyncio
async def test_ollama_narrator_rejects_non_vietnamese_output(monkeypatch: pytest.MonkeyPatch) -> None:
    response = httpx.Response(
        200,
        json={"message": {"content": "\u6211\u5efa\u8bae\u60a8\u4f11\u606f\u4e00\u4e0b\u3002"}},
        request=httpx.Request("POST", "http://test/api/chat"),
    )

    async def fake_post(self: httpx.AsyncClient, *args: object, **kwargs: object) -> httpx.Response:
        return response

    monkeypatch.setattr(httpx.AsyncClient, "post", fake_post)
    narrator = OllamaNarrator("http://127.0.0.1:11434", "test-model", 1.0)

    assert await narrator.rewrite_grounded_reply(**narration_kwargs()) is None


@pytest.mark.asyncio
async def test_ollama_narrator_rejects_reply_that_drops_approved_vehicle_facts(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    response = httpx.Response(
        200,
        json={"message": {"content": "B\u1ea1n c\u1ea7n x\u00e1c nh\u1eadn thao t\u00e1c n\u00e0y."}},
        request=httpx.Request("POST", "http://test/api/chat"),
    )

    async def fake_post(self: httpx.AsyncClient, *args: object, **kwargs: object) -> httpx.Response:
        return response

    monkeypatch.setattr(httpx.AsyncClient, "post", fake_post)
    narrator = OllamaNarrator("http://127.0.0.1:11434", "test-model", 1.0)

    assert await narrator.rewrite_grounded_reply(**narration_kwargs()) is None


@pytest.mark.asyncio
async def test_ollama_narrator_receives_structured_vehicle_context(monkeypatch: pytest.MonkeyPatch) -> None:
    captured: dict[str, object] = {}
    response = httpx.Response(
        200,
        json={"message": {"content": "Cabin \u0111ang 31 \u0111\u1ed9 C v\u00e0 n\u0103ng l\u01b0\u1ee3ng c\u00f2n 18%."}},
        request=httpx.Request("POST", "http://test/api/chat"),
    )

    async def fake_post(self: httpx.AsyncClient, *args: object, **kwargs: object) -> httpx.Response:
        captured.update(kwargs["json"])
        return response

    monkeypatch.setattr(httpx.AsyncClient, "post", fake_post)
    narrator = OllamaNarrator("http://127.0.0.1:11434", "test-model", 1.0)

    result = await narrator.rewrite_grounded_reply(**narration_kwargs())

    assert result == "Cabin \u0111ang 31 \u0111\u1ed9 C v\u00e0 n\u0103ng l\u01b0\u1ee3ng c\u00f2n 18%."
    prompt = captured["messages"][1]["content"]  # type: ignore[index]
    assert '"name":"vehicle.cabin_temperature_c","value":31.0' in prompt
    assert '"name":"vehicle.energy_percent","value":18' in prompt
    assert '"risk":{"level":"MEDIUM","reasonCodes":["hot_cabin"]}' in prompt
    assert '"type":"SET_HVAC_TEMPERATURE"' in prompt


@pytest.mark.asyncio
async def test_ollama_narrator_returns_none_on_timeout(monkeypatch: pytest.MonkeyPatch) -> None:
    async def fake_post(self: httpx.AsyncClient, *args: object, **kwargs: object) -> httpx.Response:
        raise httpx.ConnectTimeout("no route to host")

    monkeypatch.setattr(httpx.AsyncClient, "post", fake_post)
    narrator = OllamaNarrator("http://127.0.0.1:11434", "test-model", 1.0)

    assert await narrator.rewrite_grounded_reply(**narration_kwargs()) is None


@pytest.mark.asyncio
async def test_ollama_narrator_returns_none_on_empty_content(monkeypatch: pytest.MonkeyPatch) -> None:
    response = httpx.Response(
        200,
        json={"message": {"content": ""}},
        request=httpx.Request("POST", "http://test/api/chat"),
    )

    async def fake_post(self: httpx.AsyncClient, *args: object, **kwargs: object) -> httpx.Response:
        return response

    monkeypatch.setattr(httpx.AsyncClient, "post", fake_post)
    narrator = OllamaNarrator("http://127.0.0.1:11434", "test-model", 1.0)

    assert await narrator.rewrite_grounded_reply(**narration_kwargs()) is None


@pytest.mark.asyncio
async def test_ollama_narrator_sends_the_deterministic_tone_for_the_given_risk_level(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """The model is told which tone to use (never picks one itself) -- see
    _TONE_BY_RISK. This proves the mapping is wired into the actual prompt sent, not
    just defined in isolation."""

    captured: dict[str, object] = {}
    response = httpx.Response(
        200,
        json={"message": {"content": "Cabin đang 31 độ C và năng lượng còn 18%."}},
        request=httpx.Request("POST", "http://test/api/chat"),
    )

    async def fake_post(self: httpx.AsyncClient, *args: object, **kwargs: object) -> httpx.Response:
        captured.update(kwargs["json"])
        return response

    monkeypatch.setattr(httpx.AsyncClient, "post", fake_post)
    narrator = OllamaNarrator("http://127.0.0.1:11434", "test-model", 1.0)

    for risk_level, expected_tone in (("LOW", "calm"), ("MEDIUM", "cautious"), ("HIGH", "direct")):
        kwargs = narration_kwargs()
        kwargs["risk_level"] = risk_level
        await narrator.rewrite_grounded_reply(**kwargs)
        prompt = captured["messages"][1]["content"]  # type: ignore[index]
        assert f"TONE:\n{expected_tone}" in prompt


@pytest.mark.asyncio
async def test_ollama_narrator_rejects_reply_that_drops_a_required_verbatim_snippet(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """The number-preservation guardrail alone can't see a DTC code (its digits are
    preceded by a letter, invisible to _NUMBER's regex) or a non-numeric directive
    clause -- required_verbatim_snippets closes that gap for the routes that need it
    (see ContextAwareAssistant.required_narration_snippets)."""

    response = httpx.Response(
        200,
        json={"message": {"content": "Cabin đang 31 độ C và năng lượng còn 18%."}},
        request=httpx.Request("POST", "http://test/api/chat"),
    )

    async def fake_post(self: httpx.AsyncClient, *args: object, **kwargs: object) -> httpx.Response:
        return response

    monkeypatch.setattr(httpx.AsyncClient, "post", fake_post)
    narrator = OllamaNarrator("http://127.0.0.1:11434", "test-model", 1.0)

    result = await narrator.rewrite_grounded_reply(
        **narration_kwargs(),
        required_verbatim_snippets=("U0100", "Không nên tiếp tục hành trình dài"),
    )

    assert result is None


@pytest.mark.asyncio
async def test_ollama_narrator_accepts_reply_that_preserves_required_verbatim_snippets(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    response = httpx.Response(
        200,
        json={
            "message": {
                "content": (
                    "Xe đang có mã U0100, mức độ trung bình. Cabin đang 31 độ C và năng lượng còn 18%. "
                    "Bạn có thể tiếp tục lái thận trọng."
                )
            }
        },
        request=httpx.Request("POST", "http://test/api/chat"),
    )

    async def fake_post(self: httpx.AsyncClient, *args: object, **kwargs: object) -> httpx.Response:
        return response

    monkeypatch.setattr(httpx.AsyncClient, "post", fake_post)
    narrator = OllamaNarrator("http://127.0.0.1:11434", "test-model", 1.0)

    result = await narrator.rewrite_grounded_reply(
        **narration_kwargs(), required_verbatim_snippets=("U0100",)
    )

    assert result is not None
    assert "U0100" in result


def open_query_kwargs() -> dict[str, object]:
    return {
        "user_text": "Các thông tin về xe",
        "deterministic_fallback": (
            "Tôi là trợ lý an toàn khi lái xe, câu hỏi này có thể "
            "nằm ngoài phạm vi hỗ trợ của tôi. Tôi có thể giúp "
            "về tình trạng xe, cabin, cảnh báo lỗi hoặc nhu cầu nghỉ ngơi."
        ),
        "context_pack": context_pack(),
        "risk_level": "LOW",
        "risk_reasons": (),
    }


@pytest.mark.asyncio
async def test_answer_open_query_accepts_a_vehicle_related_answer_grounded_in_context(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    response = httpx.Response(
        200,
        json={"message": {"content": "Xe của bạn còn 18% năng lượng."}},
        request=httpx.Request("POST", "http://test/api/chat"),
    )

    async def fake_post(self: httpx.AsyncClient, *args: object, **kwargs: object) -> httpx.Response:
        return response

    monkeypatch.setattr(httpx.AsyncClient, "post", fake_post)
    narrator = OllamaNarrator("http://127.0.0.1:11434", "test-model", 1.0)

    result = await narrator.answer_open_query(**open_query_kwargs())

    assert result == "Xe của bạn còn 18% năng lượng."


@pytest.mark.asyncio
async def test_answer_open_query_rejects_a_hallucinated_number_not_in_context(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    response = httpx.Response(
        200,
        json={"message": {"content": "Xe của bạn còn 500 km trước khi hết pin."}},
        request=httpx.Request("POST", "http://test/api/chat"),
    )

    async def fake_post(self: httpx.AsyncClient, *args: object, **kwargs: object) -> httpx.Response:
        return response

    monkeypatch.setattr(httpx.AsyncClient, "post", fake_post)
    narrator = OllamaNarrator("http://127.0.0.1:11434", "test-model", 1.0)

    assert await narrator.answer_open_query(**open_query_kwargs()) is None


@pytest.mark.asyncio
async def test_answer_open_query_accepts_an_honest_off_topic_redirect(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    response = httpx.Response(
        200,
        json={
            "message": {
                "content": (
                    "Tôi là trợ lý an toàn khi lái xe nên không thể trả lời câu hỏi này. "
                    "Bạn có muốn hỏi về tình trạng xe không?"
                )
            }
        },
        request=httpx.Request("POST", "http://test/api/chat"),
    )

    async def fake_post(self: httpx.AsyncClient, *args: object, **kwargs: object) -> httpx.Response:
        return response

    monkeypatch.setattr(httpx.AsyncClient, "post", fake_post)
    narrator = OllamaNarrator("http://127.0.0.1:11434", "test-model", 1.0)

    kwargs = open_query_kwargs()
    kwargs["user_text"] = "1+1 bằng mấy"
    result = await narrator.answer_open_query(**kwargs)

    assert result is not None
    assert "xe" in result.lower()


@pytest.mark.asyncio
async def test_answer_open_query_returns_none_on_timeout(monkeypatch: pytest.MonkeyPatch) -> None:
    async def fake_post(self: httpx.AsyncClient, *args: object, **kwargs: object) -> httpx.Response:
        raise httpx.ConnectTimeout("no route to host")

    monkeypatch.setattr(httpx.AsyncClient, "post", fake_post)
    narrator = OllamaNarrator("http://127.0.0.1:11434", "test-model", 1.0)

    assert await narrator.answer_open_query(**open_query_kwargs()) is None


@pytest.mark.asyncio
async def test_answer_open_query_sends_user_message_and_deterministic_fallback_distinctly(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """Unlike rewrite_grounded_reply's APPROVED_REPLY (a rewrite target), the open-query
    prompt's DETERMINISTIC_FALLBACK is only a redirect-shape example -- both must reach
    the model, but the system prompt (not exercised by this test) is what tells it which
    role each plays."""

    captured: dict[str, object] = {}
    response = httpx.Response(
        200,
        json={"message": {"content": "Xe của bạn còn 18% năng lượng."}},
        request=httpx.Request("POST", "http://test/api/chat"),
    )

    async def fake_post(self: httpx.AsyncClient, *args: object, **kwargs: object) -> httpx.Response:
        captured.update(kwargs["json"])
        return response

    monkeypatch.setattr(httpx.AsyncClient, "post", fake_post)
    narrator = OllamaNarrator("http://127.0.0.1:11434", "test-model", 1.0)

    kwargs = open_query_kwargs()
    await narrator.answer_open_query(**kwargs)

    prompt = captured["messages"][1]["content"]  # type: ignore[index]
    assert f"USER_MESSAGE:\n{kwargs['user_text']}" in prompt
    assert f"DETERMINISTIC_FALLBACK:\n{kwargs['deterministic_fallback']}" in prompt


def classify_kwargs() -> dict[str, object]:
    return {
        "user_text": "Xe c\u00f3 v\u1ea5n \u0111\u1ec1 g\u00ec kh\u00f4ng nh\u1ec9",
        "context_pack": context_pack(),
        "risk_level": "MEDIUM",
        "risk_reasons": ("hot_cabin",),
    }


def _fake_chat(content: str | None, *, raises: bool = False):
    response = httpx.Response(
        200,
        json={"message": {"content": content}},
        request=httpx.Request("POST", "http://test/api/chat"),
    )

    async def fake_post(self: httpx.AsyncClient, *args: object, **kwargs: object) -> httpx.Response:
        if raises:
            raise httpx.ConnectTimeout("no route to host")
        return response

    return fake_post


@pytest.mark.asyncio
async def test_ollama_intent_classifier_accepts_a_valid_label(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(httpx.AsyncClient, "post", _fake_chat("vehicle.fault_concern"))
    classifier = OllamaIntentClassifier("http://127.0.0.1:11434", "test-model", 1.0)

    assert await classifier.classify(**classify_kwargs()) == "vehicle.fault_concern"


@pytest.mark.asyncio
async def test_ollama_intent_classifier_tolerates_stray_punctuation_and_whitespace(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setattr(httpx.AsyncClient, "post", _fake_chat("  companion.conversation.\n"))
    classifier = OllamaIntentClassifier("http://127.0.0.1:11434", "test-model", 1.0)

    assert await classifier.classify(**classify_kwargs()) == "companion.conversation"


@pytest.mark.asyncio
async def test_ollama_intent_classifier_rejects_text_outside_the_closed_label_set(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setattr(httpx.AsyncClient, "post", _fake_chat("climate.set_temperature"))
    classifier = OllamaIntentClassifier("http://127.0.0.1:11434", "test-model", 1.0)

    assert await classifier.classify(**classify_kwargs()) is None


@pytest.mark.asyncio
async def test_ollama_intent_classifier_rejects_a_sentence_that_is_not_an_exact_label(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setattr(
        httpx.AsyncClient, "post", _fake_chat("I think this is about vehicle.fault_concern maybe")
    )
    classifier = OllamaIntentClassifier("http://127.0.0.1:11434", "test-model", 1.0)

    assert await classifier.classify(**classify_kwargs()) is None


@pytest.mark.asyncio
async def test_ollama_intent_classifier_returns_none_on_timeout(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(httpx.AsyncClient, "post", _fake_chat(None, raises=True))
    classifier = OllamaIntentClassifier("http://127.0.0.1:11434", "test-model", 1.0)

    assert await classifier.classify(**classify_kwargs()) is None


@pytest.mark.asyncio
async def test_ollama_intent_classifier_receives_structured_context_not_free_text(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    captured: dict[str, object] = {}

    async def fake_post(self: httpx.AsyncClient, *args: object, **kwargs: object) -> httpx.Response:
        captured.update(kwargs["json"])
        return httpx.Response(
            200,
            json={"message": {"content": "assistant.vehicle_status"}},
            request=httpx.Request("POST", "http://test/api/chat"),
        )

    monkeypatch.setattr(httpx.AsyncClient, "post", fake_post)
    classifier = OllamaIntentClassifier("http://127.0.0.1:11434", "test-model", 1.0)

    result = await classifier.classify(**classify_kwargs())

    assert result == "assistant.vehicle_status"
    prompt = captured["messages"][1]["content"]  # type: ignore[index]
    assert '"name":"vehicle.cabin_temperature_c","value":31.0' in prompt
    assert '"risk":{"level":"MEDIUM","reasonCodes":["hot_cabin"]}' in prompt
