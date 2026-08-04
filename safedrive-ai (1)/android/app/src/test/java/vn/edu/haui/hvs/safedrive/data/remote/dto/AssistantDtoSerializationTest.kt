package vn.edu.haui.hvs.safedrive.data.remote.dto

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import org.junit.Test

/**
 * Serialization/contract tests per docs/android-mvp-plan/12 W7.10: proves the DTOs match
 * `openapi/safedrive-v1.yaml`'s examples exactly, and that the additive/optional W7 fields
 * (`source`/`locale`/`clientAttemptOf` on the request; `serverProcessingMs`/`model`/`finishReason` on
 * the response) are both forward- and backward-compatible — an older backend omitting them, or a
 * newer backend sending an extra unknown field, must never fail to parse.
 */
class AssistantDtoSerializationTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    @Test
    fun `request round-trips with source, locale and clientAttemptOf`() {
        val request = AssistantQueryRequestDto(
            sessionId = "sess_001",
            requestId = "req_001",
            text = "Xe đang có lỗi gì?",
            source = "VOICE",
            locale = "vi-VN",
            clientAttemptOf = "req_000",
            context = AssistantContextDto(stateVersion = 123L, screen = "cockpit"),
        )
        val encoded = json.encodeToString(AssistantQueryRequestDto.serializer(), request)
        val decoded = json.decodeFromString(AssistantQueryRequestDto.serializer(), encoded)
        assertThat(decoded).isEqualTo(request)
    }

    @Test
    fun `request decodes matching the openapi example exactly (openapi examples_assistant-text-query json)`() {
        val exampleJson = """
            {
              "sessionId": "sess_001",
              "requestId": "req_001",
              "text": "Xe đang có lỗi gì?",
              "source": "TEXT",
              "locale": "vi-VN",
              "clientAttemptOf": null,
              "context": { "stateVersion": 123, "screen": "cockpit" }
            }
        """.trimIndent()
        val decoded = json.decodeFromString(AssistantQueryRequestDto.serializer(), exampleJson)
        assertThat(decoded.sessionId).isEqualTo("sess_001")
        assertThat(decoded.source).isEqualTo("TEXT")
        assertThat(decoded.clientAttemptOf).isNull()
        assertThat(decoded.context.screen).isEqualTo("cockpit")
    }

    @Test
    fun `request from a backend that omits source, locale and clientAttemptOf still parses (backward compat)`() {
        val minimalJson = """
            {
              "sessionId": "sess_001",
              "requestId": "req_001",
              "text": "hello",
              "context": { "stateVersion": 0, "screen": "assistant" }
            }
        """.trimIndent()
        val decoded = json.decodeFromString(AssistantQueryRequestDto.serializer(), minimalJson)
        assertThat(decoded.source).isEqualTo("TEXT") // documented default
        assertThat(decoded.locale).isEqualTo("vi-VN")
        assertThat(decoded.clientAttemptOf).isNull()
    }

    @Test
    fun `response decodes matching the openapi example exactly (openapi examples_assistant-response json)`() {
        val exampleJson = """
            {
              "requestId": "req_001",
              "message": {
                "id": "msg_001",
                "sender": "SAFEDRIVE",
                "text": "Hiện tại không phát hiện mã lỗi DTC đang hoạt động.",
                "timestampMs": 1780000000000
              },
              "serverTimeMs": 1780000000000,
              "serverProcessingMs": 180,
              "model": "backend-selected",
              "finishReason": "STOP"
            }
        """.trimIndent()
        val decoded = json.decodeFromString(AssistantQueryResponseDto.serializer(), exampleJson)
        assertThat(decoded.serverProcessingMs).isEqualTo(180L)
        assertThat(decoded.model).isEqualTo("backend-selected")
        assertThat(decoded.finishReason).isEqualTo("STOP")
    }

    @Test
    fun `response from a backend that omits serverProcessingMs, model and finishReason still parses (backward compat)`() {
        val minimalJson = """
            {
              "requestId": "req_001",
              "message": {
                "id": "msg_001",
                "sender": "SAFEDRIVE",
                "text": "OK",
                "timestampMs": 0
              },
              "serverTimeMs": 0
            }
        """.trimIndent()
        val decoded = json.decodeFromString(AssistantQueryResponseDto.serializer(), minimalJson)
        assertThat(decoded.serverProcessingMs).isNull()
        assertThat(decoded.model).isNull()
        assertThat(decoded.finishReason).isNull()
    }

    @Test
    fun `response decodes the explicit llmUsed, fallback and fallbackReason fields`() {
        val exampleJson = """
            {
              "requestId": "req_001",
              "message": {
                "id": "msg_001",
                "sender": "SAFEDRIVE",
                "text": "Xe dang chay 72 km per h.",
                "timestampMs": 1780000000000
              },
              "serverTimeMs": 1780000000000,
              "model": "deterministic-context-router",
              "llmUsed": false,
              "fallback": true,
              "fallbackReason": "provider_unavailable"
            }
        """.trimIndent()
        val decoded = json.decodeFromString(AssistantQueryResponseDto.serializer(), exampleJson)
        assertThat(decoded.llmUsed).isFalse()
        assertThat(decoded.fallback).isTrue()
        assertThat(decoded.fallbackReason).isEqualTo("provider_unavailable")
    }

    @Test
    fun `response from a backend that omits llmUsed, fallback and fallbackReason defaults to never-attempted (backward compat)`() {
        val minimalJson = """
            {
              "requestId": "req_001",
              "message": {
                "id": "msg_001",
                "sender": "SAFEDRIVE",
                "text": "OK",
                "timestampMs": 0
              },
              "serverTimeMs": 0
            }
        """.trimIndent()
        val decoded = json.decodeFromString(AssistantQueryResponseDto.serializer(), minimalJson)
        assertThat(decoded.llmUsed).isFalse()
        assertThat(decoded.fallback).isFalse()
        assertThat(decoded.fallbackReason).isNull()
    }

    @Test
    fun `response with an unknown reserved field (safetyMetadata) still parses (forward compat)`() {
        val futureJson = """
            {
              "requestId": "req_001",
              "message": {
                "id": "msg_001",
                "sender": "SAFEDRIVE",
                "text": "OK",
                "timestampMs": 0
              },
              "serverTimeMs": 0,
              "safetyMetadata": { "flagged": false, "categories": [] }
            }
        """.trimIndent()
        val decoded = json.decodeFromString(AssistantQueryResponseDto.serializer(), futureJson)
        assertThat(decoded.requestId).isEqualTo("req_001")
    }
}
