package vn.edu.haui.hvs.safedrive.feature.cockpit.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import vn.edu.haui.hvs.safedrive.core.designsystem.Dimensions
import vn.edu.haui.hvs.safedrive.core.designsystem.LocalSafeDriveStatusColors
import vn.edu.haui.hvs.safedrive.core.designsystem.StatusBadge
import vn.edu.haui.hvs.safedrive.core.designsystem.paletteForSeverity
import vn.edu.haui.hvs.safedrive.core.model.ConfidenceLevel
import vn.edu.haui.hvs.safedrive.core.model.DriverSupportSignals
import vn.edu.haui.hvs.safedrive.core.model.RestRecommendation
import vn.edu.haui.hvs.safedrive.core.model.RiskAssessment
import vn.edu.haui.hvs.safedrive.core.model.Severity

/**
 * Cockpit status hero per docs/android-mvp-plan/04-screen-specs.md. Title/message/level always come
 * from [riskAssessment]/[restRecommendation] (gateway output) — this composable never computes them.
 */
@Composable
fun StatusHeroCard(
    riskAssessment: RiskAssessment,
    restRecommendation: RestRecommendation,
    driverSupportSignals: DriverSupportSignals,
    onOpenDetails: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = paletteForSeverity(riskAssessment.level)
    val colors = LocalSafeDriveStatusColors.current
    val icon = when (riskAssessment.level) {
        Severity.LOW -> Icons.Filled.CheckCircle
        Severity.MEDIUM -> Icons.Filled.Info
        Severity.HIGH, Severity.CRITICAL -> Icons.Filled.Warning
    }
    val badgeText = when (riskAssessment.level) {
        Severity.LOW -> "BÌNH THƯỜNG"
        Severity.MEDIUM -> "NÊN THEO DÕI"
        Severity.HIGH -> "KHUYẾN NGHỊ NGHỈ"
        Severity.CRITICAL -> "KHẨN CẤP"
    }
    val title = riskAssessment.title
    val message = riskAssessment.message
    val confidenceText = when (restRecommendation.confidence) {
        ConfidenceLevel.HIGH -> "Tin cậy: Cao"
        ConfidenceLevel.MEDIUM -> "Tin cậy: Trung bình"
        ConfidenceLevel.LOW -> "Tin cậy: Thấp"
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(palette.background, RoundedCornerShape(Dimensions.cardCornerRadius))
            .border(1.dp, palette.border.copy(alpha = 0.6f), RoundedCornerShape(Dimensions.cardCornerRadius))
            .clickable(onClickLabel = "Xem chi tiết tín hiệu hỗ trợ", onClick = onOpenDetails)
            .padding(Dimensions.cardPadding)
            .semantics { contentDescription = "$title. $message" },
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "TRẠNG THÁI HIỆN TẠI",
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceMuted,
            )
            StatusBadge(text = badgeText, palette = palette)
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = palette.icon,
                modifier = Modifier
                    .background(palette.iconContainer, RoundedCornerShape(14.dp))
                    .padding(8.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = Color.White)
                Text(
                    message,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 3,
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "$confidenceText  •  ${driverSupportSignals.availableSourceCount}/${driverSupportSignals.totalSourceCount} nguồn",
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceMuted,
            )
        }
    }
}
