from dataclasses import dataclass

from app.core.config import Settings
from app.ingestion.canonicalizer import Canonicalizer
from app.ingestion.registry import SignalRegistry
from app.services.signal_ingestion import SignalIngestionService
from app.state.manager import LatestStateManager
from app.state.rolling_window import RollingWindowManager


@dataclass(frozen=True, slots=True)
class ApplicationServices:
    settings: Settings
    signal_registry: SignalRegistry
    canonicalizer: Canonicalizer
    latest_state_manager: LatestStateManager
    rolling_window_manager: RollingWindowManager
    signal_ingestion_service: SignalIngestionService
