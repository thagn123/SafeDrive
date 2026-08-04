"""In-memory session coordinator for the app-facing SafeDrive MVP API."""

from __future__ import annotations

import asyncio
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
from app.mobile.context import ContextSnapshot, MobileContextBuilder
from app.mobile.emergency import RescueBriefBuilder, SimulatedRescueGateway
from app.mobile.emergency_reasoner import EmergencyLLMReasoner
from app.mobile.intent import IntentResolution
from app.mobile.llm import OllamaIntentClassifier, OllamaNarrator
from app.mobile.safety import SafetyEvaluation, SafetyRiskEngine
from app.mobile.state_bridge import MobileStateBridge

SESSION_TTL_MS = 60 * 60 * 1_000
VERIFYING_EVIDENCE_MS = 5_000
AWAITING_USER_RESPONSE_MS = 15_000
FINAL_COUNTDOWN_MS = 10_000
_TERMINAL_EMERGENCY_STATES = frozenset({"CANCELLED", "SOS_SIMULATED_SENT"})
# Deliberately excludes every route with a grounded vehicle fact or a
# confirmable action (assistant.vehicle_status, vehicle.fault_concern,
# comfort.too_hot, climate.*): HVAC control, status queries, fatigue and
# SOS wording must stay fully deterministic with no LLM call at all, not
# merely LLM-narrated-but-guarded. Only the routes below have no vehicle
# fact or action to preserve, so a wording rewrite carries no safety risk.
_NARRATABLE_ROUTES = frozenset(
    {
        "assistant.general",
        "assistant.clarify",
        "companion.conversation",
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


class MobileSessionStore:
    """Coordinates app sessions while delegating policy to focused services."""

    def __init__(
        self,
        *,
        state_bridge: MobileStateBridge | None = None,
        context_builder: MobileContextBuilder | None = None,
        safety_engine: SafetyRiskEngine | None = None,
        assistant: ContextAwareAssistant | None = None,
        narrator: OllamaNarrator | None = None,
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
            )
            safety = self._safety_engine.evaluate(snapshot, now_ms=started_at)
            completed_at = self._now_ms()
            plan = self._assistant.answer(
                request,
                snapshot,
                safety,
                started_at_ms=started_at,
                completed_at_ms=completed_at,
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
                "model": f"ollama/{self._narrator.model}",
                "llmUsed": True,
            }
        )

    @staticmethod
    def _can_narrate(route: str, safety: SafetyEvaluation) -> bool:
        return (
            not safety.emergency_candidate
            and safety.risk.level not in {"HIGH", "CRITICAL"}
            and route in _NARRATABLE_ROUTES
        )

    @staticmethod
    def _can_classify(resolution: IntentResolution, safety: SafetyEvaluation) -> bool:
        """Only when the deterministic router truly could not decide -- both
        ``IntentResolver.resolve()``'s top-level ``assistant.general`` fallback and
        ``_resolve_ambiguous``'s own final ``assistant.clarify`` fallback set
        ``needs_clarification=True``; nothing else does. Never during an active
        emergency or already-HIGH/CRITICAL risk, mirroring _can_narrate's gate -- in
        both of those cases `_message_and_actions` has already produced the
        emergency-aware or risk-aware deterministic text regardless of route, and a
        reclassification must never discard that."""
        return (
            resolution.needs_clarification
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

    def _purge_expired_sessions(self, timestamp: int) -> None:
        expired_ids = [
            session_id
            for session_id, session in self._sessions.items()
            if session.expires_at_ms <= timestamp
        ]
        for session_id in expired_ids:
            self._sessions.pop(session_id, None)

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
