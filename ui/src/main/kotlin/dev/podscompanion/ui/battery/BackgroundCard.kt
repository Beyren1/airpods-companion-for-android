package dev.podscompanion.ui.battery

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.podscompanion.data.settings.AppSettings
import dev.podscompanion.ui.R

/**
 * Настройки фонового режима. При включении просим два необязательных разрешения:
 * уведомления (иначе заряд в шторке не виден) и «подключение к устройствам поблизости»
 * (чтобы сервис просыпался, когда наушники подключаются). Отказ не мешает включить режим.
 */
@Composable
fun BackgroundCard(
    settings: AppSettings,
    onBackgroundChange: (Boolean) -> Unit,
    onAutoPauseChange: (Boolean) -> Unit,
) {
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { onBackgroundChange(true) }

    val optionalPermissions = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(Manifest.permission.BLUETOOTH_CONNECT)
    }.toTypedArray()

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            SettingRow(
                title = stringResource(R.string.bg_title),
                description = stringResource(R.string.bg_description),
                checked = settings.backgroundEnabled,
                onCheckedChange = { enable ->
                    if (enable && optionalPermissions.isNotEmpty()) launcher.launch(optionalPermissions)
                    else onBackgroundChange(enable)
                },
            )
            SettingRow(
                title = stringResource(R.string.autopause_title),
                description = stringResource(R.string.autopause_description),
                checked = settings.autoPause,
                enabled = settings.backgroundEnabled,
                onCheckedChange = onAutoPauseChange,
            )
        }
    }
}

@Composable
private fun SettingRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}
