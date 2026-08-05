import httpx
import pytest

from app.mobile.context import ContextPack, ContextValue
from app.mobile.llm import (
    GeminiNarrator,
    OllamaIntentClassifier,
    OllamaNarrator,
    VertexAINarrator,
    _looks_vietnamese_enough,
)


def test_looks_vietnamese_enough_accepts_diacritic_text() -> None:
    assert _looks_vietnamese_enough("Xe bạn đang chạy ở tốc độ bình thường.") is True


def test_looks_vietnamese_enough_rejects_pure_english() -> None:
    assert _looks_vietnamese_enough("Your car is fine, drive safely and enjoy the trip.") is False


def test_looks_vietnamese_enough_rejects_cjk() -> None:
    assert _looks_vietnamese_enough("我建议您休息一下。") is False


def test_looks_vietnamese_enough_accepts_a_dtc_code_mixed_into_vietnamese() -> None:
    assert _looks_vietnamese_enough("Mã U0100 đang hoạt động, mức độ trung bình.") is True


def test_looks_vietnamese_enough_rejects_empty_text() -> None:
    assert _looks_vietnamese_enough("   ") is False


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
async def test_ollama_narrator_rejects_english_heavy_output_when_vietnamese_required(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """vi-VN is the required locale; a fluent but entirely English reply must be
    rejected the same way a Chinese one is, even though it contains no CJK
    characters at all -- the old guardrail only checked for CJK plus presence of one
    of nine hardcoded Vietnamese words, which a genuine English sentence could pass
    only by accident. Both attempts return English here, so the one retry is also
    rejected and the caller falls back to the deterministic reply."""

    response = httpx.Response(
        200,
        json={"message": {"content": "Your car is running fine at a safe temperature."}},
        request=httpx.Request("POST", "http://test/api/chat"),
    )

    async def fake_post(self: httpx.AsyncClient, *args: object, **kwargs: object) -> httpx.Response:
        return response

    monkeypatch.setattr(httpx.AsyncClient, "post", fake_post)
    narrator = OllamaNarrator("http://127.0.0.1:11434", "test-model", 1.0)

    assert await narrator.rewrite_grounded_reply(**narration_kwargs()) is None


@pytest.mark.asyncio
async def test_ollama_narrator_dtc_code_does_not_cause_a_false_language_rejection(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """A DTC code or technical abbreviation carries no Vietnamese diacritics on its
    own, but must not make an otherwise-clearly-Vietnamese reply fail the language
    check just because it's mentioned."""

    kwargs = narration_kwargs()
    kwargs["approved_reply"] = "M\u00e3 U0100 hi\u1ec7n \u0111ang ho\u1ea1t \u0111\u1ed9ng."
    response = httpx.Response(
        200,
        json={
            "message": {
                "content": "M\u00e3 U0100 hi\u1ec7n \u0111ang ho\u1ea1t \u0111\u1ed9ng, m\u1ee9c \u0111\u1ed9 trung b\u00ecnh, b\u1ea1n n\u00ean ch\u00fa \u00fd."
            }
        },
        request=httpx.Request("POST", "http://test/api/chat"),
    )

    async def fake_post(self: httpx.AsyncClient, *args: object, **kwargs: object) -> httpx.Response:
        return response

    monkeypatch.setattr(httpx.AsyncClient, "post", fake_post)
    narrator = OllamaNarrator("http://127.0.0.1:11434", "test-model", 1.0)

    result = await narrator.rewrite_grounded_reply(**kwargs)

    assert result is not None
    assert "U0100" in result


@pytest.mark.asyncio
async def test_ollama_narrator_retries_once_and_accepts_a_vietnamese_retry_after_a_non_vietnamese_first_attempt(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """The first attempt comes back Chinese; per spec this must trigger exactly one
    retry with a stricter Vietnamese instruction, and the retry's clean Vietnamese
    text must be what's ultimately accepted."""

    kwargs = narration_kwargs()
    kwargs["approved_reply"] = "B\u1ea1n c\u00f3 th\u1ec3 ti\u1ebfp t\u1ee5c l\u00e1i xe b\u00ecnh th\u01b0\u1eddng."
    calls: list[dict[str, object]] = []
    chinese = httpx.Response(
        200,
        json={"message": {"content": "\u6211\u5efa\u8bae\u60a8\u4f11\u606f\u4e00\u4e0b\u3002"}},
        request=httpx.Request("POST", "http://test/api/chat"),
    )
    vietnamese = httpx.Response(
        200,
        json={"message": {"content": "B\u1ea1n n\u00ean ngh\u1ec9 ng\u01a1i m\u1ed9t ch\u00fat cho t\u1ec9nh t\u00e1o."}},
        request=httpx.Request("POST", "http://test/api/chat"),
    )

    async def fake_post(self: httpx.AsyncClient, *args: object, **kwargs: object) -> httpx.Response:
        calls.append(kwargs.get("json", {}))
        return chinese if len(calls) == 1 else vietnamese

    monkeypatch.setattr(httpx.AsyncClient, "post", fake_post)
    narrator = OllamaNarrator("http://127.0.0.1:11434", "test-model", 1.0)

    result = await narrator.rewrite_grounded_reply(**kwargs)

    assert len(calls) == 2
    assert result is not None
    assert "ngh\u1ec9" in result
    # The retry call carries an extra strict-Vietnamese system message the first call didn't.
    assert len(calls[1]["messages"]) == len(calls[0]["messages"]) + 1  # type: ignore[arg-type]


@pytest.mark.asyncio
async def test_ollama_narrator_retries_at_most_once_when_both_attempts_stay_non_vietnamese(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """If the retry also comes back non-Vietnamese, the caller must fall back to the
    deterministic reply rather than retrying indefinitely -- exactly two model calls
    total, never more."""

    calls: list[object] = []
    chinese = httpx.Response(
        200,
        json={"message": {"content": "\u6211\u5efa\u8bae\u60a8\u4f11\u606f\u4e00\u4e0b\u3002"}},
        request=httpx.Request("POST", "http://test/api/chat"),
    )

    async def fake_post(self: httpx.AsyncClient, *args: object, **kwargs: object) -> httpx.Response:
        calls.append(object())
        return chinese

    monkeypatch.setattr(httpx.AsyncClient, "post", fake_post)
    narrator = OllamaNarrator("http://127.0.0.1:11434", "test-model", 1.0)

    result = await narrator.rewrite_grounded_reply(**narration_kwargs())

    assert result is None
    assert len(calls) == 2


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
async def test_ollama_narrator_accepts_a_grounded_float_context_value_written_as_a_whole_number(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """GROUNDED_CONTEXT_JSON serializes vehicle.cabin_temperature_c as 31.0, but the
    model naturally drops the redundant decimal when it names a whole-number reading
    pulled fresh from context ("31 do C") -- something the system prompt explicitly
    encourages ("weave in a relevant real value... instead of sounding like a canned
    line"). Live testing found this silently rejected every such reply as if the model
    had invented the number, because "31" and "31.0" compared unequal as raw strings."""

    kwargs = narration_kwargs()
    kwargs["approved_reply"] = "Bạn có thể tiếp tục lái xe bình thường."
    response = httpx.Response(
        200,
        json={
            "message": {
                "content": "Xe bạn đang ở 31 độ C, vẫn trong ngưỡng an toàn."
            }
        },
        request=httpx.Request("POST", "http://test/api/chat"),
    )

    async def fake_post(self: httpx.AsyncClient, *args: object, **kwargs: object) -> httpx.Response:
        return response

    monkeypatch.setattr(httpx.AsyncClient, "post", fake_post)
    narrator = OllamaNarrator("http://127.0.0.1:11434", "test-model", 1.0)

    result = await narrator.rewrite_grounded_reply(**kwargs)

    assert result is not None
    assert "31" in result


@pytest.mark.asyncio
async def test_ollama_narrator_accepts_a_new_context_number_with_its_correct_unit(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """context_pack() has trip.continuous_driving_minutes=245, not mentioned in
    approved_reply. The model citing it fresh, with the correct MINUTES unit, must be
    accepted -- this is the positive case the semantic-unit check must not break."""

    kwargs = narration_kwargs()
    kwargs["approved_reply"] = "Bạn có thể tiếp tục lái xe bình thường."
    response = httpx.Response(
        200,
        json={"message": {"content": "Bạn đã lái liên tục 245 phút, nên cân nhắc nghỉ ngơi."}},
        request=httpx.Request("POST", "http://test/api/chat"),
    )

    async def fake_post(self: httpx.AsyncClient, *args: object, **kwargs: object) -> httpx.Response:
        return response

    monkeypatch.setattr(httpx.AsyncClient, "post", fake_post)
    narrator = OllamaNarrator("http://127.0.0.1:11434", "test-model", 1.0)

    result = await narrator.rewrite_grounded_reply(**kwargs)

    assert result is not None
    assert "245" in result


@pytest.mark.asyncio
async def test_ollama_narrator_rejects_a_value_grounded_in_the_wrong_semantic_field(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """context_pack() has trip.continuous_driving_minutes=245 (MINUTES) but no
    *_percent field equal to 245. A reply claiming "245%" must be rejected even though
    245 genuinely appears in context -- under a different field and unit entirely.
    This is the exact speed-must-not-ground-battery failure mode."""

    kwargs = narration_kwargs()
    kwargs["approved_reply"] = "Bạn có thể tiếp tục lái xe bình thường."
    response = httpx.Response(
        200,
        json={"message": {"content": "Xe bạn còn 245% năng lượng, rất an toàn."}},
        request=httpx.Request("POST", "http://test/api/chat"),
    )

    async def fake_post(self: httpx.AsyncClient, *args: object, **kwargs: object) -> httpx.Response:
        return response

    monkeypatch.setattr(httpx.AsyncClient, "post", fake_post)
    narrator = OllamaNarrator("http://127.0.0.1:11434", "test-model", 1.0)

    assert await narrator.rewrite_grounded_reply(**kwargs) is None


@pytest.mark.asyncio
async def test_ollama_narrator_rejects_a_context_value_written_with_the_wrong_unit(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """context_pack() has vehicle.energy_percent=18 (PERCENT) but no *_minutes field
    equal to 18. A reply claiming "18 phút" must be rejected -- the value exists in
    context, but never under a MINUTES unit."""

    kwargs = narration_kwargs()
    kwargs["approved_reply"] = "Bạn có thể tiếp tục lái xe bình thường."
    response = httpx.Response(
        200,
        json={"message": {"content": "Bạn đã lái liên tục 18 phút rồi, hãy chú ý."}},
        request=httpx.Request("POST", "http://test/api/chat"),
    )

    async def fake_post(self: httpx.AsyncClient, *args: object, **kwargs: object) -> httpx.Response:
        return response

    monkeypatch.setattr(httpx.AsyncClient, "post", fake_post)
    narrator = OllamaNarrator("http://127.0.0.1:11434", "test-model", 1.0)

    assert await narrator.rewrite_grounded_reply(**kwargs) is None


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
async def test_answer_open_query_accepts_a_grounded_float_context_value_written_as_a_whole_number(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """Reproduces a live bug found manually asking "xe của tôi thế nào": the model
    correctly grounded its answer in vehicle.cabin_temperature_c (31.0 in context) but
    wrote it naturally as "31 độ C". Before the number-token normalization fix, "31"
    and "31.0" compared unequal and the whole reply was rejected back to the generic
    deterministic_fallback -- exactly the "rigid, doesn't understand my question"
    behavior reported against a genuinely in-scope, well-grounded question."""

    response = httpx.Response(
        200,
        json={
            "message": {
                "content": "Xe của bạn đang ở 31 độ C trong cabin, năng lượng còn 18%."
            }
        },
        request=httpx.Request("POST", "http://test/api/chat"),
    )

    async def fake_post(self: httpx.AsyncClient, *args: object, **kwargs: object) -> httpx.Response:
        return response

    monkeypatch.setattr(httpx.AsyncClient, "post", fake_post)
    narrator = OllamaNarrator("http://127.0.0.1:11434", "test-model", 1.0)

    result = await narrator.answer_open_query(**open_query_kwargs())

    assert result is not None
    assert "31" in result


@pytest.mark.asyncio
async def test_answer_open_query_does_not_require_repeating_every_fallback_number(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """assistant.general's deterministic_fallback is now a real, number-bearing vehicle
    summary (speed/cabin/energy -- see ContextAwareAssistant). answer_open_query must
    not force the model's genuinely different free-form answer to mechanically repeat
    every one of those numbers -- only rewrite_grounded_reply's fixed-decision-
    preservation path needs that; a free-form answer may legitimately talk about a
    different subset of facts than the fallback example happens to mention."""

    kwargs = open_query_kwargs()
    kwargs["deterministic_fallback"] = (
        "Dữ liệu hiện tại cho thấy xe đang chạy 60 km/h, nhiệt độ cabin 25 độ C và mức "
        "năng lượng còn 18%. Câu hỏi này có thể nằm ngoài phạm vi hỗ trợ của tôi."
    )
    response = httpx.Response(
        200,
        json={"message": {"content": "Xe của bạn còn 18% năng lượng."}},
        request=httpx.Request("POST", "http://test/api/chat"),
    )

    async def fake_post(self: httpx.AsyncClient, *args: object, **kwargs: object) -> httpx.Response:
        return response

    monkeypatch.setattr(httpx.AsyncClient, "post", fake_post)
    narrator = OllamaNarrator("http://127.0.0.1:11434", "test-model", 1.0)

    result = await narrator.answer_open_query(**kwargs)

    assert result == "Xe của bạn còn 18% năng lượng."


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


@pytest.mark.asyncio
async def test_gemini_narrator_accepts_valid_grounded_rewrite(monkeypatch: pytest.MonkeyPatch) -> None:
    async def fake_post(self: httpx.AsyncClient, url: str, *args: object, **kwargs: object) -> httpx.Response:
        assert "generativelanguage.googleapis.com" in url
        return httpx.Response(
            200,
            json={"candidates": [{"content": {"parts": [{"text": "Cabin hiện ở 31 độ C, năng lượng 18%."}]}}]},
            request=httpx.Request("POST", url),
        )

    monkeypatch.setattr(httpx.AsyncClient, "post", fake_post)
    narrator = GeminiNarrator(api_key="test-api-key", model="gemini-2.0-flash")

    result = await narrator.rewrite_grounded_reply(**narration_kwargs())
    assert result == "Cabin hiện ở 31 độ C, năng lượng 18%."


@pytest.mark.asyncio
async def test_gemini_narrator_returns_none_on_http_error(monkeypatch: pytest.MonkeyPatch) -> None:
    async def fake_post(self: httpx.AsyncClient, url: str, *args: object, **kwargs: object) -> httpx.Response:
        return httpx.Response(403, json={"error": "Permission Denied"}, request=httpx.Request("POST", url))

    monkeypatch.setattr(httpx.AsyncClient, "post", fake_post)
    narrator = GeminiNarrator(api_key="invalid-key")

    result = await narrator.rewrite_grounded_reply(**narration_kwargs())
    assert result is None


@pytest.mark.asyncio
async def test_vertex_ai_narrator_uses_gcp_metadata_token(monkeypatch: pytest.MonkeyPatch) -> None:
    captured_auth: dict[str, str] = {}

    async def fake_get(self: httpx.AsyncClient, url: str, *args: object, **kwargs: object) -> httpx.Response:
        if "metadata.google.internal" in url:
            return httpx.Response(200, json={"access_token": "fake-adc-token-123"}, request=httpx.Request("GET", url))
        return httpx.Response(404, request=httpx.Request("GET", url))

    async def fake_post(self: httpx.AsyncClient, url: str, *args: object, **kwargs: object) -> httpx.Response:
        headers = kwargs.get("headers", {})
        captured_auth["auth"] = headers.get("Authorization", "")
        return httpx.Response(
            200,
            json={"candidates": [{"content": {"parts": [{"text": "Cabin hiện ở 31 độ C, năng lượng 18%."}]}}]},
            request=httpx.Request("POST", url),
        )

    monkeypatch.setattr(httpx.AsyncClient, "get", fake_get)
    monkeypatch.setattr(httpx.AsyncClient, "post", fake_post)
    narrator = VertexAINarrator(project_id="test-proj", region="asia-southeast1")

    result = await narrator.rewrite_grounded_reply(**narration_kwargs())
    assert result == "Cabin hiện ở 31 độ C, năng lượng 18%."
    assert captured_auth["auth"] == "Bearer fake-adc-token-123"


@pytest.mark.asyncio
async def test_vertex_ai_narrator_returns_none_when_credentials_unreachable(monkeypatch: pytest.MonkeyPatch) -> None:
    async def fake_get(self: httpx.AsyncClient, url: str, *args: object, **kwargs: object) -> httpx.Response:
        raise httpx.ConnectError("Metadata server unreachable")

    monkeypatch.setattr(httpx.AsyncClient, "get", fake_get)
    narrator = VertexAINarrator(api_key=None)

    result = await narrator.rewrite_grounded_reply(**narration_kwargs())
    assert result is None

