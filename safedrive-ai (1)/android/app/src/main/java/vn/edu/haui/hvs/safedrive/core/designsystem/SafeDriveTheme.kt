package vn.edu.haui.hvs.safedrive.core.designsystem

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color

/**
 * Minimal Material 3 theme scaffold for Phase 1. Semantic status colors (normal/monitor/high/
 * critical/offline) and full typography/spacing tokens are added in Phase 2
 * (docs/android-mvp-plan/04-screen-specs.md, "Quy tắc UI chung").
 */
private val SafeDriveDarkColorScheme = darkColorScheme(
    primary = Color(0xFF14B8A6),
    secondary = Color(0xFF38BDF8),
    background = Color(0xFF0B1220),
    surface = Color(0xFF111827),
    error = Color(0xFFEF4444),
)

private val SafeDriveLightColorScheme = lightColorScheme(
    primary = Color(0xFF0F766E),
    secondary = Color(0xFF0284C7),
    background = Color(0xFFF8FAFC),
    surface = Color(0xFFFFFFFF),
    error = Color(0xFFDC2626),
)

@Composable
fun SafeDriveTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) SafeDriveDarkColorScheme else SafeDriveLightColorScheme
    CompositionLocalProvider(LocalSafeDriveStatusColors provides statusColorsFor(darkTheme)) {
        MaterialTheme(
            colorScheme = colorScheme,
            content = content,
        )
    }
}
