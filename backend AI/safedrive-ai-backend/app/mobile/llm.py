"""Constrained local LLM narration over an approved SafeDrive context pack.

The model receives compact, structured vehicle context only after the
deterministic intent and Safety Core have produced an approved reply. It never
routes an intent, chooses a risk level, issues an action, transitions SOS, or
receives raw camera, audio, CAN, or sensor streams.
"""

from __future__ import annotations

import json
import re
from dataclasses import dataclass
from typing import Any

import httpx

from app.mobile.context import ContextPack

_CJK_CHARACTERS = re.compile(r"[\u3400-\u9fff\uf900-\ufaff]")
_VIETNAMESE_WORD = re.compile(
    r"\b(t\u00f4i|b\u1ea1n|xe|l\u00e1i|\u0111\u01b0\u1eddng|c\u00f9ng|c\u00f3|v\u00e0|n\u00ean)\b",
    re.IGNORECASE,
)
_NUMBER = re.compile(r"(?<![\w.])-?\d+(?:[.,]\d+)?")


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
            "options": {"temperature": 0.2, "num_predict": 96, "num_ctx": 1536},
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
        raw_text = await self._post_chat(payload)
        if raw_text is None:
            return None
        return self._validate_and_normalize(
            raw_text,
            approved_reply=approved_reply,
            context_json=context_json,
            required_verbatim_snippets=required_verbatim_snippets,
        )

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
            "options": {"temperature": 0.2, "num_predict": 128, "num_ctx": 1536},
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
                        "answer it factually and concisely using only those facts.\n"
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
        raw_text = await self._post_chat(payload)
        if raw_text is None:
            return None
        return self._validate_and_normalize(
            raw_text,
            approved_reply=deterministic_fallback,
            context_json=context_json,
        )

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
        context_json: str,
        required_verbatim_snippets: tuple[str, ...] = (),
    ) -> str | None:
        normalized = " ".join(raw_text.split())
        approved_numbers = _number_tokens(approved_reply)
        supported_numbers = approved_numbers | _number_tokens(context_json)
        if (
            not normalized
            or len(normalized) > 520
            or _CJK_CHARACTERS.search(normalized) is not None
            or _VIETNAMESE_WORD.search(normalized) is None
            or not approved_numbers.issubset(_number_tokens(normalized))
            or not _number_tokens(normalized).issubset(supported_numbers)
            or not all(snippet in normalized for snippet in required_verbatim_snippets)
        ):
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
