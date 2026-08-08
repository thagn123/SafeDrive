from __future__ import annotations

import pytest

from app.api.schemas.mobile import (
    AssistantContext,
    AssistantQueryRequest,
    EventRequest,
    StartSessionRequest,
)
from app.mobile.memory import InMemoryContextMemory, memory_fact
from app.mobile.session_store import MobileSessionStore
from tests.test_mobile_safety_core import make_request


async def _start(store: MobileSessionStore, device_id: str = "memory-device") -> str:
    response = await store.start(
        StartSessionRequest(
            deviceId=device_id,
            appVersion="1.0",
            platform="ANDROID",
            mode="REMOTE",
            clientTimeMs=1_000,
        )
    )
    return response.sessionId


def _ask(session_id: str, text: str) -> AssistantQueryRequest:
    return AssistantQueryRequest(
        sessionId=session_id,
        requestId=f"req-{text}",
        text=text,
        source="TEXT",
        context=AssistantContext(stateVersion=1, screen="assistant"),
    )


@pytest.mark.asyncio
async def test_memory_recalls_provenance_tagged_safety_situation() -> None:
    memory = InMemoryContextMemory()
    store = MobileSessionStore(memory=memory, clock=lambda: 1_000)
    session_id = await _start(store)
    update = make_request(updated_at_ms=1_000, engine_temperature=116.0).model_copy(
        update={"sessionId": session_id, "clientEventId": "critical-state"}
    )
    await store.update_state(update)
    response = await store.answer_assistant(_ask(session_id, "Bạn nhớ gì?"))
    assert response.message.route == "assistant.memory_recall"
    assert "CRITICAL" in response.message.text
    assert response.message.actions == []


@pytest.mark.asyncio
async def test_memory_survives_a_new_session_for_same_device() -> None:
    memory = InMemoryContextMemory()
    first = MobileSessionStore(memory=memory, clock=lambda: 1_000)
    first_session = await _start(first)
    await memory.append(
        "memory-device",
        memory_fact(
            kind="driver_preference",
            summary="Driver prefers HVAC 24 C",
            source="confirmed_vehicle_action",
            now_ms=1_000,
            ttl_ms=60_000,
        ),
    )

    second = MobileSessionStore(memory=memory, clock=lambda: 2_000)
    second_session = await _start(second)
    update = make_request(updated_at_ms=2_000).model_copy(
        update={"sessionId": second_session, "clientEventId": "new-session-state"}
    )
    await second.update_state(update)
    response = await second.answer_assistant(_ask(second_session, "Nhớ lần trước không?"))
    assert first_session != second_session
    assert "HVAC 24 C" in response.message.text


@pytest.mark.asyncio
async def test_expired_memory_is_not_returned() -> None:
    memory = InMemoryContextMemory()
    await memory.append(
        "device",
        memory_fact(
            kind="situation",
            summary="expired",
            source="test",
            now_ms=0,
            ttl_ms=10,
        ),
    )
    assert await memory.recent("device", now_ms=11) == ()


@pytest.mark.asyncio
async def test_context_event_and_response_are_remembered_with_sources_and_ttl() -> None:
    memory = InMemoryContextMemory()
    now = 1_000
    store = MobileSessionStore(memory=memory, clock=lambda: now)
    session_id = await _start(store, device_id="complete-memory-device")
    update = make_request(updated_at_ms=now).model_copy(
        update={"sessionId": session_id, "clientEventId": "context-state"}
    )
    await store.update_state(update)
    await store.accept_event(
        EventRequest(
            sessionId=session_id,
            eventId="event-fatigue",
            type="USER_REPORTED_FATIGUE",
            occurredAtMs=now,
            reason="driver pressed fatigue button",
        )
    )
    await store.answer_assistant(_ask(session_id, "Tình trạng xe thế nào?"))

    facts = await memory.recent("complete-memory-device", now_ms=now, limit=20)
    facts_by_kind = {fact.kind: fact for fact in facts}

    assert {"vehicle_context", "vehicle_event", "assistant_response"} <= facts_by_kind.keys()
    assert facts_by_kind["vehicle_context"].source.startswith("PHONE_SIMULATOR:state_v")
    assert facts_by_kind["vehicle_event"].source == "mobile_event"
    assert facts_by_kind["assistant_response"].source == "agent_armor"
    assert all(fact.expires_at_ms > now for fact in facts)
