package vn.edu.haui.hvs.safedrive.core.designsystem

import androidx.compose.ui.unit.dp

/** Spacing/sizing tokens shared by Cockpit and other screens. Never a fixed-pixel viewport height —
 * see docs/android-mvp-plan/04-screen-specs.md, "Quy tắc adaptive Cockpit". */
object Dimensions {
    val screenPadding = 12.dp
    val cardPadding = 12.dp
    val cardSpacing = 8.dp
    val cardCornerRadius = 20.dp
    val compactRowHeight = 56.dp
    val minTouchTarget = 48.dp
}
