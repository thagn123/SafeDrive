package vn.edu.haui.hvs.safedrive.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun StatusBadge(text: String, palette: StatusPalette, modifier: Modifier = Modifier) {
    Text(
        text = text,
        color = palette.onBadge,
        style = MaterialTheme.typography.labelSmall,
        modifier = modifier
            .background(palette.badgeBackground, RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}
