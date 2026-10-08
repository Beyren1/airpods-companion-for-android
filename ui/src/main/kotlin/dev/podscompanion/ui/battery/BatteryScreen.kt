package dev.podscompanion.ui.battery

import android.content.Intent
import android.os.SystemClock
import android.provider.Settings
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.podscompanion.data.NearbyPods
import dev.podscompanion.data.PodsStatus
import dev.podscompanion.data.aap.AapSessions
import dev.podscompanion.data.aap.AapSessionState
import dev.podscompanion.protocol.aap.AapCommand
import dev.podscompanion.data.displayText
import dev.podscompanion.protocol.advertising.BatteryLevel
import dev.podscompanion.protocol.advertising.Capability
import dev.podscompanion.protocol.advertising.PodState
import dev.podscompanion.protocol.advertising.PodsModel
import dev.podscompanion.ui.R
import dev.podscompanion.ui.permissions.ScanPermissionGate
import dev.podscompanion.ui.theme.PodsCompanionTheme
import kotlinx.coroutines.delay

/** Точка входа экрана: разрешения → ViewModel → отрисовка. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BatteryRoute(showDebug: Boolean) {
    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.screen_title)) }) },
    ) { padding ->
        Box(Modifier.padding(padding)) {
            ScanPermissionGate {
                val viewModel: BatteryViewModel = hiltViewModel()
                val state by viewModel.state.collectAsStateWithLifecycle()
                val refreshing by viewModel.refreshing.collectAsStateWithLifecycle()
                val settings by viewModel.settings.collectAsStateWithLifecycle()
                val logLines by viewModel.autoPauseLines.collectAsStateWithLifecycle()
                val aapSessions by viewModel.aapSessions.collectAsStateWithLifecycle()
                val aapLog by viewModel.aapLog.collectAsStateWithLifecycle()
                BatteryScreen(
                    state, refreshing, viewModel::refresh, showDebug, logLines,
                    aapSessions = aapSessions, aapLog = aapLog, onAapCheck = viewModel::checkAap,
                    onCommand = viewModel::send,
                ) {
                    BackgroundCard(settings, viewModel::setBackgroundEnabled, viewModel::setAutoPause)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BatteryScreen(
    state: BatteryUiState,
    refreshing: Boolean,
    onRefresh: () -> Unit,
    showDebug: Boolean,
    autoPauseLog: List<String> = emptyList(),
    aapSessions: AapSessions = AapSessions(),
    aapLog: List<String> = emptyList(),
    onAapCheck: () -> Unit = {},
    onCommand: (address: String, AapCommand) -> Unit = { _, _ -> },
    footer: @Composable () -> Unit = {},
) {
    // PullToRefreshBox ловит свайп вниз; содержимое должно прокручиваться, иначе жест не дойдёт.
    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = onRefresh,
        modifier = Modifier.fillMaxSize(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when (state) {
                BatteryUiState.Searching -> Searching()
                BatteryUiState.BluetoothOff -> BluetoothOff()
                is BatteryUiState.Error -> Message(Icons.Filled.ErrorOutline, stringResource(R.string.scan_error, state.message))
                is BatteryUiState.Found -> {
                    val main = state.nearby.primary
                    if (main != null) PodsCard(main) else NotConnectedCard()
                    val session = if (main?.connected == true) aapSessions.forModel(main.model) else null
                    if (main?.connected == true) {
                        AapCard(session, aapSessions.noPermission, main.model, onAapCheck, onCommand)
                    }
                    if (session is AapSessionState.Connected) HeadphoneSettingsCard(session, main?.model, onCommand)
                    if (state.nearby.others.isNotEmpty()) OthersCard(state.nearby.others, showDebug)
                    if (showDebug && main != null) DebugCard(main, autoPauseLog, aapLog)
                }
            }
            footer()
            Text(
                stringResource(R.string.pull_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

// ---------- Найденные наушники ----------

@Composable
private fun PodsCard(status: PodsStatus) {
    val model = status.model
    val stereo = model == null || Capability.STEREO_BUDS in model.capabilities
    val hasCase = model == null || Capability.CHARGING_CASE in model.capabilities

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Header(status)
            if (stereo) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    val exact = status.exactBattery
                    BatteryRing(stringResource(R.string.left), status.left.battery, status.left.charging, podNote(status.left), exact = exact)
                    BatteryRing(stringResource(R.string.right), status.right.battery, status.right.charging, podNote(status.right), exact = exact)
                    if (hasCase) {
                        BatteryRing(
                            stringResource(R.string.case_label),
                            status.caseBattery,
                            status.caseCharging,
                            if (status.caseBatteryRemembered) stringResource(R.string.case_remembered) else null,
                            dimmed = status.caseBatteryRemembered,
                            exact = status.aap?.case != null,
                        )
                    }
                }
            } else {
                val pod = status.primary
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    BatteryRing(
                        stringResource(R.string.headphones), pod.battery, pod.charging,
                        podNote(pod, onHead = true), size = 140.dp, exact = status.exactBattery,
                    )
                }
            }
        }
    }
}

@Composable
private fun Header(status: PodsStatus) {
    val now by produceState(SystemClock.elapsedRealtime()) {
        while (true) {
            delay(1_000)
            value = SystemClock.elapsedRealtime()
        }
    }
    val seconds = ((now - status.lastSeenMs) / 1000).coerceAtLeast(0)
    val signal = when {
        status.rssi >= -55 -> R.string.signal_excellent
        status.rssi >= -70 -> R.string.signal_good
        else -> R.string.signal_weak
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Headphones, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        Column(Modifier.padding(start = 16.dp)) {
            Text(
                status.model?.displayName ?: stringResource(R.string.unknown_model, status.modelId),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                stringResource(R.string.updated_ago, seconds, stringResource(signal)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (status.connected) {
                Spacer(Modifier.height(6.dp))
                StatusPill(stringResource(R.string.connected_badge))
            }
        }
    }
}

/** К телефону ничего не подключено: заряд виден только в списке «рядом». */
@Composable
private fun NotConnectedCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Headphones, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                stringResource(R.string.not_connected),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(start = 16.dp),
            )
        }
    }
}

/** Другие наушники рядом (не подключённые): одна строка на пару. */
@Composable
private fun OthersCard(others: List<PodsStatus>, showDebug: Boolean) {
    OutlinedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.nearby_title), style = MaterialTheme.typography.titleSmall)
            others.forEach { pods ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.Headphones, null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                    Text(
                        pods.model?.displayName ?: stringResource(R.string.unknown_model, pods.modelId),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(start = 12.dp).weight(1f),
                    )
                    Text(
                        shortBattery(pods),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (showDebug) {
                    // Сырые пакеты: по ним видно, чьи это наушники и правильно ли разобран заряд.
                    SelectionContainer {
                        Text(
                            "RSSI ${pods.rssi} dBm\n" + pods.rawByAddress.entries
                                .joinToString("\n") { (address, hex) -> "$address\n$hex" }
                                .ifEmpty { pods.rawHex },
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

private fun shortBattery(status: PodsStatus): String {
    val model = status.model
    if (model != null && Capability.STEREO_BUDS !in model.capabilities) {
        return status.primary.battery?.displayText() ?: "—"
    }
    val left = status.left.battery?.displayText() ?: "—"
    val right = status.right.battery?.displayText() ?: "—"
    return "L $left · R $right"
}

@Composable
private fun podNote(pod: PodState, onHead: Boolean = false): String? = when {
    pod.inCase -> stringResource(R.string.in_case)
    !pod.inEar -> null
    onHead -> stringResource(R.string.on_head)
    else -> stringResource(R.string.in_ear)
}

/** Кольцо заряда: дуга по проценту, в центре число, под ним подпись и статус. */
@Composable
private fun BatteryRing(
    label: String,
    battery: BatteryLevel?,
    charging: Boolean,
    note: String?,
    size: Dp = 88.dp,
    dimmed: Boolean = false,
    exact: Boolean = false,
) {
    val percent = battery?.percent
    val color = when {
        percent == null || dimmed -> MaterialTheme.colorScheme.outline
        percent <= 20 -> MaterialTheme.colorScheme.error
        charging -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.primary
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(size)) {
            CircularProgressIndicator(
                progress = { (percent ?: 0) / 100f },
                modifier = Modifier.fillMaxSize(),
                color = color,
                strokeWidth = size / 11,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
                strokeCap = StrokeCap.Round,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (charging) {
                    Icon(
                        Icons.Filled.Bolt,
                        contentDescription = stringResource(R.string.charging),
                        tint = color,
                        modifier = Modifier.size(size / 5),
                    )
                }
                Text(
                    battery?.displayText(exact) ?: "—",
                    style = if (size > 100.dp) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
        Text(label, style = MaterialTheme.typography.labelLarge)
        StatusPill(note)
    }
}

@Composable
private fun StatusPill(text: String?) {
    // Пустая «таблетка» той же высоты, чтобы кольца не прыгали при смене статуса.
    Box(
        Modifier
            .height(24.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (text != null) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text ?: "",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}

@Composable
private fun DebugCard(status: PodsStatus, autoPauseLog: List<String>, aapLog: List<String>) {
    OutlinedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.debug_title), style = MaterialTheme.typography.titleSmall)
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
            if (autoPauseLog.isNotEmpty()) {
                Text(stringResource(R.string.debug_autopause_log), style = MaterialTheme.typography.titleSmall)
                SelectionContainer {
                    Text(
                        autoPauseLog.joinToString("\n"),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
            if (aapLog.isNotEmpty()) {
                Text(stringResource(R.string.debug_aap_log), style = MaterialTheme.typography.titleSmall)
                SelectionContainer {
                    Text(
                        aapLog.joinToString("\n"),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }
    }
}

// ---------- Пустые состояния ----------

@Composable
private fun Searching() {
    val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(
        initialValue = 0.85f,
        targetValue = 1.1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "scale",
    )
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 96.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(
            Modifier
                .size(112.dp)
                .scale(pulse)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.Headphones, null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(48.dp),
            )
        }
        Text(stringResource(R.string.searching_title), style = MaterialTheme.typography.titleLarge)
        Text(
            stringResource(R.string.searching_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun BluetoothOff() {
    val context = LocalContext.current
    Message(Icons.Filled.BluetoothDisabled, stringResource(R.string.bt_off)) {
        Button(onClick = {
            context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }) { Text(stringResource(R.string.bt_open_settings)) }
    }
}

@Composable
private fun Message(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
    action: @Composable () -> Unit = {},
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 96.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(icon, null, modifier = Modifier.size(56.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(4.dp))
        action()
    }
}

// ---------- Превью в Android Studio ----------

private val previewStatus = PodsStatus(
    model = PodsModel.AIRPODS_4_ANC, modelId = 0x1B20,
    left = PodState(BatteryLevel(90), charging = true, inEar = false, inCase = true),
    right = PodState(BatteryLevel(100), charging = false, inEar = true),
    primary = PodState(BatteryLevel(100), charging = false, inEar = true),
    caseBattery = BatteryLevel(50), caseCharging = false,
    lidCounter = 0x11, colorCode = 0, rssi = -52, lastSeenMs = 0,
    rawHex = "07 19 01 1B 20 13 9A AF 11 00 04 …",
)

@Preview(showBackground = true)
@Composable
private fun FoundPreview() {
    PodsCompanionTheme { BatteryScreen(BatteryUiState.Found(NearbyPods(previewStatus.copy(connected = true), listOf(previewStatus.copy(model = PodsModel.AIRPODS_MAX_USB_C, modelId = 0x1F20)))), false, {}, showDebug = true) }
}

@Preview(showBackground = true)
@Composable
private fun MaxPreview() {
    PodsCompanionTheme {
        BatteryScreen(
            BatteryUiState.Found(
                NearbyPods(previewStatus.copy(
                    model = PodsModel.AIRPODS_MAX_USB_C, modelId = 0x1F20,
                    left = PodState(BatteryLevel(70), charging = false, inEar = true),
                    right = PodState(null, charging = false, inEar = false),
                    primary = PodState(BatteryLevel(70), charging = false, inEar = true),
                ), emptyList()),
            ),
            false, {}, showDebug = false,
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun SearchingPreview() {
    PodsCompanionTheme { BatteryScreen(BatteryUiState.Searching, false, {}, showDebug = false) }
}
