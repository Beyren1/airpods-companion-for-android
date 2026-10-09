package dev.podscompanion.ui.battery

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.RadioButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.podscompanion.data.settings.AppSettings
import dev.podscompanion.data.settings.LOW_BATTERY_THRESHOLDS
import dev.podscompanion.data.settings.ThemeMode
import dev.podscompanion.ui.R
import dev.podscompanion.ui.theme.AppLanguage

/**
 * Настройки приложения: фоновый режим, автопауза, уведомления о заряде и оформление.
 *
 * При включении фонового режима просим два необязательных разрешения: уведомления (иначе заряд
 * в шторке не виден) и «подключение к устройствам поблизости» (чтобы сервис просыпался, когда
 * наушники подключаются). Отказ не мешает включить режим.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppSettingsScreen(
    settings: AppSettings,
    onBackgroundChange: (Boolean) -> Unit,
    onAutoPauseChange: (Boolean) -> Unit,
    onCasePopupChange: (Boolean) -> Unit,
    onLowBatteryChange: (Boolean) -> Unit,
    onLowBatteryThresholdChange: (Int) -> Unit,
    onThemeChange: (ThemeMode) -> Unit,
    onOpenDeviceInfo: () -> Unit,
    onBack: (() -> Unit)? = null,
) {
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { onBackgroundChange(true) }
    val optionalPermissions = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(Manifest.permission.BLUETOOTH_CONNECT)
    }.toTypedArray()

    // Предупреждение о заряде — обычное уведомление: на Android 13+ без разрешения его не видно.
    // Отказ не мешает включить переключатель, но тогда уведомлений не будет.
    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { onLowBatteryChange(true) }

    // Окно при открытии кейса рисуется поверх других приложений: без разрешения «Поверх других
    // приложений» Android не даст фоновому сервису его открыть. Ведём в системные настройки,
    // а переключатель включаем, когда пользователь вернётся с разрешением.
    val context = LocalContext.current
    val overlayLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { if (Settings.canDrawOverlays(context)) onCasePopupChange(true) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_settings_title)) },
                navigationIcon = {
                    // Во вкладке внизу кнопки «назад» нет: экран открыт не поверх другого.
                    if (onBack != null) {
                        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SettingsGroup {
                row {
                    SwitchRow(
                        stringResource(R.string.bg_title), settings.backgroundEnabled,
                        { enable ->
                            if (enable && optionalPermissions.isNotEmpty()) launcher.launch(optionalPermissions)
                            else onBackgroundChange(enable)
                        },
                        icon = Icons.Filled.Notifications,
                        description = stringResource(R.string.bg_description),
                    )
                }
                row {
                    SwitchRow(
                        stringResource(R.string.autopause_title), settings.autoPause, onAutoPauseChange,
                        icon = Icons.Filled.Pause,
                        description = stringResource(R.string.autopause_description),
                        enabled = settings.backgroundEnabled,
                    )
                }
                row {
                    SwitchRow(
                        stringResource(R.string.case_popup_title), settings.casePopup,
                        { enable ->
                            if (enable && !Settings.canDrawOverlays(context)) {
                                overlayLauncher.launch(
                                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")),
                                )
                            } else {
                                onCasePopupChange(enable)
                            }
                        },
                        icon = Icons.Filled.Inventory2,
                        description = stringResource(R.string.case_popup_description),
                        enabled = settings.backgroundEnabled,
                    )
                }
                row {
                    SwitchRow(
                        stringResource(R.string.low_battery_title), settings.lowBatteryAlerts,
                        { enable ->
                            if (enable && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            } else {
                                onLowBatteryChange(enable)
                            }
                        },
                        icon = Icons.Filled.BatteryAlert,
                        description = stringResource(R.string.low_battery_description),
                        enabled = settings.backgroundEnabled,
                    )
                }
                if (settings.backgroundEnabled && settings.lowBatteryAlerts) {
                    row {
                        BlockRow {
                            Text(stringResource(R.string.low_battery_threshold), style = MaterialTheme.typography.bodyLarge)
                            Choice(LOW_BATTERY_THRESHOLDS, settings.lowBatteryThreshold, { "$it%" }, onLowBatteryThresholdChange)
                        }
                    }
                }
            }

            SectionTitle(stringResource(R.string.section_appearance))
            SettingsGroup {
                row {
                    BlockRow {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.DarkMode, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                stringResource(R.string.theme_title),
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.padding(start = 16.dp),
                            )
                        }
                        Choice(ThemeMode.entries, settings.theme, { themeLabel(it) }, onThemeChange)
                    }
                }
                row { LanguageRow() }
            }

            SettingsGroup {
                row {
                    NavRow(
                        stringResource(R.string.device_info_title), onOpenDeviceInfo,
                        icon = Icons.Filled.PhoneAndroid,
                        description = stringResource(R.string.device_info_row_hint),
                    )
                }
                row {
                    InfoRow(
                        stringResource(R.string.about_title),
                        stringResource(R.string.about_version, appVersion()),
                        icon = Icons.Filled.Info,
                    )
                }
            }
        }
    }
}

@Composable
private fun themeLabel(mode: ThemeMode): String = stringResource(
    when (mode) {
        ThemeMode.SYSTEM -> R.string.theme_system
        ThemeMode.LIGHT -> R.string.theme_light
        ThemeMode.DARK -> R.string.theme_dark
    },
)

/** Название языка: свои названия пишем на самом языке, чтобы их узнал тот, кто им говорит. */
@Composable
private fun languageLabel(language: AppLanguage): String = when (language) {
    AppLanguage.SYSTEM -> stringResource(R.string.language_system)
    AppLanguage.RUSSIAN -> "Русский"
    AppLanguage.ENGLISH -> "English"
    AppLanguage.FRENCH -> "Français"
}

/**
 * Строка «Язык» и окно выбора. После выбора Android 13+ сам пересоздаёт окна с новыми строками;
 * на старых версиях пересоздаём экран сами.
 */
@Composable
private fun LanguageRow() {
    val context = LocalContext.current
    val current = remember { AppLanguage.current(context) }
    var open by rememberSaveable { mutableStateOf(false) }
    NavRow(
        stringResource(R.string.language_title), { open = true },
        icon = Icons.Filled.Language,
        description = languageLabel(current),
    )
    if (!open) return
    AlertDialog(
        onDismissRequest = { open = false },
        title = { Text(stringResource(R.string.language_title)) },
        text = {
            Column(Modifier.selectableGroup()) {
                AppLanguage.entries.forEach { language ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .selectable(
                                selected = language == current,
                                role = Role.RadioButton,
                                onClick = {
                                    open = false
                                    if (language != current) {
                                        AppLanguage.set(context, language) { (context as? Activity)?.recreate() }
                                    }
                                },
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = language == current, onClick = null)
                        Text(languageLabel(language), Modifier.padding(start = 16.dp), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { open = false }) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@Composable
internal fun appVersion(): String {
    val context = LocalContext.current
    return runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?"
}
