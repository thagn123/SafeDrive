"""Constrained local LLM narration over an approved SafeDrive context pack.

The model receives compact, structured vehicle context only after the
deterministic intent and Safety Core have produced an approved reply. It never
routes an intent, chooses a risk level, issues an action, transitions SOS, or
receives raw camera, audio, CAN, or sensor streams.
"""

from __future__ import annotations

import json
import logging
import re
import unicodedata
from dataclasses import dataclass
from typing import Any

import httpx

from app.mobile.context import ContextPack

_logger = logging.getLogger("app.narration")

_CJK_CHARACTERS = re.compile(r"[\u3400-\u9fff\uf900-\ufaff]")
_NUMBER = re.compile(r"(?<![\w.])-?\d+(?:[.,]\d+)?")


def _has_vietnamese_diacritics(text: str) -> bool:
    if "\u0111" in text.lower():  # "\u0111" does not decompose under NFD
        return True
    decomposed = unicodedata.normalize("NFD", text)
    return any(unicodedata.category(char) == "Mn" for char in decomposed)


def _looks_vietnamese_enough(text: str) -> bool:
    """Minimal output-language validator: rejects CJK-heavy or English-heavy replies
    when Vietnamese (vi-VN) is required. Presence of any Vietnamese diacritic mark
    anywhere in the reply is a reliable "substantially Vietnamese" signal, since
    virtually every genuine Vietnamese sentence carries tone marks -- while a DTC code,
    unit, or product name embedded in an otherwise-Vietnamese sentence carries no
    diacritics of its own and must not by itself fail this check.
    """

    stripped = text.strip()
    if not stripped:
        return False
    if _CJK_CHARACTERS.search(stripped) is not None:
        return False
    return _has_vietnamese_diacritics(stripped)


def _with_stricter_vietnamese_retry(payload: dict[str, Any]) -> dict[str, Any]:
    return {
        **payload,
        "messages": [
            *payload["messages"],
            {
                "role": "system",
                "content": (
                    "Your previous reply was rejected for not being clearly Vietnamese. "
                    "Reply again, entirely in Vietnamese (Tieng Viet), using Vietnamese "
                    "words and grammar throughout -- not Chinese, not English. Technical "
                    "codes, units, and product names may stay as-is."
                ),
            },
        ],
    }


def _normalize_number_token(token: str) -> str:
    """Canonicalize a numeric token so equal values compare equal regardless of
    trailing-zero formatting. GROUNDED_CONTEXT_JSON serializes floats like
    ``speedKmh`` as "60.0", but the model naturally drops the redundant decimal
    when speaking a whole number aloud ("60 km/h") -- without this, that
    perfectly grounded value would look like an invented number and the whole
    reply would be rejected back to the deterministic fallback.
    """

    try:
        value = float(token)
    except ValueError:
        return token
    if value == int(value):
        return str(int(value))
    return f"{value:.6f}".rstrip("0").rstrip(".")

# Deterministic tone selection from the already-decided risk level -- the model is told
# which tone to use, it never picks one itself. HIGH/CRITICAL are included for
# defensiveness even though MobileSessionStore._can_narrate never actually routes those
# risk levels to the narrator today; if that gate ever changes, "direct" is still the
# correct instruction rather than silently falling back to "cautious".
_TONE_BY_RISK: dict[str, str] = {
    "LOW": "calm",
    "MEDIUM": "cautious",
    "HIGH": "direct",
    "CRITICAL": "direct",
}


def _tone_for_risk(risk_level: str) -> str:
    return _TONE_BY_RISK.get(risk_level, "cautious")


def _json_default(value: object) -> object:
    """Serialize only already-bounded ContextPack values for the local model."""

    model_dump = getattr(value, "model_dump", None)
    if callable(model_dump):
        return model_dump(mode="json")
    enum_value = getattr(value, "value", None)
    if isinstance(enum_value, (str, int, float, bool)):
        return enum_value
    return str(value)


def _number_tokens(value: str) -> set[str]:
    return {
        _normalize_number_token(match.group(0).replace(",", "."))
        for match in _NUMBER.finditer(value)
    }


# Every ContextValue.name in app/mobile/context.py is a dot/underscore-separated
# semantic path (e.g. "vehicle.speed_kmh", "vehicle.cabin_temperature_c"). The unit is
# already encoded in that name's suffix -- this table reads it back out instead of
# hardcoding a unit per specific field, so any future field ending in one of these
# suffixes is covered automatically.
_FIELD_UNIT_SUFFIXES: tuple[tuple[str, str], ...] = (
    ("_kmh", "KMH"),
    ("_temperature_c", "CELSIUS"),
    ("_percent", "PERCENT"),
    ("_minutes", "MINUTES"),
    ("_seconds", "SECONDS"),
)

# Matched against the text immediately following a number in the model's raw output
# (Vietnamese with diacritics, not accent-stripped) to decide which of the units above,
# if any, the model is claiming for that number. A number with no recognized trailing
# unit falls back to the older, unit-agnostic "does this value appear anywhere in
# context" check -- this table only ever makes grounding *stricter*, never looser.
_UNIT_SURFACE_PATTERNS: tuple[tuple[re.Pattern[str], str], ...] = (
    (re.compile(r"\s*(?:km\s*/\s*(?:h|giờ)|kmh)\b", re.IGNORECASE), "KMH"),
    (re.compile(r"\s*(?:°\s*c|độ\s*c|do\s*c)\b", re.IGNORECASE), "CELSIUS"),
    (re.compile(r"\s*(?:%|phần\s*trăm|phan\s*tram)"), "PERCENT"),
    (re.compile(r"\s*(?:phút|phut|minutes?|min)\b", re.IGNORECASE), "MINUTES"),
    (re.compile(r"\s*(?:giây|giay|seconds?|sec)\b", re.IGNORECASE), "SECONDS"),
)


def _unit_for_field(name: str) -> str | None:
    for suffix, unit in _FIELD_UNIT_SUFFIXES:
        if name.endswith(suffix):
            return unit
    return None


def _detect_trailing_unit(text: str, end: int) -> str | None:
    tail = text[end : end + 16]
    for pattern, unit in _UNIT_SURFACE_PATTERNS:
        if pattern.match(tail):
            return unit
    return None


def _numeric_context_values(context_pack: ContextPack) -> list[tuple[str, float]]:
    values: list[tuple[str, float]] = []
    for value in context_pack.values:
        if isinstance(value.value, bool) or not isinstance(value.value, (int, float)):
            continue
        values.append((value.name, float(value.value)))
    return values


def _grounded_values_by_unit(
    context_pack: ContextPack, allowed_actions: tuple[dict[str, object], ...]
) -> dict[str, set[str]]:
    """Field-name-derived unit -> the set of normalized numeric values genuinely
    carrying that unit right now. Used to reject e.g. a narrated "60%" when the only
    context value equal to 60 is `vehicle.speed_kmh` (unit KMH, not PERCENT) -- the
    old check only asked "does 60 appear anywhere in context", which a same-numbered
    but semantically wrong field would incorrectly satisfy.
    """

    by_unit: dict[str, set[str]] = {}
    for name, numeric_value in _numeric_context_values(context_pack):
        unit = _unit_for_field(name)
        if unit is None:
            continue
        by_unit.setdefault(unit, set()).add(_normalize_number_token(str(numeric_value)))
    for action in allowed_actions:
        target = action.get("hvacTargetTemperatureC")
        if isinstance(target, (int, float)) and not isinstance(target, bool):
            by_unit.setdefault("CELSIUS", set()).add(_normalize_number_token(str(target)))
    return by_unit


def _bare_context_numbers(
    context_pack: ContextPack, allowed_actions: tuple[dict[str, object], ...]
) -> set[str]:
    """Unit-agnostic fallback set for numbers the model writes with no unit the
    parser can recognize -- preserves the original, more permissive behavior for
    anything the stricter per-unit check above cannot classify either way."""

    numbers = {_normalize_number_token(str(context_pack.state_version))}
    for name, numeric_value in _numeric_context_values(context_pack):
        numbers.add(_normalize_number_token(str(numeric_value)))
    for value in context_pack.values:
        if value.age_ms is not None:
            numbers.add(_normalize_number_token(str(value.age_ms)))
    for action in allowed_actions:
        target = action.get("hvacTargetTemperatureC")
        if isinstance(target, (int, float)) and not isinstance(target, bool):
            numbers.add(_normalize_number_token(str(target)))
    return numbers


@dataclass(frozen=True, slots=True)
class OllamaNarrator:
    """Uses a local Ollama model only to narrate an already-approved plan."""

    base_url: str
    model: str
    timeout_seconds: float

    async def rewrite_grounded_reply(
        self,
        *,
        user_text: str,
        approved_reply: str,
        context_pack: ContextPack,
        risk_level: str,
        risk_reasons: tuple[str, ...],
        allowed_actions: list[dict[str, object]],
        required_verbatim_snippets: tuple[str, ...] = (),
    ) -> str | None:
        """Return a validated contextual rewrite, or ``None`` for deterministic fallback."""

        context_json = json.dumps(
            {
                "stateVersion": context_pack.state_version,
                "values": [
                    {
                        "name": value.name,
                        "value": value.value,
                        "source": value.source,
                        "ageMs": value.age_ms,
                        "status": value.status,
                    }
                    for value in context_pack.values
                ],
                "missingContext": list(context_pack.missing_context),
                "constraints": list(context_pack.constraints),
                "risk": {"level": risk_level, "reasonCodes": list(risk_reasons)},
                "allowedActions": allowed_actions,
            },
            ensure_ascii=False,
            default=_json_default,
            separators=(",", ":"),
        )
        tone = _tone_for_risk(risk_level)
        payload: dict[str, Any] = {
            "model": self.model,
            "stream": False,
            "keep_alive": "30m",
            "options": {"temperature": 0.2, "num_predict": 128, "num_ctx": 1536},
            "messages": [
                {
                    "role": "system",
                    "content": (
                        "ROLE: You are the in-vehicle response narrator for SafeDrive, the AI companion riding "
                        "along with the driver -- not a generic chatbot. You transform verified vehicle context "
                        "and an already-decided safety judgment into concise, natural Vietnamese guidance. The "
                        "deterministic safety system has already made every decision; your only job is "
                        "language, not judgment.\n\n"
                        "PRIORITIES, in order:\n"
                        "1. Preserve the deterministic decision -- APPROVED_REPLY is the fixed, already-safe "
                        "content: preserve every safety warning, vehicle fact, action type, and confirmation "
                        "requirement in it exactly, with the same numbers and units.\n"
                        "2. Use only supplied verified context (APPROVED_REPLY and GROUNDED_CONTEXT_JSON). You "
                        "are encouraged -- not just allowed -- to naturally weave in a relevant real value from "
                        "GROUNDED_CONTEXT_JSON when that makes your answer feel like it truly knows this car "
                        "right now, the way a good co-driver would, instead of sounding like a canned line.\n"
                        "3. State the immediate action plainly before explaining why.\n"
                        "4. Explain the reason briefly, in plain words a driver can absorb at a glance without "
                        "reading a screen.\n"
                        "5. Ask for confirmation only when APPROVED_REPLY already asks for one -- never invent "
                        "a new confirmation question of your own.\n"
                        "6. If context is missing or stale, say so plainly instead of guessing.\n\n"
                        "FORBIDDEN:\n"
                        "- Never calculate or change a risk level, DTC severity, or any safety threshold.\n"
                        "- Never invent a number, DTC, location, distance, or fact that is not present in "
                        "APPROVED_REPLY or GROUNDED_CONTEXT_JSON.\n"
                        "- Never omit a safety warning, diagnose a medical or mechanical condition "
                        "conclusively, create an action, dispatch rescue, change an HVAC target, or claim an "
                        "action is complete unless APPROVED_REPLY already says so.\n"
                        "- Never claim a nearby location or distance unless it is already present in "
                        "APPROVED_REPLY or GROUNDED_CONTEXT_JSON.\n"
                        "- Never contradict APPROVED_REPLY's severity or tone.\n\n"
                        "STYLE:\n"
                        "- Vietnamese by default, one to four short sentences a driver can absorb at a glance.\n"
                        "- Natural spoken language suitable for text-to-speech: no markdown, no raw reason "
                        "codes, no JSON in the reply.\n"
                        "- TONE tells you how direct to be -- calm (relaxed phrasing), cautious (explain the "
                        "why before the action), or direct (lead with the action, no filler, no jokes). TONE "
                        "mirrors the already-decided risk level; never choose or soften it yourself.\n"
                        "- Avoid generic filler such as \"hay dam bao an toan\" -- prefer one concrete next "
                        "step.\n"
                        "- Include every fact and number from APPROVED_REPLY, even ones that feel less "
                        "central to what the driver asked -- never trim it down to only the part that "
                        "seems most relevant. When TONE is calm and nothing is wrong, ADD (do not "
                        "substitute) one brief co-driver remark on top of the complete facts. Compare: "
                        "flat -- \"Cabin hien o 25 do C, nang luong 74%.\"; co-driver -- \"Cabin hien o 25 "
                        "do C, mat me, va nang luong van con thoai mai voi 74%.\" -- same facts, same "
                        "numbers, just warmer wording around them. Never close every reply with the same "
                        "stock question (e.g. always \"ban co cau hoi gi khac khong\") -- vary it, tie it "
                        "to what the driver actually asked, or leave it out entirely.\n"
                        "- Repeat every numeric fact or target from APPROVED_REPLY exactly, with its unit."
                    ),
                },
                {
                    "role": "user",
                    "content": (
                        f"TONE:\n{tone}\n\n"
                        f"USER_MESSAGE:\n{user_text}\n\n"
                        f"APPROVED_REPLY:\n{approved_reply}\n\n"
                        f"GROUNDED_CONTEXT_JSON:\n{context_json}\n\n"
                        "Return only the Vietnamese user-facing reply."
                    ),
                },
            ],
        }
        raw_text, language_ok, retried = await self._post_chat_with_language_retry(payload)
        if raw_text is None:
            return None
        result = self._validate_and_normalize(
            raw_text,
            approved_reply=approved_reply,
            context_pack=context_pack,
            allowed_actions=tuple(allowed_actions),
            required_verbatim_snippets=required_verbatim_snippets,
        )
        _logger.info(
            "narration_language_check",
            extra={
                "event": "narration_language_check",
                "language_ok": language_ok,
                "retried": retried,
                "fallback_used": result is None,
            },
        )
        return result

    async def answer_open_query(
        self,
        *,
        user_text: str,
        deterministic_fallback: str,
        context_pack: ContextPack,
        risk_level: str,
        risk_reasons: tuple[str, ...],
    ) -> str | None:
        """Return a validated, genuinely free-form answer for ``assistant.general`` --
        text the deterministic router could not match to any known category at all --
        or ``None`` for deterministic fallback.

        Unlike ``rewrite_grounded_reply``, the model here is given a real mandate to read
        ``USER_MESSAGE`` and answer it, not merely reword an already-decided string. It
        may only draw on ``GROUNDED_CONTEXT_JSON`` for vehicle/trip/driving facts, and must
        decline (briefly, honestly, in scope) anything unrelated to driving safety --
        SafeDrive is a driving-safety assistant, not a general-purpose chatbot. The same
        guardrail as ``rewrite_grounded_reply`` still gates the output: this route has no
        DTC code or safety directive to preserve, so ``required_verbatim_snippets`` is
        never needed here, but hallucinated numbers are still rejected the same way.
        """

        context_json = json.dumps(
            {
                "stateVersion": context_pack.state_version,
                "values": [
                    {
                        "name": value.name,
                        "value": value.value,
                        "source": value.source,
                        "ageMs": value.age_ms,
                        "status": value.status,
                    }
                    for value in context_pack.values
                ],
                "missingContext": list(context_pack.missing_context),
                "constraints": list(context_pack.constraints),
                "risk": {"level": risk_level, "reasonCodes": list(risk_reasons)},
            },
            ensure_ascii=False,
            default=_json_default,
            separators=(",", ":"),
        )
        tone = _tone_for_risk(risk_level)
        payload: dict[str, Any] = {
            "model": self.model,
            "stream": False,
            "keep_alive": "30m",
            "options": {"temperature": 0.2, "num_predict": 160, "num_ctx": 1536},
            "messages": [
                {
                    "role": "system",
                    "content": (
                        "ROLE: You are the in-vehicle assistant for SafeDrive, a driving-safety "
                        "companion riding along with the driver -- not a general-purpose chatbot. "
                        "The deterministic system found no known category for this message (not "
                        "fatigue, HVAC, a vehicle fault, a status check, or small talk), so you get a "
                        "real chance to read USER_MESSAGE and respond to it directly, using only "
                        "GROUNDED_CONTEXT_JSON as your source of vehicle/trip/driving facts.\n\n"
                        "DECIDE, in order:\n"
                        "1. If USER_MESSAGE names a specific code, part, or identifier (a DTC-shaped "
                        "code, a part number, anything token-like) that is NOT present in "
                        "GROUNDED_CONTEXT_JSON, do not guess or invent what it means -- even a "
                        "plausible-sounding guess is a fabrication. Say plainly you have no verified "
                        "information about that specific item, then invite a vehicle-related question "
                        "you can actually answer.\n"
                        "2. Otherwise, if USER_MESSAGE is genuinely about this vehicle, this trip, or "
                        "driving right now, and it is answerable from GROUNDED_CONTEXT_JSON alone, "
                        "answer it using only those facts -- but sound like an attentive co-driver, not a "
                        "status readout. Compare: flat -- \"Toc do 61 km/h, cabin 25 do C.\"; co-driver -- "
                        "\"Xe dang chay 61 km/h rat on dinh, cabin cung mat me o 25 do.\" Say which reading "
                        "prompted your remark, not a generic phrase. Vary how you close the reply, or leave "
                        "it without a closing question, instead of always asking the same stock question.\n"
                        "3. Otherwise (general knowledge, math, opinions, anything unrelated to "
                        "driving/vehicle/safety) -- do not attempt to answer it, even if you know the "
                        "answer. Give one brief, honest sentence that SafeDrive is a driving-safety "
                        "assistant and cannot help with that specific topic, then invite a "
                        "vehicle-related question instead. DETERMINISTIC_FALLBACK shows the kind of "
                        "topics you can redirect toward.\n\n"
                        "FORBIDDEN:\n"
                        "- Never invent a number, DTC, location, distance, or fact not present in "
                        "GROUNDED_CONTEXT_JSON.\n"
                        "- Never invent, guess, or assign a meaning/category/explanation to any code, "
                        "identifier, or token the driver mentions unless GROUNDED_CONTEXT_JSON verifies "
                        "it -- not even by analogy to something that IS in GROUNDED_CONTEXT_JSON.\n"
                        "- Never calculate or state a risk level, diagnose a medical or mechanical "
                        "condition, create or claim a vehicle action, or dispatch rescue.\n"
                        "- Never actually answer an off-topic question (trivia, math, general "
                        "knowledge) just because you can -- redirect instead, per rule 3 above.\n\n"
                        "STYLE:\n"
                        "- Vietnamese by default, one to four short sentences a driver can absorb at "
                        "a glance.\n"
                        "- Natural spoken language suitable for text-to-speech: no markdown, no raw "
                        "reason codes, no JSON in the reply.\n"
                        "- TONE tells you how direct to be -- calm, cautious, or direct -- mirroring "
                        "the already-decided risk level; never choose or soften it yourself."
                    ),
                },
                {
                    "role": "user",
                    "content": (
                        f"TONE:\n{tone}\n\n"
                        f"USER_MESSAGE:\n{user_text}\n\n"
                        f"DETERMINISTIC_FALLBACK:\n{deterministic_fallback}\n\n"
                        f"GROUNDED_CONTEXT_JSON:\n{context_json}\n\n"
                        "Return only the Vietnamese user-facing reply."
                    ),
                },
            ],
        }
        raw_text, language_ok, retried = await self._post_chat_with_language_retry(payload)
        if raw_text is None:
            return None
        result = self._validate_and_normalize(
            raw_text,
            approved_reply=deterministic_fallback,
            context_pack=context_pack,
            require_approved_numbers=False,
        )
        _logger.info(
            "narration_language_check",
            extra={
                "event": "narration_language_check",
                "language_ok": language_ok,
                "retried": retried,
                "fallback_used": result is None,
            },
        )
        return result

    async def _post_chat_with_language_retry(
        self, payload: dict[str, Any]
    ) -> tuple[str | None, bool, bool]:
        """Calls the model, and if the reply fails the Vietnamese language check,
        retries exactly once with a stricter instruction appended. Returns
        ``(raw_text, initial_language_ok, retried)`` -- ``raw_text`` is whichever
        attempt's text should be validated next (the retry's, if one was made and the
        provider answered), or ``None`` if the provider was unreachable outright.
        HIGH/CRITICAL replies never reach this code path at all (``_can_narrate``
        excludes them before any narrator method is called), so this retry can never
        delay a safety-critical response.
        """

        raw_text = await self._post_chat(payload)
        if raw_text is None:
            return None, False, False
        initial_ok = _looks_vietnamese_enough(raw_text)
        if initial_ok:
            return raw_text, True, False
        retry_text = await self._post_chat(_with_stricter_vietnamese_retry(payload))
        if retry_text is not None:
            raw_text = retry_text
        return raw_text, initial_ok, True

    async def _post_chat(self, payload: dict[str, Any]) -> str | None:
        try:
            async with httpx.AsyncClient(timeout=self.timeout_seconds) as client:
                response = await client.post(f"{self.base_url.rstrip('/')}/api/chat", json=payload)
                response.raise_for_status()
        except httpx.HTTPError:
            return None
        text = response.json().get("message", {}).get("content")
        return text if isinstance(text, str) else None

    @staticmethod
    def _validate_and_normalize(
        raw_text: str,
        *,
        approved_reply: str,
        context_pack: ContextPack,
        allowed_actions: tuple[dict[str, object], ...] = (),
        required_verbatim_snippets: tuple[str, ...] = (),
        require_approved_numbers: bool = True,
    ) -> str | None:
        normalized = " ".join(raw_text.split())
        approved_numbers = _number_tokens(approved_reply)
        # require_approved_numbers is False only for answer_open_query: its
        # deterministic_fallback is a real, number-bearing vehicle-status summary (see
        # ContextAwareAssistant's assistant.general branch), used there purely as an
        # example of grounded tone/topics -- unlike rewrite_grounded_reply, this route
        # gives the model a genuine mandate to answer a *different* question than the
        # fallback covers, so it must not be forced to mechanically repeat every number
        # from a fallback text it may have had no reason to reference at all.
        if (
            not normalized
            or len(normalized) > 650
            or not _looks_vietnamese_enough(normalized)
            or (require_approved_numbers and not approved_numbers.issubset(_number_tokens(normalized)))
            or not all(snippet in normalized for snippet in required_verbatim_snippets)
        ):
            return None

        by_unit = _grounded_values_by_unit(context_pack, allowed_actions)
        bare_numbers = approved_numbers | _bare_context_numbers(context_pack, allowed_actions)
        for match in _NUMBER.finditer(normalized):
            token = _normalize_number_token(match.group(0).replace(",", "."))
            if token in approved_numbers:
                continue
            unit = _detect_trailing_unit(normalized, match.end())
            if unit is not None:
                # A number with a recognized unit must match a context field that
                # actually carries that unit -- e.g. a narrated "60%" is rejected
                # unless some *_percent field genuinely equals 60, even though 60
                # might separately be the (unrelated) speed in km/h.
                if token not in by_unit.get(unit, ()):
                    return None
            elif token not in bare_numbers:
                return None
        return normalized


# Closed label set an OllamaIntentClassifier verdict is restricted to. Deliberately
# excludes anything the deterministic IntentResolver already handles confidently
# before a classification would ever run (emergency keywords, HVAC commands with an
# explicit target or a generic on/off phrase) -- see MobileSessionStore._can_classify.
AMBIGUOUS_INTENT_LABELS = (
    "safety.driver_fatigue",
    "comfort.too_hot",
    "vehicle.fault_concern",
    "assistant.vehicle_status",
    "companion.conversation",
    "assistant.clarify",
)


@dataclass(frozen=True, slots=True)
class OllamaIntentClassifier:
    """Advisory-only reclassifier for text the deterministic keyword router could not
    match confidently at all (``IntentResolution.needs_clarification``).

    It never runs on the safety/action-critical fast paths: emergency keywords, an
    explicit HVAC target, and DTC/fatigue/status keyword matches are all resolved by
    ``IntentResolver`` before this ever executes, and ``MobileSessionStore._can_classify``
    additionally refuses to run it during an active emergency or HIGH/CRITICAL risk.

    Its only allowed output is one label from ``AMBIGUOUS_INTENT_LABELS``; anything else
    -- including a timeout, a non-2xx response, or free text that isn't an exact label --
    is rejected and the caller keeps the original deterministic reply unchanged. A label
    only selects an existing deterministic reply template in ``ContextAwareAssistant``; it
    never writes user-facing text and never creates or targets an action itself, so a
    wrong or hallucinated label can, at worst, show the wrong *existing* grounded
    template -- never an invented one.
    """

    base_url: str
    model: str
    timeout_seconds: float

    async def classify(
        self,
        *,
        user_text: str,
        context_pack: ContextPack,
        risk_level: str,
        risk_reasons: tuple[str, ...],
    ) -> str | None:
        """Return a label from ``AMBIGUOUS_INTENT_LABELS``, or ``None`` to keep the
        deterministic fallback."""

        context_json = json.dumps(
            {
                "stateVersion": context_pack.state_version,
                "values": [
                    {"name": value.name, "value": value.value, "ageMs": value.age_ms, "status": value.status}
                    for value in context_pack.values
                ],
                "missingContext": list(context_pack.missing_context),
                "risk": {"level": risk_level, "reasonCodes": list(risk_reasons)},
            },
            ensure_ascii=False,
            default=_json_default,
            separators=(",", ":"),
        )
        payload: dict[str, Any] = {
            "model": self.model,
            "stream": False,
            "keep_alive": "30m",
            "options": {"temperature": 0.0, "num_predict": 12, "num_ctx": 1024},
            "messages": [
                {
                    "role": "system",
                    "content": (
                        "Classify the driver's message into exactly one label from this fixed "
                        "list, using only the supplied vehicle context: "
                        + ", ".join(AMBIGUOUS_INTENT_LABELS)
                        + ". Prefer the most specific label the CONTEXT_JSON actually supports (for example, "
                        "an active DTC favors vehicle.fault_concern; a long driving duration or fatigue reason "
                        "code favors safety.driver_fatigue; a hot cabin reason code favors comfort.too_hot; a "
                        "plain status question favors assistant.vehicle_status) rather than defaulting to "
                        "companion.conversation or assistant.clarify when a more specific label fits. Use "
                        "companion.conversation only for genuine small talk with no vehicle-relevant angle, "
                        "and assistant.clarify only when the message is truly ambiguous even given the context. "
                        "Reply with only the label itself and nothing else -- no punctuation, no explanation, "
                        "no quotes. If nothing clearly fits, reply assistant.clarify."
                    ),
                },
                {
                    "role": "user",
                    "content": f"MESSAGE:\n{user_text}\n\nCONTEXT_JSON:\n{context_json}",
                },
            ],
        }
        try:
            async with httpx.AsyncClient(timeout=self.timeout_seconds) as client:
                response = await client.post(f"{self.base_url.rstrip('/')}/api/chat", json=payload)
                response.raise_for_status()
        except httpx.HTTPError:
            return None

        text = response.json().get("message", {}).get("content")
        if not isinstance(text, str):
            return None
        label = text.strip().splitlines()[0].strip().strip(".").strip()
        return label if label in AMBIGUOUS_INTENT_LABELS else None
