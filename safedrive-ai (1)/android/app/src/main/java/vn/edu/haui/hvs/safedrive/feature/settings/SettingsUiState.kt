package vn.edu.haui.hvs.safedrive.feature.settings

import vn.edu.haui.hvs.safedrive.core.model.BackendMode
import vn.edu.haui.hvs.safedrive.core.model.SimulatedLatencyProfile
import vn.edu.haui.hvs.safedrive.core.network.EndpointConfig

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
    BaseUrlPreset("GCP Cloud · HTTPS 443", EndpointConfig.PRODUCTION_BASE_URL),
    BaseUrlPreset("USB Local · port 8000", EndpointConfig.USB_LOCAL_BASE_URL),
    BaseUrlPreset("Emulator · port 8000", EndpointConfig.EMULATOR_BASE_URL),
    BaseUrlPreset("LAN mẫu · port 8000", EndpointConfig.LEGACY_LAN_BASE_URL),
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
    /** Raw "provider/model" string from ChatMessage.model, e.g. "vertex_ai/gemini-2.5-flash" or
     * "ollama/qwen2.5:7b-instruct-q4_K_M" -- read directly from the backend, never guessed, so
     * Settings can name the actual provider that answered instead of assuming Ollama. */
    val lastModel: String? = null,
)

private val providerDisplayNames = mapOf(
    "ollama" to "Ollama",
    "gemini" to "Gemini",
    "vertex_ai" to "Vertex AI",
)

/** Names the real provider/model that produced the last reply when one used an LLM and wasn't
 * a fallback; "Deterministic fallback" when a route is deterministic by design (never attempted)
 * *or* an LLM attempt failed -- both cases mean the driver saw a non-LLM reply, so Settings
 * deliberately does not distinguish them further here. "Unknown" only before any reply has been
 * received. Never hardcodes a specific provider name: [lastModel] is read verbatim from the
 * backend response, so a new provider is labeled correctly without an app update. */
fun llmStatusLabel(lastLlmUsed: Boolean?, lastFallback: Boolean, lastModel: String? = null): String = when {
    lastLlmUsed == null -> "Chưa rõ (chưa có phản hồi nào)"
    lastLlmUsed && !lastFallback -> {
        val parts = lastModel?.split("/", limit = 2)
        val provider = parts?.getOrNull(0)
        val model = parts?.getOrNull(1)
        val providerLabel = provider?.let { providerDisplayNames[it] ?: it } ?: "AI"
        if (model != null) "$providerLabel ($model)" else providerLabel
    }
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
