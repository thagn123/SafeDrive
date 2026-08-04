package vn.edu.haui.hvs.safedrive.feature.settings

import vn.edu.haui.hvs.safedrive.core.model.BackendMode
import vn.edu.haui.hvs.safedrive.core.model.SimulatedLatencyProfile

sealed interface HealthCheckState {
    data object Idle : HealthCheckState
    data object Checking : HealthCheckState
    data class Success(val message: String) : HealthCheckState
    data class Failure(val message: String) : HealthCheckState
}

/** Explicit feedback for saving the Remote URL so a persistent change is never invisible. */
sealed interface BaseUrlSaveState {
    data object Idle : BaseUrlSaveState
    data object Saving : BaseUrlSaveState
    data class Saved(val normalizedUrl: String) : BaseUrlSaveState
}

data class BaseUrlPreset(val label: String, val url: String)

val baseUrlPresets = listOf(
    BaseUrlPreset("USB Local", "http://127.0.0.1:8002/"),
    BaseUrlPreset("Emulator", "http://10.0.2.2:8002/"),
    BaseUrlPreset("LAN Wi-Fi", "http://192.168.1.15:8002/"),
    BaseUrlPreset("Cloud Staging", "https://api.example.com/"),
)

/**
 * Settings state per docs/android-mvp-plan/04-screen-specs.md ("Settings"). Developer-only fields
 * (BASE_URL, backend mode, health check) are rendered only when [developerMode] is true.
 */
data class SettingsUiState(
    val ttsEnabled: Boolean = true,
    val wakeWordEnabled: Boolean = true,
    val developerMode: Boolean = false,
    val backendMode: BackendMode = BackendMode.DEMO,
    val baseUrl: String = "",
    val baseUrlDraft: String = "",
    val baseUrlError: String? = null,
    val baseUrlSaveState: BaseUrlSaveState = BaseUrlSaveState.Idle,
    val healthStatus: HealthCheckState = HealthCheckState.Idle,
    val appVersion: String = "",
    val wearableConnected: Boolean = false,
    val developerLatencyProfile: SimulatedLatencyProfile = SimulatedLatencyProfile.NONE,
    val lastTurnLatencySummary: String? = null,
    /** From the most recent SAFEDRIVE chat reply's explicit llmUsed/fallback fields -- never
     * inferred from a model-name string. `null` means no assistant reply has been received yet
     * this process (not "LLM unavailable"). */
    val lastLlmUsed: Boolean? = null,
    val lastFallback: Boolean = false,
    val lastFallbackReason: String? = null,
)

/** "Ollama" only when the last reply both used an LLM and wasn't a fallback; "Deterministic
 * fallback" when a route is deterministic by design (never attempted) *or* an LLM attempt failed
 * -- both cases mean the driver saw a non-LLM reply, so Settings deliberately does not
 * distinguish them further here. "Unknown" only before any reply has been received. */
fun llmStatusLabel(lastLlmUsed: Boolean?, lastFallback: Boolean): String = when {
    lastLlmUsed == null -> "Chưa rõ (chưa có phản hồi nào)"
    lastLlmUsed && !lastFallback -> "Ollama"
    else -> "Deterministic fallback"
}

/** Developer-Mode-only latency profile labels for Settings (docs/android-mvp-plan/12 W4.4). */
val latencyProfileLabels = mapOf(
    SimulatedLatencyProfile.NONE to "Không giả lập (mặc định)",
    SimulatedLatencyProfile.MS_100 to "100 ms",
    SimulatedLatencyProfile.MS_500 to "500 ms",
    SimulatedLatencyProfile.MS_2000 to "2000 ms",
    SimulatedLatencyProfile.TIMEOUT to "Hết thời gian chờ (timeout)",
)
