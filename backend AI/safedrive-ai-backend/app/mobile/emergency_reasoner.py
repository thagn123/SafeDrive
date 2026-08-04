"""LLM-assisted second opinion for emergency candidate open/escalation decisions.

The deterministic SafetyRiskEngine and the fixed emergency state-machine
timers (VERIFYING_EVIDENCE_MS/AWAITING_USER_RESPONSE_MS/FINAL_COUNTDOWN_MS in
app/mobile/session_store.py) remain the sole safety net whenever this
reasoner is unavailable, times out, or returns anything that fails
validation -- callers must always have a deterministic fallback path and must
never block on this class. Nothing here changes a timer, and nothing here
ever claims a real rescue dispatch happens: SimulatedRescueGateway and
realEmergencyDispatchEnabled=False (app/mobile/emergency.py) are untouched by
this module.
"""

from __future__ import annotations

import json
import re
from collections.abc import Sequence
from dataclasses import dataclass

import httpx

from app.api.schemas.mobile import VehicleState

_CJK_CHARACTERS = re.compile(r"[㐀-鿿豈-﫿]")
_VIETNAMESE_WORD = re.compile(
    r"\b(tôi|bạn|xe|lái|đường|cùng|có|và|nên|người|xác|nhận)\b",
    re.IGNORECASE,
)
_MAX_REASONING_CHARS = 320


@dataclass(frozen=True, slots=True)
class EmergencyJudgment:
    """A validated, bounded LLM opinion. Never authoritative on its own -- see
    module docstring: the caller decides what, if anything, to do with it."""

    open_candidate: bool
    reasoning: str


@dataclass(frozen=True, slots=True)
class EmergencyLLMReasoner:
    """Uses a local Ollama model only to form a bounded, validated opinion on
    an already-deterministically-triggered emergency decision point."""

    base_url: str
    model: str
    timeout_seconds: float

    async def assess_candidate(
        self, *, state: VehicleState, evidence_codes: Sequence[str]
    ) -> EmergencyJudgment | None:
        """Called after the deterministic rule has already opened a
        VERIFYING_EVIDENCE candidate. A `False` verdict may cancel that
        candidate back to IDLE (see session_store.py); a `None` result (any
        failure) leaves the already-open candidate exactly as it is."""

        return await self._assess(
            state=state,
            evidence_codes=evidence_codes,
            question=(
                "A deterministic safety rule has already flagged a possible crash "
                "candidate and opened a fixed verification window. Decide whether "
                "this genuinely warrants staying open, or looks like a false "
                "trigger given the full structured context."
            ),
        )

    async def assess_escalation(
        self, *, state: VehicleState, evidence_codes: Sequence[str]
    ) -> EmergencyJudgment | None:
        """Called when the occupant has not responded within the fixed waiting
        window. The deterministic escalation to the final SOS countdown always
        proceeds regardless of this result -- it only supplies the recorded
        reasoning text, never gates the transition (session_store.py already
        commits to escalating before this is even called)."""

        return await self._assess(
            state=state,
            evidence_codes=evidence_codes,
            question=(
                "The occupant has not responded within the fixed waiting window. "
                "A deterministic rule is about to escalate to the final SOS "
                "countdown regardless of your answer. Explain briefly whether "
                "escalating is warranted given the structured context; your "
                "answer only affects the recorded reasoning, never the escalation "
                "itself."
            ),
        )

    async def _assess(
        self,
        *,
        state: VehicleState,
        evidence_codes: Sequence[str],
        question: str,
    ) -> EmergencyJudgment | None:
        context = {
            "crashDetected": state.crashDetected,
            "passengerResponse": state.passengerResponse,
            "speedKmh": state.speedKmh,
            "cabinTemperatureC": state.cabinTemperatureC,
            "continuousDrivingMinutes": state.continuousDrivingMinutes,
            "activeDtcCodes": [dtc.code for dtc in state.activeDtcs],
            "evidence": list(evidence_codes),
        }
        context_json = json.dumps(context, ensure_ascii=False, separators=(",", ":"))
        payload = {
            "model": self.model,
            "stream": False,
            "keep_alive": "30m",
            "format": "json",
            "options": {"temperature": 0.0, "num_predict": 96, "num_ctx": 768},
            "messages": [
                {
                    "role": "system",
                    "content": (
                        "You are a second-opinion safety reviewer for SafeDrive's "
                        "simulated SOS flow. You only ever see the structured "
                        "vehicle fields given to you -- never raw video, audio, "
                        "CAN, or sensor streams. The countdown timers are fixed "
                        "and never change based on your answer. You never claim a "
                        "real rescue call happens; this system is simulation-only. "
                        "You never diagnose a medical condition. Reply with strict "
                        'JSON only: {"open_candidate": true|false, "reasoning": '
                        '"<short Vietnamese explanation>"}.'
                    ),
                },
                {
                    "role": "user",
                    "content": f"{question}\n\nSTRUCTURED_CONTEXT_JSON:\n{context_json}",
                },
            ],
        }
        try:
            async with httpx.AsyncClient(timeout=self.timeout_seconds) as client:
                response = await client.post(f"{self.base_url.rstrip('/')}/api/chat", json=payload)
                response.raise_for_status()
        except httpx.HTTPError:
            return None

        content = response.json().get("message", {}).get("content")
        if not isinstance(content, str):
            return None
        try:
            parsed = json.loads(content)
        except (json.JSONDecodeError, TypeError):
            return None
        if not isinstance(parsed, dict):
            return None
        open_candidate = parsed.get("open_candidate")
        reasoning = parsed.get("reasoning")
        if not isinstance(open_candidate, bool) or not isinstance(reasoning, str):
            return None
        normalized = " ".join(reasoning.split())
        if (
            not normalized
            or len(normalized) > _MAX_REASONING_CHARS
            or _CJK_CHARACTERS.search(normalized) is not None
            or _VIETNAMESE_WORD.search(normalized) is None
        ):
            return None
        return EmergencyJudgment(open_candidate=open_candidate, reasoning=normalized)
