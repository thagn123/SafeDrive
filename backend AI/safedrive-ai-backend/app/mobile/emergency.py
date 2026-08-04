"""Simulation-only rescue brief construction and gateway receipt handling."""

from __future__ import annotations

import hashlib
from dataclasses import dataclass

from app.api.schemas.mobile import RescueBrief, RescueDispatchReceipt, RescueLocation
from app.mobile.context import ContextSnapshot
from app.mobile.safety import SafetyEvaluation


@dataclass(frozen=True, slots=True)
class SimulatedRescueGateway:
    """In-memory gateway boundary for a visibly non-production SOS demo."""

    endpoint: str = "mock://safedrive-rescue-gateway/v1/events"

    def submit(
        self, *, emergency_id: str, brief: RescueBrief, received_at_ms: int
    ) -> RescueDispatchReceipt:
        del brief  # The payload is retained in EmergencySnapshot, not sent over a real network.
        reference_suffix = hashlib.sha256(emergency_id.encode("utf-8")).hexdigest()[:12]
        return RescueDispatchReceipt(
            provider="MOCK_ROADSIDE_ASSISTANCE_GATEWAY",
            endpoint=self.endpoint,
            outcome="SIMULATED_ACCEPTED",
            referenceId=f"mock_rescue_{reference_suffix}",
            receivedAtMs=received_at_ms,
        )


class RescueBriefBuilder:
    """Builds a concise brief from verified structured state, never raw streams."""

    LOCATION_FRESHNESS_MS = 60_000

    def build(
        self,
        *,
        vehicle_id: str,
        snapshot: ContextSnapshot,
        evaluation: SafetyEvaluation,
        timestamp_ms: int,
    ) -> RescueBrief:
        state = snapshot.state
        location = state.location
        rescue_location: RescueLocation | None = None
        location_status = "UNAVAILABLE"
        if location is not None:
            age_ms = max(0, timestamp_ms - location.capturedAtMs)
            freshness = "FRESH" if age_ms <= self.LOCATION_FRESHNESS_MS else "STALE"
            rescue_location = RescueLocation(
                latitude=location.latitude,
                longitude=location.longitude,
                source=location.source,
                ageMs=age_ms,
                freshness=freshness,
            )
            location_status = freshness

        evidence = [item.code for item in evaluation.evidence]
        if state.speedKmh <= 2:
            evidence.append("vehicle_stopped")
            movement_summary = "The latest vehicle state reports the vehicle as stopped."
        else:
            movement_summary = (
                f"The latest vehicle state reports a speed of {state.speedKmh:.0f} km/h."
            )
        if location_status == "STALE":
            evidence.append("location_stale")
        if location_status == "UNAVAILABLE":
            evidence.append("location_unavailable")

        return RescueBrief(
            eventType="CRASH_AND_NO_RESPONSE",
            vehicleId=vehicle_id,
            timestampMs=timestamp_ms,
            lastKnownLocation=rescue_location,
            locationStatus=location_status,
            vehicleStatusSummary=(
                "Crash signal detected. "
                f"{movement_summary} "
                "An occupant has not responded to the in-cabin check. "
                "Human verification is required."
            ),
            riskLevel="CRITICAL",
            evidence=list(dict.fromkeys(evidence)),
            realEmergencyDispatchEnabled=False,
        )
