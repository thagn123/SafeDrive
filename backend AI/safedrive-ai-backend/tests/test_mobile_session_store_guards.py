"""Unit tests for MobileSessionStore's deterministic pre-narration guards.

MobileSessionStore._find_unverified_code_token is tested directly (not through the full
FastAPI app) because it's pure, self-contained logic: given raw user text and a
ContextPack, decide whether a code-like token needs a deterministic intercept before
any LLM call is even attempted. See docs/TEST_EVIDENCE.md for the live-model evidence
that motivated this guard (a real 7B model fabricated a plausible-sounding explanation
for a made-up token when given a genuine mandate to answer freely).
"""

from __future__ import annotations

from app.mobile.context import ContextPack, ContextValue
from app.mobile.session_store import MobileSessionStore


def _context_pack(*values: ContextValue) -> ContextPack:
    return ContextPack(
        state_version=1,
        values=(ContextValue("vehicle.speed_kmh", 60.0, "PHONE_SIMULATOR", 10, "FRESH"), *values),
        missing_context=(),
        constraints=("Use only supplied structured values.",),
    )


def test_unverified_code_token_detects_xyz123() -> None:
    token = MobileSessionStore._find_unverified_code_token("XYZ123 nghia la gi?", _context_pack())

    assert token == "XYZ123"


def test_unverified_code_token_detects_abx900() -> None:
    token = MobileSessionStore._find_unverified_code_token("Ma ABX900 co nghia gi?", _context_pack())

    assert token == "ABX900"


def test_unverified_code_token_ignores_ordinary_mixed_text() -> None:
    token = MobileSessionStore._find_unverified_code_token(
        "Xe toi hom nay chay binh thuong, toc do 60 km/h", _context_pack()
    )

    assert token is None


def test_unverified_code_token_ignores_a_dtc_shaped_token() -> None:
    # DTC-shaped tokens are handled entirely separately (IntentResolver routes them to
    # vehicle.fault_concern before assistant.general is ever reached) -- this guard must
    # never double-process them.
    token = MobileSessionStore._find_unverified_code_token("U0100 la gi?", _context_pack())

    assert token is None


def test_unverified_code_token_allows_a_token_present_in_grounded_context() -> None:
    # A code-like token that genuinely appears in the grounded context must not be
    # flagged -- the guard exists for *unverified* tokens only.
    context_pack = _context_pack(ContextValue("test.identifier", "AB1200", "PHONE_SIMULATOR", 10, "FRESH"))

    token = MobileSessionStore._find_unverified_code_token("Ma AB1200 la gi?", context_pack)

    assert token is None


def test_unverified_code_token_is_case_insensitive_against_context() -> None:
    context_pack = _context_pack(ContextValue("test.identifier", "ab1200", "PHONE_SIMULATOR", 10, "FRESH"))

    token = MobileSessionStore._find_unverified_code_token("Ma AB1200 la gi?", context_pack)

    assert token is None


def test_unverified_code_token_ignores_a_single_letter_prefix_run() -> None:
    # Plain digit runs, and words without an immediate letters-then-digits shape,
    # should never trigger this guard -- keeps false positives low on ordinary speech.
    token = MobileSessionStore._find_unverified_code_token(
        "Toi da lai duoc 245 phut roi, hoi met", _context_pack()
    )

    assert token is None
