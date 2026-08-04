package vn.edu.haui.hvs.safedrive.data.remote

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Before
import org.junit.Test
import vn.edu.haui.hvs.safedrive.core.common.GatewayError
import vn.edu.haui.hvs.safedrive.core.common.GatewayResult
import vn.edu.haui.hvs.safedrive.core.network.NetworkModule

/**
 * Error mapping per docs/android-mvp-plan/03-data-api-contract.md ("Gateway error mapping table"):
 * timeout/offline/401/404/409/422/5xx/invalid JSON must map to the exact typed [GatewayError].
 */
class RemoteSafeDriveGatewayErrorMappingTest {

    private lateinit var server: MockWebServer
    private lateinit var gateway: RemoteSafeDriveGateway

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val retrofit = NetworkModule.createRetrofit(server.url("/").toString(), allowCleartext = true)
        val socketClient = AssistantSocketClient(
            NetworkModule.createOkHttpClient(allowCleartext = true),
            server.url("/").toString(),
        )
        gateway = RemoteSafeDriveGateway(retrofit.create(SafeDriveApi::class.java), socketClient)
    }

    @After
    fun tearDown() {
        // A WebSocket connection that hasn't fully drained yet can make MockWebServer's own
        // shutdown time out waiting for its per-connection thread -- unrelated to whether the
        // test's own assertions passed.
        runCatching { server.shutdown() }
    }

    @Test
    fun `401 maps to Unauthorized`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))
        val result = gateway.checkHealth()
        assertThat((result as GatewayResult.Failure).error).isEqualTo(GatewayError.Unauthorized)
    }

    @Test
    fun `404 maps to Unsupported`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))
        val result = gateway.checkHealth()
        assertThat((result as GatewayResult.Failure).error).isEqualTo(GatewayError.Unsupported)
    }

    @Test
    fun `409 maps to Conflict`() = runTest {
        server.enqueue(MockResponse().setResponseCode(409))
        val result = gateway.checkHealth()
        assertThat((result as GatewayResult.Failure).error).isInstanceOf(GatewayError.Conflict::class.java)
    }

    @Test
    fun `422 maps to Validation`() = runTest {
        server.enqueue(MockResponse().setResponseCode(422))
        val result = gateway.checkHealth()
        assertThat((result as GatewayResult.Failure).error).isInstanceOf(GatewayError.Validation::class.java)
    }

    @Test
    fun `500 maps to Server`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))
        val result = gateway.checkHealth()
        assertThat((result as GatewayResult.Failure).error).isInstanceOf(GatewayError.Server::class.java)
    }

    @Test
    fun `invalid JSON body maps to Protocol, never crashes`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{not valid json"))
        val result = gateway.checkHealth()
        assertThat((result as GatewayResult.Failure).error).isInstanceOf(GatewayError.Protocol::class.java)
    }

    @Test
    fun `a connection that never responds maps to Timeout, not a crash`() = runTest {
        // A short client-side read timeout keeps this test fast instead of waiting the production
        // 15s timeout for a response that will never arrive (SocketPolicy.NO_RESPONSE).
        val shortTimeoutRetrofit = NetworkModule.createRetrofit(
            server.url("/").toString(),
            allowCleartext = true,
            readTimeoutSeconds = 1,
        )
        val shortTimeoutGateway = RemoteSafeDriveGateway(
            shortTimeoutRetrofit.create(SafeDriveApi::class.java),
            AssistantSocketClient(NetworkModule.createOkHttpClient(allowCleartext = true), server.url("/").toString()),
        )
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))

        val result = shortTimeoutGateway.checkHealth()
        assertThat((result as GatewayResult.Failure).error).isEqualTo(GatewayError.Timeout)
    }

    @Test
    fun `connection reset maps to Offline`() = runTest {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        val result = gateway.checkHealth()
        assertThat((result as GatewayResult.Failure).error).isEqualTo(GatewayError.Offline)
    }

    // --- Remediation item 4: parse the ErrorEnvelope body per openapi/safedrive-v1.yaml instead of
    // mapping HTTP status alone; the status is only ever a fallback. ---

    @Test
    fun `a VALIDATION ErrorEnvelope body maps to Validation with the envelope's message, even on a 422`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(422).setBody(
                """{"code":"VALIDATION","message":"Câu hỏi trống","requestId":"req_1","retryable":false,"serverTimeMs":1000}""",
            ),
        )
        val result = gateway.checkHealth()
        val error = (result as GatewayResult.Failure).error
        assertThat(error).isInstanceOf(GatewayError.Validation::class.java)
        assertThat((error as GatewayError.Validation).message).isEqualTo("Câu hỏi trống")
    }

    @Test
    fun `a CONFLICT ErrorEnvelope body maps to Conflict with the envelope's message`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(409).setBody(
                """{"code":"CONFLICT","message":"Trạng thái đã thay đổi","retryable":true,"serverTimeMs":1000}""",
            ),
        )
        val result = gateway.checkHealth()
        val error = (result as GatewayResult.Failure).error
        assertThat(error).isInstanceOf(GatewayError.Conflict::class.java)
        assertThat((error as GatewayError.Conflict).message).isEqualTo("Trạng thái đã thay đổi")
    }

    @Test
    fun `code and HTTP status mismatch defers to the envelope's code, not the status`() = runTest {
        // A backend that returns HTTP 500 but a typed UNAUTHORIZED envelope — the envelope is the
        // source of truth for the typed error, not the transport-level status.
        server.enqueue(
            MockResponse().setResponseCode(500).setBody(
                """{"code":"UNAUTHORIZED","message":"Phiên hết hạn","retryable":false,"serverTimeMs":1000}""",
            ),
        )
        val result = gateway.checkHealth()
        assertThat((result as GatewayResult.Failure).error).isEqualTo(GatewayError.Unauthorized)
    }

    @Test
    fun `a malformed error body falls back to the HTTP status, never crashes`() = runTest {
        server.enqueue(MockResponse().setResponseCode(422).setBody("{not valid json"))
        val result = gateway.checkHealth()
        assertThat((result as GatewayResult.Failure).error).isInstanceOf(GatewayError.Validation::class.java)
    }

    @Test
    fun `an empty error body falls back to the HTTP status, never crashes`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404).setBody(""))
        val result = gateway.checkHealth()
        assertThat((result as GatewayResult.Failure).error).isEqualTo(GatewayError.Unsupported)
    }

    @Test
    fun `an unrecognized future error code falls back to the HTTP status, never crashes`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(503).setBody(
                """{"code":"RATE_LIMITED","message":"Slow down","retryable":true,"serverTimeMs":1000}""",
            ),
        )
        val result = gateway.checkHealth()
        assertThat((result as GatewayResult.Failure).error).isInstanceOf(GatewayError.Server::class.java)
    }

    // --- queryAssistant now runs internally over AssistantSocketClient's heartbeating WebSocket
    // transport instead of one-shot HTTP. Full transport coverage (heartbeats, frame parsing, URL
    // building) lives in AssistantSocketClientTest; these confirm only the mapping this class adds
    // on top, using the exact same public suspend contract every other caller/test double relies on. ---

    private fun sampleRequest() = vn.edu.haui.hvs.safedrive.core.model.AssistantQueryRequest(
        sessionId = "sess_1",
        requestId = "req_1",
        text = "Xin chao",
        context = vn.edu.haui.hvs.safedrive.core.model.AssistantContext(1, "assistant"),
    )

    @Test
    fun `queryAssistant succeeds once the socket sends a final frame`() = kotlinx.coroutines.runBlocking {
        server.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : okhttp3.WebSocketListener() {
                    override fun onOpen(webSocket: okhttp3.WebSocket, response: okhttp3.Response) {
                        webSocket.send(
                            """{"type":"final","requestId":"req_1","message":{"id":"msg_1",
                               "sender":"SAFEDRIVE","text":"Xin chao","timestampMs":1000},
                               "serverTimeMs":1000,"llmUsed":true,"fallback":false}""",
                        )
                    }
                },
            ),
        )

        val result = gateway.queryAssistant(sampleRequest())

        check(result is GatewayResult.Success)
        assertThat(result.data.requestId).isEqualTo("req_1")
        assertThat(result.data.llmUsed).isTrue()
    }

    @Test
    fun `queryAssistant still succeeds after the socket sends several heartbeats first`() = kotlinx.coroutines.runBlocking {
        server.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : okhttp3.WebSocketListener() {
                    override fun onOpen(webSocket: okhttp3.WebSocket, response: okhttp3.Response) {
                        webSocket.send("""{"type":"heartbeat"}""")
                        webSocket.send("""{"type":"heartbeat"}""")
                        webSocket.send(
                            """{"type":"final","requestId":"req_1","message":{"id":"msg_1",
                               "sender":"SAFEDRIVE","text":"Xin chao","timestampMs":1000},
                               "serverTimeMs":1000}""",
                        )
                    }
                },
            ),
        )

        val result = gateway.queryAssistant(sampleRequest())

        check(result is GatewayResult.Success)
        assertThat(result.data.requestId).isEqualTo("req_1")
    }

    @Test
    fun `queryAssistant maps a VALIDATION error frame to GatewayError Validation`() = kotlinx.coroutines.runBlocking {
        server.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : okhttp3.WebSocketListener() {
                    override fun onOpen(webSocket: okhttp3.WebSocket, response: okhttp3.Response) {
                        webSocket.send("""{"type":"error","code":"VALIDATION","message":"bad request"}""")
                    }
                },
            ),
        )

        val result = gateway.queryAssistant(sampleRequest())

        val failure = result as GatewayResult.Failure
        assertThat(failure.error).isInstanceOf(GatewayError.Validation::class.java)
    }
}
