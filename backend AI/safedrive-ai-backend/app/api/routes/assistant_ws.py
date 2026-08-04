"""WebSocket transport for the SafeDrive assistant chat channel.

This is a thin adapter, not a new safety surface: every query received here is
answered by the exact same `MobileSessionStore.answer_assistant(...)` the REST
`POST /api/v1/assistant/query` route already calls (`app/api/routes/mobile.py`).
Nothing about routing, risk evaluation, narration eligibility, or grounding/guardrail
checks changes -- this file only changes how the request/response travels and adds
periodic heartbeat frames while a call is in flight, so a real (possibly multi-second,
e.g. cold-start Ollama) narration no longer needs a client-side fixed wall-clock
timeout guess. See docs/ARCHITECTURE.md and the plan this was built from for the full
rationale.
"""

import asyncio
import contextlib

from fastapi import APIRouter, WebSocket, WebSocketDisconnect
from pydantic import ValidationError

from app.api.errors import MobileApiError
from app.api.schemas.mobile import AssistantQueryRequest
from app.mobile.session_store import MobileSessionStore

router = APIRouter(tags=["mobile-compatibility"])

_HEARTBEAT_INTERVAL_SECONDS = 2.0
_SESSION_INVALID_CLOSE_CODE = 4401


async def _send_heartbeats(websocket: WebSocket) -> None:
    """Runs alongside `answer_assistant`; cancelled the moment it returns."""
    while True:
        await asyncio.sleep(_HEARTBEAT_INTERVAL_SECONDS)
        await websocket.send_json({"type": "heartbeat"})


@router.websocket("/ws/assistant")
async def assistant_socket(websocket: WebSocket) -> None:
    store: MobileSessionStore | None = getattr(
        websocket.app.state, "mobile_session_store", None
    )
    if store is None:
        await websocket.close(code=1013, reason="service_not_ready")
        return

    session_id = websocket.query_params.get("sessionId")
    if not session_id:
        await websocket.close(code=_SESSION_INVALID_CLOSE_CODE, reason="session_required")
        return
    try:
        await store.validate_session(session_id)
    except MobileApiError:
        await websocket.close(
            code=_SESSION_INVALID_CLOSE_CODE, reason="session_not_found_or_expired"
        )
        return

    await websocket.accept()
    try:
        while True:
            payload = await websocket.receive_json()
            try:
                request = AssistantQueryRequest.model_validate(payload)
            except ValidationError as exc:
                await websocket.send_json(
                    {
                        "type": "error",
                        "requestId": payload.get("requestId"),
                        "code": "VALIDATION",
                        "message": "Request validation failed.",
                        "details": exc.errors(include_url=False, include_context=False),
                    }
                )
                continue

            heartbeat_task = asyncio.ensure_future(_send_heartbeats(websocket))
            try:
                response = await store.answer_assistant(request)
            except MobileApiError as exc:
                await websocket.send_json(
                    {
                        "type": "error",
                        "requestId": request.requestId,
                        "code": exc.code,
                        "message": exc.safe_message,
                    }
                )
                continue
            finally:
                heartbeat_task.cancel()
                with contextlib.suppress(asyncio.CancelledError):
                    await heartbeat_task

            await websocket.send_json(
                {"type": "final", **response.model_dump(mode="json")}
            )
    except WebSocketDisconnect:
        return
