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


async def _ready_store(*, speed_kmh: float = 0.0) -> tuple[MobileSessionStore, str]:
    now_ms = 1_000
    store = MobileSessionStore(clock=lambda: now_ms)
    started = await store.start(
        StartSessionRequest(
            deviceId="vehicle-actions",
            appVersion="1.0",
            platform="ANDROID",
            mode="REMOTE",
            clientTimeMs=now_ms,
        )
    )
    update = make_request(updated_at_ms=now_ms).model_copy(
        update={"sessionId": started.sessionId, "clientEventId": "state-actions"}
    )
    update = update.model_copy(
        update={"state": update.state.model_copy(update={"speedKmh": speed_kmh})}
    )
    await store.update_state(update)
    return store, started.sessionId


def _query(session_id: str, text: str) -> AssistantQueryRequest:
    return AssistantQueryRequest(
        sessionId=session_id,
        requestId=f"req-{text}",
        text=text,
        source="TEXT",
        context=AssistantContext(stateVersion=1, screen="assistant"),
    )


@pytest.mark.asyncio
@pytest.mark.parametrize(
    ("text", "action_type"),
    [
        ("Khóa cửa", "LOCK_DOORS"),
        ("Mở khóa cửa", "UNLOCK_DOORS"),
        ("Mở nhạc", "PLAY_MEDIA"),
    ],
)
async def test_real_vehicle_tools_are_issued_only_as_confirmable_actions(
    text: str, action_type: str
) -> None:
    store, session_id = await _ready_store()
    response = await store.answer_assistant(_query(session_id, text))
    assert response.message.actions[0].type == action_type
    assert response.message.actions[0].requiresConfirmation is True


@pytest.mark.asyncio
async def test_door_action_is_refused_while_vehicle_is_moving() -> None:
    store, session_id = await _ready_store(speed_kmh=20.0)
    response = await store.answer_assistant(_query(session_id, "Mở khóa cửa"))
    assert response.message.actions == []
    assert "đang di chuyển" in response.message.text


@pytest.mark.asyncio
async def test_action_authority_rejects_tampered_vehicle_tool_type() -> None:
    store, session_id = await _ready_store()
    response = await store.answer_assistant(_query(session_id, "Khóa cửa"))
    action = response.message.actions[0]
    confirmed = await store.confirm_action(
        ActionConfirmRequest(
            sessionId=session_id,
            actionId=action.id,
            actionType="UNLOCK_DOORS",
            confirmed=True,
            confirmationId="confirm-tampered",
            contextVersion=1,
        )
    )
    assert confirmed.accepted is False


@pytest.mark.asyncio
async def test_authorized_vehicle_tool_is_single_use() -> None:
    store, session_id = await _ready_store()
    response = await store.answer_assistant(_query(session_id, "Mở nhạc"))
    action = response.message.actions[0]
    request = ActionConfirmRequest(
        sessionId=session_id,
        actionId=action.id,
        actionType=action.type,
        confirmed=True,
        confirmationId="confirm-media",
        contextVersion=1,
    )
    first = await store.confirm_action(request)
    replay = await store.confirm_action(
        request.model_copy(update={"confirmationId": "confirm-media-replay"})
    )
    assert first.accepted is True
    assert first.actionResult == "AUTHORIZED_PLAY_MEDIA"
    assert replay.accepted is False
