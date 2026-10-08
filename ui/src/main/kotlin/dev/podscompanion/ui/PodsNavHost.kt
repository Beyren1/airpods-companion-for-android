package dev.podscompanion.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import dev.podscompanion.ui.battery.AppSettingsScreen
import dev.podscompanion.ui.battery.BatteryUiState
import dev.podscompanion.ui.battery.BatteryViewModel
import dev.podscompanion.ui.battery.DebugInfo
import dev.podscompanion.ui.battery.GesturesUi
import dev.podscompanion.ui.battery.HeadphoneSettingsScreen
import dev.podscompanion.ui.battery.HomeScreen
import dev.podscompanion.ui.permissions.ScanPermissionGate

private object Routes {
    const val HOME = "home"
    const val HEADPHONES = "headphones"
    const val APP_SETTINGS = "app_settings"
}

/**
 * Три экрана приложения: главная, настройки наушников и настройки приложения.
 * ViewModel одна на все экраны (создаётся до NavHost, поэтому живёт, пока открыта Activity):
 * скан и прямое подключение не перезапускаются при переходе между экранами.
 */
@Composable
fun PodsNavHost() {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        ScanPermissionGate {
            val viewModel: BatteryViewModel = hiltViewModel()
            val state by viewModel.state.collectAsStateWithLifecycle()
            val refreshing by viewModel.refreshing.collectAsStateWithLifecycle()
            val settings by viewModel.settings.collectAsStateWithLifecycle()
            val aapSessions by viewModel.aapSessions.collectAsStateWithLifecycle()
            val nav = rememberNavController()

            val nearby = (state as? BatteryUiState.Found)?.nearby
            val main = nearby?.primary
            val session = if (main?.connected == true) aapSessions.forModel(main.model) else null

            NavHost(nav, startDestination = Routes.HOME) {
                composable(Routes.HOME) {
                    HomeScreen(
                        state, refreshing, viewModel::refresh,
                        aapSessions = aapSessions,
                        onAapCheck = viewModel::checkAap,
                        onCommand = viewModel::send,
                        onOpenHeadphoneSettings = { nav.navigate(Routes.HEADPHONES) },
                        onOpenAppSettings = { nav.navigate(Routes.APP_SETTINGS) },
                    )
                }
                composable(Routes.HEADPHONES) {
                    val calibration by viewModel.calibration.collectAsStateWithLifecycle()
                    HeadphoneSettingsScreen(
                        session, main?.model, viewModel::send,
                        onBack = { nav.popBackStack() },
                        gestures = GesturesUi(
                            enabled = settings.headGestures,
                            calibrated = settings.headCalibration != null,
                            backgroundEnabled = settings.backgroundEnabled,
                            calibration = calibration,
                            onEnabledChange = viewModel::setHeadGestures,
                            onCalibrate = viewModel::startCalibration,
                            onCalibrationDismiss = viewModel::dismissCalibration,
                        ),
                    )
                }
                composable(Routes.APP_SETTINGS) {
                    val autoPauseLog by viewModel.autoPauseLines.collectAsStateWithLifecycle()
                    val aapLog by viewModel.aapLog.collectAsStateWithLifecycle()
                    AppSettingsScreen(
                        settings,
                        onBackgroundChange = viewModel::setBackgroundEnabled,
                        onAutoPauseChange = viewModel::setAutoPause,
                        onDebugChange = viewModel::setDebugEnabled,
                        debug = DebugInfo(main, nearby?.others.orEmpty(), autoPauseLog, aapLog),
                        onBack = { nav.popBackStack() },
                    )
                }
            }
        }
    }
}
