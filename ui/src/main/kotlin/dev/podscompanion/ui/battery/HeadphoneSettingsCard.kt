package dev.podscompanion.ui.battery

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
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
 * Настройки наушников по прямому подключению. Показываем только те, о которых наушники сами
 * сообщили после подключения: так у Max не появятся жесты и микрофон, а у старых моделей —
 * то, чего их прошивка не умеет. Значение на экране меняется, когда наушники подтвердят команду.
 */
@Composable
fun HeadphoneSettingsCard(
    session: AapSessionState.Connected,
    model: PodsModel?,
    onCommand: (address: String, AapCommand) -> Unit,
) {
    val device = session.device
    val send = { command: AapCommand -> onCommand(session.address, command) }

    val toggles = listOf(
        AapToggle.CONVERSATIONAL_AWARENESS to R.string.setting_ca,
        AapToggle.PERSONALIZED_VOLUME to R.string.setting_personalized_volume,
        AapToggle.EAR_DETECTION to R.string.setting_ear_detection,
        AapToggle.ONE_BUD_NOISE_CONTROL to R.string.setting_one_bud_nc,
    ).mapNotNull { (toggle, title) -> device.toggle(toggle)?.let { Triple(toggle, title, it) } }
    val mic = device.micMode
    val hold = device.pressAndHold
    val cycle = device.modeCycle
    val strength = device.adaptiveStrength
    if (toggles.isEmpty() && mic == null && hold == null && cycle == null && strength == null) return

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.titleMedium)

            toggles.forEach { (toggle, title, enabled) ->
                SwitchRow(stringResource(title), enabled) { send(AapCommand.SetToggle(toggle, it)) }
            }

            if (strength != null) {
                HorizontalDivider()
                StrengthSlider(strength) { send(AapCommand.SetAdaptiveStrength(it)) }
            }

            if (mic != null) {
                HorizontalDivider()
                Text(stringResource(R.string.setting_mic), style = MaterialTheme.typography.bodyLarge)
                Choice(
                    options = listOf(MicMode.AUTO, MicMode.ALWAYS_LEFT, MicMode.ALWAYS_RIGHT),
                    selected = mic,
                    label = { stringResource(it.title()) },
                ) { send(AapCommand.SetMicMode(it)) }
            }

            if (hold != null) {
                HorizontalDivider()
                Text(stringResource(R.string.setting_press_hold), style = MaterialTheme.typography.bodyLarge)
                val actions = listOf(PressAction.NOISE_CONTROL, PressAction.VOICE_ASSISTANT)
                val right = hold.right ?: PressAction.NOISE_CONTROL
                val left = hold.left ?: PressAction.NOISE_CONTROL
                SideChoice(R.string.side_left, actions, hold.left) { send(AapCommand.SetPressAndHold(right = right, left = it)) }
                SideChoice(R.string.side_right, actions, hold.right) { send(AapCommand.SetPressAndHold(right = it, left = left)) }
            }

            if (cycle != null) {
                HorizontalDivider()
                Text(stringResource(R.string.setting_mode_cycle), style = MaterialTheme.typography.bodyLarge)
                Text(
                    stringResource(R.string.setting_mode_cycle_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ModeCycle(availableModes(model).ifEmpty { ListeningMode.entries - ListeningMode.UNKNOWN }, cycle) {
                    send(AapCommand.SetModeCycle(it))
                }
            }

            Text(
                stringResource(R.string.settings_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SwitchRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** Ряд кнопок с одним выбранным вариантом. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> Choice(options: List<T>, selected: T?, label: @Composable (T) -> String, onSelect: (T) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, option ->
            SegmentedButton(
                selected = option == selected,
                onClick = { if (option != selected) onSelect(option) },
                shape = SegmentedButtonDefaults.itemShape(index, options.size),
                icon = {},
            ) {
                Text(label(option), maxLines = 1, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun SideChoice(side: Int, actions: List<PressAction>, selected: PressAction?, onSelect: (PressAction) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(side), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Choice(actions, selected, { stringResource(it.title()) }, onSelect)
    }
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
                label = { Text(stringResource(mode.shortTitle()), style = MaterialTheme.typography.labelMedium) },
            )
        }
    }
}

/** Ползунок шлёт команду, когда палец отпущен, а не на каждый шаг. */
@Composable
private fun StrengthSlider(value: Int, onChange: (Int) -> Unit) {
    var position by remember(value) { mutableFloatStateOf(value.toFloat()) }
    Column {
        Text(stringResource(R.string.setting_adaptive_strength), style = MaterialTheme.typography.bodyLarge)
        Slider(
            value = position,
            onValueChange = { position = it },
            onValueChangeFinished = { onChange(position.toInt()) },
            valueRange = 0f..100f,
        )
        Row(Modifier.fillMaxWidth()) {
            Text(
                stringResource(R.string.setting_adaptive_less),
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.weight(1f),
            )
            Text(stringResource(R.string.setting_adaptive_more), style = MaterialTheme.typography.labelSmall)
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

private fun ListeningMode.shortTitle() = when (this) {
    ListeningMode.OFF -> R.string.mode_short_off
    ListeningMode.NOISE_CANCELLATION -> R.string.mode_short_nc
    ListeningMode.TRANSPARENCY -> R.string.mode_short_transparency
    ListeningMode.ADAPTIVE -> R.string.mode_short_adaptive
    ListeningMode.UNKNOWN -> R.string.mode_unknown
}
