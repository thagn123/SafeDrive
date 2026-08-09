package vn.edu.haui.hvs.safedrive

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import vn.edu.haui.hvs.safedrive.feature.emergency.EmergencyScreen
import vn.edu.haui.hvs.safedrive.feature.emergency.EmergencyUiState
import vn.edu.haui.hvs.safedrive.feature.emergency.EmergencyViewModel
import vn.edu.haui.hvs.safedrive.feature.voice.VoiceOverlay
import vn.edu.haui.hvs.safedrive.feature.voice.rememberVoiceTrigger
import vn.edu.haui.hvs.safedrive.navigation.AppRoute
import vn.edu.haui.hvs.safedrive.navigation.SafeDriveNavHost
import vn.edu.haui.hvs.safedrive.navigation.bottomNavItems
import vn.edu.haui.hvs.safedrive.service.WakeWordListeningService

private fun iconFor(route: AppRoute) = when (route) {
    AppRoute.Cockpit -> Icons.Filled.DirectionsCar
    AppRoute.Assistant -> Icons.AutoMirrored.Filled.Chat
    AppRoute.Diagnostics -> Icons.Filled.Build
    AppRoute.Settings -> Icons.Filled.Settings
    AppRoute.Simulator -> Icons.Filled.Build
}

/**
 * Root composable: bottom navigation + [SafeDriveNavHost]. Emergency renders as a full-screen
 * overlay above this Scaffold and the bottom bar is skipped entirely while it is active — Emergency
 * is not one of the [bottomNavItems] routes (see docs/android-mvp-plan/04-screen-specs.md).
 */
@Composable
fun SafeDriveApp(container: SafeDriveContainer) {
    val navController = rememberNavController()
    val context = LocalContext.current
    val preferences by container.appPreferences.collectAsStateWithLifecycle()

    // Real background "Mai ơi" listening runs in WakeWordListeningService, independent of this
    // Activity's lifecycle — started here (a foreground-causing Compose context, satisfying Android 12+'s
    // background-start restriction) and self-stopped by the service observing wakeWordEnabled directly.
    // Audio is handled entirely on-device (Android's own SpeechRecognizer for both the ambient wake
    // word and the bounded command capture) and is never persisted or forwarded to the vehicle backend.
    LaunchedEffect(preferences.wakeWordEnabled) {
        val hasRecordAudio = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        val hasNotificationPermission = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        val serviceIntent = Intent(context, WakeWordListeningService::class.java)
        if (preferences.wakeWordEnabled && hasRecordAudio && hasNotificationPermission) {
            ContextCompat.startForegroundService(context, serviceIntent)
        } else {
            context.stopService(serviceIntent)
        }
    }

    val emergencyViewModel: EmergencyViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                EmergencyViewModel(
                    emergencyRepository = container.emergencyRepository,
                    clock = container.clock,
                    preferencesRepository = container.preferencesRepository,
                )
            }
        },
    )
    val emergencyState by emergencyViewModel.uiState.collectAsStateWithLifecycle()
    val emergencyActive = emergencyState is EmergencyUiState.Active

    Scaffold(
        bottomBar = {
            if (!emergencyActive) {
                val backStackEntry by navController.currentBackStackEntryAsState()
                val currentDestination = backStackEntry?.destination
                NavigationBar {
                    bottomNavItems.forEach { item ->
                        val selected = currentDestination?.hierarchy?.any { it.route == item.route.route } == true
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                navController.navigate(item.route.route) {
                                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(iconFor(item.route), contentDescription = item.label) },
                            label = { androidx.compose.material3.Text(item.label) },
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        Box(modifier = Modifier.padding(innerPadding)) {
            SafeDriveNavHost(
                navController = navController,
                container = container,
            )
        }
    }

    // Voice overlay renders above the Scaffold/bottom nav regardless of which tab is active
    // (docs/android-mvp-plan/04-screen-specs.md, "Voice overlay ... overlay trên screen hiện tại").
    VoiceOverlay(
        voiceController = container.voiceController,
        ttsController = container.ttsController,
        onCancelProcessing = container.assistantTurnCoordinator::cancelCurrent,
        turnOutcome = container.voiceAssistantCoordinator.turnOutcome,
        onDismissTurnOutcome = {
            container.voiceAssistantCoordinator.stopContinuousMode()
            container.voiceAssistantCoordinator.dismissTurnOutcome()
        },
    )

    if (emergencyActive) {
        val triggerVoiceCancel = rememberVoiceTrigger(container.voiceController, screen = "emergency")
        EmergencyScreen(viewModel = emergencyViewModel, onTriggerVoiceCancel = triggerVoiceCancel)
    }
}
