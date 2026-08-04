"""Adapter from the Android state envelope to existing canonical signals."""

from __future__ import annotations

import datetime
import hashlib
from dataclasses import dataclass

from app.api.schemas.mobile import StateUpdateRequest
from app.api.schemas.signals import SignalBatchRequest
from app.core.services import ApplicationServices
from app.domain.models.signal import (
    CrashSignalInput,
    DTCCodeSignalInput,
    HVACTemperatureSignalInput,
    SignalSource,
    SpeedSignalInput,
)
from app.services.signal_ingestion import IngestionError, SignalBatchBusinessResult


@dataclass(frozen=True, slots=True)
class BridgeReceipt:
    """Internal record of a best-effort canonical projection for one app update."""

    attempted: bool
    accepted: int = 0
    duplicate: int = 0
    quarantined: int = 0
    canonical_state_version: int = 0
    reason: str | None = None


class MobileStateBridge:
    """Publishes only semantics that the existing canonical registry can represent.

    The app-facing v1 request does not yet carry an authoritative physical
    vehicle/trip identity. A stable hashed session partition is therefore used
    until the FPT VHAL adapter can provide actual platform identifiers.
    """

    def __init__(self, services: ApplicationServices) -> None:
        self._services = services

    async def publish(
        self,
        request: StateUpdateRequest,
        *,
        session_id: str,
        state_version: int,
    ) -> BridgeReceipt:
        signals = self._to_canonical_inputs(request, session_id=session_id, sequence=state_version)
        if not signals:
            return BridgeReceipt(attempted=False, reason="No canonical mapping is available.")
        try:
            result = await self._services.signal_ingestion_service.process_batch(
                SignalBatchRequest(signals=signals),
                idempotency_key=self._idempotency_key(session_id, request.clientEventId),
                active_profile=self._services.settings.active_profile,
            )
        except IngestionError as exc:
            # App state is still retained and visibly marked with quality data.
            # A bridge projection must never make a cockpit state update vanish.
            return BridgeReceipt(attempted=True, reason=exc.code)
        return self._receipt(result)

    def _to_canonical_inputs(
        self,
        request: StateUpdateRequest,
        *,
        session_id: str,
        sequence: int,
    ) -> list[
        SpeedSignalInput | HVACTemperatureSignalInput | CrashSignalInput | DTCCodeSignalInput
    ]:
        partition_hash = hashlib.sha256(session_id.encode("utf-8")).hexdigest()[:16]
        vehicle_id = f"mobile_{partition_hash}"
        trip_id = f"trip_{partition_hash}"
        occurred_at = datetime.datetime.fromtimestamp(
            request.state.updatedAtMs / 1_000, tz=datetime.UTC
        )
        common = {
            "source": SignalSource.SIMULATOR,
            "occurred_at": occurred_at,
            "quality": "VALID",
            "vehicle_id": vehicle_id,
            "trip_id": trip_id,
            "sequence": sequence,
            "metadata": {"simulated": True, "mobile_session": partition_hash},
        }
        event_hash = hashlib.sha256(request.clientEventId.encode("utf-8")).hexdigest()[:16]
        signals: list[
            SpeedSignalInput | HVACTemperatureSignalInput | CrashSignalInput | DTCCodeSignalInput
        ] = [
            SpeedSignalInput(
                **common,
                signal_id=f"m_{event_hash}_speed",
                signal_type="vehicle.speed_kmh",
                value={"value": request.state.speedKmh},
            ),
            HVACTemperatureSignalInput(
                **common,
                signal_id=f"m_{event_hash}_cabin",
                signal_type="hvac.temperature",
                value={
                    "temperature": request.state.hvacTargetTemperatureC
                    if request.state.hvacTargetTemperatureC is not None
                    else request.state.cabinTemperatureC
                },
            ),
        ]
        if request.state.crashDetected:
            signals.append(
                CrashSignalInput(
                    **common,
                    signal_id=f"m_{event_hash}_crash",
                    signal_type="vehicle.crash",
                    value={"severity": "SIMULATED_CRASH"},
                )
            )
        high_priority_dtc = next(
            (
                dtc
                for dtc in request.state.activeDtcs
                if dtc.severity in {"CRITICAL", "HIGH", "MEDIUM", "LOW"}
            ),
            None,
        )
        if high_priority_dtc is not None:
            signals.append(
                DTCCodeSignalInput(
                    **common,
                    signal_id=f"m_{event_hash}_dtc",
                    signal_type="dtc.code",
                    value={"code": high_priority_dtc.code},
                )
            )
        return signals

    @staticmethod
    def _idempotency_key(session_id: str, client_event_id: str) -> str:
        digest = hashlib.sha256(f"{session_id}:{client_event_id}".encode()).hexdigest()
        return f"mobile-{digest[:48]}"

    @staticmethod
    def _receipt(result: SignalBatchBusinessResult) -> BridgeReceipt:
        return BridgeReceipt(
            attempted=True,
            accepted=result.accepted,
            duplicate=result.duplicate,
            quarantined=result.quarantined,
            canonical_state_version=result.state_version,
        )
