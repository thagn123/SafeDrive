"""In-memory session coordinator for the app-facing SafeDrive MVP API."""

from __future__ import annotations

import asyncio
import re
import time
import uuid
from collections.abc import Callable, Sequence
from dataclasses import dataclass, replace

from app.api.errors import MobileApiError
from app.api.schemas.mobile import (
    ActionConfirmRequest,
    ActionConfirmResponse,
    AssistantQueryRequest,
    AssistantQueryResponse,
    ChatMessage,
    EmergencyResponseRequest,
    EmergencySnapshot,
    EventAccepted,
    EventRequest,
    EvidenceItem,
    StartSessionRequest,
    StartSessionResponse,
    StateEnvelope,
    StateUpdateRequest,
    VehicleState,
)
from app.mobile.assistant import AssistantPlan, ContextAwareAssistant
from app.mobile.context import ContextPack, ContextSnapshot, MobileContextBuilder
from app.mobile.emergency import RescueBriefBuilder, SimulatedRescueGateway
from app.mobile.emergency_reasoner import EmergencyLLMReasoner
from app.mobile.intent import DTC_CODE_PATTERN, IntentResolution, PendingDialogue
from app.mobile.llm import NarrationProvider, OllamaIntentClassifier
from app.mobile.safety import SafetyEvaluation, SafetyRiskEngine
from app.mobile.state_bridge import MobileStateBridge

# A code-like token (2-5 letters immediately followed by 2-5 digits, e.g. "XYZ123",
# "ABX900") that a driver asks about in a message the deterministic router couldn't
# otherwise categorize. Deliberately narrow: requires letters-then-digits with no
# separator, which is uncommon in ordinary Vietnamese/English sentences but is exactly
# the shape of a made-up or misremembered technical code. DTC-shaped tokens
# ([PBCU]+4 hex, always a single leading letter) don't match this pattern's {2,5}
# leading-letter requirement and are excluded explicitly besides, since those are
# already handled by the dedicated vehicle.fault_concern/DTC_CODE_PATTERN path.
_UNVERIFIED_CODE_TOKEN_PATTERN = re.compile(r"\b[A-Za-z]{2,5}[0-9]{2,5}\b")

SESSION_TTL_MS = 60 * 60 * 1_000
VERIFYING_EVIDENCE_MS = 5_000
AWAITING_USER_RESPONSE_MS = 15_000
FINAL_COUNTDOWN_MS = 10_000
# Purely defensive cap on MobileSession.engine_temperature_samples, independent of
# MobileContextBuilder.ENGINE_TREND_WINDOW_MS's time-based trim -- guards against a
# pathological client sending updates far faster than the ~4s heartbeat cadence, never a
# factor in normal operation (SAFEDRIVE_AGENT_ARMOR_PLAN.md Slice 4).
_ENGINE_TREND_MAX_SAMPLES = 100
# Short-turn dialogue continuity only (SAFEDRIVE_AGENT_ARMOR_PLAN.md Slice 6) -- the smallest
# explicit TTL on top of the existing per-turn-replacement/dependency-fingerprint invalidation
# below. Without this, an "ok" arriving minutes later would still resolve as long as none of
# the dependency fingerprint's fields happened to change in the meantime (plausible if
# telemetry is quiet), which is exactly the "stale ok executes an old request" case this slice
# must prevent. 60s is generous for a genuine immediate follow-up reply, far short of anything
# that could be called memory.
_DIALOGUE_CONTINUITY_TTL_MS = 60_000
_TERMINAL_EMERGENCY_STATES = frozenset({"CANCELLED", "SOS_SIMULATED_SENT"})
# Risk level and emergency-candidate status (both checked in _can_narrate) already gate
# narration for every route below to LOW/MEDIUM, non-emergency turns -- HIGH/CRITICAL
# replies are never narrated regardless of route. safety.emergency_request is the one
# route additionally excluded here, independent of risk level: it's the SOS-simulation
# offer/countdown, the single most safety-critical route, and wording for it stays fully
# deterministic no matter what. Every other route's grounded facts/actions are decided
# and bound to the session before narration ever runs (see answer_assistant), and the
# narrator's guardrail (number-grounding plus, for DTC/fatigue/status,
# ContextAwareAssistant.required_narration_snippets) preserves them verbatim -- so a
# wording rewrite for these routes carries no safety risk beyond what the guardrail
# already enforces.
_NARRATABLE_ROUTES = frozenset(
    {
        "assistant.general",
        "assistant.clarify",
        "assistant.vehicle_status",
        "assistant.missing_context",
        "companion.conversation",
        "safety.driver_fatigue",
        "safety.user_discomfort_check",
        "climate.set_temperature",
        "climate.enable_default",
        "climate.invalid_temperature",
        "comfort.too_hot",
        "vehicle.fault_concern",
    }
)


def now_ms() -> int:
    return int(time.time() * 1_000)


@dataclass(slots=True)
class MobileSession:
    session_id: str
    expires_at_ms: int
    state: StateEnvelope | None
    emergency: EmergencySnapshot
    last_update: StateUpdateRequest | None
    issued_actions: dict[str, IssuedAction]
    # Bounded, in-memory-only recent history for the engine-temperature trend (see
    # SAFEDRIVE_AGENT_ARMOR_PLAN.md Slice 4): (timestamp_ms, engineTemperatureC), oldest first,
    # trimmed to MobileContextBuilder.ENGINE_TREND_WINDOW_MS on every append in update_state().
    # Never persisted; lost on session expiry/process restart like the rest of MobileSession.
    engine_temperature_samples: list[tuple[int, float]]


@dataclass(frozen=True, slots=True)
class IssuedAction:
    """Server-side binding for a confirmation-capable assistant action."""

    action_type: str
    state_version: int
    hvac_target_temperature_c: float | None
    # A typed HVAC recommendation can survive telemetry changes that do not
    # affect its safety/comfort basis (for example speed or GPS updates). The
    # server owns this fingerprint; clients can never supply or alter it.
    dependency_fingerprint: tuple[object, ...] | None
    # Server clock at issuance, used only to bound short-turn dialogue continuity
    # (SAFEDRIVE_AGENT_ARMOR_PLAN.md Slice 6 -- see _DIALOGUE_CONTINUITY_TTL_MS and
    # MobileSessionStore._pending_dialogue). Not used by confirm_action()'s own validation,
    # which already has its own independent state-version/dependency-fingerprint checks.
    issued_at_ms: int


class MobileSessionStore:
    """Coordinates app sessions while delegating policy to focused services."""

    def __init__(
        self,
        *,
        state_bridge: MobileStateBridge | None = None,
        context_builder: MobileContextBuilder | None = None,
        safety_engine: SafetyRiskEngine | None = None,
        assistant: ContextAwareAssistant | None = None,
        narrator: NarrationProvider | None = None,
        classifier: OllamaIntentClassifier | None = None,
        reasoner: EmergencyLLMReasoner | None = None,
        rescue_brief_builder: RescueBriefBuilder | None = None,
        rescue_gateway: SimulatedRescueGateway | None = None,
        clock: Callable[[], int] | None = None,
    ) -> None:
        self._sessions: dict[str, MobileSession] = {}
        self._lock = asyncio.Lock()
        self._state_bridge = state_bridge
        self._context_builder = context_builder or MobileContextBuilder()
        self._safety_engine = safety_engine or SafetyRiskEngine()
        self._assistant = assistant or ContextAwareAssistant()
        self._narrator = narrator
        # Advisory-only reclassification for text the deterministic router could not
        # match confidently at all -- see OllamaIntentClassifier's docstring and
        # _can_classify for the exact safety gate.
        self._classifier = classifier
        # Optional second opinion on the two emergency decision points
        # (app/mobile/emergency_reasoner.py). Always additive to the
        # deterministic rules below, which remain the safety net -- see that
        # module's docstring.
        self._reasoner = reasoner
        self._rescue_brief_builder = rescue_brief_builder or RescueBriefBuilder()
        self._rescue_gateway = rescue_gateway or SimulatedRescueGateway()
        self._clock = clock or now_ms

    def _now_ms(self) -> int:
        return self._clock()

    async def start(self, request: StartSessionRequest) -> StartSessionResponse:
        del request  # The MVP tracks no device identity beyond the issued session ID.
        created_at = self._now_ms()
        session_id = f"session_{uuid.uuid4().hex}"
        session = MobileSession(
            session_id=session_id,
            expires_at_ms=created_at + SESSION_TTL_MS,
            state=None,
            emergency=EmergencySnapshot(
                emergencyId=f"emg_{uuid.uuid4().hex}",
                state="IDLE",
                realEmergencyDispatchEnabled=False,
            ),
            last_update=None,
            issued_actions={},
            engine_temperature_samples=[],
        )
        async with self._lock:
            self._purge_expired_sessions(created_at)
            self._sessions[session_id] = session
        return StartSessionResponse(
            sessionId=session_id,
            expiresAtMs=session.expires_at_ms,
            serverTimeMs=created_at,
            contractVersion="v1",
            realEmergencyDispatchEnabled=False,
        )

    async def get_state(self, session_id: str) -> StateEnvelope:
        async with self._lock:
            session = self._require_session(session_id)
            if session.state is None:
                raise MobileApiError(
                    404, "UNSUPPORTED", "No state has been accepted for this session."
                )
            escalated_deadline = self._advance_emergency_if_due(session, self._now_ms())
            session.state = session.state.model_copy(update={"emergency": session.emergency})
            envelope = session.state
            last_state = session.last_update.state if session.last_update is not None else None
            evidence_codes = [item.code for item in session.emergency.evidence]
        self._maybe_schedule_escalation_reasoning(
            session_id, last_state, evidence_codes, escalated_deadline
        )
        return envelope

    async def update_state(self, request: StateUpdateRequest) -> StateEnvelope:
        accepted_at = self._now_ms()
        async with self._lock:
            session = self._require_session(request.sessionId)
            next_version = 1 if session.state is None else session.state.stateVersion + 1
            snapshot = self._context_builder.build(
                request,
                state_version=next_version,
                now_ms=accepted_at,
            )
            evaluation = self._safety_engine.evaluate(snapshot, now_ms=accepted_at)
            session.state = StateEnvelope(
                state=request.state,
                driverSupportSignals=request.driverSupportSignals,
                riskAssessment=evaluation.risk,
                restRecommendation=evaluation.rest,
                stateVersion=next_version,
                acceptedAtMs=accepted_at,
            )
            session.last_update = request
            if snapshot.state_is_fresh:
                self._record_engine_temperature_sample(
                    session,
                    timestamp_ms=request.state.updatedAtMs,
                    temperature_c=request.state.engineTemperatureC,
                )
            session.issued_actions = self._rebind_issued_actions(
                session.issued_actions,
                request,
                state_version=next_version,
                state_is_fresh=snapshot.state_is_fresh,
            )
            new_candidate_deadline = self._refresh_emergency(session, snapshot, evaluation, accepted_at)
            session.state = session.state.model_copy(update={"emergency": session.emergency})
            envelope = session.state

        if self._state_bridge is not None:
            await self._state_bridge.publish(
                request,
                session_id=request.sessionId,
                state_version=envelope.stateVersion,
            )
        self._maybe_schedule_candidate_reasoning(
            request.sessionId,
            request.state,
            [item.code for item in evaluation.evidence],
            new_candidate_deadline,
        )
        return envelope

    async def accept_event(self, request: EventRequest) -> EventAccepted:
        accepted_at = self._now_ms()
        async with self._lock:
            session = self._require_session(request.sessionId)
            if request.type == "USER_REPORTED_FATIGUE" and session.state is not None:
                signals = session.state.driverSupportSignals.model_copy(
                    update={"userReportedFatigue": True}
                )
                state_request = StateUpdateRequest(
                    sessionId=request.sessionId,
                    state=session.state.state,
                    driverSupportSignals=signals,
                    source="PHONE_SIMULATOR",
                    clientEventId=request.eventId,
                )
                next_version = session.state.stateVersion + 1
                snapshot = self._context_builder.build(
                    state_request,
                    state_version=next_version,
                    now_ms=accepted_at,
                )
                evaluation = self._safety_engine.evaluate(snapshot, now_ms=accepted_at)
                session.state = StateEnvelope(
                    state=state_request.state,
                    driverSupportSignals=signals,
                    riskAssessment=evaluation.risk,
                    restRecommendation=evaluation.rest,
                    stateVersion=next_version,
                    acceptedAtMs=accepted_at,
                )
                session.last_update = state_request
                session.issued_actions = self._rebind_issued_actions(
                    session.issued_actions,
                    state_request,
                    state_version=next_version,
                    state_is_fresh=snapshot.state_is_fresh,
                )
                new_candidate_deadline = self._refresh_emergency(session, snapshot, evaluation, accepted_at)
                session.state = session.state.model_copy(update={"emergency": session.emergency})
                evidence_codes = [item.code for item in evaluation.evidence]
            else:
                new_candidate_deadline = None
                evidence_codes = []
            result = EventAccepted(
                eventId=request.eventId,
                accepted=True,
                acceptedAtMs=accepted_at,
                stateVersion=session.state.stateVersion if session.state is not None else None,
            )
        if session.state is not None:
            self._maybe_schedule_candidate_reasoning(
                request.sessionId, session.state.state, evidence_codes, new_candidate_deadline
            )
        return result

    async def confirm_action(self, request: ActionConfirmRequest) -> ActionConfirmResponse:
        bridge_request: StateUpdateRequest | None = None
        async with self._lock:
            session = self._require_session(request.sessionId)
            latest_version = session.state.stateVersion if session.state is not None else 0
            if request.contextVersion != latest_version:
                return ActionConfirmResponse(
                    accepted=False,
                    message="The vehicle context changed. Please review the latest recommendation.",
                    serverTimeMs=self._now_ms(),
                )
            issued = session.issued_actions.get(request.actionId)
            if issued is None or issued.state_version != latest_version:
                return ActionConfirmResponse(
                    accepted=False,
                    message="This action was not issued for the current vehicle context.",
                    serverTimeMs=self._now_ms(),
                )
            if (
                request.actionType != issued.action_type
                or request.hvacTargetTemperatureC != issued.hvac_target_temperature_c
            ):
                return ActionConfirmResponse(
                    accepted=False,
                    message="The action details do not match the approved assistant plan.",
                    serverTimeMs=self._now_ms(),
                )
            if not request.confirmed:
                session.issued_actions.pop(request.actionId, None)
                return ActionConfirmResponse(
                    accepted=False,
                    message="Action was not confirmed.",
                    serverTimeMs=self._now_ms(),
                )
            session.issued_actions.pop(request.actionId, None)
            if request.actionType == "SET_HVAC_TEMPERATURE":
                bridge_request = self._apply_hvac_action(session, request)
                result = ActionConfirmResponse(
                    accepted=True,
                    actionResult=(
                        f"SIMULATED_SET_HVAC_TEMPERATURE_{request.hvacTargetTemperatureC:g}C"
                    ),
                    message=(
                        f"HVAC target was set to {request.hvacTargetTemperatureC:g} C in simulation mode."
                    ),
                    serverTimeMs=self._now_ms(),
                )
            else:
                result = ActionConfirmResponse(
                    accepted=True,
                    actionResult=f"SIMULATED_{request.actionType}",
                    message="Action confirmation was accepted in simulation mode.",
                    serverTimeMs=self._now_ms(),
                )

        if bridge_request is not None and self._state_bridge is not None:
            await self._state_bridge.publish(
                bridge_request,
                session_id=request.sessionId,
                state_version=latest_version + 1,
            )
        return result

    def _apply_hvac_action(
        self, session: MobileSession, request: ActionConfirmRequest
    ) -> StateUpdateRequest:
        """Recheck fresh state, then apply the one typed cockpit control in the MVP."""

        if session.state is None or session.last_update is None:
            raise MobileApiError(
                409,
                "CONFLICT",
                "A current vehicle state is required before changing HVAC.",
            )
        timestamp = self._now_ms()
        current_snapshot = self._context_builder.build(
            session.last_update,
            state_version=session.state.stateVersion,
            now_ms=timestamp,
        )
        if not current_snapshot.state_is_fresh:
            raise MobileApiError(
                409,
                "CONFLICT",
                "The vehicle context is no longer fresh. Please review the latest state.",
            )

        target = request.hvacTargetTemperatureC
        if target is None:  # Guarded by schema validation; retained for defensive clarity.
            raise MobileApiError(422, "VALIDATION", "HVAC target is required.")
        updated_request = session.last_update.model_copy(
            update={
                "state": session.last_update.state.model_copy(
                    update={"hvacTargetTemperatureC": target, "updatedAtMs": timestamp}
                ),
                "clientEventId": f"hvac-action-{request.confirmationId}",
            }
        )
        next_version = session.state.stateVersion + 1
        snapshot = self._context_builder.build(
            updated_request,
            state_version=next_version,
            now_ms=timestamp,
        )
        evaluation = self._safety_engine.evaluate(snapshot, now_ms=timestamp)
        session.last_update = updated_request
        # Any other action from the previous response is bound to a now-stale
        # vehicle context and must be re-planned before it can be confirmed.
        session.issued_actions.clear()
        session.state = StateEnvelope(
            state=updated_request.state,
            driverSupportSignals=updated_request.driverSupportSignals,
            riskAssessment=evaluation.risk,
            restRecommendation=evaluation.rest,
            stateVersion=next_version,
            acceptedAtMs=timestamp,
        )
        self._refresh_emergency(session, snapshot, evaluation, timestamp)
        session.state = session.state.model_copy(update={"emergency": session.emergency})
        return updated_request

    async def get_emergency(self, session_id: str, emergency_id: str) -> EmergencySnapshot:
        async with self._lock:
            session = self._require_session(session_id)
            if session.emergency.emergencyId != emergency_id:
                raise MobileApiError(404, "UNSUPPORTED", "Emergency state was not found.")
            escalated_deadline = self._advance_emergency_if_due(session, self._now_ms())
            snapshot = session.emergency
            last_state = session.last_update.state if session.last_update is not None else None
            evidence_codes = [item.code for item in session.emergency.evidence]
        self._maybe_schedule_escalation_reasoning(
            session_id, last_state, evidence_codes, escalated_deadline
        )
        return snapshot

    async def respond_emergency(
        self, emergency_id: str, request: EmergencyResponseRequest
    ) -> EmergencySnapshot:
        async with self._lock:
            session = self._require_session(request.sessionId)
            if session.emergency.emergencyId != emergency_id:
                raise MobileApiError(404, "UNSUPPORTED", "Emergency state was not found.")

            if session.emergency.state in _TERMINAL_EMERGENCY_STATES:
                # A cancelled or already-simulated-sent emergency is terminal.
                # A stray, duplicate, or late response must never reopen it or
                # re-run the simulated dispatch.
                return session.emergency

            if request.response in {"USER_OK", "CANCEL_SOS"}:
                session.emergency = session.emergency.model_copy(
                    update={"state": "CANCELLED", "deadlineMs": None}
                )
            elif request.response == "NO_RESPONSE" and session.emergency.state != "IDLE":
                timestamp = self._now_ms()
                evidence = self._append_evidence(
                    session.emergency.evidence,
                    EvidenceItem(
                        code="occupant_no_response",
                        label="No occupant response was recorded",
                        detectedAtMs=timestamp,
                    ),
                )
                session.emergency = session.emergency.model_copy(
                    update={
                        "state": "FINAL_COUNTDOWN",
                        "deadlineMs": timestamp + FINAL_COUNTDOWN_MS,
                        "evidence": evidence,
                    }
                )
            return session.emergency

    async def answer_assistant(self, request: AssistantQueryRequest) -> AssistantQueryResponse:
        started_at = self._now_ms()
        async with self._lock:
            session = self._require_session(request.sessionId)
            if session.state is None or session.last_update is None:
                return self._missing_context_response(request, started_at)
            snapshot = self._context_builder.build(
                session.last_update,
                state_version=session.state.stateVersion,
                now_ms=started_at,
                engine_temperature_samples=session.engine_temperature_samples,
            )
            safety = self._safety_engine.evaluate(snapshot, now_ms=started_at)
            # Read BEFORE this turn's session.issued_actions reassignment below overwrites it --
            # this captures the *previous* turn's single pending HVAC proposal, if any (SAFEDRIVE_
            # AGENT_ARMOR_PLAN.md Slice 6, short-turn dialogue continuity only).
            pending_dialogue = self._pending_dialogue(session, now_ms=started_at)
            completed_at = self._now_ms()
            plan = self._assistant.answer(
                request,
                snapshot,
                safety,
                started_at_ms=started_at,
                completed_at_ms=completed_at,
                pending_dialogue=pending_dialogue,
            )
            # Confirmation requests are accepted only when they refer to an
            # action the server issued for this exact state version. This is a
            # contract guardrail, not a client-side trust assumption.
            session.issued_actions = {
                action.id: IssuedAction(
                    action_type=action.type,
                    state_version=session.state.stateVersion,
                    hvac_target_temperature_c=action.hvacTargetTemperatureC,
                    dependency_fingerprint=self._action_dependency_fingerprint(
                        action.type, session.last_update
                    ),
                    issued_at_ms=completed_at,
                )
                for action in plan.response.message.actions
            }
            response = plan.response
            state_version = session.state.stateVersion

        # Advisory reclassification for text the deterministic router could not match
        # confidently at all (see _can_classify). Runs outside the session lock for the
        # same reason as the narrator below: local inference must never block
        # telemetry. It can only ever substitute a different, still-fixed reply
        # template -- see OllamaIntentClassifier's docstring for why this can't turn
        # into an invented action or safety wording.
        if self._classifier is not None and self._can_classify(plan.resolution, safety):
            plan, response = await self._maybe_reclassify(
                request, plan, response, snapshot, safety, started_at, state_version
            )

        # The model sees only a bounded ContextPack after intent, risk, and allowed
        # actions have been fixed. It runs outside the session lock so local
        # inference cannot block telemetry. High-risk and emergency wording stays
        # deterministic and is never delayed or softened by model generation.
        if self._narrator is None or not self._can_narrate(plan.resolution.route, safety):
            return response
        if plan.resolution.route == "assistant.general":
            unverified_token = self._find_unverified_code_token(request.text, plan.context_pack)
            if unverified_token is not None:
                # Deterministic intercept, no LLM call at all: a live 7B model was
                # observed fabricating a plausible-sounding technical explanation for
                # exactly this shape of input (see docs/TEST_EVIDENCE.md). Prompt
                # wording alone was not treated as sufficient for this case.
                safe_text = (
                    f'Tôi không có dữ liệu đã xác minh về mã "{unverified_token}", nên không thể '
                    "giải thích chính xác. Hãy kiểm tra lại mã hoặc cung cấp thông tin từ hệ thống "
                    "chẩn đoán xe."
                )
                return response.model_copy(
                    update={"message": response.message.model_copy(update={"text": safe_text})}
                )
            # The true catch-all -- nothing matched a known category -- gets a genuine
            # free-form answer-or-redirect instead of a rewrite of an already-fixed reply.
            narrated = await self._narrator.answer_open_query(
                user_text=request.text,
                deterministic_fallback=response.message.text,
                context_pack=plan.context_pack,
                risk_level=safety.risk.level,
                risk_reasons=tuple(safety.risk.reasonCodes),
            )
        else:
            narrated = await self._narrator.rewrite_grounded_reply(
                user_text=request.text,
                approved_reply=response.message.text,
                context_pack=plan.context_pack,
                risk_level=safety.risk.level,
                risk_reasons=tuple(safety.risk.reasonCodes),
                allowed_actions=[
                    {
                        "type": action.type,
                        "title": action.title,
                        "requiresConfirmation": action.requiresConfirmation,
                        "hvacTargetTemperatureC": action.hvacTargetTemperatureC,
                    }
                    for action in response.message.actions
                ],
                required_verbatim_snippets=self._assistant.required_narration_snippets(
                    plan.resolution, snapshot, safety
                ),
            )
        if narrated is None:
            # An LLM call was actually attempted for this route (unlike the early return
            # above, which never attempts one at all) but was unreachable, timed out, or
            # was rejected by rewrite_grounded_reply's own grounding/language checks --
            # either way the caller already has the safe deterministic `response` and
            # must not see a raw error.
            return response.model_copy(
                update={"fallback": True, "fallbackReason": "provider_unavailable"}
            )
        completed_at = self._now_ms()
        return response.model_copy(
            update={
                "message": response.message.model_copy(
                    update={"text": narrated, "latencyMs": completed_at - started_at}
                ),
                "serverTimeMs": completed_at,
                "serverProcessingMs": completed_at - started_at,
                "model": f"{self._narrator.provider_name}/{self._narrator.model}",
                "llmUsed": True,
            }
        )

    @staticmethod
    def _find_unverified_code_token(text: str, context_pack: ContextPack) -> str | None:
        """A code-like token (letters immediately followed by digits, e.g. "XYZ123")
        the driver asks about that appears nowhere in the grounded context. Returns the
        canonicalized (uppercase) token, or ``None`` if the text contains no such token.

        DTC-shaped tokens are excluded: those are already intercepted earlier by
        ``IntentResolver`` and answered by the dedicated, catalog-aware
        ``vehicle.fault_concern`` path, never reaching ``assistant.general`` at all.
        """

        context_blob = repr(context_pack).upper()
        for match in _UNVERIFIED_CODE_TOKEN_PATTERN.finditer(text):
            token = match.group(0)
            if DTC_CODE_PATTERN.fullmatch(token):
                continue
            if token.upper() in context_blob:
                continue
            return token.upper()
        return None

    @staticmethod
    def _can_narrate(route: str, safety: SafetyEvaluation) -> bool:
        return (
            not safety.emergency_candidate
            and safety.risk.level not in {"HIGH", "CRITICAL"}
            and route in _NARRATABLE_ROUTES
        )

    @staticmethod
    def _can_classify(resolution: IntentResolution, safety: SafetyEvaluation) -> bool:
        """Only for ``_resolve_ambiguous``'s own final ``assistant.clarify`` fallback --
        text that plausibly matched a safety-relevant category (fatigue/comfort/vehicle-
        concern keywords) but couldn't be disambiguated with confidence. Deliberately
        excludes ``IntentResolver.resolve()``'s top-level ``assistant.general`` catch-all
        (nothing matched at all): live evidence showed the classifier, forced to pick
        from a closed label set, will sometimes commit to an unrelated-but-superficially-
        plausible label (e.g. assistant.vehicle_status) for genuinely off-topic text
        instead of admitting nothing fits -- silently routing it to an irrelevant
        deterministic template and defeating OllamaNarrator.answer_open_query's whole
        purpose. assistant.general goes straight to that open-answer path instead; only
        assistant.clarify's genuinely safety-adjacent ambiguity is worth the classifier's
        judgment call. Never during an active emergency or already-HIGH/CRITICAL risk,
        mirroring _can_narrate's gate -- in both of those cases `_message_and_actions`
        has already produced the emergency-aware or risk-aware deterministic text
        regardless of route, and a reclassification must never discard that."""
        return (
            resolution.route == "assistant.clarify"
            and not safety.emergency_candidate
            and safety.risk.level not in {"HIGH", "CRITICAL"}
        )

    async def _maybe_reclassify(
        self,
        request: AssistantQueryRequest,
        plan: AssistantPlan,
        response: AssistantQueryResponse,
        snapshot: ContextSnapshot,
        safety: SafetyEvaluation,
        started_at: int,
        state_version: int,
    ) -> tuple[AssistantPlan, AssistantQueryResponse]:
        assert self._classifier is not None
        label = await self._classifier.classify(
            user_text=request.text,
            context_pack=plan.context_pack,
            risk_level=safety.risk.level,
            risk_reasons=tuple(safety.risk.reasonCodes),
        )
        if label is None or label == plan.resolution.route:
            return plan, response

        reclassified_resolution = replace(plan.resolution, route=label, needs_clarification=False)
        text, actions = self._assistant.build_reply(reclassified_resolution, snapshot, safety, request.requestId)
        completed_at = self._now_ms()
        reclassified_response = response.model_copy(
            update={
                "message": response.message.model_copy(
                    update={
                        "text": text,
                        "actions": actions,
                        "route": label,
                        "latencyMs": completed_at - started_at,
                    }
                ),
                "serverTimeMs": completed_at,
                "serverProcessingMs": completed_at - started_at,
                "model": f"ollama-intent/{self._classifier.model}",
            }
        )

        async with self._lock:
            session = self._sessions.get(request.sessionId)
            if session is None or session.state is None or session.state.stateVersion != state_version:
                # Vehicle state moved on while the classifier was thinking; discard
                # the reclassification rather than bind actions against a stale state
                # version -- the original deterministic response is still correct.
                return plan, response
            session.issued_actions = {
                action.id: IssuedAction(
                    action_type=action.type,
                    state_version=state_version,
                    hvac_target_temperature_c=action.hvacTargetTemperatureC,
                    dependency_fingerprint=self._action_dependency_fingerprint(
                        action.type, session.last_update
                    ),
                    issued_at_ms=completed_at,
                )
                for action in actions
            }
        return replace(plan, response=reclassified_response, resolution=reclassified_resolution), reclassified_response

    def _require_session(self, session_id: str) -> MobileSession:
        session = self._sessions.get(session_id)
        if session is None:
            raise MobileApiError(404, "UNSUPPORTED", "Session was not found or has expired.")
        if session.expires_at_ms <= self._now_ms():
            self._sessions.pop(session_id, None)
            raise MobileApiError(404, "UNSUPPORTED", "Session was not found or has expired.")
        return session

    async def validate_session(self, session_id: str) -> MobileSession:
        """Public existence/expiry check with the exact semantics ``_require_session``
        already gives every REST route. Used by the WebSocket assistant endpoint
        (``app/api/routes/assistant_ws.py``) to gate the connection at handshake time,
        since WebSocket has no equivalent of FastAPI's HTTPException-based exception
        handlers to translate ``MobileApiError`` automatically."""
        async with self._lock:
            return self._require_session(session_id)

    @staticmethod
    def _record_engine_temperature_sample(
        session: MobileSession, *, timestamp_ms: int, temperature_c: float
    ) -> None:
        """Appends one engine-temperature sample and trims to the trend window
        (SAFEDRIVE_AGENT_ARMOR_PLAN.md Slice 4). Called only from update_state(), only when the
        just-accepted state is itself fresh (mirrors the same freshness gate the rest of the
        session already applies) -- this is retention/bookkeeping only, never a safety decision,
        and never read by SafetyRiskEngine.

        Rejects a sample whose timestamp does not strictly advance the last stored one (clock
        skew, replay, or a duplicate/out-of-order client event): appending is silently skipped in
        that case rather than raising, since a single malformed sample must never break state
        ingestion. app/mobile/context.py's derive_engine_temperature_trend independently applies
        the same guard, so this is defense-in-depth, not the only line protecting it.
        """

        history = session.engine_temperature_samples
        if history and timestamp_ms <= history[-1][0]:
            return
        history.append((timestamp_ms, temperature_c))
        cutoff_ms = timestamp_ms - MobileContextBuilder.ENGINE_TREND_WINDOW_MS
        trimmed = [sample for sample in history if sample[0] >= cutoff_ms]
        session.engine_temperature_samples = trimmed[-_ENGINE_TREND_MAX_SAMPLES:]

    def _purge_expired_sessions(self, timestamp: int) -> None:
        expired_ids = [
            session_id
            for session_id, session in self._sessions.items()
            if session.expires_at_ms <= timestamp
        ]
        for session_id in expired_ids:
            self._sessions.pop(session_id, None)

    @staticmethod
    def _pending_dialogue(session: MobileSession, *, now_ms: int) -> PendingDialogue | None:
        """Short-turn dialogue continuity only (SAFEDRIVE_AGENT_ARMOR_PLAN.md Slice 6) -- NOT
        long-term memory. A short affirmative/negative reply may only ever be resolved against
        the single most recently issued HVAC action from the immediately preceding turn, never
        anything older and never any other action type in this slice (SUGGEST_REST_STOP/
        START_SOS_COUNTDOWN confirmations are out of scope here -- see FUTURE WORK).

        Deliberately reuses session.issued_actions instead of introducing a separate stored
        structure: that dict is already fully replaced every answer_assistant() call (so a
        pending dialogue naturally cannot outlive one turn), and _rebind_issued_actions already
        drops an entry whose dependency fingerprint no longer matches the latest telemetry (so a
        context change that matters to the HVAC basis already clears this for free). The explicit
        _DIALOGUE_CONTINUITY_TTL_MS check below additionally covers the case where nothing
        fingerprint-relevant happens to change for several minutes -- without it, a stale "ok"
        would still resolve just because no telemetry field happened to move. Returns None
        whenever there is anything other than exactly one pending, non-expired HVAC action, so an
        ambiguous or non-HVAC pending state never gets guessed at.
        """

        if len(session.issued_actions) != 1:
            return None
        (issued,) = session.issued_actions.values()
        if issued.action_type != "SET_HVAC_TEMPERATURE" or issued.hvac_target_temperature_c is None:
            return None
        if now_ms - issued.issued_at_ms > _DIALOGUE_CONTINUITY_TTL_MS:
            return None
        return PendingDialogue(hvac_target_temperature_c=issued.hvac_target_temperature_c)

    @staticmethod
    def _action_dependency_fingerprint(
        action_type: str, request: StateUpdateRequest | None
    ) -> tuple[object, ...] | None:
        """Return the server-owned state subset that makes an HVAC plan safe.

        The latest state version still protects the confirm endpoint. This
        fingerprint decides whether a newly received telemetry sample changed
        the *basis* of an already issued HVAC recommendation. Fields such as
        speed, location and sampling timestamps are deliberately excluded: a
        remote cockpit updates them frequently, but they do not change the
        selected HVAC target or its safety policy in this MVP.
        """

        if action_type != "SET_HVAC_TEMPERATURE" or request is None:
            return None
        state = request.state
        signals = request.driverSupportSignals
        dtc_severity = tuple((dtc.code, dtc.severity) for dtc in state.activeDtcs)
        return (
            state.cabinTemperatureC,
            state.energyPercent,
            state.hvacTargetTemperatureC,
            state.continuousDrivingMinutes,
            state.crashDetected,
            state.passengerResponse,
            signals.userReportedFatigue,
            dtc_severity,
        )

    def _rebind_issued_actions(
        self,
        issued_actions: dict[str, IssuedAction],
        request: StateUpdateRequest,
        *,
        state_version: int,
        state_is_fresh: bool,
    ) -> dict[str, IssuedAction]:
        """Keep only typed actions whose decision inputs are unchanged.

        This preserves the anti-tampering contract: the server retains action
        id, target and current state version. It only avoids invalidating a
        legitimate HVAC confirmation because of unrelated live telemetry.
        """

        if not state_is_fresh:
            return {}
        rebound: dict[str, IssuedAction] = {}
        for action_id, issued in issued_actions.items():
            fingerprint = self._action_dependency_fingerprint(issued.action_type, request)
            if issued.dependency_fingerprint is None or fingerprint != issued.dependency_fingerprint:
                continue
            rebound[action_id] = replace(issued, state_version=state_version)
        return rebound

    @staticmethod
    def _append_evidence(items: list[EvidenceItem], item: EvidenceItem) -> list[EvidenceItem]:
        if any(existing.code == item.code for existing in items):
            return items
        return [*items, item]

    def _refresh_emergency(
        self,
        session: MobileSession,
        snapshot: ContextSnapshot,
        evaluation: SafetyEvaluation,
        timestamp: int,
    ) -> int | None:
        """Returns the newly-set VERIFYING_EVIDENCE deadline if this call just
        opened a fresh candidate, else None. The caller uses that to decide
        whether to schedule an LLM second opinion -- see
        _maybe_schedule_candidate_reasoning."""

        # The context builder's public shape is intentionally small; this
        # narrow runtime check keeps emergency escalation dependent on fresh
        # structured evidence instead of an old chat plan.
        if not snapshot.state.crashDetected or not snapshot.state_is_fresh:
            return None
        evidence = session.emergency.evidence
        for item in evaluation.evidence:
            evidence = self._append_evidence(
                evidence,
                EvidenceItem(code=item.code, label=item.label, detectedAtMs=timestamp),
            )
        # Once the safety workflow has started, repeated state samples must not
        # reset its deadline. Remote Mode treats this backend state machine as
        # authoritative, just as Demo Mode does with its local reducer.
        if not evaluation.emergency_candidate:
            return None
        if session.emergency.state not in {"IDLE", "CANDIDATE_DETECTED"}:
            return None
        deadline = timestamp + VERIFYING_EVIDENCE_MS
        session.emergency = session.emergency.model_copy(
            update={
                "state": "VERIFYING_EVIDENCE",
                "deadlineMs": deadline,
                "evidence": evidence,
                "rescueBrief": self._rescue_brief_builder.build(
                    vehicle_id=self._simulated_vehicle_id(session.session_id),
                    snapshot=snapshot,
                    evaluation=evaluation,
                    timestamp_ms=timestamp,
                ),
                "rescueDispatch": None,
                "reasoningSummary": None,
            }
        )
        return deadline

    def _maybe_schedule_candidate_reasoning(
        self,
        session_id: str,
        state: VehicleState,
        evidence_codes: Sequence[str],
        new_candidate_deadline: int | None,
    ) -> None:
        if new_candidate_deadline is None or self._reasoner is None:
            return
        asyncio.create_task(
            self._run_candidate_reasoning(
                session_id, state, list(evidence_codes), new_candidate_deadline
            )
        )

    async def _run_candidate_reasoning(
        self,
        session_id: str,
        state: VehicleState,
        evidence_codes: list[str],
        expected_deadline_ms: int,
    ) -> None:
        """Runs entirely outside self._lock (independent re-audit item: local
        inference must never block telemetry -- same reasoning as the
        narrator in answer_assistant). Applies its verdict only if the same
        VERIFYING_EVIDENCE window it was asked about is still open and
        unchanged; any progression, cancellation or new episode in the
        meantime makes this a safe no-op."""

        assert self._reasoner is not None
        judgment = await self._reasoner.assess_candidate(
            state=state, evidence_codes=evidence_codes
        )
        if judgment is None:
            return
        async with self._lock:
            session = self._sessions.get(session_id)
            if session is None:
                return
            if (
                session.emergency.state != "VERIFYING_EVIDENCE"
                or session.emergency.deadlineMs != expected_deadline_ms
            ):
                return
            if judgment.open_candidate:
                session.emergency = session.emergency.model_copy(
                    update={"reasoningSummary": judgment.reasoning}
                )
            else:
                session.emergency = session.emergency.model_copy(
                    update={
                        "state": "IDLE",
                        "deadlineMs": None,
                        "evidence": [],
                        "rescueBrief": None,
                        "rescueDispatch": None,
                        "reasoningSummary": judgment.reasoning,
                    }
                )
            if session.state is not None:
                session.state = session.state.model_copy(update={"emergency": session.emergency})

    def _maybe_schedule_escalation_reasoning(
        self,
        session_id: str,
        state: VehicleState | None,
        evidence_codes: Sequence[str],
        escalated_deadline: int | None,
    ) -> None:
        if escalated_deadline is None or self._reasoner is None or state is None:
            return
        asyncio.create_task(
            self._run_escalation_reasoning(session_id, state, list(evidence_codes))
        )

    async def _run_escalation_reasoning(
        self, session_id: str, state: VehicleState, evidence_codes: list[str]
    ) -> None:
        """The no-response escalation to FINAL_COUNTDOWN has already happened
        deterministically by the time this runs (see _advance_emergency_if_due)
        -- this only attaches a reasoning explanation, purely informational,
        never gating the transition itself. Applied unconditionally if the
        session still exists: unlike the candidate-open veto, there is no
        state to protect here, only a text field."""

        assert self._reasoner is not None
        judgment = await self._reasoner.assess_escalation(
            state=state, evidence_codes=evidence_codes
        )
        if judgment is None:
            return
        async with self._lock:
            session = self._sessions.get(session_id)
            if session is None:
                return
            session.emergency = session.emergency.model_copy(
                update={"reasoningSummary": judgment.reasoning}
            )
            if session.state is not None:
                session.state = session.state.model_copy(update={"emergency": session.emergency})

    def _advance_emergency_if_due(self, session: MobileSession, timestamp: int) -> int | None:
        """Returns the newly-set FINAL_COUNTDOWN deadline if this call just
        escalated on no-response, else None -- used to schedule an LLM
        reasoning explanation for that escalation (the escalation itself
        always proceeds deterministically; see _maybe_schedule_escalation_reasoning)."""

        escalated_deadline: int | None = None
        # Catch up after a delayed poll without sending twice. The only I/O-like
        # boundary is the simulation-only gateway call in FINAL_COUNTDOWN.
        while True:
            emergency = session.emergency
            if emergency.deadlineMs is None or timestamp < emergency.deadlineMs:
                return escalated_deadline
            if emergency.state == "VERIFYING_EVIDENCE":
                session.emergency = emergency.model_copy(
                    update={
                        "state": "AWAITING_USER_RESPONSE",
                        "deadlineMs": emergency.deadlineMs + AWAITING_USER_RESPONSE_MS,
                    }
                )
                continue
            if emergency.state == "AWAITING_USER_RESPONSE":
                escalated_deadline = emergency.deadlineMs + FINAL_COUNTDOWN_MS
                session.emergency = emergency.model_copy(
                    update={
                        "state": "FINAL_COUNTDOWN",
                        "deadlineMs": escalated_deadline,
                    }
                )
                continue
            if emergency.state != "FINAL_COUNTDOWN" or emergency.rescueBrief is None:
                return escalated_deadline
            receipt = self._rescue_gateway.submit(
                emergency_id=emergency.emergencyId,
                brief=emergency.rescueBrief,
                received_at_ms=timestamp,
            )
            session.emergency = emergency.model_copy(
                update={
                    "state": "SOS_SIMULATED_SENT",
                    "deadlineMs": None,
                    "rescueDispatch": receipt,
                }
            )
            return escalated_deadline

    @staticmethod
    def _simulated_vehicle_id(session_id: str) -> str:
        return f"veh_sim_{session_id.removeprefix('session_')[:12]}"

    def _missing_context_response(
        self, request: AssistantQueryRequest, started_at: int
    ) -> AssistantQueryResponse:
        completed_at = self._now_ms()
        return AssistantQueryResponse(
            requestId=request.requestId,
            message=ChatMessage(
                id=f"msg_{request.requestId}",
                sender="SAFEDRIVE",
                text="Tôi cần trạng thái xe hiện tại trước khi có thể trả lời theo ngữ cảnh.",
                timestampMs=completed_at,
                actions=[],
                route="assistant.missing_context",
                latencyMs=completed_at - started_at,
            ),
            serverTimeMs=completed_at,
            serverProcessingMs=completed_at - started_at,
            model="deterministic-context-router",
            finishReason="STOP",
        )
