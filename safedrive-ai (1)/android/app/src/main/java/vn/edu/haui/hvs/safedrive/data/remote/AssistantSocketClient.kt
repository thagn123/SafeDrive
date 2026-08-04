package vn.edu.haui.hvs.safedrive.data.remote

import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.serialization.Serializable
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import vn.edu.haui.hvs.safedrive.core.network.NetworkModule
import vn.edu.haui.hvs.safedrive.data.remote.dto.AssistantQueryRequestDto
import vn.edu.haui.hvs.safedrive.data.remote.dto.AssistantQueryResponseDto

/**
 * One event from an in-flight assistant turn sent over `/api/v1/ws/assistant`.
 * [Heartbeat] carries no data -- it exists purely so a caller waiting on this [Flow] can reset
 * its own liveness timer instead of relying on a single fixed read timeout (see
 * `AssistantQueryUseCase`, which is what actually replaces the old `withTimeoutOrNull(10_000L)`).
 */
sealed interface AssistantSocketEvent {
    data object Heartbeat : AssistantSocketEvent
    data class Final(val response: AssistantQueryResponseDto) : AssistantSocketEvent
    data class Error(val code: String?, val message: String?) : AssistantSocketEvent
}

@Serializable
private data class FrameType(val type: String)

@Serializable
private data class ErrorFrame(val code: String? = null, val message: String? = null)

/**
 * Thin OkHttp WebSocket wrapper for the assistant chat channel. One socket per turn: connects,
 * sends the query as its first (and only) outbound frame, emits [AssistantSocketEvent.Heartbeat]
 * for each `{"type":"heartbeat"}` frame the server sends while a real (possibly multi-second)
 * narration call is in flight, then emits exactly one terminal [AssistantSocketEvent.Final] or
 * [AssistantSocketEvent.Error] and closes -- matching `AssistantTurnCoordinator`'s existing
 * single-terminal-result-per-turn contract, just fed by a liveness-aware transport instead of a
 * one-shot HTTP call with a fixed timeout.
 */
class AssistantSocketClient(
    private val okHttpClient: OkHttpClient,
    private val baseUrl: String,
) {
    fun query(sessionId: String, request: AssistantQueryRequestDto): Flow<AssistantSocketEvent> = callbackFlow {
        val url = webSocketUrl(baseUrl, sessionId)
        val httpRequest = Request.Builder().url(url).build()
        val requestJson = NetworkModule.json.encodeToString(AssistantQueryRequestDto.serializer(), request)

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(requestJson)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val type = runCatching {
                    NetworkModule.json.decodeFromString(FrameType.serializer(), text).type
                }.getOrNull()
                when (type) {
                    "heartbeat" -> trySend(AssistantSocketEvent.Heartbeat)
                    "final" -> {
                        val parsed = runCatching {
                            NetworkModule.json.decodeFromString(AssistantQueryResponseDto.serializer(), text)
                        }.getOrNull()
                        if (parsed != null) {
                            trySend(AssistantSocketEvent.Final(parsed))
                        } else {
                            trySend(AssistantSocketEvent.Error("PROTOCOL", "Malformed final frame"))
                        }
                        webSocket.close(NORMAL_CLOSURE, "turn_complete")
                    }
                    "error" -> {
                        val errorFrame = runCatching {
                            NetworkModule.json.decodeFromString(ErrorFrame.serializer(), text)
                        }.getOrNull()
                        trySend(AssistantSocketEvent.Error(errorFrame?.code, errorFrame?.message))
                        webSocket.close(NORMAL_CLOSURE, "turn_complete")
                    }
                    else -> Unit // Unknown frame type: forward-compatible, silently ignored.
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                close(t)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                close()
            }
        }

        val webSocket = okHttpClient.newWebSocket(httpRequest, listener)
        awaitClose { webSocket.close(NORMAL_CLOSURE, "cancelled") }
    }

    companion object {
        private const val NORMAL_CLOSURE = 1000

        /** `http(s)://host/` -&gt; `ws(s)://host/api/v1/ws/assistant?sessionId=...`. `baseUrl` is
         * assumed already validated by [vn.edu.haui.hvs.safedrive.core.network.BaseUrlValidator]
         * (scheme is always `http`/`https`, matching Retrofit's own requirement). */
        internal fun webSocketUrl(baseUrl: String, sessionId: String): String {
            val wsScheme = when {
                baseUrl.startsWith("https://") -> "wss://" + baseUrl.removePrefix("https://")
                baseUrl.startsWith("http://") -> "ws://" + baseUrl.removePrefix("http://")
                else -> baseUrl
            }
            val withSlash = if (wsScheme.endsWith("/")) wsScheme else "$wsScheme/"
            return "${withSlash}api/v1/ws/assistant?sessionId=$sessionId"
        }
    }
}
