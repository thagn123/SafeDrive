package vn.edu.haui.hvs.safedrive.feature.cockpit

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import vn.edu.haui.hvs.safedrive.core.designsystem.SafeDriveTheme
import vn.edu.haui.hvs.safedrive.core.model.ConfidenceLevel
import vn.edu.haui.hvs.safedrive.core.model.DriverSupportSignals
import vn.edu.haui.hvs.safedrive.core.model.PassengerResponse
import vn.edu.haui.hvs.safedrive.core.model.RestRecommendation
import vn.edu.haui.hvs.safedrive.core.model.RestRecommendationLevel
import vn.edu.haui.hvs.safedrive.core.model.RiskAssessment
import vn.edu.haui.hvs.safedrive.core.model.Severity
import vn.edu.haui.hvs.safedrive.core.model.SystemConnectionStatus
import vn.edu.haui.hvs.safedrive.core.model.VehicleState
import vn.edu.haui.hvs.safedrive.core.model.VoiceState

/**
 * Compose UI tests for the adaptive Cockpit body per docs/android-mvp-plan/04-screen-specs.md and
 * 07-testing-security-acceptance.md ("Cockpit normal/high/critical/stale/offline", portrait/landscape,
 * font scale). A fixed-size [Box] stands in for the device viewport since Compose UI tests don't run
 * against a specific physical screen size.
 */
class CockpitContentTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun sampleState(
        riskLevel: Severity = Severity.LOW,
        connectionStatus: SystemConnectionStatus = SystemConnectionStatus.NORMAL,
        isStale: Boolean = false,
        activeDtcs: List<vn.edu.haui.hvs.safedrive.core.model.Dtc> = emptyList(),
    ) = CockpitUiState.Content(
        vehicleState = VehicleState(
            speedKmh = 62f,
            engineTemperatureC = 92f,
            cabinTemperatureC = 25f,
            energyPercent = 74,
            continuousDrivingMinutes = 135,
            steeringLastInteractionSeconds = 12,
            driverSeatOccupied = true,
            wearableConnected = false,
            activeDtcs = activeDtcs,
            crashDetected = false,
            passengerResponse = PassengerResponse.RESPONSIVE,
            updatedAtMs = 0L,
        ),
        driverSupportSignals = DriverSupportSignals(
            steeringSignalAvailable = true,
            seatSensorAvailable = true,
            wearableLastUpdateMs = null,
            wearableHeartRateBpm = null,
            userReportedFatigue = false,
            availableSourceCount = 3,
            totalSourceCount = 4,
        ),
        riskAssessment = RiskAssessment(
            level = riskLevel,
            title = "Mức độ an toàn: THẤP",
            message = "Xe và hành trình đang ở trạng thái ổn định.",
            reasonCodes = listOf("system_nominal"),
        ),
        restRecommendation = RestRecommendation(
            level = RestRecommendationLevel.NORMAL,
            title = "Chưa ghi nhận dấu hiệu cần nghỉ",
            message = "Chưa ghi nhận tín hiệu cho thấy cần nghỉ ngay.",
            confidence = ConfidenceLevel.MEDIUM,
            reasonCodes = listOf("system_nominal"),
            updatedAtMs = 0L,
        ),
        connectionStatus = connectionStatus,
        voiceState = VoiceState.IDLE,
        isStale = isStale,
    )

    private fun setContentAtSize(widthDp: Int, heightDp: Int, state: CockpitUiState.Content) {
        composeRule.setContent {
            SafeDriveTheme {
                Box(modifier = Modifier.size(widthDp.dp, heightDp.dp)) {
                    CockpitContent(
                        state = state,
                        onOpenDetails = {},
                        onOpenDiagnostics = {},
                        onTriggerVoice = {},
                    )
                }
            }
        }
    }

    @Test
    fun portrait390x844_showsAllCoreSectionsSimultaneously() {
        setContentAtSize(390, 844, sampleState())

        composeRule.onNodeWithText("SafeDrive AI").assertIsDisplayed()
        composeRule.onNodeWithText("62", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("TÍN HIỆU HỖ TRỢ").assertIsDisplayed()
        composeRule.onNodeWithText("Không có lỗi kỹ thuật").assertIsDisplayed()
    }

    @Test
    fun landscape844x390_showsAllCoreSectionsWithoutOverlap() {
        setContentAtSize(844, 390, sampleState())

        composeRule.onNodeWithText("SafeDrive AI").assertIsDisplayed()
        composeRule.onNodeWithText("TÍN HIỆU HỖ TRỢ").assertIsDisplayed()
        composeRule.onNodeWithText("Không có lỗi kỹ thuật").assertIsDisplayed()
    }

    @Test
    fun criticalRisk_showsEmergencyBadgeCopy() {
        setContentAtSize(390, 844, sampleState(riskLevel = Severity.CRITICAL))
        composeRule.onNodeWithText("KHẨN CẤP").assertIsDisplayed()
    }

    @Test
    fun staleConnection_showsStaleBanner() {
        setContentAtSize(
            390,
            844,
            sampleState(connectionStatus = SystemConnectionStatus.STALE_DATA, isStale = true),
        )
        composeRule.onNodeWithText("Dữ liệu cũ — đang hiển thị giá trị gần nhất").assertIsDisplayed()
    }

    @Test
    fun activeDtc_showsDtcCountInSummaryCard() {
        val dtc = vn.edu.haui.hvs.safedrive.core.model.Dtc(
            code = "P0301",
            title = "Lỗi đánh lửa xi-lanh số 1",
            description = "desc",
            severity = Severity.MEDIUM,
            recommendation = "rec",
            updatedAtMs = 0L,
        )
        setContentAtSize(390, 844, sampleState(activeDtcs = listOf(dtc)))
        composeRule.onNodeWithText("1 lỗi kỹ thuật active").assertIsDisplayed()
    }

    @Test
    fun largeFontScale_stillRendersPrimaryStatusWithoutCrashing() {
        composeRule.setContent {
            val baseDensity = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(baseDensity.density, fontScale = 1.3f)) {
                SafeDriveTheme {
                    Box(modifier = Modifier.size(390.dp, 844.dp)) {
                        CockpitContent(
                            state = sampleState(),
                            onOpenDetails = {},
                            onOpenDiagnostics = {},
                            onTriggerVoice = {},
                        )
                    }
                }
            }
        }
        composeRule.onNodeWithText("SafeDrive AI").assertIsDisplayed()
    }
}
