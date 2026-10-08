package dev.podscompanion

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dagger.hilt.android.AndroidEntryPoint
import dev.podscompanion.data.settings.SettingsRepository
import dev.podscompanion.service.PodsService
import dev.podscompanion.ui.battery.BatteryRoute
import dev.podscompanion.ui.permissions.ScanPermissions
import dev.podscompanion.ui.theme.PodsCompanionTheme
import javax.inject.Inject
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var settings: SettingsRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PodsCompanionTheme {
                BatteryRoute(showDebug = BuildConfig.PACKET_LOGGING)
            }
        }

        // Переключатель «Фоновый режим» запускает и останавливает сервис.
        // Сервис стартует только из видимого Activity: из фона Android 12+ запуск запрещает.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                settings.settings.map { it.backgroundEnabled }.distinctUntilChanged().collect { enabled ->
                    when {
                        // Без разрешения на скан сервис упадёт на старте, поэтому ждём его.
                        enabled && ScanPermissions.granted(this@MainActivity) -> PodsService.start(this@MainActivity)
                        !enabled -> PodsService.stop(this@MainActivity)
                    }
                }
            }
        }
    }
}
