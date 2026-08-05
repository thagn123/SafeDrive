package vn.edu.haui.hvs.safedrive.feature.settings

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SettingsUiStateTest {

    @Test
    fun `llm status names the real provider and model instead of assuming Ollama`() {
        assertThat(llmStatusLabel(lastLlmUsed = true, lastFallback = false, lastModel = "vertex_ai/gemini-2.5-flash"))
            .isEqualTo("Vertex AI (gemini-2.5-flash)")
        assertThat(llmStatusLabel(lastLlmUsed = true, lastFallback = false, lastModel = "gemini/gemini-2.5-flash"))
            .isEqualTo("Gemini (gemini-2.5-flash)")
        assertThat(llmStatusLabel(lastLlmUsed = true, lastFallback = false, lastModel = "ollama/qwen2.5:7b-instruct-q4_K_M"))
            .isEqualTo("Ollama (qwen2.5:7b-instruct-q4_K_M)")
    }

    @Test
    fun `llm status degrades gracefully for an unrecognized or missing provider prefix`() {
        assertThat(llmStatusLabel(lastLlmUsed = true, lastFallback = false, lastModel = "future_provider/some-model"))
            .isEqualTo("future_provider (some-model)")
        assertThat(llmStatusLabel(lastLlmUsed = true, lastFallback = false, lastModel = null))
            .isEqualTo("AI")
    }

    @Test
    fun `llm status is deterministic fallback whenever fallback is true, regardless of model string`() {
        assertThat(
            llmStatusLabel(lastLlmUsed = false, lastFallback = true, lastModel = "deterministic-context-router"),
        ).isEqualTo("Deterministic fallback")
    }

    @Test
    fun `llm status is unknown before any reply has been received`() {
        assertThat(llmStatusLabel(lastLlmUsed = null, lastFallback = false, lastModel = null))
            .isEqualTo("Chưa rõ (chưa có phản hồi nào)")
    }
}
