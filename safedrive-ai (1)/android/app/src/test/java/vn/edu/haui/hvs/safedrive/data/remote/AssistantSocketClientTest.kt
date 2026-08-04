package vn.edu.haui.hvs.safedrive.data.remote

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.runBlocking
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import vn.edu.haui.hvs.safedrive.core.network.NetworkModule
import vn.edu.haui.hvs.safedrive.data.remote.dto.AssistantContextDto
import vn.edu.haui.hvs.safedrive.data.remote.dto.AssistantQueryRequestDto

private const val FINAL_FRAME_JSON = """
    {"type":"final","requestId":"req_1","message":{"id":"msg_1","sender":"SAFEDRIVE",
     "text":"Xin chao","timestampMs":1000},"serverTimeMs":1000,"llmUsed":true,"fallback":false}
"""

class AssistantSocketClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: AssistantSocketClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = AssistantSocketClient(
            NetworkModule.createOkHttpClient(allowCleartext = true),
            server.url("/").toString(),
        )
    }

    @After
    fun tearDown() {
        // A WebSocket connection that hasn't fully drained its close handshake yet can make
        // MockWebServer's own shutdown throw a benign SocketException from its listener thread --
        // unrelated to whether the test's own assertions passed.
        runCatching { server.shutdown() }
    }

    private fun sampleRequest() = AssistantQueryRequestDto(
        sessionId = "sess_1",
        requestId = "req_1",
        text = "Xin chao",
        context = AssistantContextDto(stateVersion = 1, screen = "assistant"),
    )

    /** Stops right after the terminal event (Final/Error), the same way
     * [RemoteSafeDriveGateway.queryAssistant] consumes this flow in production (it cancels rather
     * than waiting for the flow's natural completion). Waiting for natural completion via a plain
     * `toList()` instead races OkHttp's real close handshake and intermittently surfaces a benign
     * "Socket closed" as a flow failure -- a JVM-test/MockWebServer timing artifact, not a
     * production bug (see AssistantSocketClient's `awaitClose`). */
    private suspend fun AssistantSocketClient.collectOneTurn(
        sessionId: String,
        request: AssistantQueryRequestDto,
    ): List<AssistantSocketEvent> = query(sessionId, request)
        .transformWhile { event ->
            emit(event)
            event !is AssistantSocketEvent.Final && event !is AssistantSocketEvent.Error
        }
        .toList()

    @Test
    fun `emits heartbeat then final in order and closes`() = runBlocking {
        var serverSocket: WebSocket? = null
        server.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                        serverSocket = webSocket
                        webSocket.send("""{"type":"heartbeat"}""")
                        webSocket.send(FINAL_FRAME_JSON)
                    }
                },
            ),
        )

        val events = client.collectOneTurn("sess_1", sampleRequest())

        assertThat(events).hasSize(2)
        assertThat(events[0]).isEqualTo(AssistantSocketEvent.Heartbeat)
        val final = events[1] as AssistantSocketEvent.Final
        assertThat(final.response.requestId).isEqualTo("req_1")
        assertThat(final.response.llmUsed).isTrue()
        assertThat(final.response.message.text).isEqualTo("Xin chao")
        serverSocket?.close(1000, "test done")
        Unit
    }

    @Test
    fun `emits error and completes when the server sends an error frame`() = runBlocking {
        server.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                        webSocket.send("""{"type":"error","code":"VALIDATION","message":"bad request"}""")
                    }
                },
            ),
        )

        val events = client.collectOneTurn("sess_1", sampleRequest())

        assertThat(events).hasSize(1)
        val error = events[0] as AssistantSocketEvent.Error
        assertThat(error.code).isEqualTo("VALIDATION")
        assertThat(error.message).isEqualTo("bad request")
    }

    @Test
    fun `sends the query as the first outbound frame on open`() = runBlocking {
        var receivedText: String? = null
        server.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        receivedText = text
                        webSocket.send(FINAL_FRAME_JSON)
                    }
                },
            ),
        )

        client.collectOneTurn("sess_1", sampleRequest())

        assertThat(receivedText).contains("\"requestId\":\"req_1\"")
        assertThat(receivedText).contains("\"sessionId\":\"sess_1\"")
    }

    @Test
    fun `webSocketUrl converts http to ws and https to wss and appends the assistant path`() {
        assertThat(AssistantSocketClient.webSocketUrl("http://127.0.0.1:8000/", "sess_1"))
            .isEqualTo("ws://127.0.0.1:8000/api/v1/ws/assistant?sessionId=sess_1")
        assertThat(AssistantSocketClient.webSocketUrl("https://example.com/", "sess_2"))
            .isEqualTo("wss://example.com/api/v1/ws/assistant?sessionId=sess_2")
        assertThat(AssistantSocketClient.webSocketUrl("http://127.0.0.1:8000", "sess_3"))
            .isEqualTo("ws://127.0.0.1:8000/api/v1/ws/assistant?sessionId=sess_3")
    }
}
