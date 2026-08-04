"""Runs tests/fixtures/narration_cases.json against ContextAwareAssistant's deterministic
templates.

Per the eval-set spec this was built from: checked by concept/keyword presence, not exact
string matching. Every case is exercised through the same plan_reply() harness as
test_mobile_assistant.py -- since it never calls an LLM, this also happens to be exactly
the deterministic-fallback text a real narrator failure would fall back to, so the two
"ollama_*" cases (documented with a skip_reason for where their live-HTTP-mocked
counterpart lives) are still meaningfully checked here, not skipped.
"""

from __future__ import annotations

import json
import time
from pathlib import Path

import pytest

from app.mobile.context import MobileContextBuilder
from tests.test_mobile_assistant import plan_reply

FIXTURE_PATH = Path(__file__).parent / "fixtures" / "narration_cases.json"


def _load_cases() -> list[dict[str, object]]:
    data = json.loads(FIXTURE_PATH.read_text(encoding="utf-8"))
    return data["cases"]


def _resolve_kwargs(case_input: dict[str, object]) -> dict[str, object]:
    kwargs = dict(case_input)
    stale_by_ms = kwargs.pop("stale_by_ms", None)
    if stale_by_ms is not None:
        now = int(time.time() * 1_000)
        kwargs["now_ms"] = now
        kwargs["updated_at_ms"] = now - MobileContextBuilder.STATE_FRESHNESS_MS - int(stale_by_ms)  # type: ignore[arg-type]
    return kwargs


CASES = _load_cases()


def test_fixture_file_is_well_formed() -> None:
    assert len(CASES) >= 15
    required_keys = {"id", "input", "must_include_concepts", "must_not_include", "expected_tone", "expected_fallback"}
    for case in CASES:
        assert required_keys.issubset(case.keys()), case["id"]
        assert "text" in case["input"], case["id"]  # type: ignore[operator]


@pytest.mark.parametrize("case", CASES, ids=lambda case: case["id"])
def test_narration_case_matches_expected_concepts(case: dict[str, object]) -> None:
    case_input = dict(case["input"])  # type: ignore[arg-type]
    text = case_input.pop("text")
    kwargs = _resolve_kwargs(case_input)

    result_text, _ = plan_reply(text, **kwargs)  # type: ignore[arg-type]

    for concept in case["must_include_concepts"]:  # type: ignore[union-attr]
        assert concept in result_text, f"{case['id']}: expected {concept!r} in {result_text!r}"
    for forbidden in case["must_not_include"]:  # type: ignore[union-attr]
        assert forbidden not in result_text, f"{case['id']}: found forbidden {forbidden!r} in {result_text!r}"
