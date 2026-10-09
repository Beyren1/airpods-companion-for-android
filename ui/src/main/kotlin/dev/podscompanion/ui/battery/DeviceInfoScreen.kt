package dev.podscompanion.ui.battery

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.podscompanion.data.ConnectedNameMatcher
import dev.podscompanion.data.NearbyPods
import dev.podscompanion.data.PodsStatus
import dev.podscompanion.data.aap.AapSessionState
import dev.podscompanion.data.aap.AapSessions
import dev.podscompanion.data.aap.FailureReason
import dev.podscompanion.data.displayText
import dev.podscompanion.protocol.aap.AapBattery
import dev.podscompanion.protocol.aap.AapDeviceState
import dev.podscompanion.protocol.advertising.Capability
import dev.podscompanion.protocol.advertising.PodsModel
import dev.podscompanion.ui.R

/**
 * Окно «Об устройствах»: всё, что приложение знает о подключённых наушниках и о телефоне.
 * Паспорт наушников (номер модели, серийные номера, прошивку) наушники присылают сами
 * по прямому подключению; без него видно только то, что есть в рекламе: модель и заряд.
 * Значения можно выделить долгим нажатием и скопировать.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceInfoScreen(aapSessions: AapSessions, nearby: NearbyPods?, onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.device_info_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
                },
            )
        },
    ) { padding ->
        SelectionContainer {
            Column(
                Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                HeadphonesSections(aapSessions, nearby)
                PhoneSection(aapSessions)
            }
        }
    }
}

@Composable
private fun HeadphonesSections(aapSessions: AapSessions, nearby: NearbyPods?) {
    val main = nearby?.primary
    if (aapSessions.sessions.isEmpty()) {
        // Прямого подключения нет: показываем наушники из рекламы, если они рядом.
        SectionTitle(stringResource(R.string.headphones))
        SettingsGroup {
            if (main == null) {
                row { InfoRow(stringResource(R.string.not_connected)) }
            } else {
                row { InfoRow(stringResource(R.string.info_model), modelText(main.model, main.modelId, null)) }
                row { InfoRow(stringResource(R.string.info_battery), advertBattery(main)) }
                row { InfoRow(stringResource(R.string.info_more_when_direct)) }
            }
        }
        return
    }
    aapSessions.sessions.forEach { session ->
        // Модель по имени наушников, а если имя своё («Мои уши») — по наушникам рядом.
        val model = ConnectedNameMatcher.modelsForName(session.deviceName).singleOrNull()
            ?: main?.model?.takeIf { aapSessions.forModel(it) == session }
        val status = main?.takeIf { it.model == model && model != null }
        val device = (session as? AapSessionState.Connected)?.device
        val info = device?.info
        val battery = device?.let { aapBattery(it) } ?: status?.let { advertBattery(it) }

        SectionTitle(session.deviceName)
        SettingsGroup {
            row {
                InfoRow(
                    stringResource(R.string.info_model),
                    modelText(model, status?.modelId, info?.modelNumber),
                )
            }
            row { InfoRow(stringResource(R.string.info_connection), connectionText(session)) }
            battery?.let { row { InfoRow(stringResource(R.string.info_battery), it) } }
            info?.firmware?.let { row { InfoRow(stringResource(R.string.info_firmware), it) } }
            info?.serialNumber?.let { row { InfoRow(stringResource(R.string.info_serial), it) } }
            info?.leftSerialNumber?.let { row { InfoRow(stringResource(R.string.info_serial_left), it) } }
            info?.rightSerialNumber?.let { row { InfoRow(stringResource(R.string.info_serial_right), it) } }
            row { InfoRow(stringResource(R.string.info_bt_address), session.address) }
            if (device != null && info == null) row { InfoRow(stringResource(R.string.info_no_passport)) }
        }
    }
}

@Composable
private fun modelText(model: PodsModel?, modelId: Int?, modelNumber: String?): String {
    val name = model?.displayName
        ?: modelId?.let { stringResource(R.string.unknown_model, it) }
        ?: stringResource(R.string.info_unknown)
    return if (modelNumber != null) "$name · $modelNumber" else name
}

@Composable
private fun connectionText(session: AapSessionState): String = when (session) {
    is AapSessionState.Connected -> stringResource(R.string.info_connection_direct)
    is AapSessionState.Connecting -> stringResource(R.string.info_connection_connecting)
    is AapSessionState.Failed -> stringResource(R.string.info_connection_basic)
}

@Composable
private fun aapBattery(device: AapDeviceState): String? {
    fun AapBattery.text() = "${percent}%" + if (charging) " ⚡" else ""
    device.single?.let { return it.text() }
    if (device.left == null && device.right == null) return null
    val pair = stringResource(R.string.battery_pair, device.left?.text() ?: "—", device.right?.text() ?: "—")
    val case = device.case?.let { stringResource(R.string.battery_case_suffix, it.text()) }.orEmpty()
    return pair + case
}

@Composable
private fun advertBattery(status: PodsStatus): String {
    val exact = status.exactBattery
    val model = status.model
    if (model != null && Capability.STEREO_BUDS !in model.capabilities) {
        return status.primary.battery?.displayText(exact) ?: "—"
    }
    val pair = stringResource(
        R.string.battery_pair,
        status.left.battery?.displayText(exact) ?: "—",
        status.right.battery?.displayText(exact) ?: "—",
    )
    val case = status.caseBattery?.let { stringResource(R.string.battery_case_suffix, it.displayText(exact)) }.orEmpty()
    return pair + case
}

@Composable
private fun PhoneSection(aapSessions: AapSessions) {
    val context = LocalContext.current
    val bluetooth = remember { bluetoothFacts(context) }
    val yes = stringResource(R.string.info_yes)
    val no = stringResource(R.string.info_no)

    SectionTitle(stringResource(R.string.info_phone))
    SettingsGroup {
        row { InfoRow(stringResource(R.string.info_phone_model), "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}") }
        row {
            InfoRow(
                stringResource(R.string.info_android),
                stringResource(R.string.info_android_value, Build.VERSION.RELEASE, Build.VERSION.SDK_INT),
            )
        }
        Build.VERSION.SECURITY_PATCH.takeIf { it.isNotEmpty() }?.let { patch ->
            row { InfoRow(stringResource(R.string.info_security_patch), patch) }
        }
        bluetooth.name?.let { name -> row { InfoRow(stringResource(R.string.info_bt_name), name) } }
        row {
            InfoRow(
                stringResource(R.string.info_bt_state),
                stringResource(if (bluetooth.enabled) R.string.info_bt_on else R.string.info_bt_off),
            )
        }
        row {
            InfoRow(
                stringResource(R.string.info_bt_features),
                listOf(
                    stringResource(R.string.info_bt_2m_phy) to bluetooth.le2mPhy,
                    stringResource(R.string.info_bt_coded_phy) to bluetooth.leCodedPhy,
                    stringResource(R.string.info_bt_ext_adv) to bluetooth.extendedAdvertising,
                    stringResource(R.string.info_bt_le_audio) to bluetooth.leAudio,
                ).joinToString("\n") { (name, supported) -> "$name: ${if (supported) yes else no}" },
            )
        }
        row { InfoRow(stringResource(R.string.info_direct_mode), directModeText(aapSessions)) }
        row { InfoRow(stringResource(R.string.about_title), stringResource(R.string.about_version, appVersion())) }
    }
}

/** Работает ли прямое подключение на этом телефоне: по текущим попыткам подключиться. */
@Composable
private fun directModeText(aapSessions: AapSessions): String = stringResource(
    when {
        aapSessions.sessions.any { it is AapSessionState.Connected } -> R.string.info_direct_works
        aapSessions.sessions.any { it is AapSessionState.Failed && it.reason == FailureReason.SOCKET_BLOCKED } ->
            R.string.info_direct_blocked
        else -> R.string.info_direct_unknown
    },
)

private class BluetoothFacts(
    val name: String?,
    val enabled: Boolean,
    val le2mPhy: Boolean,
    val leCodedPhy: Boolean,
    val extendedAdvertising: Boolean,
    val leAudio: Boolean,
)

@SuppressLint("MissingPermission") // имя адаптера: разрешение «Устройства поблизости» выдано на первом экране
private fun bluetoothFacts(context: Context): BluetoothFacts {
    val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
    return BluetoothFacts(
        name = runCatching { adapter?.name }.getOrNull()?.takeIf { it.isNotBlank() },
        enabled = adapter?.isEnabled == true,
        le2mPhy = adapter?.isLe2MPhySupported == true,
        leCodedPhy = adapter?.isLeCodedPhySupported == true,
        extendedAdvertising = adapter?.isLeExtendedAdvertisingSupported == true,
        leAudio = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            runCatching { adapter?.isLeAudioSupported == android.bluetooth.BluetoothStatusCodes.FEATURE_SUPPORTED }.getOrDefault(false),
    )
}
