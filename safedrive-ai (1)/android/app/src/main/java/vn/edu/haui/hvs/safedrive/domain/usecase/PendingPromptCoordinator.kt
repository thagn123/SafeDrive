package vn.edu.haui.hvs.safedrive.domain.usecase

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Carries a prefill query from Diagnostics ("Hỏi SafeDrive AI") to the Assistant composer, matching
 * the prototype's `prefillAssistantQuery`/`clearPendingPrompt` navigation helper
 * (docs/android-mvp-plan/01-source-audit.md). Not safety state — a simple one-shot handoff.
 */
class PendingPromptCoordinator {
    private val _pendingPrompt = MutableStateFlow<String?>(null)
    val pendingPrompt: StateFlow<String?> = _pendingPrompt.asStateFlow()

    fun prefill(text: String) {
        _pendingPrompt.value = text
    }

    fun consume(): String? {
        val value = _pendingPrompt.value
        _pendingPrompt.value = null
        return value
    }
}
