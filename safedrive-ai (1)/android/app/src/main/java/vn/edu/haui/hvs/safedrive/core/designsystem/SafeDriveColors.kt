package vn.edu.haui.hvs.safedrive.core.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import vn.edu.haui.hvs.safedrive.core.model.Severity
import vn.edu.haui.hvs.safedrive.core.model.SystemConnectionStatus

/**
 * Semantic status palette per docs/android-mvp-plan/04-screen-specs.md ("Quy tắc UI chung"):
 * normal/teal, monitor/amber, high/orange, critical/red, offline/slate. Never tied to attention or
 * drowsiness — only to gateway-provided [Severity]/[SystemConnectionStatus].
 */
data class StatusPalette(
    val background: Color,
    val border: Color,
    val badgeBackground: Color,
    val onBadge: Color,
    val icon: Color,
    val iconContainer: Color,
)

data class SafeDriveStatusColors(
    val normal: StatusPalette,
    val monitor: StatusPalette,
    val high: StatusPalette,
    val critical: StatusPalette,
    val offline: StatusPalette,
    val surface: Color,
    val surfaceVariant: Color,
    val onSurfaceMuted: Color,
)

private val DarkStatusColors = SafeDriveStatusColors(
    normal = StatusPalette(
        background = Color(0xFF102A2A),
        border = Color(0xFF14B8A6),
        badgeBackground = Color(0x3314B8A6),
        onBadge = Color(0xFF5EEAD4),
        icon = Color(0xFF34D399),
        iconContainer = Color(0x2634D399),
    ),
    monitor = StatusPalette(
        background = Color(0xFF2E260F),
        border = Color(0xFFF59E0B),
        badgeBackground = Color(0x33F59E0B),
        onBadge = Color(0xFFFCD34D),
        icon = Color(0xFFFBBF24),
        iconContainer = Color(0x26FBBF24),
    ),
    high = StatusPalette(
        background = Color(0xFF33230F),
        border = Color(0xFFF97316),
        badgeBackground = Color(0x33F97316),
        onBadge = Color(0xFFFDBA74),
        icon = Color(0xFFFB923C),
        iconContainer = Color(0x26FB923C),
    ),
    critical = StatusPalette(
        background = Color(0xFF3A1220),
        border = Color(0xFFEF4444),
        badgeBackground = Color(0x33EF4444),
        onBadge = Color(0xFFFCA5A5),
        icon = Color(0xFFF87171),
        iconContainer = Color(0x26F87171),
    ),
    offline = StatusPalette(
        background = Color(0xFF1E293B),
        border = Color(0xFF64748B),
        badgeBackground = Color(0x3364748B),
        onBadge = Color(0xFFCBD5E1),
        icon = Color(0xFF94A3B8),
        iconContainer = Color(0x2694A3B8),
    ),
    surface = Color(0xFF132437),
    surfaceVariant = Color(0xFF0B1724),
    onSurfaceMuted = Color(0xFF94A3B8),
)

private val LightStatusColors = SafeDriveStatusColors(
    normal = StatusPalette(
        background = Color(0xFFE6FFFA),
        border = Color(0xFF0F766E),
        badgeBackground = Color(0x330F766E),
        onBadge = Color(0xFF0F766E),
        icon = Color(0xFF0F766E),
        iconContainer = Color(0x260F766E),
    ),
    monitor = StatusPalette(
        background = Color(0xFFFFF7E6),
        border = Color(0xFFB45309),
        badgeBackground = Color(0x33B45309),
        onBadge = Color(0xFFB45309),
        icon = Color(0xFFB45309),
        iconContainer = Color(0x26B45309),
    ),
    high = StatusPalette(
        background = Color(0xFFFFEDE0),
        border = Color(0xFFC2410C),
        badgeBackground = Color(0x33C2410C),
        onBadge = Color(0xFFC2410C),
        icon = Color(0xFFC2410C),
        iconContainer = Color(0x26C2410C),
    ),
    critical = StatusPalette(
        background = Color(0xFFFEE2E2),
        border = Color(0xFFB91C1C),
        badgeBackground = Color(0x33B91C1C),
        onBadge = Color(0xFFB91C1C),
        icon = Color(0xFFB91C1C),
        iconContainer = Color(0x26B91C1C),
    ),
    offline = StatusPalette(
        background = Color(0xFFF1F5F9),
        border = Color(0xFF475569),
        badgeBackground = Color(0x33475569),
        onBadge = Color(0xFF334155),
        icon = Color(0xFF475569),
        iconContainer = Color(0x26475569),
    ),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFF8FAFC),
    onSurfaceMuted = Color(0xFF64748B),
)

val LocalSafeDriveStatusColors = staticCompositionLocalOf { DarkStatusColors }

internal fun statusColorsFor(darkTheme: Boolean) = if (darkTheme) DarkStatusColors else LightStatusColors

@Composable
fun paletteForSeverity(severity: Severity): StatusPalette {
    val colors = LocalSafeDriveStatusColors.current
    return when (severity) {
        Severity.LOW -> colors.normal
        Severity.MEDIUM -> colors.monitor
        Severity.HIGH -> colors.high
        Severity.CRITICAL -> colors.critical
    }
}

@Composable
fun paletteForConnectionStatus(status: SystemConnectionStatus): StatusPalette {
    val colors = LocalSafeDriveStatusColors.current
    return when (status) {
        SystemConnectionStatus.NORMAL -> colors.normal
        SystemConnectionStatus.STALE_DATA -> colors.monitor
        SystemConnectionStatus.NO_VEHICLE_DATA, SystemConnectionStatus.NO_AI_SERVICE -> colors.high
        SystemConnectionStatus.OFFLINE -> colors.offline
    }
}
