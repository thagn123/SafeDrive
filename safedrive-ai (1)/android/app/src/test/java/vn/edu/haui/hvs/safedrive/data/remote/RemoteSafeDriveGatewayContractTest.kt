package vn.edu.haui.hvs.safedrive.data.remote

import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import vn.edu.haui.hvs.safedrive.core.network.NetworkModule
import vn.edu.haui.hvs.safedrive.core.testing.driverSupportSignalsFixture
import vn.edu.haui.hvs.safedrive.core.testing.vehicleStateFixture
import vn.edu.haui.hvs.safedrive.domain.repository.SafeDriveGateway

/** Runs the exact same [SafeDriveGatewayContractTest] suite against a real HTTP round-trip. */
class RemoteSafeDriveGatewayContractTest : SafeDriveGatewayContractTest() {

    private lateinit var server: MockWebServer

    @Before
    fun startServer() {
        server = MockWebServer()
        server.dispatcher = FakeSafeDriveBackendDispatcher()
        server.start()
    }

    @After
    fun stopServer() {
        // A WebSocket connection that hasn't fully drained yet can make MockWebServer's own
        // shutdown time out waiting for its per-connection thread -- unrelated to whether the
        // test's own assertions passed.
        runCatching { server.shutdown() }
    }

    override fun buildGateway(): SafeDriveGateway {
        val retrofit = NetworkModule.createRetrofit(server.url("/").toString(), allowCleartext = true)
        val socketClient = AssistantSocketClient(
            NetworkModule.createOkHttpClient(allowCleartext = true),
            server.url("/").toString(),
        )
        return RemoteSafeDriveGateway(retrofit.create(SafeDriveApi::class.java), socketClient)
    }

    override fun sampleVehicleState() = vehicleStateFixture()
    override fun sampleDriverSupportSignals() = driverSupportSignalsFixture()
}
