package dev.podscompanion.ui.battery

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.podscompanion.data.PodsStatus
import dev.podscompanion.data.settings.AppSettings
import dev.podscompanion.ui.R

/**
 * Настройки приложения: фоновый режим, автопауза и отладка. Журналы и сырые пакеты видны
 * только при включённой отладке.
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
    onDebugChange: (Boolean) -> Unit,
    debug: DebugInfo,
    onBack: () -> Unit,
) {
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { onBackgroundChange(true) }
    val optionalPermissions = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(Manifest.permission.BLUETOOTH_CONNECT)
    }.toTypedArray()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
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
            }

            SectionTitle(stringResource(R.string.section_dev))
            SettingsGroup {
                row {
                    SwitchRow(
                        stringResource(R.string.debug_title), settings.debugEnabled, onDebugChange,
                        icon = Icons.Filled.BugReport,
                        description = stringResource(R.string.debug_description),
                    )
                }
                if (settings.debugEnabled) {
                    debug.main?.let { main -> row { BlockRow { MainPackets(main) } } }
                    if (debug.others.isNotEmpty()) row { BlockRow { OtherPackets(debug.others) } }
                    if (debug.autoPauseLog.isNotEmpty()) row { BlockRow { LogBlock(stringResource(R.string.debug_autopause_log), debug.autoPauseLog) } }
                    if (debug.aapLog.isNotEmpty()) row { BlockRow { LogBlock(stringResource(R.string.debug_aap_log), debug.aapLog) } }
                }
            }

            SettingsGroup {
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

/** Что показать в отладке: пакеты главных и соседних наушников и оба журнала. */
data class DebugInfo(
    val main: PodsStatus? = null,
    val others: List<PodsStatus> = emptyList(),
    val autoPauseLog: List<String> = emptyList(),
    val aapLog: List<String> = emptyList(),
)

@Composable
private fun MainPackets(status: PodsStatus) {
    Text(stringResource(R.string.debug_packets), style = MaterialTheme.typography.titleSmall)
    if (status.model?.verified != true) {
        // Модель не сверена по реальным пакетам: просим прислать байты.
        Text(
            stringResource(R.string.debug_unverified_model),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.tertiary,
        )
    }
    Text(
        "RSSI ${status.rssi} dBm · model=0x%04X · lid=0x%02X · color=0x%02X"
            .format(status.modelId, status.lidCounter, status.colorCode),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    status.packetIntervalMs?.let { interval ->
        Text(
            stringResource(R.string.debug_packet_interval, interval / 1000f),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    // Долгое нажатие выделяет текст: байты можно скопировать и прислать вместо скриншота.
    SelectionContainer {
        val text = if (status.rawByAddress.size > 1) {
            // Несколько адресов: показываем последний пакет с каждого.
            status.rawByAddress.entries.joinToString("\n\n") { (address, hex) -> "$address\n$hex" }
        } else {
            status.rawHex
        }
        Text(text, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun OtherPackets(others: List<PodsStatus>) {
    Text(stringResource(R.string.debug_nearby_packets), style = MaterialTheme.typography.titleSmall)
    SelectionContainer {
        Text(
            others.joinToString("\n\n") { pods ->
                "${pods.model?.displayName ?: "0x%04X".format(pods.modelId)} · RSSI ${pods.rssi} dBm\n" +
                    pods.rawByAddress.entries.joinToString("\n") { (address, hex) -> "$address\n$hex" }.ifEmpty { pods.rawHex }
            },
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LogBlock(title: String, lines: List<String>) {
    Text(title, style = MaterialTheme.typography.titleSmall)
    SelectionContainer {
        Text(lines.joinToString("\n"), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun appVersion(): String {
    val context = LocalContext.current
    return runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?"
}
