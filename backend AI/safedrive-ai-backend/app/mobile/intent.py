"""Deterministic intent hypotheses grounded by SafeDrive context."""

from __future__ import annotations

import re
import unicodedata
from dataclasses import dataclass

from app.mobile.context import ContextSnapshot
from app.mobile.safety import SafetyEvaluation


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
    ) -> IntentResolution:
        normalized = normalize_text(text)
        if self._contains(normalized, "sos", "cuu ho", "cap cuu", "emergency", "help"):
            return self._single("safety.emergency_request", 0.98)
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
