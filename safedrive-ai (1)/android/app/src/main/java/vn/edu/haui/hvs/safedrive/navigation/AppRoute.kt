package vn.edu.haui.hvs.safedrive.navigation

/** Route contract for [SafeDriveNavHost]. Simulator/Emergency are intentionally not bottom-nav routes. */
sealed class AppRoute(val route: String) {
    data object Cockpit : AppRoute("cockpit")
    data object Assistant : AppRoute("assistant")
    data object Diagnostics : AppRoute("diagnostics")
    data object Settings : AppRoute("settings")
    data object Simulator : AppRoute("simulator")
}

data class BottomNavItem(val route: AppRoute, val label: String)

/** Simulator is a guided demo route, opened from a shortcut and never one of the four main tabs. */
val bottomNavItems = listOf(
    BottomNavItem(AppRoute.Cockpit, "Cockpit"),
    BottomNavItem(AppRoute.Assistant, "Trợ lý"),
    BottomNavItem(AppRoute.Diagnostics, "Chẩn đoán"),
    BottomNavItem(AppRoute.Settings, "Cài đặt"),
)
