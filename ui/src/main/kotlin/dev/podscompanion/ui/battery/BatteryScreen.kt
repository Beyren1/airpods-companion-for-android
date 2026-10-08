package dev.podscompanion.ui.battery

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.podscompanion.data.PodsStatus
import dev.podscompanion.protocol.advertising.BatteryLevel
import dev.podscompanion.protocol.advertising.Capability
import dev.podscompanion.protocol.advertising.PodState
import dev.podscompanion.protocol.advertising.PodsModel
import dev.podscompanion.ui.R
import dev.podscompanion.ui.permissions.ScanPermissionGate
import dev.podscompanion.ui.theme.PodsCompanionTheme

/** Точка входа экрана: разрешения → ViewModel → отрисовка. */
@Composable
fun BatteryRoute(showDebug: Boolean) {
    Scaffold { padding ->
        Column(Modifier.padding(padding)) {
            ScanPermissionGate {
                val viewModel: BatteryViewModel = hiltViewModel()
                val state by viewModel.state.collectAsStateWithLifecycle()
                BatteryScreen(state, showDebug)
            }
        }
    }
}

@Composable
fun BatteryScreen(state: BatteryUiState, showDebug: Boolean) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        when (state) {
            BatteryUiState.Searching -> Searching()
            BatteryUiState.BluetoothOff -> Text(stringResource(R.string.bt_off))
            is BatteryUiState.Error -> Text(stringResource(R.string.scan_error, state.message))
            is BatteryUiState.Found -> PodsCard(state.status, showDebug)
        }
    }
}

@Composable
private fun Searching() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(24.dp))
        Text(stringResource(R.string.searching_hint), Modifier.padding(start = 16.dp))
    }
}

@Composable
private fun PodsCard(status: PodsStatus, showDebug: Boolean) {
    val model = status.model
    val stereo = model == null || Capability.STEREO_BUDS in model.capabilities
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                model?.displayName ?: stringResource(R.string.unknown_model, status.modelId),
                style = MaterialTheme.typography.titleLarge,
            )
            if (stereo) {
                PodRow(stringResource(R.string.left), status.left)
                PodRow(stringResource(R.string.right), status.right)
            } else {
                // У Max в пакете одно значение; где именно оно лежит, уточним по дампу.
                PodRow(stringResource(R.string.headphones), status.left.takeIf { it.battery != null } ?: status.right)
            }
            if (model == null || Capability.CHARGING_CASE in model.capabilities) {
                BatteryRow(stringResource(R.string.case_label), status.caseBattery, status.caseCharging, null)
            }
            if (showDebug) {
                Text(
                    "RSSI ${status.rssi} dBm · lid=${status.lidCounter} · color=0x%02X".format(status.colorCode),
                    style = MaterialTheme.typography.labelSmall,
                )
                Text(status.rawHex, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

@Composable
private fun PodRow(label: String, pod: PodState) {
    val ear = if (pod.inEar) stringResource(R.string.in_ear) else null
    BatteryRow(label, pod.battery, pod.charging, ear)
}

@Composable
private fun BatteryRow(label: String, battery: BatteryLevel?, charging: Boolean, note: String?) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            if (charging) Icon(Icons.Filled.Bolt, contentDescription = stringResource(R.string.charging))
            Text(battery?.let { "${it.percent} %" } ?: "—", style = MaterialTheme.typography.titleMedium)
        }
        LinearProgressIndicator(
            progress = { (battery?.percent ?: 0) / 100f },
            modifier = Modifier.fillMaxWidth(),
        )
        if (note != null) Text(note, style = MaterialTheme.typography.bodySmall)
    }
}

@Preview(showBackground = true)
@Composable
private fun PodsCardPreview() {
    PodsCompanionTheme {
        BatteryScreen(
            BatteryUiState.Found(
                PodsStatus(
                    model = PodsModel.AIRPODS_4_ANC, modelId = 0x1B20,
                    left = PodState(BatteryLevel(80), charging = false, inEar = true),
                    right = PodState(BatteryLevel(90), charging = true, inEar = false),
                    caseBattery = BatteryLevel(50), caseCharging = false,
                    lidCounter = 0x31, colorCode = 0, rssi = -52, lastSeenMs = 0,
                    rawHex = "07 19 01 1B 20 22 98 05 31 00 00 …",
                ),
            ),
            showDebug = true,
        )
    }
}
