package vn.edu.haui.hvs.safedrive.core.model

/** Shared LOW/MEDIUM/HIGH/CRITICAL scale used by both [RiskAssessment.level] and [Dtc.severity]. */
enum class Severity { LOW, MEDIUM, HIGH, CRITICAL }

enum class RestRecommendationLevel { INSUFFICIENT_DATA, NORMAL, MONITOR, CONSIDER_REST, REST_RECOMMENDED }

enum class ConfidenceLevel { LOW, MEDIUM, HIGH }

/** [UNKNOWN] covers the case where no crash-response signal is available (contract addition vs. prototype). */
enum class PassengerResponse { RESPONSIVE, NO_RESPONSE, UNKNOWN }

enum class ChatSender { USER, SAFEDRIVE }

enum class ActionType {
    SHOW_WARNING,
    OPEN_DIAGNOSTICS,
    SUGGEST_REST_STOP,
    START_SOS_COUNTDOWN,
    SET_HVAC_TEMPERATURE,
    NONE,
}

enum class EmergencyState {
    IDLE, CANDIDATE_DETECTED, VERIFYING_EVIDENCE, AWAITING_USER_RESPONSE, FINAL_COUNTDOWN, SOS_SIMULATED_SENT, CANCELLED
}

enum class VoiceState { DISABLED, IDLE, WAKE_WORD_DETECTED, LISTENING, PROCESSING, SPEAKING, ERROR }

enum class SystemConnectionStatus { NORMAL, OFFLINE, NO_AI_SERVICE, NO_VEHICLE_DATA, STALE_DATA }

/** Demo uses [MockSafeDriveGateway]; Remote uses [RemoteSafeDriveGateway]. Chosen at runtime in Developer Mode. */
enum class BackendMode { DEMO, REMOTE }

enum class StateSource { MOCK, PHONE_SIMULATOR, VEHICLE_ADAPTER }

enum class EmergencyResponseType { USER_OK, CANCEL_SOS, NO_RESPONSE }

enum class SafeDriveEventType { USER_REPORTED_FATIGUE, VOICE_ERROR, SCENARIO_APPLIED, CONNECTION_CHANGED }

/**
 * Developer-Mode-only simulated latency for Demo Mode assistant replies (docs/android-mvp-plan/12
 * W4.3/W4.4). [NONE] is the production default: no artificial delay. The others exist only so a
 * developer/demo can deliberately reproduce a slow/timeout condition to test UI, never as a default.
 */
enum class SimulatedLatencyProfile { NONE, MS_100, MS_500, MS_2000, TIMEOUT }
