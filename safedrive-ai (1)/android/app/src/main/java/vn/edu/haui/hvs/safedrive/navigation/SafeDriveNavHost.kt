package vn.edu.haui.hvs.safedrive.navigation

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import vn.edu.haui.hvs.safedrive.BuildConfig
import vn.edu.haui.hvs.safedrive.SafeDriveContainer
import vn.edu.haui.hvs.safedrive.feature.assistant.AssistantScreen
import vn.edu.haui.hvs.safedrive.feature.assistant.AssistantViewModel
import vn.edu.haui.hvs.safedrive.feature.cockpit.CockpitScreen
import vn.edu.haui.hvs.safedrive.feature.cockpit.CockpitViewModel
import vn.edu.haui.hvs.safedrive.feature.diagnostics.DiagnosticsScreen
import vn.edu.haui.hvs.safedrive.feature.diagnostics.DiagnosticsViewModel
import vn.edu.haui.hvs.safedrive.feature.settings.SettingsScreen
import vn.edu.haui.hvs.safedrive.feature.settings.SettingsViewModel
import vn.edu.haui.hvs.safedrive.feature.simulator.SimulatorScreen
import vn.edu.haui.hvs.safedrive.feature.simulator.SimulatorViewModel
import vn.edu.haui.hvs.safedrive.feature.voice.rememberVoiceTrigger

@Composable
fun SafeDriveNavHost(navController: NavHostController, container: SafeDriveContainer) {
    NavHost(navController = navController, startDestination = AppRoute.Cockpit.route) {
        composable(AppRoute.Cockpit.route) {
            val viewModel: CockpitViewModel = viewModel(
                factory = viewModelFactory {
                    initializer {
                        CockpitViewModel(container.cockpitSnapshot, container.voiceController, container.preferencesRepository)
                    }
                },
            )
            val triggerVoice = rememberVoiceTrigger(container.voiceController, screen = "cockpit")
            CockpitScreen(
                viewModel = viewModel,
                onOpenDiagnostics = { navController.navigate(AppRoute.Diagnostics.route) },
                onTriggerVoice = triggerVoice,
                onOpenSimulator = { navController.navigate(AppRoute.Simulator.route) },
            )
        }

        composable(AppRoute.Assistant.route) {
            val viewModel: AssistantViewModel = viewModel(
                factory = viewModelFactory {
                    initializer {
                        AssistantViewModel(
                            conversationRepository = container.conversationRepository,
                            assistantTurnCoordinator = container.assistantTurnCoordinator,
                            confirmActionUseCase = container.confirmActionUseCase,
                            preferencesRepository = container.preferencesRepository,
                            cockpitSnapshot = container.cockpitSnapshot,
                            pendingPromptCoordinator = container.pendingPromptCoordinator,
                            vehicleDataSource = container.vehicleDataSource,
                            vehicleActionExecutor = container.vehicleActionExecutor,
                            clock = container.clock,
                            voiceController = container.voiceController,
                        )
                    }
                },
            )
            val triggerVoice = rememberVoiceTrigger(container.voiceController, screen = "assistant")
            AssistantScreen(
                viewModel = viewModel,
                ttsController = container.ttsController,
                onOpenDiagnostics = { navController.navigate(AppRoute.Diagnostics.route) },
                onTriggerVoice = triggerVoice,
                onOpenSimulator = { navController.navigate(AppRoute.Simulator.route) },
            )
        }

        composable(AppRoute.Diagnostics.route) {
            val viewModel: DiagnosticsViewModel = viewModel(
                factory = viewModelFactory {
                    initializer {
                        DiagnosticsViewModel(
                            cockpitSnapshot = container.cockpitSnapshot,
                            preferencesRepository = container.preferencesRepository,
                            pendingPromptCoordinator = container.pendingPromptCoordinator,
                        )
                    }
                },
            )
            DiagnosticsScreen(
                viewModel = viewModel,
                onNavigateToAssistant = { navController.navigate(AppRoute.Assistant.route) },
                onNavigateToSimulator = { navController.navigate(AppRoute.Simulator.route) },
            )
        }

        composable(AppRoute.Settings.route) {
            val viewModel: SettingsViewModel = viewModel(
                factory = viewModelFactory {
                    initializer {
                        SettingsViewModel(
                            preferencesRepository = container.preferencesRepository,
                            gatewayProvider = container.gatewayProvider,
                            metricsRecorder = container.assistantTurnMetricsRecorder,
                            cockpitSnapshot = container.cockpitSnapshot,
                            conversationState = container.conversationRepository.state,
                            appVersionLabel = "SafeDrive AI v${BuildConfig.VERSION_NAME} (HVS - HaUI Automotive)",
                            onHealthChecked = container::recordHealthStatus,
                        )
                    }
                },
            )
            SettingsScreen(
                viewModel = viewModel,
                onOpenSimulator = { navController.navigate(AppRoute.Simulator.route) },
            )
        }

        composable(AppRoute.Simulator.route) {
            val viewModel: SimulatorViewModel = viewModel(
                factory = viewModelFactory {
                    initializer {
                        SimulatorViewModel(
                            vehicleDataSource = container.vehicleDataSource,
                            fixtures = container.fixtures,
                            preferencesRepository = container.preferencesRepository,
                            sessionCoordinator = container.sessionCoordinator,
                            idGenerator = container.idGenerator,
                            clock = container.clock,
                            cockpitSnapshot = container.cockpitSnapshot,
                        )
                    }
                },
            )
            SimulatorScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack() },
            )
        }
    }
}
