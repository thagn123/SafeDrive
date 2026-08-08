"""Deterministic intent hypotheses grounded by SafeDrive context."""

from __future__ import annotations

import re
import unicodedata
from dataclasses import dataclass

from app.mobile.context import ContextSnapshot
from app.mobile.safety import SafetyEvaluation

# Standard 5-character OBD-II DTC shape: P/B/C/U (Powertrain/Body/Chassis/Network)
# followed by 4 hex digits, e.g. U0100, P0128, B1234. Module-level (not just an
# IntentResolver attribute) so app/mobile/session_store.py's unverified-code-token
# guard can reuse the exact same shape to exclude DTC-shaped tokens, which are already
# handled by the dedicated, catalog-aware vehicle.fault_concern path and must never be
# double-processed by that separate guard.
DTC_CODE_PATTERN = re.compile(r"\b[PBCU][0-9A-F]{4}\b", re.IGNORECASE)


def normalize_text(value: str) -> str:
    decomposed = unicodedata.normalize("NFD", value.casefold())
    without_tone_marks = "".join(char for char in decomposed if unicodedata.category(char) != "Mn")
    # Vietnamese đ/Đ is not a combining character, so Unicode NFD alone does
    # not make it match the ASCII intent vocabulary used by the MVP router.
    return without_tone_marks.replace("đ", "d")


@dataclass(frozen=True, slots=True)
class IntentHypothesis:
    name: str
    confidence: float


@dataclass(frozen=True, slots=True)
class IntentResolution:
    route: str
    confidence: float
    hypotheses: tuple[IntentHypothesis, ...]
    needs_clarification: bool = False
    hvac_target_temperature_c: float | None = None
    requested_temperature_c: float | None = None
    # Set only when the driver's raw text contains a DTC-code-shaped token (see
    # module-level DTC_CODE_PATTERN). Canonicalized to uppercase. Distinct from a
    # plain vehicle.fault_concern match via keyword ("bao loi", "ma loi", ...): this
    # field lets ContextAwareAssistant look up the *specific* code asked about instead
    # of always reporting whichever active DTC happens to be primary.
    mentioned_dtc_code: str | None = None
    # True only when the driver explicitly asked about battery/energy
    # (SAFEDRIVE_AGENT_ARMOR_PLAN.md Slice 7). Kept as a flag rather than a separate route so
    # the reply stays the existing assistant.vehicle_status template; it only adds the one
    # figure that was actually asked for. Deliberately NOT set for other vehicle-status
    # phrasings: the deterministic reply is the narrator's approved-number set (see
    # app/mobile/llm.py's require_approved_numbers), so stating energy unconditionally would
    # force every narrated status reply to repeat it and raise the narration rejection rate.
    asked_about_energy: bool = False


@dataclass(frozen=True, slots=True)
class PendingDialogue:
    """Short-turn dialogue continuity only (SAFEDRIVE_AGENT_ARMOR_PLAN.md Slice 6) -- NOT
    long-term memory. Carries just enough from the single most recently issued HVAC action
    (app.mobile.session_store.MobileSession.issued_actions) for a short affirmative/negative
    reply to be resolved. Always derived fresh from that dict on every turn, never stored
    independently -- it inherits that dict's existing scope/expiry (full replacement every
    answer_assistant() call, and dropped early by _rebind_issued_actions when a telemetry
    change invalidates the action's dependency fingerprint)."""

    hvac_target_temperature_c: float


_AFFIRMATIVE_SHORT_REPLIES = frozenset(
    {"co", "ok", "okay", "duoc", "u", "uh", "dong y", "yes"}
)
_NEGATIVE_SHORT_REPLIES = frozenset({"khong", "thoi", "khoi", "no", "cancel"})


def _classify_short_reply(normalized: str) -> str | None:
    """Deterministic exact-match only against a short, fixed reply vocabulary -- deliberately
    NOT a substring/keyword search like every other route below (IntentResolver._contains),
    and deliberately not a general Vietnamese NLU dictionary. A longer sentence that happens
    to contain "co" or "khong" as a substring (e.g. a vehicle-status question) must never be
    misread as a yes/no answer, so the whole trimmed utterance must equal one of these words."""

    stripped = normalized.strip(" .!?,")
    if stripped in _AFFIRMATIVE_SHORT_REPLIES:
        return "AFFIRMATIVE"
    if stripped in _NEGATIVE_SHORT_REPLIES:
        return "NEGATIVE"
    return None


class IntentResolver:
    """Small, transparent intent resolver for the hackathon MVP.

    It deliberately emits several hypotheses for vague language and resolves
    them only after state and safety context are available. A future constrained
    LLM can use the same candidates and Context Pack without changing policy.
    """

    _CLIMATE_TERMS = (
        "nhiet do",
        "dieu hoa",
        "may lanh",
        "hvac",
        "air conditioning",
        "temperature",
    )
    _GENERIC_HVAC_COMMANDS = (
        "bat dieu hoa",
        "mo dieu hoa",
        "bat may lanh",
        "mo may lanh",
        "turn on ac",
        "turn on air conditioning",
    )
    # Group 1 is an optional negative marker (Vietnamese "am"/"âm" after tone
    # stripping, or a literal minus sign) so a negative request is correctly
    # parsed as negative and rejected by the 16-30C range check, rather than
    # silently dropping the sign and treating e.g. "am 20 do" as +20C.
    _TEMPERATURE_PATTERN = re.compile(
        r"\b(am\s+|-\s*)?(\d{1,3})(?:[.,](\d))?(?:\s*(?:do|degree|degrees|°)\s*(?:c)?|\s*c)\b"
    )
    _MIN_HVAC_TEMPERATURE_C = 16.0
    _MAX_HVAC_TEMPERATURE_C = 30.0

    def resolve(
        self,
        text: str,
        snapshot: ContextSnapshot,
        safety: SafetyEvaluation,
        *,
        pending_dialogue: PendingDialogue | None = None,
    ) -> IntentResolution:
        normalized = normalize_text(text)
        # Short-turn dialogue continuity (SAFEDRIVE_AGENT_ARMOR_PLAN.md Slice 6) is checked
        # first, but only ever matches the tiny fixed reply vocabulary above -- it can never
        # collide with the keyword routes below. Gated on safety exactly like _can_narrate/
        # _can_classify already gate narration/reclassification: an active emergency or
        # already-HIGH/CRITICAL risk must never let a stale comfort dialogue be silently
        # reaffirmed (e.g. engine temperature going CRITICAL between propose and "ok" is not
        # covered by the HVAC action's dependency fingerprint, so this is the layer that
        # closes that gap for this specific path).
        if (
            pending_dialogue is not None
            and not safety.emergency_candidate
            and safety.risk.level not in {"HIGH", "CRITICAL"}
        ):
            reply_kind = _classify_short_reply(normalized)
            if reply_kind == "AFFIRMATIVE":
                return IntentResolution(
                    route="dialogue.affirmed",
                    confidence=0.97,
                    hypotheses=(IntentHypothesis("dialogue.affirmed", 0.97),),
                    hvac_target_temperature_c=pending_dialogue.hvac_target_temperature_c,
                )
            if reply_kind == "NEGATIVE":
                return self._single("dialogue.declined", 0.97)
        if self._contains(normalized, "sos", "cuu ho", "cap cuu", "emergency", "help"):
            return self._single("safety.emergency_request", 0.98)
        # Matched against the *raw* text (case-insensitive), not the accent-stripped/
        # case-folded `normalized` string, so the canonical uppercase code can be
        # recovered for display and lookup.
        dtc_code_match = DTC_CODE_PATTERN.search(text)
        if dtc_code_match is not None:
            code = dtc_code_match.group(0).upper()
            return IntentResolution(
                route="vehicle.fault_concern",
                confidence=0.97,
                hypotheses=(IntentHypothesis("vehicle.fault_concern", 0.97),),
                mentioned_dtc_code=code,
            )
        requested_temperature = self._requested_temperature(normalized)
        is_climate_request = self._contains(normalized, *self._CLIMATE_TERMS)
        if is_climate_request and requested_temperature is not None:
            if not self._is_supported_hvac_temperature(requested_temperature):
                return IntentResolution(
                    route="climate.invalid_temperature",
                    confidence=0.98,
                    hypotheses=(IntentHypothesis("climate.invalid_temperature", 0.98),),
                    requested_temperature_c=requested_temperature,
                )
            return IntentResolution(
                route="climate.set_temperature",
                confidence=0.97,
                hypotheses=(IntentHypothesis("climate.set_temperature", 0.97),),
                hvac_target_temperature_c=requested_temperature,
                requested_temperature_c=requested_temperature,
            )
        # A compound request such as "nong qua, bat dieu hoa" is not merely a
        # toggle. Route it through the comfort path so the reply is grounded in
        # the actual cabin and energy context.
        if self._contains(normalized, "nong", "bi qua", "ngot ngat", "stuffy", "hot"):
            return self._single("comfort.too_hot", 0.92)
        if self._contains(normalized, *self._GENERIC_HVAC_COMMANDS):
            target = self._default_hvac_temperature(snapshot)
            return IntentResolution(
                route="climate.enable_default",
                confidence=0.92,
                hypotheses=(IntentHypothesis("climate.enable_default", 0.92),),
                hvac_target_temperature_c=target,
            )
        # Symmetric to comfort.too_hot above: a driver reporting cold discomfort
        # (e.g. "Lanh qua") gets the same context-aware HVAC comfort path in the
        # opposite direction, instead of falling through to assistant.general,
        # which can never create an action. Checked *after* _GENERIC_HVAC_COMMANDS
        # above, not before: "lanh" is also a substring of "may lanh" ("air
        # conditioner", e.g. "bat may lanh"/"turn on the AC"), so the more specific
        # multi-word HVAC-toggle phrases must win first, or a plain request to turn
        # on the AC would be misread as a cold complaint.
        if self._contains(normalized, "lanh", "ret", "buot", "cold", "freezing"):
            return self._single("comfort.too_cold", 0.92)
        if self._contains(normalized, "buon ngu", "ngu gat", "met", "sleepy", "tired"):
            return self._single("safety.driver_fatigue", 0.95)
        if self._contains(
            normalized,
            "bao loi",
            "ma loi",
            "dtc",
            "den canh bao",
            "xe co gi la",
            "xe co van de",
            "xe bi lam sao",
            "xe co sao khong",
        ):
            return self._single("vehicle.fault_concern", 0.93)
        if self._contains(
            normalized,
            "tinh trang xe",
            "xe the nao",
            "thong tin xe",
            "vehicle status",
            "tinh hinh hien tai",
            "tinh hinh xe",
            "tinh hinh nhu the nao",
            "how is the car",
            "how is my car",
            "lai xe bao lau",
            "da lai bao lau",
            "lai bao lau roi",
            "thoi gian lai xe",
            "how long have i been driving",
        ):
            return self._single("assistant.vehicle_status", 0.9)
        # Explicit energy/battery questions (SAFEDRIVE_AGENT_ARMOR_PLAN.md Slice 7). These were
        # falling to assistant.general even as fully self-contained utterances ("con bao nhieu
        # pin") -- a routing vocabulary gap, not a conversation-memory gap. Same route as above,
        # but flagged so the reply adds the energy figure; kept as its own branch precisely so
        # the other vehicle-status phrasings keep their existing wording byte-for-byte.
        # Placed after the climate/comfort/fatigue/fault routes, so a discomfort or HVAC
        # sentence that merely mentions energy is still handled by those.
        if self._contains(normalized, "pin", "nang luong", "battery"):
            return IntentResolution(
                route="assistant.vehicle_status",
                confidence=0.9,
                hypotheses=(IntentHypothesis("assistant.vehicle_status", 0.9),),
                asked_about_energy=True,
            )
        if self._contains(
            normalized,
            "khong on",
            "khong khoe",
            "kho chiu",
            "co nen dung",
            "i don't feel well",
            "i do not feel well",
            "what should i do",
        ):
            return self._resolve_ambiguous(snapshot, safety)
        if self._contains(normalized, "noi chuyen", "cang thang", "co don", "stay with me"):
            return self._single("companion.conversation", 0.8)
        return self._single("assistant.general", 0.55, needs_clarification=True)

    @staticmethod
    def _contains(text: str, *terms: str) -> bool:
        return any(term in text for term in terms)

    @staticmethod
    def _requested_temperature(text: str) -> float | None:
        match = IntentResolver._TEMPERATURE_PATTERN.search(text)
        if match is None:
            return None
        sign = -1.0 if match.group(1) else 1.0
        decimal = match.group(3)
        return sign * float(f"{match.group(2)}.{decimal or '0'}")

    @classmethod
    def _is_supported_hvac_temperature(cls, target: float) -> bool:
        return cls._MIN_HVAC_TEMPERATURE_C <= target <= cls._MAX_HVAC_TEMPERATURE_C

    @staticmethod
    def _default_hvac_temperature(snapshot: ContextSnapshot) -> float:
        """Pick a modest default from fresh vehicle energy context.

        The action remains confirmable; this simply avoids making the driver
        repeat a natural command such as "Bật điều hòa".
        """

        return 24.0 if snapshot.state.energyPercent <= 20 else 22.0

    @staticmethod
    def _single(
        name: str, confidence: float, *, needs_clarification: bool = False
    ) -> IntentResolution:
        return IntentResolution(
            route=name,
            confidence=confidence,
            hypotheses=(IntentHypothesis(name, confidence),),
            needs_clarification=needs_clarification,
        )

    @staticmethod
    def _resolve_ambiguous(
        snapshot: ContextSnapshot,
        safety: SafetyEvaluation,
    ) -> IntentResolution:
        hypotheses = [
            IntentHypothesis("safety.driver_fatigue", 0.28),
            IntentHypothesis("comfort.too_hot", 0.24),
            IntentHypothesis("vehicle.fault_concern", 0.2),
            IntentHypothesis("safety.emergency_request", 0.12),
        ]
        reasons = set(safety.risk.reasonCodes)
        if {"user_reported_fatigue", "driving_over_4_hours"} & reasons:
            return IntentResolution(
                route="safety.user_discomfort_check",
                confidence=0.86,
                hypotheses=tuple(hypotheses),
            )
        if "high_severity_dtc" in reasons:
            return IntentResolution(
                route="vehicle.fault_concern",
                confidence=0.83,
                hypotheses=tuple(hypotheses),
            )
        if "hot_cabin" in reasons:
            return IntentResolution(
                route="comfort.too_hot",
                confidence=0.79,
                hypotheses=tuple(hypotheses),
            )
        if not snapshot.state_is_fresh:
            return IntentResolution(
                route="assistant.missing_context",
                confidence=0.9,
                hypotheses=tuple(hypotheses),
            )
        return IntentResolution(
            route="assistant.clarify",
            confidence=0.35,
            hypotheses=tuple(hypotheses),
            needs_clarification=True,
        )
