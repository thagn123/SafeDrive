package vn.edu.haui.hvs.safedrive.core.model

data class SafeDriveAction(
    val id: String,
    val type: ActionType,
    val title: String,
    val requiresConfirmation: Boolean,
    /** Typed payload for the MVP's one state-backed cockpit control; never an arbitrary JSON bag. */
    val hvacTargetTemperatureC: Float? = null,
)

data class ChatMessage(
    val id: String,
    val sender: ChatSender,
    val text: String,
    val timestampMs: Long,
    val risk: RiskAssessment? = null,
    val actions: List<SafeDriveAction> = emptyList(),
    val route: String? = null,
    val latencyMs: Long? = null,
    /** Observability only. Never used to select safety policy or execute an action. */
    val model: String? = null,
    /**
     * Explicit narration-provider observability (competition MVP requirement). Never inferred
     * from [model]'s string contents -- the backend sets these deliberately, so Android must
     * only read them, not guess. `llmUsed=false, fallback=false` together mean this route never
     * calls an LLM at all (deterministic by design); `fallback=true` means an LLM attempt was
     * made and failed/was rejected, and the deterministic reply above was used instead.
     */
    val llmUsed: Boolean = false,
    val fallback: Boolean = false,
    val fallbackReason: String? = null,
)
