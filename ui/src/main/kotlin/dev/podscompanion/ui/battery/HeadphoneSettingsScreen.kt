package dev.podscompanion.ui.battery

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.podscompanion.data.aap.AapSessionState
import dev.podscompanion.protocol.aap.AapCommand
import dev.podscompanion.protocol.aap.AapToggle
import dev.podscompanion.protocol.aap.ListeningMode
import dev.podscompanion.protocol.aap.MicMode
import dev.podscompanion.protocol.aap.PressAction
import dev.podscompanion.protocol.aap.adaptiveStrength
import dev.podscompanion.protocol.aap.micMode
import dev.podscompanion.protocol.aap.modeCycle
import dev.podscompanion.protocol.aap.pressAndHold
import dev.podscompanion.protocol.aap.toggle
import dev.podscompanion.protocol.advertising.PodsModel
import dev.podscompanion.ui.R

/**
 * Настройки наушников по прямому подключению, сгруппированные по смыслу. Показываем только то,
 * о чём наушники сами сообщили после подключения: у Max не будет жестов и микрофона.
 * Адаптация к разговору живёт на главном экране, рядом с режимами.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HeadphoneSettingsScreen(
    session: AapSessionState?,
    model: PodsModel?,
    onCommand: (address: String, AapCommand) -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
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
            if (session is AapSessionState.Connected) {
                SettingsContent(session, model) { onCommand(session.address, it) }
            } else {
                Text(
                    stringResource(R.string.settings_need_direct),
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 48.dp),
                )
            }
        }
    }
}

@Composable
private fun SettingsContent(session: AapSessionState.Connected, model: PodsModel?, send: (AapCommand) -> Unit) {
    val device = session.device
    val personalized = device.toggle(AapToggle.PERSONALIZED_VOLUME)
    val strength = device.adaptiveStrength
    val hold = device.pressAndHold
    val cycle = device.modeCycle
    val mic = device.micMode
    val ear = device.toggle(AapToggle.EAR_DETECTION)

    if (personalized != null || strength != null) {
        SectionTitle(stringResource(R.string.section_sound))
        SettingsGroup {
            if (personalized != null) row {
                SwitchRow(
                    stringResource(R.string.setting_personalized_volume), personalized,
                    { on -> send(AapCommand.SetToggle(AapToggle.PERSONALIZED_VOLUME, on)) },
                    description = stringResource(R.string.setting_personalized_volume_hint),
                )
            }
            if (strength != null) row { StrengthSlider(strength) { value -> send(AapCommand.SetAdaptiveStrength(value)) } }
        }
    }

    if (hold != null || cycle != null) {
        SectionTitle(stringResource(R.string.setting_press_hold))
        SettingsGroup {
            if (hold != null) row {
                BlockRow {
                    val actions = listOf(PressAction.NOISE_CONTROL, PressAction.VOICE_ASSISTANT)
                    val right = hold.right ?: PressAction.NOISE_CONTROL
                    val left = hold.left ?: PressAction.NOISE_CONTROL
                    SideLabel(R.string.side_left)
                    Choice(actions, hold.left, { a -> stringResource(a.title()) }) { a -> send(AapCommand.SetPressAndHold(right = right, left = a)) }
                    SideLabel(R.string.side_right)
                    Choice(actions, hold.right, { a -> stringResource(a.title()) }) { a -> send(AapCommand.SetPressAndHold(right = a, left = left)) }
                }
            }
            if (cycle != null) row {
                BlockRow {
                    Text(stringResource(R.string.setting_mode_cycle), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        stringResource(R.string.setting_mode_cycle_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    ModeCycle(availableModes(model).ifEmpty { ListeningMode.entries - ListeningMode.UNKNOWN }, cycle) { modes ->
                        send(AapCommand.SetModeCycle(modes))
                    }
                }
            }
        }
    }

    if (mic != null || ear != null) {
        SectionTitle(stringResource(R.string.section_other))
        SettingsGroup {
            if (mic != null) row {
                BlockRow {
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Icon(Icons.Filled.Mic, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(stringResource(R.string.setting_mic), style = MaterialTheme.typography.bodyLarge)
                    }
                    Choice(listOf(MicMode.AUTO, MicMode.ALWAYS_LEFT, MicMode.ALWAYS_RIGHT), mic, { m -> stringResource(m.title()) }) { m ->
                        send(AapCommand.SetMicMode(m))
                    }
                }
            }
            if (ear != null) row {
                SwitchRow(
                    stringResource(R.string.setting_ear_detection), ear,
                    { on -> send(AapCommand.SetToggle(AapToggle.EAR_DETECTION, on)) },
                    icon = Icons.Filled.Hearing,
                )
            }
        }
    }

    if (personalized == null && strength == null && hold == null && cycle == null && mic == null && ear == null) {
        Text(
            stringResource(R.string.settings_none),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 48.dp),
        )
    }

    Text(
        stringResource(R.string.settings_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 8.dp),
    )
}

@Composable
private fun SideLabel(text: Int) {
    Text(stringResource(text), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** Режимы, которые перебирает долгое нажатие. Меньше двух выбрать нельзя: перебирать будет нечего. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ModeCycle(modes: List<ListeningMode>, selected: Set<ListeningMode>, onChange: (Set<ListeningMode>) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        modes.forEach { mode ->
            val on = mode in selected
            FilterChip(
                selected = on,
                onClick = {
                    val next = if (on) selected - mode else selected + mode
                    if (next.size >= 2) onChange(next)
                },
                label = { Text(stringResource(mode.title())) },
            )
        }
    }
}

/** Ползунок шлёт команду, когда палец отпущен, а не на каждый шаг. Проверено на Pro 2: 0 — больше звуков вокруг. */
@Composable
private fun StrengthSlider(value: Int, onChange: (Int) -> Unit) {
    var position by remember(value) { mutableFloatStateOf(value.toFloat()) }
    BlockRow {
        Text(stringResource(R.string.setting_adaptive_strength), style = MaterialTheme.typography.bodyLarge)
        Slider(
            value = position,
            onValueChange = { position = it },
            onValueChangeFinished = { onChange(position.toInt()) },
            valueRange = 0f..100f,
        )
        Row(Modifier.fillMaxWidth()) {
            Text(
                stringResource(R.string.setting_adaptive_more),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Text(
                stringResource(R.string.setting_adaptive_less),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun MicMode.title() = when (this) {
    MicMode.AUTO -> R.string.mic_auto
    MicMode.ALWAYS_LEFT -> R.string.mic_left
    MicMode.ALWAYS_RIGHT -> R.string.mic_right
}

private fun PressAction.title() = when (this) {
    PressAction.NOISE_CONTROL -> R.string.press_noise_control
    PressAction.VOICE_ASSISTANT -> R.string.press_assistant
}
