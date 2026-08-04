package vn.edu.haui.hvs.safedrive.feature.cockpit.components

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * `formatTemp` is a pure function backing the Cockpit's Cabin/HVAC badges; it
 * is unit-testable on the JVM without a device. Locks in the "one decimal
 * only when needed" behaviour (22.0 -> "22", 23.5 -> "23.5") so the Cabin
 * badge, HVAC badge, and their content-description strings stay consistent
 * with the backend's `_fmt_temp`/`:g` formatting.
 */
class VehicleMetricsPanelFormattingTest {

    @Test
    fun `whole number renders without a decimal point`() {
        assertThat(formatTemp(22.0f)).isEqualTo("22")
        assertThat(formatTemp(30.0f)).isEqualTo("30")
        assertThat(formatTemp(16.0f)).isEqualTo("16")
    }

    @Test
    fun `fractional value preserves exactly one decimal digit`() {
        assertThat(formatTemp(23.5f)).isEqualTo("23.5")
        assertThat(formatTemp(24.0f)).isEqualTo("24")
    }

    @Test
    fun `negative and boundary values format sensibly`() {
        assertThat(formatTemp(0.0f)).isEqualTo("0")
        assertThat(formatTemp(-1.5f)).isEqualTo("-1.5")
    }
}
