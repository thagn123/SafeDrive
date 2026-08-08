"""Tests for SAFEDRIVE_AGENT_ARMOR_PLAN.md Slice 6 (short-turn dialogue continuity).

Exercises MobileSessionStore.answer_assistant()/confirm_action() directly (the same pattern
tests/test_mobile_safety_core.py uses for its emergency-workflow test) so the injectable clock
gives precise control over the dialogue-continuity TTL, rather than going through the HTTP/ASGI
layer tests/test_mobile_compatibility.py uses.

This is short-turn continuity only, not long-term memory: a short affirmative/negative reply
resolves only against the single most recently issued HVAC action from the immediately
preceding turn, and only when nothing safety-relevant intervened.
"""

from __future__ import annotations

import pytest

from app.api.schemas.mobile import (
    ActionConfirmRequest,
    AssistantContext,
    AssistantQueryRequest,
    StartSessionRequest,
)
from app.mobile.session_store import MobileSessionStore
from tests.test_mobile_safety_core import make_request

# Must exceed MobileSessionStore._DIALOGUE_CONTINUITY_TTL_MS (60_000ms) -- hardcoded rather
# than imported, matching this test suite's existing convention for MobileSessionStore.
# SESSION_TTL_MS (see test_mobile_safety_core.test_start_purges_expired_sessions_from_memory).
_PAST_DIALOGUE_TTL_MS = 61_000


async def _start(store: MobileSessionStore, *, now_ms: int) -> str:
    response = await store.start(
        StartSessionRequest(
            deviceId="dev-dialogue",
            appVersion="1.0.0",
            platform="ANDROID",
            mode="REMOTE",
            clientTimeMs=now_ms,
        )
    )
    return response.sessionId


def _ask(session_id: str, text: str, request_id: str) -> AssistantQueryRequest:
    return AssistantQueryRequest(
        sessionId=session_id,
        requestId=request_id,
        text=text,
        source="TEXT",
        context=AssistantContext(stateVersion=1, screen="assistant"),
    )


@pytest.mark.asyncio
async def test_pending_hvac_plus_co_resolves_as_dialogue_affirmed() -> None:
    clock_ms = [0]
    store = MobileSessionStore(clock=lambda: clock_ms[0])
    session_id = await _start(store, now_ms=clock_ms[0])
    update = make_request(updated_at_ms=clock_ms[0]).model_copy(
        update={"sessionId": session_id, "clientEventId": "evt-1"}
    )
    await store.update_state(update)

    proposal = await store.answer_assistant(_ask(session_id, "Lanh qua", "req-1"))
    assert proposal.message.route == "comfort.too_cold"
    assert proposal.message.actions[0].type == "SET_HVAC_TEMPERATURE"
    proposed_target = proposal.message.actions[0].hvacTargetTemperatureC

    clock_ms[0] += 2_000
    reply = await store.answer_assistant(_ask(session_id, "co", "req-2"))
    assert reply.message.route == "dialogue.affirmed"
    assert reply.message.actions[0].type == "SET_HVAC_TEMPERATURE"
    assert reply.message.actions[0].hvacTargetTemperatureC == proposed_target
    assert str(int(proposed_target)) in reply.message.text


@pytest.mark.asyncio
async def test_pending_hvac_plus_ok_resolves_as_dialogue_affirmed() -> None:
    clock_ms = [0]
    store = MobileSessionStore(clock=lambda: clock_ms[0])
    session_id = await _start(store, now_ms=clock_ms[0])
    update = make_request(updated_at_ms=clock_ms[0]).model_copy(
        update={"sessionId": session_id, "clientEventId": "evt-1"}
    )
    await store.update_state(update)
    await store.answer_assistant(_ask(session_id, "Lanh qua", "req-1"))

    clock_ms[0] += 2_000
    reply = await store.answer_assistant(_ask(session_id, "ok", "req-2"))
    assert reply.message.route == "dialogue.affirmed"
    assert reply.message.actions[0].type == "SET_HVAC_TEMPERATURE"


@pytest.mark.asyncio
async def test_pending_hvac_plus_khong_cancels_dialogue() -> None:
    clock_ms = [0]
    store = MobileSessionStore(clock=lambda: clock_ms[0])
    session_id = await _start(store, now_ms=clock_ms[0])
    update = make_request(updated_at_ms=clock_ms[0]).model_copy(
        update={"sessionId": session_id, "clientEventId": "evt-1"}
    )
    await store.update_state(update)
    await store.answer_assistant(_ask(session_id, "Lanh qua", "req-1"))

    clock_ms[0] += 2_000
    reply = await store.answer_assistant(_ask(session_id, "khong", "req-2"))
    assert reply.message.route == "dialogue.declined"
    assert reply.message.text == "Được, tôi giữ nguyên."
    assert reply.message.actions == []


@pytest.mark.asyncio
async def test_no_pending_dialogue_ok_falls_back_to_general() -> None:
    clock_ms = [0]
    store = MobileSessionStore(clock=lambda: clock_ms[0])
    session_id = await _start(store, now_ms=clock_ms[0])
    update = make_request(updated_at_ms=clock_ms[0]).model_copy(
        update={"sessionId": session_id, "clientEventId": "evt-1"}
    )
    await store.update_state(update)

    reply = await store.answer_assistant(_ask(session_id, "ok", "req-1"))
    assert reply.message.route == "assistant.general"
    assert reply.message.actions == []


@pytest.mark.asyncio
async def test_unrelated_vehicle_query_after_hvac_proposal_wins_and_clears_dialogue() -> None:
    clock_ms = [0]
    store = MobileSessionStore(clock=lambda: clock_ms[0])
    session_id = await _start(store, now_ms=clock_ms[0])
    update = make_request(updated_at_ms=clock_ms[0]).model_copy(
        update={"sessionId": session_id, "clientEventId": "evt-1"}
    )
    await store.update_state(update)
    await store.answer_assistant(_ask(session_id, "Lanh qua", "req-1"))

    clock_ms[0] += 2_000
    reply = await store.answer_assistant(_ask(session_id, "Tinh trang xe the nao", "req-2"))
    assert reply.message.route == "assistant.vehicle_status"
    assert all(action.type != "SET_HVAC_TEMPERATURE" for action in reply.message.actions)

    # The stale HVAC dialogue must not survive this unrelated turn either -- clearing is
    # preferred over retaining it, per the slice's own explicit instruction, since retaining it
    # would create ambiguous behavior for whatever comes next.
    clock_ms[0] += 2_000
    followup = await store.answer_assistant(_ask(session_id, "ok", "req-3"))
    assert followup.message.route == "assistant.general"


@pytest.mark.asyncio
async def test_stale_pending_action_after_context_change_ok_does_not_reaffirm() -> None:
    clock_ms = [0]
    store = MobileSessionStore(clock=lambda: clock_ms[0])
    session_id = await _start(store, now_ms=clock_ms[0])
    update = make_request(updated_at_ms=clock_ms[0], cabin_temperature=18.0).model_copy(
        update={"sessionId": session_id, "clientEventId": "evt-1"}
    )
    await store.update_state(update)
    await store.answer_assistant(_ask(session_id, "Lanh qua", "req-1"))

    # cabinTemperatureC is part of the HVAC dependency fingerprint (app/mobile/session_store.py
    # _action_dependency_fingerprint), so this drops the pending action via the EXISTING
    # _rebind_issued_actions guard -- no new logic in this slice is responsible for this case.
    clock_ms[0] += 2_000
    changed = make_request(updated_at_ms=clock_ms[0], cabin_temperature=24.0).model_copy(
        update={"sessionId": session_id, "clientEventId": "evt-2"}
    )
    await store.update_state(changed)

    reply = await store.answer_assistant(_ask(session_id, "ok", "req-2"))
    assert reply.message.route == "assistant.general"
    assert reply.message.actions == []


@pytest.mark.asyncio
async def test_critical_engine_temperature_overrides_pending_dialogue() -> None:
    clock_ms = [0]
    store = MobileSessionStore(clock=lambda: clock_ms[0])
    session_id = await _start(store, now_ms=clock_ms[0])
    update = make_request(updated_at_ms=clock_ms[0]).model_copy(
        update={"sessionId": session_id, "clientEventId": "evt-1"}
    )
    await store.update_state(update)
    await store.answer_assistant(_ask(session_id, "Lanh qua", "req-1"))

    # engineTemperatureC is deliberately NOT part of the HVAC dependency fingerprint (a
    # separate, pre-existing gap -- see FUTURE WORK), so without this slice's explicit
    # safety-level gate in IntentResolver.resolve(), the pending action would still be
    # considered valid here and "ok" would silently reaffirm it despite CRITICAL engine risk.
    clock_ms[0] += 2_000
    critical = make_request(updated_at_ms=clock_ms[0], engine_temperature=118.0).model_copy(
        update={"sessionId": session_id, "clientEventId": "evt-2"}
    )
    await store.update_state(critical)

    reply = await store.answer_assistant(_ask(session_id, "ok", "req-2"))
    assert reply.message.route != "dialogue.affirmed"
    assert all(action.type != "SET_HVAC_TEMPERATURE" for action in reply.message.actions)
    assert reply.message.risk.level == "CRITICAL"


@pytest.mark.asyncio
async def test_dialogue_cleared_after_real_confirmation() -> None:
    clock_ms = [0]
    store = MobileSessionStore(clock=lambda: clock_ms[0])
    session_id = await _start(store, now_ms=clock_ms[0])
    update = make_request(updated_at_ms=clock_ms[0]).model_copy(
        update={"sessionId": session_id, "clientEventId": "evt-1"}
    )
    await store.update_state(update)
    await store.answer_assistant(_ask(session_id, "Lanh qua", "req-1"))

    clock_ms[0] += 2_000
    affirmed = await store.answer_assistant(_ask(session_id, "co", "req-2"))
    action = affirmed.message.actions[0]

    clock_ms[0] += 1_000
    confirmed = await store.confirm_action(
        ActionConfirmRequest(
            sessionId=session_id,
            actionId=action.id,
            actionType=action.type,
            hvacTargetTemperatureC=action.hvacTargetTemperatureC,
            confirmed=True,
            confirmationId="confirm-1",
            contextVersion=1,
        )
    )
    assert confirmed.accepted is True

    # _apply_hvac_action() already clears session.issued_actions on real confirmation -- this
    # slice adds no new clearing logic here, it just relies on that existing behavior.
    clock_ms[0] += 1_000
    followup = await store.answer_assistant(_ask(session_id, "ok", "req-3"))
    assert followup.message.route == "assistant.general"


@pytest.mark.asyncio
async def test_dialogue_cleared_after_decline() -> None:
    clock_ms = [0]
    store = MobileSessionStore(clock=lambda: clock_ms[0])
    session_id = await _start(store, now_ms=clock_ms[0])
    update = make_request(updated_at_ms=clock_ms[0]).model_copy(
        update={"sessionId": session_id, "clientEventId": "evt-1"}
    )
    await store.update_state(update)
    await store.answer_assistant(_ask(session_id, "Lanh qua", "req-1"))

    clock_ms[0] += 2_000
    await store.answer_assistant(_ask(session_id, "khong", "req-2"))

    clock_ms[0] += 2_000
    followup = await store.answer_assistant(_ask(session_id, "ok", "req-3"))
    assert followup.message.route == "assistant.general"


@pytest.mark.asyncio
async def test_expired_pending_dialogue_ttl_cannot_be_reused() -> None:
    clock_ms = [0]
    store = MobileSessionStore(clock=lambda: clock_ms[0])
    session_id = await _start(store, now_ms=clock_ms[0])
    update = make_request(updated_at_ms=clock_ms[0]).model_copy(
        update={"sessionId": session_id, "clientEventId": "evt-1"}
    )
    await store.update_state(update)
    await store.answer_assistant(_ask(session_id, "Lanh qua", "req-1"))

    # Heartbeat-style updates with UNCHANGED fingerprint-relevant fields keep the underlying
    # state fresh (and keep the action alive via _rebind_issued_actions) while wall-clock time
    # passes well beyond the dialogue-continuity TTL. issued_at_ms is not reset by rebinding,
    # so this isolates "TTL expired" from "state went stale" -- without the explicit TTL added
    # in this slice, "ok" would still resolve here since nothing fingerprint-relevant changed.
    elapsed_ms = 0
    while elapsed_ms < _PAST_DIALOGUE_TTL_MS:
        clock_ms[0] += 8_000
        elapsed_ms += 8_000
        heartbeat = make_request(updated_at_ms=clock_ms[0]).model_copy(
            update={"sessionId": session_id, "clientEventId": f"evt-{clock_ms[0]}"}
        )
        await store.update_state(heartbeat)

    reply = await store.answer_assistant(_ask(session_id, "ok", "req-final"))
    assert reply.message.route == "assistant.general"
