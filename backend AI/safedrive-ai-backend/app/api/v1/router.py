from fastapi import APIRouter

from app.api.routes.mobile import router as mobile_router
from app.api.routes.signals import router as signals_router
from app.api.routes.state import router as state_router

v1_router = APIRouter(prefix="/v1")
v1_router.include_router(signals_router)
v1_router.include_router(state_router)
v1_router.include_router(mobile_router)
