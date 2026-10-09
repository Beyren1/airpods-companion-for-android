package dev.podscompanion.ui.battery

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Replay5
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.podscompanion.data.media.MediaNotificationListener
import dev.podscompanion.data.media.PlayerApp
import dev.podscompanion.data.settings.AppSettings
import dev.podscompanion.protocol.aap.ListeningMode
import dev.podscompanion.ui.R

/** Какую функцию включить, когда пользователь вернётся из настроек с доступом к уведомлениям. */
private enum class Pending { CARD, AUTO_LAUNCH, APP_MODES, SMART_RESUME }

/**
 * Экран «Музыка»: доступ к плеерам, карточка текущего трека, автозапуск плеера,
 * режим шумоподавления под приложение и умное продолжение. У каждой функции свой переключатель.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MusicSettingsScreen(viewModel: MusicViewModel, onBack: () -> Unit) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val access by viewModel.access.collectAsStateWithLifecycle()
    val players by viewModel.players.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }

    var pending by rememberSaveable { mutableStateOf<Pending?>(null) }
    var pickPlayer by rememberSaveable { mutableStateOf(false) }
    var ruleFor by rememberSaveable { mutableStateOf<String?>(null) }

    fun enable(feature: Pending) {
        when (feature) {
            Pending.CARD -> viewModel.setNowPlayingCard(true)
            Pending.AUTO_LAUNCH -> {
                viewModel.setAutoLaunch(true)
                if (settings.autoLaunchPackage == null) pickPlayer = true
            }
            Pending.APP_MODES -> viewModel.setAppModes(true)
            Pending.SMART_RESUME -> viewModel.setSmartResume(true)
        }
    }

    // Доступ к уведомлениям даётся в системных настройках: ведём туда и включаем функцию,
    // когда пользователь вернётся с доступом.
    val accessLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        viewModel.refresh()
        val feature = pending
        pending = null
        if (feature != null && hasAccess(context)) enable(feature)
    }
    val requestAccess = { feature: Pending? ->
        pending = feature
        accessLauncher.launch(accessIntent(context))
    }
    val toggle = { feature: Pending, on: Boolean, off: () -> Unit ->
        when {
            !on -> off()
            access -> enable(feature)
            else -> requestAccess(feature)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.music_title)) },
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
                    NavRow(
                        stringResource(R.string.music_access_title), { requestAccess(null) },
                        icon = Icons.Filled.NotificationsActive,
                        description = stringResource(if (access) R.string.music_access_granted else R.string.music_access_missing),
                    )
                }
            }

            SettingsGroup {
                row {
                    SwitchRow(
                        stringResource(R.string.music_card_title), settings.nowPlayingCard,
                        { toggle(Pending.CARD, it) { viewModel.setNowPlayingCard(false) } },
                        icon = Icons.Filled.MusicNote,
                        description = stringResource(R.string.music_card_description),
                    )
                }
            }

            if (!settings.backgroundEnabled) {
                Text(
                    stringResource(R.string.music_needs_background),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
            }

            SettingsGroup {
                row {
                    SwitchRow(
                        stringResource(R.string.music_autolaunch_title), settings.autoLaunch,
                        { toggle(Pending.AUTO_LAUNCH, it) { viewModel.setAutoLaunch(false) } },
                        icon = Icons.Filled.PlayCircle,
                        description = stringResource(R.string.music_autolaunch_description),
                        enabled = settings.backgroundEnabled,
                    )
                }
                if (settings.autoLaunch) {
                    row {
                        NavRow(
                            stringResource(R.string.music_player_title), { pickPlayer = true },
                            icon = Icons.Filled.LibraryMusic,
                            description = settings.autoLaunchPackage?.let { pkg -> players.firstOrNull { it.packageName == pkg }?.label ?: pkg }
                                ?: stringResource(R.string.music_player_none),
                        )
                    }
                }
            }

            SettingsGroup {
                row {
                    SwitchRow(
                        stringResource(R.string.music_resume_title), settings.smartResume,
                        { toggle(Pending.SMART_RESUME, it) { viewModel.setSmartResume(false) } },
                        icon = Icons.Filled.Replay5,
                        description = stringResource(R.string.music_resume_description),
                        enabled = settings.backgroundEnabled && settings.autoPause,
                    )
                }
            }

            SectionTitle(stringResource(R.string.music_modes_section))
            SettingsGroup {
                row {
                    SwitchRow(
                        stringResource(R.string.music_modes_title), settings.appModes,
                        { toggle(Pending.APP_MODES, it) { viewModel.setAppModes(false) } },
                        icon = Icons.Filled.Headphones,
                        description = stringResource(R.string.music_modes_description),
                        enabled = settings.backgroundEnabled,
                    )
                }
                if (settings.appModes) {
                    players.forEach { player ->
                        row { RuleRow(player, settings.appModeRules[player.packageName]) { ruleFor = player.packageName } }
                    }
                }
            }
            if (settings.appModes && players.isEmpty()) {
                Text(
                    stringResource(R.string.music_no_players),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
            }
        }
    }

    if (pickPlayer) {
        OptionsDialog(
            title = stringResource(R.string.music_player_title),
            options = players,
            selected = players.firstOrNull { it.packageName == settings.autoLaunchPackage },
            label = { it.label },
            empty = stringResource(R.string.music_no_players),
            onSelect = { viewModel.setAutoLaunchPackage(it.packageName) },
            onDismiss = { pickPlayer = false },
        )
    }
    ruleFor?.let { pkg ->
        val player = players.firstOrNull { it.packageName == pkg }
        val modes = listOf<ListeningMode?>(null) + RULE_MODES
        OptionsDialog(
            title = player?.label ?: pkg,
            options = modes,
            selected = settings.appModeRules[pkg],
            label = { modeLabel(it) },
            onSelect = { viewModel.setAppModeRule(pkg, it) },
            onDismiss = { ruleFor = null },
        )
    }
}

private val RULE_MODES = listOf(
    ListeningMode.NOISE_CANCELLATION, ListeningMode.TRANSPARENCY, ListeningMode.ADAPTIVE, ListeningMode.OFF,
)

@Composable
private fun modeLabel(mode: ListeningMode?): String =
    if (mode == null) stringResource(R.string.music_mode_none) else stringResource(mode.title())

/** Строка правила: значок и название приложения, под ним выбранный режим. */
@Composable
private fun RuleRow(player: PlayerApp, mode: ListeningMode?, onClick: () -> Unit) {
    val context = LocalContext.current
    val icon = remember(player.packageName) {
        runCatching { context.packageManager.getApplicationIcon(player.packageName).toBitmap(96, 96).asImageBitmap() }.getOrNull()
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = 60.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (icon != null) Image(icon, null, Modifier.size(28.dp))
        else Icon(Icons.Filled.MusicNote, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.weight(1f)) {
            Text(player.label, style = MaterialTheme.typography.bodyLarge)
            Text(
                modeLabel(mode),
                style = MaterialTheme.typography.bodySmall,
                color = if (mode != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Окно выбора одного варианта из списка. */
@Composable
private fun <T> OptionsDialog(
    title: String,
    options: List<T>,
    selected: T?,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit,
    empty: String? = null,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.selectableGroup().verticalScroll(rememberScrollState())) {
                if (options.isEmpty() && empty != null) Text(empty, style = MaterialTheme.typography.bodyMedium)
                options.forEach { option ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .selectable(
                                selected = option == selected,
                                role = Role.RadioButton,
                                onClick = {
                                    onSelect(option)
                                    onDismiss()
                                },
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = option == selected, onClick = null)
                        Text(label(option), Modifier.padding(start = 16.dp), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

private fun hasAccess(context: Context): Boolean =
    NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

/** Android 11+: сразу страница доступа для нашего приложения; раньше — общий список. */
private fun accessIntent(context: Context): Intent =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).putExtra(
            Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
            ComponentName(context, MediaNotificationListener::class.java).flattenToString(),
        )
    } else {
        Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
    }
