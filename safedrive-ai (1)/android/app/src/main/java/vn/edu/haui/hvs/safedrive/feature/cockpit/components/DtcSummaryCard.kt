package vn.edu.haui.hvs.safedrive.feature.cockpit.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import vn.edu.haui.hvs.safedrive.core.designsystem.Dimensions
import vn.edu.haui.hvs.safedrive.core.designsystem.LocalSafeDriveStatusColors
import vn.edu.haui.hvs.safedrive.core.model.Dtc

/** Navigates to Diagnostics on tap. Severity/copy always come from the [Dtc] list — never computed here. */
@Composable
fun DtcSummaryCard(activeDtcs: List<Dtc>, onOpenDiagnostics: () -> Unit, modifier: Modifier = Modifier) {
    val colors = LocalSafeDriveStatusColors.current
    val hasDtcs = activeDtcs.isNotEmpty()
    val palette = if (hasDtcs) colors.monitor else colors.normal
    val title = if (hasDtcs) "${activeDtcs.size} lỗi kỹ thuật active" else "Không có lỗi kỹ thuật"
    val subtitle = if (hasDtcs) {
        "${activeDtcs.first().code} · ${activeDtcs.first().title}"
    } else {
        "Hệ thống xe hoạt động bình thường"
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(Dimensions.compactRowHeight)
            .background(palette.background, RoundedCornerShape(Dimensions.cardCornerRadius))
            .clickable(onClickLabel = "Mở màn hình chẩn đoán", onClick = onOpenDiagnostics)
            .padding(horizontal = 12.dp)
            .semantics { contentDescription = "$title. $subtitle" },
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(modifier = Modifier.fillMaxHeight(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Icon(
                imageVector = if (hasDtcs) Icons.Filled.Warning else Icons.Filled.CheckCircle,
                contentDescription = null,
                tint = palette.icon,
                modifier = Modifier
                    .background(palette.iconContainer, RoundedCornerShape(10.dp))
                    .padding(6.dp),
            )
            Column(modifier = Modifier.padding(start = 8.dp)) {
                Text(title, style = MaterialTheme.typography.labelMedium, maxLines = 1)
                Text(subtitle, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceMuted, maxLines = 1)
            }
        }
    }
}
