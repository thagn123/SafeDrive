package vn.edu.haui.hvs.safedrive.domain.usecase

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.edu.haui.hvs.safedrive.core.model.EmergencyState

class EmergencyProactiveCheckPolicyTest {
    @Test
    fun `only awaiting user response starts proactive voice check`() {
        EmergencyState.entries.forEach { state ->
            if (state == EmergencyState.AWAITING_USER_RESPONSE) {
                assertTrue(shouldStartProactiveOccupantCheck(state))
            } else {
                assertFalse("Unexpected proactive check for $state", shouldStartProactiveOccupantCheck(state))
            }
        }
    }
}
