"""Pydantic models for the existing Android Remote Mode contract.

This layer intentionally uses the app's camelCase field names so FastAPI can
exchange JSON with the Android client without a mapper in the mobile app.
"""

from typing import Literal

from pydantic import BaseModel, ConfigDict, Field, model_validator


class MobileModel(BaseModel):
    model_config = ConfigDict(extra="forbid")


class HealthCapabilities(MobileModel):
    assistant: bool
    emergencySimulation: bool
    cockpitStream: bool


class StartSessionRequest(MobileModel):
    deviceId: str = Field(min_length=1, max_length=128)
    appVersion: str = Field(min_length=1, max_length=64)
    platform: str = Field(min_length=1, max_length=64)
    mode: Literal["DEMO", "REMOTE"]
    clientTimeMs: int = Field(ge=0)


class StartSessionResponse(MobileModel):
    sessionId: str
    expiresAtMs: int = Field(ge=0)
    serverTimeMs: int = Field(ge=0)
    contractVersion: str
    realEmergencyDispatchEnabled: Literal[False] = False


class Dtc(MobileModel):
    code: str = Field(min_length=1, max_length=32)
    title: str = Field(min_length=1, max_length=200)
    description: str = Field(min_length=1, max_length=500)
    severity: Literal["LOW", "MEDIUM", "HIGH", "CRITICAL"]
    recommendation: str = Field(min_length=1, max_length=500)
    updatedAtMs: int = Field(ge=0)


class VehicleLocation(MobileModel):
    """Last known location supplied by a GPS or simulator adapter."""

    latitude: float = Field(ge=-90, le=90)
    longitude: float = Field(ge=-180, le=180)
    source: Literal["GPS", "SIMULATOR", "GPS_OR_SIMULATOR"]
    capturedAtMs: int = Field(ge=0)


class VehicleState(MobileModel):
    speedKmh: float = Field(ge=0, le=350)
    engineTemperatureC: float = Field(ge=-50, le=250)
    cabinTemperatureC: float = Field(ge=-50, le=100)
    energyPercent: int = Field(ge=0, le=100)
    continuousDrivingMinutes: int | None = Field(default=None, ge=0, le=1_440)
    steeringLastInteractionSeconds: int | None = Field(default=None, ge=0, le=86_400)
    driverSeatOccupied: bool | None = None
    wearableConnected: bool
    activeDtcs: list[Dtc] = Field(default_factory=list, max_length=20)
    crashDetected: bool
    passengerResponse: Literal["RESPONSIVE", "NO_RESPONSE", "UNKNOWN"]
    updatedAtMs: int = Field(ge=0)
    hvacTargetTemperatureC: float | None = Field(default=None, ge=16, le=30)
    location: VehicleLocation | None = None


class DriverSupportSignals(MobileModel):
    steeringSignalAvailable: bool
    seatSensorAvailable: bool
    wearableLastUpdateMs: int | None = Field(default=None, ge=0)
    wearableHeartRateBpm: int | None = Field(default=None, ge=0, le=300)
    userReportedFatigue: bool | None = None
    availableSourceCount: int = Field(ge=0, le=20)
    totalSourceCount: int = Field(ge=0, le=20)


class RiskAssessment(MobileModel):
    level: Literal["LOW", "MEDIUM", "HIGH", "CRITICAL"]
    title: str
    message: str
    reasonCodes: list[str] = Field(default_factory=list)


class RestRecommendation(MobileModel):
    level: Literal["INSUFFICIENT_DATA", "NORMAL", "MONITOR", "CONSIDER_REST", "REST_RECOMMENDED"]
    title: str
    message: str
    confidence: Literal["LOW", "MEDIUM", "HIGH"]
    reasonCodes: list[str] = Field(default_factory=list)
    updatedAtMs: int = Field(ge=0)


class StateUpdateRequest(MobileModel):
    sessionId: str = Field(min_length=1, max_length=128)
    state: VehicleState
    driverSupportSignals: DriverSupportSignals
    source: Literal["MOCK", "PHONE_SIMULATOR", "VEHICLE_ADAPTER"]
    clientEventId: str = Field(min_length=1, max_length=128)


class AssistantContext(MobileModel):
    stateVersion: int = Field(ge=0)
    screen: str = Field(min_length=1, max_length=64)


class AssistantQueryRequest(MobileModel):
    sessionId: str = Field(min_length=1, max_length=128)
    requestId: str = Field(min_length=1, max_length=128)
    text: str = Field(min_length=1, max_length=2_000)
    source: Literal["TEXT", "VOICE", "QUICK_PROMPT", "RETRY"] = "TEXT"
    locale: str = Field(default="vi-VN", min_length=2, max_length=16)
    clientAttemptOf: str | None = Field(default=None, max_length=128)
    context: AssistantContext


class SafeDriveAction(MobileModel):
    id: str
    type: Literal[
        "SHOW_WARNING",
        "OPEN_DIAGNOSTICS",
        "SUGGEST_REST_STOP",
        "START_SOS_COUNTDOWN",
        "SET_HVAC_TEMPERATURE",
        "LOCK_DOORS",
        "UNLOCK_DOORS",
        "PLAY_MEDIA",
        "NONE",
    ]
    title: str
    requiresConfirmation: bool
    hvacTargetTemperatureC: float | None = Field(default=None, ge=16, le=30)


class ChatMessage(MobileModel):
    id: str
    sender: Literal["USER", "SAFEDRIVE"]
    text: str
    timestampMs: int = Field(ge=0)
    risk: RiskAssessment | None = None
    actions: list[SafeDriveAction] = Field(default_factory=list)
    route: str | None = None
    latencyMs: int | None = Field(default=None, ge=0)


class AssistantQueryResponse(MobileModel):
    requestId: str
    message: ChatMessage
    serverTimeMs: int = Field(ge=0)
    serverProcessingMs: int | None = Field(default=None, ge=0)
    model: str | None = None
    finishReason: str | None = None
    # Observable narration metadata (competition MVP requirement). llmUsed=False and
    # fallback=False together mean this route never calls an LLM at all (deterministic
    # by design, e.g. DTC/fatigue/HVAC) -- not the same as an LLM being attempted and
    # rejected/unreachable, which is fallback=True with a reason.
    llmUsed: bool = False
    fallback: bool = False
    # "provider_unavailable" covers every rewrite_grounded_reply failure mode alike
    # (unreachable, timeout, invalid JSON, or a rejected/ungrounded reply) -- the
    # narrator's own contract only distinguishes "accepted" vs None today, not why.
    fallbackReason: Literal["provider_unavailable"] | None = None


class EventRequest(MobileModel):
    sessionId: str = Field(min_length=1, max_length=128)
    eventId: str = Field(min_length=1, max_length=128)
    type: Literal["USER_REPORTED_FATIGUE", "VOICE_ERROR", "SCENARIO_APPLIED", "CONNECTION_CHANGED"]
    occurredAtMs: int = Field(ge=0)
    reason: str | None = Field(default=None, max_length=500)
    scenarioId: str | None = Field(default=None, max_length=128)
    connectionStatus: str | None = Field(default=None, max_length=64)


class EventAccepted(MobileModel):
    eventId: str
    accepted: bool
    acceptedAtMs: int = Field(ge=0)
    stateVersion: int | None = Field(default=None, ge=0)


class ActionConfirmRequest(MobileModel):
    sessionId: str = Field(min_length=1, max_length=128)
    actionId: str = Field(min_length=1, max_length=128)
    actionType: Literal[
        "SHOW_WARNING",
        "OPEN_DIAGNOSTICS",
        "SUGGEST_REST_STOP",
        "START_SOS_COUNTDOWN",
        "SET_HVAC_TEMPERATURE",
        "LOCK_DOORS",
        "UNLOCK_DOORS",
        "PLAY_MEDIA",
        "NONE",
    ]
    confirmed: bool
    confirmationId: str = Field(min_length=1, max_length=128)
    contextVersion: int = Field(ge=0)
    hvacTargetTemperatureC: float | None = Field(default=None, ge=16, le=30)

    @model_validator(mode="after")
    def validate_hvac_target(self) -> "ActionConfirmRequest":
        if self.actionType == "SET_HVAC_TEMPERATURE" and self.hvacTargetTemperatureC is None:
            raise ValueError("hvacTargetTemperatureC is required for SET_HVAC_TEMPERATURE.")
        if self.actionType != "SET_HVAC_TEMPERATURE" and self.hvacTargetTemperatureC is not None:
            raise ValueError("hvacTargetTemperatureC is only allowed for SET_HVAC_TEMPERATURE.")
        return self


class ActionConfirmResponse(MobileModel):
    accepted: bool
    actionResult: str | None = None
    message: str | None = None
    serverTimeMs: int = Field(ge=0)


class EvidenceItem(MobileModel):
    code: str
    label: str
    detectedAtMs: int = Field(ge=0)


class RescueLocation(MobileModel):
    latitude: float = Field(ge=-90, le=90)
    longitude: float = Field(ge=-180, le=180)
    source: Literal["GPS", "SIMULATOR", "GPS_OR_SIMULATOR"]
    ageMs: int = Field(ge=0)
    freshness: Literal["FRESH", "STALE"]


class RescueBrief(MobileModel):
    """Compact, simulated-only handoff for the MVP rescue demonstration."""

    dispatchMode: Literal["SIMULATION_ONLY"] = "SIMULATION_ONLY"
    eventType: Literal["CRASH_AND_NO_RESPONSE"]
    vehicleId: str = Field(min_length=1, max_length=128)
    timestampMs: int = Field(ge=0)
    lastKnownLocation: RescueLocation | None = None
    locationStatus: Literal["FRESH", "STALE", "UNAVAILABLE"]
    vehicleStatusSummary: str = Field(min_length=1, max_length=500)
    riskLevel: Literal["CRITICAL"]
    evidence: list[str] = Field(default_factory=list, max_length=20)
    realEmergencyDispatchEnabled: Literal[False] = False


class RescueDispatchReceipt(MobileModel):
    """Receipt from the in-memory mock rescue gateway, never a real dispatch."""

    provider: Literal["MOCK_ROADSIDE_ASSISTANCE_GATEWAY"]
    endpoint: Literal["mock://safedrive-rescue-gateway/v1/events"]
    outcome: Literal["SIMULATED_ACCEPTED"]
    referenceId: str = Field(min_length=1, max_length=128)
    receivedAtMs: int = Field(ge=0)


class EmergencySnapshot(MobileModel):
    emergencyId: str
    state: Literal[
        "IDLE",
        "CANDIDATE_DETECTED",
        "VERIFYING_EVIDENCE",
        "AWAITING_USER_RESPONSE",
        "FINAL_COUNTDOWN",
        "SOS_SIMULATED_SENT",
        "CANCELLED",
    ]
    deadlineMs: int | None = Field(default=None, ge=0)
    evidence: list[EvidenceItem] = Field(default_factory=list)
    rescueBrief: RescueBrief | None = None
    rescueDispatch: RescueDispatchReceipt | None = None
    realEmergencyDispatchEnabled: Literal[False] = False
    # Optional, bounded LLM second-opinion explanation (app/mobile/emergency_reasoner.py).
    # Advisory only -- never influences state/deadlineMs/rescueDispatch on its own.
    reasoningSummary: str | None = None


class EmergencyResponseRequest(MobileModel):
    sessionId: str = Field(min_length=1, max_length=128)
    responseId: str = Field(min_length=1, max_length=128)
    response: Literal["USER_OK", "CANCEL_SOS", "NO_RESPONSE"]
    clientTimeMs: int = Field(ge=0)


class StateEnvelope(MobileModel):
    state: VehicleState
    driverSupportSignals: DriverSupportSignals
    riskAssessment: RiskAssessment
    restRecommendation: RestRecommendation
    stateVersion: int = Field(ge=0)
    acceptedAtMs: int = Field(ge=0)
    emergency: EmergencySnapshot | None = None
