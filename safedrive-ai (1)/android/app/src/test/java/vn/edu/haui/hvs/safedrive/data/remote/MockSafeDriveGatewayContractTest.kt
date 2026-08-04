package vn.edu.haui.hvs.safedrive.data.remote

import vn.edu.haui.hvs.safedrive.core.common.UuidIdGenerator
import vn.edu.haui.hvs.safedrive.core.testing.FakeClock
import vn.edu.haui.hvs.safedrive.data.mock.MockFixtures
import vn.edu.haui.hvs.safedrive.data.mock.MockPolicyEvaluator
import vn.edu.haui.hvs.safedrive.data.mock.MockSafeDriveGateway
import vn.edu.haui.hvs.safedrive.domain.repository.SafeDriveGateway

class MockSafeDriveGatewayContractTest : SafeDriveGatewayContractTest() {

    private val clock = FakeClock(1_000L)
    private val fixtures = MockFixtures(clock)

    override fun buildGateway(): SafeDriveGateway =
        MockSafeDriveGateway(clock, UuidIdGenerator(), fixtures, MockPolicyEvaluator(clock))

    override fun sampleVehicleState() = fixtures.defaultVehicleState()
    override fun sampleDriverSupportSignals() = fixtures.defaultDriverSupportSignals()
}
