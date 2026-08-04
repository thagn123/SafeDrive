package vn.edu.haui.hvs.safedrive.core

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import vn.edu.haui.hvs.safedrive.BuildConfig
import vn.edu.haui.hvs.safedrive.core.model.EmergencySnapshot
import vn.edu.haui.hvs.safedrive.core.model.EmergencyState

/**
 * Compile/config assertions required by docs/android-mvp-plan/00-executive-plan.md and
 * 07-testing-security-acceptance.md: `realEmergencyDispatchEnabled` must be false everywhere.
 */
class SafetyInvariantsTest {

    @Test
    fun `BuildConfig never enables real emergency dispatch`() {
        assertThat(BuildConfig.REAL_EMERGENCY_DISPATCH_ENABLED).isFalse()
    }

    @Test
    fun `EmergencySnapshot default never enables real emergency dispatch`() {
        val snapshot = EmergencySnapshot(
            emergencyId = "emg_1",
            state = EmergencyState.IDLE,
            deadlineMs = null,
            evidence = emptyList(),
        )
        assertThat(snapshot.realEmergencyDispatchEnabled).isFalse()
    }
}
