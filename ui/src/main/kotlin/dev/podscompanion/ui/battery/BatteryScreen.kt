package dev.podscompanion.ui.battery

import android.content.Intent
import android.os.SystemClock
import android.provider.Settings
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.podscompanion.data.NearbyPods
import dev.podscompanion.data.PodsStatus
import dev.podscompanion.data.aap.AapSessionState
import dev.podscompanion.data.aap.AapSessions
import dev.podscompanion.data.displayText
import dev.podscompanion.protocol.aap.AapCommand
import dev.podscompanion.protocol.aap.AapToggle
import dev.podscompanion.protocol.aap.toggle
import dev.podscompanion.protocol.advertising.BatteryLevel
import dev.podscompanion.protocol.advertising.Capability
import dev.podscompanion.protocol.advertising.PodState
import dev.podscompanion.protocol.advertising.PodsModel
import dev.podscompanion.ui.R
import dev.podscompanion.ui.theme.PodsCompanionTheme
import kotlinx.coroutines.delay

/**
 * Главный экран: заряд подключённых наушников, режим шумоподавления, адаптация к разговору,
 * вход в настройки наушников и список «Рядом». Настройки приложения — по шестерёнке.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    state: BatteryUiState,
    refreshing: Boolean,
    onRefresh: () -> Unit,
    aapSessions: AapSessions = AapSessions(),
    onAapCheck: () -> Unit = {},
    onCommand: (address: String, AapCommand) -> Unit = { _, _ -> },
    onOpenHeadphoneSettings: () -> Unit = {},
    onOpenAppSettings: () -> Unit = {},
) {
    val main = (state as? BatteryUiState.Found)?.nearby?.primary
    val session = if (main?.connected == true) aapSessions.forModel(main.model) else null

    Scaffold(
        topBar = {
            TopAppBar(
                title = { TitleBlock(main, session) },
                actions = {
                    IconButton(onClick = onOpenAppSettings) {
                        Icon(Icons.Filled.Settings, stringResource(R.string.app_settings_title))
                    }
                },
            )
        },
    ) { padding ->
        // PullToRefreshBox ловит свайп вниз; содержимое должно прокручиваться, иначе жест не дойдёт.
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = onRefresh,
            modifier = Modifier.padding(padding).fillMaxSize(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                when (state) {
                    BatteryUiState.Searching -> Searching()
                    BatteryUiState.BluetoothOff -> BluetoothOff()
                    is BatteryUiState.Error -> Message(Icons.Filled.ErrorOutline, stringResource(R.string.scan_error, state.message))
                    is BatteryUiState.Found -> {
                        if (main != null) HeroCard(main) else NotConnectedCard()
                        if (main?.connected == true) AapStatusCard(session, aapSessions.noPermission, onAapCheck)
                        if (session is AapSessionState.Connected) {
                            Controls(session, main?.model, onCommand, onOpenHeadphoneSettings)
                        }
                        if (state.nearby.others.isNotEmpty()) Nearby(state.nearby.others)
                    }
                }
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
}

/** Заголовок: имя наушников и как они подключены (зелёная точка — напрямую). */
@Composable
private fun TitleBlock(main: PodsStatus?, session: AapSessionState?) {
    Column {
        Text(
            main?.let { modelName(it) } ?: stringResource(R.string.screen_title),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (main != null) {
            val direct = session is AapSessionState.Connected
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (main.connected) {
                    Box(
                        Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(if (direct) StatusGreen else MaterialTheme.colorScheme.outline),
                    )
                }
                Text(
                    stringResource(
                        when {
                            direct -> R.string.status_direct
                            main.connected -> R.string.status_connected
                            else -> R.string.status_nearest
                        },
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Зелёный «подключено» один для светлой и тёмной темы: семантический цвет, не из обоев. */
private val StatusGreen = Color(0xFF4CAF7A)

// ---------- Заряд ----------

@Composable
private fun HeroCard(status: PodsStatus) {
    val model = status.model
    val stereo = model == null || Capability.STEREO_BUDS in model.capabilities
    val hasCase = model == null || Capability.CHARGING_CASE in model.capabilities
    val exact = status.exactBattery

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(vertical = 18.dp, horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (stereo) {
                Row(Modifier.fillMaxWidth()) {
                    Part(
                        PodsArt.budFor(model, left = true), stringResource(R.string.left), status.left.battery, status.left.charging,
                        podNote(status.left), highlighted = status.left.inEar, exact = exact, modifier = Modifier.weight(1f),
                    )
                    if (hasCase) {
                        Part(
                            PodsArt.caseFor(model), stringResource(R.string.case_label), status.caseBattery, status.caseCharging,
                            caseNote(status), highlighted = false, exact = status.aap?.case != null,
                            dimmed = status.caseBatteryRemembered, modifier = Modifier.weight(1f),
                        )
                    }
                    Part(
                        PodsArt.budFor(model, left = false), stringResource(R.string.right), status.right.battery, status.right.charging,
                        podNote(status.right), highlighted = status.right.inEar, exact = exact, modifier = Modifier.weight(1f),
                    )
                }
            } else {
                val pod = status.primary
                Part(
                    PodsArt.OverEarArt, stringResource(R.string.headphones), pod.battery, pod.charging,
                    if (pod.inEar) stringResource(R.string.on_head) else stringResource(R.string.off_head),
                    highlighted = pod.inEar, exact = exact, ringSize = 132.dp, modifier = Modifier.fillMaxWidth(),
                )
            }
            Freshness(status)
        }
    }
}

/** Одна часть: кольцо заряда вокруг рисунка, процент, название и где она сейчас. */
@Composable
internal fun Part(
    art: PodsArt.Art,
    label: String,
    battery: BatteryLevel?,
    charging: Boolean,
    note: String?,
    highlighted: Boolean,
    exact: Boolean,
    modifier: Modifier = Modifier,
    dimmed: Boolean = false,
    ringSize: Dp = 92.dp,
) {
    val percent = battery?.percent
    val colors = MaterialTheme.colorScheme
    val ringColor = when {
        percent == null || dimmed -> colors.outline
        percent <= 20 -> colors.error
        charging -> colors.tertiary
        else -> colors.primary
    }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(ringSize), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(
                progress = { (percent ?: 0) / 100f },
                modifier = Modifier.fillMaxSize(),
                color = ringColor,
                strokeWidth = ringSize / 15,
                trackColor = colors.surfaceContainerHighest,
                strokeCap = StrokeCap.Round,
            )
            // Все части в одном масштабе по реальным размерам: кейс Pro заметно шире наушника.
            val dpPerMm = ringSize.value / 92f * 0.95f
            Image(
                painterResource(art.image), null,
                alpha = if (highlighted || !dimmed) 1f else 0.5f,
                modifier = Modifier.size((art.widthMm * dpPerMm).dp, (art.heightMm * dpPerMm).dp),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                battery?.displayText(exact) ?: "—",
                style = if (ringSize > 100.dp) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            if (charging) {
                Icon(Icons.Filled.Bolt, stringResource(R.string.charging), tint = colors.tertiary, modifier = Modifier.size(18.dp))
            }
        }
        Text(label, style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant)
        StatusChip(note, highlighted)
    }
}

@Composable
private fun StatusChip(text: String?, highlighted: Boolean) {
    // Пустая «таблетка» той же высоты, чтобы части не прыгали при смене статуса.
    val colors = MaterialTheme.colorScheme
    Box(
        Modifier
            .height(24.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(
                when {
                    text == null -> Color.Transparent
                    highlighted -> colors.primaryContainer
                    else -> colors.surfaceContainerHighest
                },
            )
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text ?: "",
            style = MaterialTheme.typography.labelMedium,
            color = if (highlighted) colors.onPrimaryContainer else colors.onSurfaceVariant,
        )
    }
}

/** Под кольцами: точный ли заряд и насколько он свежий. */
@Composable
private fun Freshness(status: PodsStatus) {
    val now by produceState(SystemClock.elapsedRealtime()) {
        while (true) {
            delay(1_000)
            value = SystemClock.elapsedRealtime()
        }
    }
    val seconds = ((now - status.lastSeenMs) / 1000).coerceAtLeast(0)
    val text = if (status.exactBattery) {
        stringResource(R.string.battery_exact)
    } else {
        stringResource(R.string.battery_approx, seconds)
    }
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun podNote(pod: PodState): String? = when {
    pod.inEar -> stringResource(R.string.in_ear)
    pod.inCase -> stringResource(R.string.in_case)
    pod.battery != null -> stringResource(R.string.out_of_ear)
    else -> null
}

@Composable
private fun caseNote(status: PodsStatus): String? = when {
    status.caseBatteryRemembered -> stringResource(R.string.case_remembered)
    status.caseCharging -> stringResource(R.string.charging_short)
    else -> null
}

// ---------- Управление ----------

@Composable
private fun Controls(
    session: AapSessionState.Connected,
    model: PodsModel?,
    onCommand: (address: String, AapCommand) -> Unit,
    onOpenHeadphoneSettings: () -> Unit,
) {
    val modes = availableModes(model)
    if (modes.isNotEmpty()) {
        SectionTitle(stringResource(R.string.section_noise))
        ModeTiles(modes, session.device.listeningMode) { onCommand(session.address, AapCommand.SetListeningMode(it)) }
    }
    val ca = session.device.toggle(AapToggle.CONVERSATIONAL_AWARENESS)
    SettingsGroup {
        if (ca != null) row {
            SwitchRow(
                stringResource(R.string.setting_ca), ca,
                { onCommand(session.address, AapCommand.SetToggle(AapToggle.CONVERSATIONAL_AWARENESS, it)) },
                icon = Icons.Filled.RecordVoiceOver,
                description = stringResource(R.string.setting_ca_hint),
            )
        }
        row {
            NavRow(
                stringResource(R.string.settings_title), onOpenHeadphoneSettings,
                icon = Icons.Filled.Tune,
                description = stringResource(R.string.settings_row_hint),
            )
        }
    }
}

// ---------- Рядом ----------

/** Другие наушники рядом (не подключённые): одна строка на пару. */
@Composable
private fun Nearby(others: List<PodsStatus>) {
    SectionTitle(stringResource(R.string.nearby_title))
    SettingsGroup {
        others.forEach { pods ->
            row {
                val stereo = pods.model?.let { Capability.STEREO_BUDS in it.capabilities } ?: true
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHighest, modifier = Modifier.size(40.dp)) {
                        Box(contentAlignment = Alignment.Center) {
                            Image(
                                painterResource(if (stereo) PodsArt.caseFor(pods.model).image else PodsArt.OverEarArt.image), null,
                                modifier = Modifier.size(26.dp),
                            )
                        }
                    }
                    Column(Modifier.weight(1f)) {
                        Text(modelName(pods), style = MaterialTheme.typography.bodyLarge)
                        Text(
                            shortBattery(pods),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun modelName(status: PodsStatus): String =
    status.model?.displayName ?: stringResource(R.string.unknown_model, status.modelId)

private fun shortBattery(status: PodsStatus): String {
    val model = status.model
    if (model != null && Capability.STEREO_BUDS !in model.capabilities) {
        return status.primary.battery?.displayText() ?: "—"
    }
    val left = status.left.battery?.displayText() ?: "—"
    val right = status.right.battery?.displayText() ?: "—"
    val case = status.caseBattery?.displayText()
    return "L $left · R $right" + (case?.let { " · кейс $it" } ?: "")
}

// ---------- Пустые состояния ----------

/** К телефону ничего не подключено: заряд виден только в списке «рядом». */
@Composable
private fun NotConnectedCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(PodsArt.Case, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(28.dp))
            Text(
                stringResource(R.string.not_connected),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(start = 16.dp),
            )
        }
    }
}

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
                PodsArt.Case, null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(52.dp),
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
private fun Message(icon: ImageVector, text: String, action: @Composable () -> Unit = {}) {
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
    model = PodsModel.AIRPODS_PRO_2_USB_C, modelId = 0x2420,
    left = PodState(BatteryLevel(90), charging = false, inEar = false),
    right = PodState(BatteryLevel(100), charging = false, inEar = true),
    primary = PodState(BatteryLevel(100), charging = false, inEar = true),
    caseBattery = BatteryLevel(50), caseCharging = true,
    lidCounter = 0x11, colorCode = 0, rssi = -52, lastSeenMs = 0,
    rawHex = "07 19 01 24 20 13 9A AF 11 00 04 …",
    connected = true,
)

@Preview(showBackground = true)
@Composable
private fun HomePreview() {
    PodsCompanionTheme {
        HomeScreen(
            BatteryUiState.Found(
                NearbyPods(previewStatus, listOf(previewStatus.copy(model = PodsModel.AIRPODS_MAX_USB_C, modelId = 0x1F20, connected = false))),
            ),
            refreshing = false,
            onRefresh = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun MaxPreview() {
    PodsCompanionTheme {
        HomeScreen(
            BatteryUiState.Found(
                NearbyPods(
                    previewStatus.copy(
                        model = PodsModel.AIRPODS_MAX_USB_C, modelId = 0x1F20,
                        left = PodState(BatteryLevel(70), charging = false, inEar = true),
                        right = PodState(null, charging = false, inEar = false),
                        primary = PodState(BatteryLevel(70), charging = false, inEar = true),
                    ),
                    emptyList(),
                ),
            ),
            refreshing = false,
            onRefresh = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun SearchingPreview() {
    PodsCompanionTheme { HomeScreen(BatteryUiState.Searching, refreshing = false, onRefresh = {}) }
}
