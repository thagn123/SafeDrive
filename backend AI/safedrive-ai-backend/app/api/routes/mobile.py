"""App-facing compatibility routes for the existing Android Remote gateway."""

from typing import Annotated

from fastapi import APIRouter, Depends, Query, Request

from app.api.errors import MobileApiError
from app.api.schemas.mobile import (
    ActionConfirmRequest,
    ActionConfirmResponse,
    AssistantQueryRequest,
    AssistantQueryResponse,
    EmergencyResponseRequest,
    EmergencySnapshot,
    EventAccepted,
    EventRequest,
    StartSessionRequest,
    StartSessionResponse,
    StateEnvelope,
    StateUpdateRequest,
)
from app.mobile.session_store import MobileSessionStore

router = APIRouter(tags=["mobile-compatibility"])


def get_mobile_session_store(request: Request) -> MobileSessionStore:
    store = getattr(request.app.state, "mobile_session_store", None)
    if store is None:
        raise MobileApiError(
            503, "SERVER", "Mobile compatibility service is not ready.", retryable=True
        )
    return store


@router.post(
    "/sessions/start",
    response_model=StartSessionResponse,
    summary="Start a SafeDrive cockpit session",
)
async def start_session(
    payload: StartSessionRequest,
    store: Annotated[MobileSessionStore, Depends(get_mobile_session_store)],
) -> StartSessionResponse:
    return await store.start(payload)


@router.post(
    "/state/update",
    response_model=StateEnvelope,
    summary="Accept the latest cockpit state for a session",
)
async def update_state(
    payload: StateUpdateRequest,
    store: Annotated[MobileSessionStore, Depends(get_mobile_session_store)],
) -> StateEnvelope:
    return await store.update_state(payload)


async def get_mobile_state(
    request: Request,
    session_id: str,
    since_version: int | None,
) -> StateEnvelope:
    """Used by the shared GET /state route when `sessionId` is supplied."""
    del since_version  # The MVP returns the latest envelope for bootstrap/reconnect.
    return await get_mobile_session_store(request).get_state(session_id)


@router.post(
    "/assistant/query",
    response_model=AssistantQueryResponse,
    summary="Answer a text or transcript-only SafeDrive assistant turn",
)
async def query_assistant(
    payload: AssistantQueryRequest,
    store: Annotated[MobileSessionStore, Depends(get_mobile_session_store)],
) -> AssistantQueryResponse:
    return await store.answer_assistant(payload)


@router.post(
    "/events",
    response_model=EventAccepted,
    summary="Accept a non-critical cockpit simulator or assistant event",
)
async def accept_event(
    payload: EventRequest,
    store: Annotated[MobileSessionStore, Depends(get_mobile_session_store)],
) -> EventAccepted:
    return await store.accept_event(payload)


@router.post(
    "/actions/confirm",
    response_model=ActionConfirmResponse,
    summary="Confirm an allowed simulated cockpit action",
)
async def confirm_action(
    payload: ActionConfirmRequest,
    store: Annotated[MobileSessionStore, Depends(get_mobile_session_store)],
) -> ActionConfirmResponse:
    return await store.confirm_action(payload)


@router.get(
    "/emergency/{emergency_id}",
    response_model=EmergencySnapshot,
    summary="Read the simulated emergency state for a session",
)
async def get_emergency(
    emergency_id: str,
    session_id: Annotated[str, Query(alias="sessionId", min_length=1, max_length=128)],
    store: Annotated[MobileSessionStore, Depends(get_mobile_session_store)],
) -> EmergencySnapshot:
    return await store.get_emergency(session_id, emergency_id)


@router.post(
    "/emergency/{emergency_id}/respond",
    response_model=EmergencySnapshot,
    summary="Record an occupant response in the simulated emergency workflow",
)
async def respond_emergency(
    emergency_id: str,
    payload: EmergencyResponseRequest,
    store: Annotated[MobileSessionStore, Depends(get_mobile_session_store)],
) -> EmergencySnapshot:
    return await store.respond_emergency(emergency_id, payload)
