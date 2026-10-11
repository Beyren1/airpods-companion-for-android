package dev.podscompanion.ui.battery

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import dev.podscompanion.protocol.aap.Aap
import dev.podscompanion.protocol.advertising.Capability
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
import dev.podscompanion.protocol.aap.CrownDirection
import dev.podscompanion.protocol.aap.HoldDuration
import dev.podscompanion.protocol.aap.PressSpeed
import dev.podscompanion.protocol.aap.holdDuration
import dev.podscompanion.protocol.aap.pressSpeed
import dev.podscompanion.protocol.aap.toneVolume
import dev.podscompanion.protocol.aap.MAX_DEFAULT_CONTROLS
import dev.podscompanion.protocol.aap.NOISE_CONTROL_DEFAULT_CONTROLS
import dev.podscompanion.protocol.aap.crownDirection
import dev.podscompanion.protocol.aap.withDefaults
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
import android.content.Intent
import android.provider.Settings
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import dev.podscompanion.data.settings.CUSTOM_STEM_PRESSES
import dev.podscompanion.data.settings.StemAction
import dev.podscompanion.data.stem.CameraBridge
import dev.podscompanion.protocol.aap.CallControl
import dev.podscompanion.protocol.aap.StemPress
import dev.podscompanion.protocol.aap.VolumeSwipeSpeed
import dev.podscompanion.protocol.aap.callControl
import dev.podscompanion.protocol.aap.volumeSwipeSpeed

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
    gestures: GesturesUi = GesturesUi(),
    stem: StemUi = StemUi(),
) {
    val context = LocalContext.current
    val setAlias = rememberAliasSetter { result ->
        val text = when (result) {
            AliasResult.DONE -> R.string.rename_done
            AliasResult.DECLINED -> R.string.rename_declined
            AliasResult.FAILED -> R.string.rename_failed
            AliasResult.UNSUPPORTED -> R.string.rename_old_android
        }
        Toast.makeText(context, text, Toast.LENGTH_LONG).show()
    }
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
                NameRow(session.deviceName) { name ->
                    onCommand(session.address, AapCommand.Rename(name))
                    setAlias(session.address, name)
                }
                if (model == null || Capability.HEAD_GESTURES in model.capabilities) {
                    HeadGesturesSection(gestures, onCalibrate = { gestures.onCalibrate(session.address) })
                }
                SettingsContent(session, model, stem) { onCommand(session.address, it) }
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

/** Всё, что нужно разделу жестов головой: настройки, калибровка и действия. */
data class GesturesUi(
    val enabled: Boolean = false,
    val calibrated: Boolean = false,
    val backgroundEnabled: Boolean = false,
    val calibration: CalibrationState = CalibrationState.Idle,
    val onEnabledChange: (Boolean) -> Unit = {},
    val onCalibrate: (address: String) -> Unit = {},
    val onCalibrationDismiss: () -> Unit = {},
)

/** Свои действия на нажатия ножки и затвор камеры: хранятся в приложении, выполняет их фоновый сервис. */
data class StemUi(
    val actions: Map<StemPress, StemAction> = emptyMap(),
    val cameraShutter: Boolean = false,
    val backgroundEnabled: Boolean = false,
    val onActionChange: (StemPress, StemAction?) -> Unit = { _, _ -> },
    val onCameraShutterChange: (Boolean) -> Unit = {},
)

/** Имя наушников: по нажатию окно для нового имени. */
@Composable
private fun NameRow(current: String, onRename: (String) -> Unit) {
    var editing by rememberSaveable { mutableStateOf(false) }
    // Android отдаёт новое имя только после переподключения, поэтому сразу показываем сохранённое.
    var saved by rememberSaveable(current) { mutableStateOf<String?>(null) }
    val shown = saved ?: current
    SettingsGroup {
        row {
            NavRow(stringResource(R.string.setting_name), { editing = true }, icon = Icons.Filled.Edit, description = shown)
        }
    }
    if (editing) {
        var text by rememberSaveable { mutableStateOf(shown) }
        val valid = text.isNotBlank() && text.trim().toByteArray().size <= Aap.MAX_NAME_BYTES
        AlertDialog(
            onDismissRequest = { editing = false },
            title = { Text(stringResource(R.string.setting_name)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        singleLine = true,
                        isError = !valid,
                        supportingText = { Text(stringResource(R.string.rename_limit, Aap.MAX_NAME_BYTES)) },
                    )
                    Text(
                        stringResource(R.string.rename_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { saved = text.trim(); onRename(text.trim()); editing = false }, enabled = valid) {
                    Text(stringResource(R.string.save))
                }
            },
            dismissButton = { TextButton(onClick = { editing = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/**
 * Жесты головой при звонке. Включение просит разрешения «Телефон» и «Ответ на звонки»,
 * а если калибровки ещё не было — сначала проводит её.
 */
@Composable
private fun HeadGesturesSection(ui: GesturesUi, onCalibrate: () -> Unit) {
    val context = LocalContext.current
    val permissions = arrayOf(Manifest.permission.READ_PHONE_STATE, Manifest.permission.ANSWER_PHONE_CALLS)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.all { it }) {
            if (ui.calibrated) ui.onEnabledChange(true) else onCalibrate()
        }
    }
    fun enable() {
        val granted = permissions.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
        when {
            !granted -> launcher.launch(permissions)
            ui.calibrated -> ui.onEnabledChange(true)
            else -> onCalibrate()
        }
    }

    SectionTitle(stringResource(R.string.section_gestures))
    SettingsGroup {
        row {
            SwitchRow(
                stringResource(R.string.gestures_title), ui.enabled,
                { on -> if (on) enable() else ui.onEnabledChange(false) },
                icon = Icons.Filled.Call,
                description = stringResource(R.string.gestures_hint) +
                    if (!ui.backgroundEnabled) "\n" + stringResource(R.string.gestures_need_background) else "",
            )
        }
        if (ui.calibrated) row {
            NavRow(stringResource(R.string.gestures_recalibrate), onCalibrate, icon = Icons.Filled.Tune)
        }
    }
    CalibrationDialog(ui.calibration, onRetry = onCalibrate, onDismiss = ui.onCalibrationDismiss)
}

@Composable
private fun CalibrationDialog(state: CalibrationState, onRetry: () -> Unit, onDismiss: () -> Unit) {
    if (state == CalibrationState.Idle) return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.calibration_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                when (state) {
                    is CalibrationState.Recording -> {
                        Text(
                            stringResource(
                                if (state.step == CalibrationState.Step.NOD) R.string.calibration_nod else R.string.calibration_shake,
                            ),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
                    }
                    CalibrationState.Done -> Text(stringResource(R.string.calibration_done))
                    is CalibrationState.Failed -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(if (state.noData) R.string.calibration_no_data else R.string.calibration_failed))
                        // Цифры для отладки: пришлите их, если ошибка повторяется.
                        Text(
                            state.details,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    CalibrationState.Idle -> Unit
                }
            }
        },
        confirmButton = {
            when (state) {
                is CalibrationState.Failed -> TextButton(onClick = onRetry) { Text(stringResource(R.string.retry)) }
                CalibrationState.Done -> TextButton(onClick = onDismiss) { Text(stringResource(R.string.ok)) }
                else -> Unit
            }
        },
        dismissButton = {
            if (state !is CalibrationState.Done) TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@Composable
private fun SettingsContent(session: AapSessionState.Connected, model: PodsModel?, stem: StemUi, send: (AapCommand) -> Unit) {
    // Часть настроек наушники не присылают (Max — см. MAX_DEFAULT_CONTROLS, у остальных бывает без
    // набора режимов для зажатия): недостающие берём заводскими, чтобы их всё равно можно было выбрать.
    val isMax = model != null && Capability.DIGITAL_CROWN in model.capabilities
    val defaults = when {
        isMax -> MAX_DEFAULT_CONTROLS
        model != null && Capability.NOISE_CONTROL in model.capabilities -> NOISE_CONTROL_DEFAULT_CONTROLS
        else -> emptyMap()
    }
    val usingDefaults = defaults.keys.any { it !in session.device.controls }
    val device = session.device.withDefaults(defaults)
    val personalized = device.toggle(AapToggle.PERSONALIZED_VOLUME)
    val strength = device.adaptiveStrength
    val hold = device.pressAndHold
    val cycle = device.modeCycle
    val mic = device.micMode
    val ear = device.toggle(AapToggle.EAR_DETECTION)
    val crown = device.crownDirection
    val speed = device.pressSpeed
    val holdTime = device.holdDuration
    val tones = device.toneVolume
    val call = device.callControl
    val swipe = device.toggle(AapToggle.VOLUME_SWIPE)
    val swipeSpeed = device.volumeSwipeSpeed
    val allowOff = device.toggle(AapToggle.ALLOW_OFF)
    val sleep = device.toggle(AapToggle.SLEEP_DETECTION)
    val charging = device.toggle(AapToggle.OPTIMIZED_CHARGING)
    // Ножка есть у наушников, которые сообщают настройку зажатия; у Max её нет.
    val hasStem = !isMax && hold != null

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
        SectionTitle(stringResource(if (isMax) R.string.setting_noise_button else R.string.setting_press_hold))
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
                    Text(
                        stringResource(if (isMax) R.string.setting_noise_button_cycle else R.string.setting_mode_cycle),
                        style = MaterialTheme.typography.bodyLarge,
                    )
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

    if (hasStem) StemSection(stem, noiseControl = cycle != null)

    if (call != null) {
        SectionTitle(stringResource(R.string.section_calls))
        SettingsGroup {
            row {
                BlockRow {
                    Text(stringResource(R.string.setting_call_mute), style = MaterialTheme.typography.bodyLarge)
                    Choice(CallControl.entries, call, { c -> stringResource(c.title()) }) { c -> send(AapCommand.SetCallControl(c)) }
                    Text(
                        stringResource(if (call == CallControl.MUTE_ON_SINGLE) R.string.setting_call_end_double else R.string.setting_call_end_single),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    if (crown != null) {
        SectionTitle(stringResource(R.string.setting_crown))
        SettingsGroup {
            row {
                BlockRow {
                    Text(stringResource(R.string.setting_crown_hint), style = MaterialTheme.typography.bodyLarge)
                    Choice(CrownDirection.entries, crown, { d -> stringResource(d.title()) }) { d ->
                        send(AapCommand.SetCrownDirection(d))
                    }
                }
            }
        }
    }

    if (speed != null || holdTime != null || swipe != null || swipeSpeed != null) {
        SectionTitle(stringResource(R.string.section_press))
        SettingsGroup {
            if (speed != null) row {
                BlockRow {
                    Text(stringResource(R.string.setting_press_speed), style = MaterialTheme.typography.bodyLarge)
                    ChipChoice(PressSpeed.entries, speed, { v -> stringResource(v.title()) }) { v -> send(AapCommand.SetPressSpeed(v)) }
                }
            }
            if (holdTime != null) row {
                BlockRow {
                    Text(stringResource(R.string.setting_hold_duration), style = MaterialTheme.typography.bodyLarge)
                    ChipChoice(HoldDuration.entries, holdTime, { v -> stringResource(v.title()) }) { v -> send(AapCommand.SetHoldDuration(v)) }
                }
            }
            if (swipe != null) row {
                SwitchRow(
                    stringResource(R.string.setting_volume_swipe), swipe,
                    { on -> send(AapCommand.SetToggle(AapToggle.VOLUME_SWIPE, on)) },
                    description = stringResource(R.string.setting_volume_swipe_hint),
                )
            }
            if (swipeSpeed != null && swipe != false) row {
                BlockRow {
                    Text(stringResource(R.string.setting_volume_swipe_speed), style = MaterialTheme.typography.bodyLarge)
                    ChipChoice(VolumeSwipeSpeed.entries, swipeSpeed, { v -> stringResource(v.title()) }) { v -> send(AapCommand.SetVolumeSwipeSpeed(v)) }
                }
            }
        }
    }

    if (mic != null || ear != null || tones != null || allowOff != null || sleep != null || charging != null) {
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
                    stringResource(if (isMax) R.string.setting_head_detection else R.string.setting_ear_detection), ear,
                    { on -> send(AapCommand.SetToggle(AapToggle.EAR_DETECTION, on)) },
                    icon = Icons.Filled.Hearing,
                )
            }
            if (tones != null) row { ToneVolumeSlider(tones) { value -> send(AapCommand.SetToneVolume(value)) } }
            if (allowOff != null) row {
                SwitchRow(
                    stringResource(R.string.setting_allow_off), allowOff,
                    { on -> send(AapCommand.SetToggle(AapToggle.ALLOW_OFF, on)) },
                    description = stringResource(R.string.setting_allow_off_hint),
                )
            }
            if (sleep != null) row {
                SwitchRow(
                    stringResource(R.string.setting_sleep), sleep,
                    { on -> send(AapCommand.SetToggle(AapToggle.SLEEP_DETECTION, on)) },
                    icon = Icons.Filled.Bedtime,
                    description = stringResource(R.string.setting_sleep_hint),
                )
            }
            if (charging != null) row {
                SwitchRow(
                    stringResource(R.string.setting_optimized_charging), charging,
                    { on -> send(AapCommand.SetToggle(AapToggle.OPTIMIZED_CHARGING, on)) },
                    icon = Icons.Filled.BatteryChargingFull,
                    description = stringResource(R.string.setting_optimized_charging_hint),
                )
            }
        }
    }

    if (personalized == null && strength == null && hold == null && cycle == null && mic == null && ear == null && crown == null &&
        speed == null && holdTime == null && tones == null && call == null && swipe == null && swipeSpeed == null &&
        allowOff == null && sleep == null && charging == null
    ) {
        Text(
            stringResource(R.string.settings_none),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 48.dp),
        )
    }

    Text(
        stringResource(
            when {
                !usingDefaults -> R.string.settings_hint
                isMax -> R.string.settings_max_hint
                else -> R.string.settings_cycle_default_hint
            },
        ),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 8.dp),
    )
}

/**
 * Свои действия на одно, двойное и тройное нажатие ножки и затвор камеры. Их выполняет приложение,
 * поэтому нужен фоновый режим; «Обычное» — наушники делают своё (пауза, следующий, предыдущий трек).
 */
@Composable
private fun StemSection(ui: StemUi, noiseControl: Boolean) {
    val context = LocalContext.current
    var serviceOn by remember { mutableStateOf(CameraBridge.serviceEnabled(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { serviceOn = CameraBridge.serviceEnabled(context) }
    var explainCamera by rememberSaveable { mutableStateOf(false) }
    val actions = listOf<StemAction?>(null, StemAction.PLAY_PAUSE, StemAction.NEXT_TRACK, StemAction.PREVIOUS_TRACK, StemAction.ASSISTANT) +
        if (noiseControl) listOf(StemAction.NOISE_MODE) else emptyList()

    SectionTitle(stringResource(R.string.section_stem))
    SettingsGroup {
        CUSTOM_STEM_PRESSES.forEach { press ->
            row {
                BlockRow {
                    Text(stringResource(press.title()), style = MaterialTheme.typography.bodyLarge)
                    ChipChoice(actions, ui.actions[press], { a -> stringResource(a?.title() ?: press.defaultTitle()) }) { a ->
                        ui.onActionChange(press, a)
                    }
                }
            }
        }
        row {
            SwitchRow(
                stringResource(R.string.setting_camera), ui.cameraShutter && serviceOn,
                { on -> if (on && !serviceOn) explainCamera = true else ui.onCameraShutterChange(on) },
                icon = Icons.Filled.PhotoCamera,
                description = stringResource(R.string.setting_camera_hint),
            )
        }
    }
    if (!ui.backgroundEnabled && (ui.actions.isNotEmpty() || ui.cameraShutter)) {
        Text(
            stringResource(R.string.stem_need_background),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
    }
    if (explainCamera) {
        AlertDialog(
            onDismissRequest = { explainCamera = false },
            title = { Text(stringResource(R.string.camera_dialog_title)) },
            text = { Text(stringResource(R.string.camera_dialog_text)) },
            confirmButton = {
                TextButton(onClick = {
                    explainCamera = false
                    ui.onCameraShutterChange(true)
                    runCatching {
                        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                }) { Text(stringResource(R.string.camera_dialog_open)) }
            },
            dismissButton = { TextButton(onClick = { explainCamera = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
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

/** Выбор одного варианта чипами: длинные подписи переносятся на новую строку, а не обрезаются. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> ChipChoice(options: List<T>, selected: T, label: @Composable (T) -> String, onSelect: (T) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { option ->
            FilterChip(
                selected = option == selected,
                onClick = { if (option != selected) onSelect(option) },
                label = { Text(label(option)) },
            )
        }
    }
}

/** Громкость сигналов наушников, 0..100 %. Команда уходит, когда палец отпущен. */
@Composable
private fun ToneVolumeSlider(value: Int, onChange: (Int) -> Unit) {
    var position by remember(value) { mutableFloatStateOf(value.toFloat()) }
    BlockRow {
        Text(stringResource(R.string.setting_tone_volume, position.toInt()), style = MaterialTheme.typography.bodyLarge)
        Slider(
            value = position,
            onValueChange = { position = it },
            onValueChangeFinished = { onChange(position.toInt()) },
            valueRange = 0f..100f,
        )
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

private fun PressSpeed.title() = when (this) {
    PressSpeed.DEFAULT -> R.string.speed_default
    PressSpeed.SLOWER -> R.string.speed_slower
    PressSpeed.SLOWEST -> R.string.speed_slowest
}

private fun HoldDuration.title() = when (this) {
    HoldDuration.DEFAULT -> R.string.speed_default
    HoldDuration.SHORTER -> R.string.hold_shorter
    HoldDuration.SHORTEST -> R.string.hold_shortest
}

private fun CrownDirection.title() = when (this) {
    CrownDirection.BACK_TO_FRONT -> R.string.crown_back_to_front
    CrownDirection.FRONT_TO_BACK -> R.string.crown_front_to_back
}

private fun CallControl.title() = when (this) {
    CallControl.MUTE_ON_SINGLE -> R.string.call_press_once
    CallControl.MUTE_ON_DOUBLE -> R.string.call_press_twice
}

private fun VolumeSwipeSpeed.title() = when (this) {
    VolumeSwipeSpeed.DEFAULT -> R.string.speed_default
    VolumeSwipeSpeed.LONGER -> R.string.swipe_longer
    VolumeSwipeSpeed.LONGEST -> R.string.swipe_longest
}

private fun StemPress.title() = when (this) {
    StemPress.SINGLE -> R.string.stem_single
    StemPress.DOUBLE -> R.string.stem_double
    StemPress.TRIPLE -> R.string.stem_triple
    StemPress.LONG -> R.string.setting_press_hold
}

/** «Обычное» с подсказкой, что делают наушники сами. */
private fun StemPress.defaultTitle() = when (this) {
    StemPress.SINGLE -> R.string.stem_default_single
    StemPress.DOUBLE -> R.string.stem_default_double
    StemPress.TRIPLE -> R.string.stem_default_triple
    StemPress.LONG -> R.string.stem_default_single
}

private fun StemAction.title() = when (this) {
    StemAction.PLAY_PAUSE -> R.string.stem_play_pause
    StemAction.NEXT_TRACK -> R.string.stem_next
    StemAction.PREVIOUS_TRACK -> R.string.stem_previous
    StemAction.ASSISTANT -> R.string.press_assistant
    StemAction.NOISE_MODE -> R.string.stem_noise_mode
}

private fun PressAction.title() = when (this) {
    PressAction.NOISE_CONTROL -> R.string.press_noise_control
    PressAction.VOICE_ASSISTANT -> R.string.press_assistant
}
