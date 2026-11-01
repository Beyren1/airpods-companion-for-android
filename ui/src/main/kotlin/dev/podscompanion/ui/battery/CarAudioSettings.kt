package dev.podscompanion.ui.battery

import android.annotation.SuppressLint
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.Build
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import dev.podscompanion.bluetooth.route.looksLikeCar
import dev.podscompanion.data.ConnectedNameMatcher
import dev.podscompanion.data.settings.AppSettings
import dev.podscompanion.protocol.aap.Aap
import dev.podscompanion.ui.R

/** Сопряжённое устройство, которое можно отметить как машину. */
data class CarCandidate(val address: String, val name: String, val looksLikeCar: Boolean)

/**
 * Раздел «Машина»: переключатель «Звук в машину» и список устройств, которые считать машиной.
 * Магнитолы обычно сами сообщают, что они машина (класс Bluetooth), такие отмечены сразу;
 * остальные пользователь отмечает сам.
 */
@Composable
internal fun CarAudioGroup(
    settings: AppSettings,
    onCarAudioChange: (Boolean) -> Unit,
    onCarDeviceChange: (CarCandidate, Boolean) -> Unit,
) {
    val context = LocalContext.current
    var pick by rememberSaveable { mutableStateOf(false) }
    // Список читаем заново при каждом открытии окна: могли сопрячь новую машину.
    val candidates = remember(pick) { carCandidates(context) }
    val chosen = candidates.filter { settings.isCar(it.address, it.looksLikeCar) }

    SectionTitle(stringResource(R.string.section_car))
    SettingsGroup {
        row {
            SwitchRow(
                stringResource(R.string.car_audio_title), settings.carAudio, onCarAudioChange,
                icon = Icons.Filled.DirectionsCar,
                description = stringResource(R.string.car_audio_description),
                enabled = settings.backgroundEnabled,
            )
        }
        if (settings.carAudio) {
            row {
                NavRow(
                    stringResource(R.string.car_devices_title), { pick = true },
                    icon = Icons.Filled.Checklist,
                    description = if (chosen.isEmpty()) {
                        stringResource(R.string.car_devices_none)
                    } else {
                        chosen.joinToString { it.name }
                    },
                )
            }
        }
    }

    if (!pick) return
    AlertDialog(
        onDismissRequest = { pick = false },
        title = { Text(stringResource(R.string.car_devices_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    stringResource(R.string.car_devices_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                if (candidates.isEmpty()) {
                    Text(stringResource(R.string.car_devices_empty), style = MaterialTheme.typography.bodyLarge)
                }
                candidates.forEach { device ->
                    val checked = settings.isCar(device.address, device.looksLikeCar)
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .toggleable(checked, role = Role.Checkbox) { onCarDeviceChange(device, it) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = checked, onCheckedChange = null)
                        Text(device.name, Modifier.padding(start = 16.dp), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { pick = false }) { Text(stringResource(R.string.done)) }
        },
    )
}

/** Сопряжённые устройства, кроме наушников Apple: похожие на машину — первыми. */
@SuppressLint("MissingPermission") // BLUETOOTH_CONNECT выдано на первом экране
private fun carCandidates(context: Context): List<CarCandidate> = runCatching {
    val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return emptyList()
    adapter.bondedDevices.orEmpty()
        .filter { device ->
            val name = device.name.orEmpty()
            device.uuids.orEmpty().none { it.uuid.toString().equals(Aap.SERVICE_UUID, ignoreCase = true) } &&
                ConnectedNameMatcher.knownModels(listOf(name)).isEmpty() &&
                device.bluetoothClass?.majorDeviceClass == BluetoothClass.Device.Major.AUDIO_VIDEO
        }
        .map { device ->
            val name = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) device.alias else null) ?: device.name ?: device.address
            CarCandidate(device.address, name, device.looksLikeCar())
        }
        .sortedWith(compareByDescending<CarCandidate> { it.looksLikeCar }.thenBy { it.name.lowercase() })
}.getOrDefault(emptyList())
