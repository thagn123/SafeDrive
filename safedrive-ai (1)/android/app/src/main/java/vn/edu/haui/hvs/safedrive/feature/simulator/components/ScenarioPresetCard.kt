package vn.edu.haui.hvs.safedrive.feature.simulator.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import vn.edu.haui.hvs.safedrive.core.designsystem.Dimensions
import vn.edu.haui.hvs.safedrive.core.designsystem.LocalSafeDriveStatusColors
import vn.edu.haui.hvs.safedrive.core.model.ScenarioPreset

@Composable
fun ScenarioPresetCard(preset: ScenarioPreset, isSelected: Boolean, onSelect: () -> Unit, modifier: Modifier = Modifier) {
    val colors = LocalSafeDriveStatusColors.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.surface, RoundedCornerShape(Dimensions.cardCornerRadius))
            .border(
                1.dp,
                if (isSelected) colors.normal.border else colors.surfaceVariant,
                RoundedCornerShape(Dimensions.cardCornerRadius),
            )
            .clickable(onClick = onSelect)
            .padding(Dimensions.cardPadding),
    ) {
        Text(preset.title, style = MaterialTheme.typography.titleSmall)
        Text(preset.subtitle, style = MaterialTheme.typography.labelSmall, color = colors.normal.icon)
        Text(
            preset.description,
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceMuted,
            maxLines = 2,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}
