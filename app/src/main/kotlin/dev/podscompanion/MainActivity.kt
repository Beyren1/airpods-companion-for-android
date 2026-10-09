package dev.podscompanion

import android.content.Context
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dagger.hilt.android.AndroidEntryPoint
import dev.podscompanion.data.settings.SettingsRepository
import dev.podscompanion.service.PodsService
import dev.podscompanion.ui.PodsNavHost
import dev.podscompanion.ui.permissions.ScanPermissions
import dev.podscompanion.ui.theme.AppLanguage
import dev.podscompanion.ui.theme.AppTheme
import dev.podscompanion.ui.theme.PodsCompanionTheme
import dev.podscompanion.ui.theme.isDark
import javax.inject.Inject
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var settings: SettingsRepository

    // Язык, выбранный в приложении (на Android 10–12; на 13+ это делает система).
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguage.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Тему читаем до первого кадра, иначе при тёмной теме на миг мелькнёт светлая.
        // DataStore отвечает за миллисекунды, поэтому ждать на главном потоке тут можно.
        val initial = runBlocking { settings.settings.first() }
        enableEdgeToEdge()
        setContent {
            val appSettings by settings.settings.collectAsStateWithLifecycle(initial)
            val dark = appSettings.theme.isDark()
            // Значки строки состояния: тёмные на светлой теме, светлые на тёмной.
            LaunchedEffect(dark) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
                    navigationBarStyle = SystemBarStyle.auto(LIGHT_SCRIM, DARK_SCRIM) { dark },
                )
            }
            PodsCompanionTheme(appSettings.theme) {
                PodsNavHost()
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
        lifecycleScope.launch {
            settings.settings.map { it.theme }.distinctUntilChanged().collect { AppTheme.applyToSystem(this@MainActivity, it) }
        }
    }

    private companion object {
        // Как у enableEdgeToEdge() по умолчанию: полупрозрачная подложка под кнопками навигации.
        val LIGHT_SCRIM = Color.argb(0xE6, 0xFF, 0xFF, 0xFF)
        val DARK_SCRIM = Color.argb(0x80, 0x1B, 0x1B, 0x1B)
    }
}
