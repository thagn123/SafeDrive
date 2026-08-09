package vn.edu.haui.hvs.safedrive.vehicle

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import vn.edu.haui.hvs.safedrive.domain.repository.PerimeterSensorFaultTracker

class PerimeterSensorFaultTrackerTest {

    @Test
    fun `a single faulty sensor is not a severe fault`() {
        val tracker = PerimeterSensorFaultTracker()
        assertThat(tracker.onSensorStatus(areaId = 1, isError = true, nowMs = 1_000)).isFalse()
    }

    @Test
    fun `two distinct sensors faulting within the window is a severe fault`() {
        val tracker = PerimeterSensorFaultTracker()
        assertThat(tracker.onSensorStatus(areaId = 1, isError = true, nowMs = 1_000)).isFalse()
        assertThat(tracker.onSensorStatus(areaId = 2, isError = true, nowMs = 1_500)).isTrue()
    }

    @Test
    fun `the same sensor erroring repeatedly never counts as two`() {
        val tracker = PerimeterSensorFaultTracker()
        assertThat(tracker.onSensorStatus(areaId = 1, isError = true, nowMs = 1_000)).isFalse()
        assertThat(tracker.onSensorStatus(areaId = 1, isError = true, nowMs = 1_500)).isFalse()
        assertThat(tracker.onSensorStatus(areaId = 1, isError = true, nowMs = 1_900)).isFalse()
    }

    @Test
    fun `a fault outside the window expires and no longer counts`() {
        val tracker = PerimeterSensorFaultTracker(windowMs = 2_000L)
        assertThat(tracker.onSensorStatus(areaId = 1, isError = true, nowMs = 1_000)).isFalse()
        assertThat(tracker.onSensorStatus(areaId = 2, isError = true, nowMs = 4_000)).isFalse()
    }

    @Test
    fun `a recovered sensor is removed and no longer counts toward the threshold`() {
        val tracker = PerimeterSensorFaultTracker()
        assertThat(tracker.onSensorStatus(areaId = 1, isError = true, nowMs = 1_000)).isFalse()
        assertThat(tracker.onSensorStatus(areaId = 1, isError = false, nowMs = 1_200)).isFalse()
        assertThat(tracker.onSensorStatus(areaId = 2, isError = true, nowMs = 1_400)).isFalse()
    }
}
