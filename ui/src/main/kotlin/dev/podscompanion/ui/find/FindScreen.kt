package dev.podscompanion.ui.find

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Directions
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.podscompanion.data.find.Closeness
import dev.podscompanion.data.find.LastPlace
import dev.podscompanion.data.find.SoundSide
import dev.podscompanion.data.find.Trend
import dev.podscompanion.ui.R
import dev.podscompanion.ui.battery.BlockRow
import dev.podscompanion.ui.battery.Choice
import dev.podscompanion.ui.battery.SectionTitle
import dev.podscompanion.ui.battery.SettingsGroup
import java.text.DateFormat
import java.util.Date

/**
 * Вкладка «Найти»: как «Локатор» на iPhone, насколько это возможно без сети Apple.
 * Звук на подключённых наушниках, «горячо/холодно» по силе сигнала и место, где наушники
 * последний раз отключились от телефона. Внизу — ссылка на настоящий «Локатор» в браузере.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FindScreen(viewModel: FindViewModel) {
    val places by viewModel.places.collectAsStateWithLifecycle()
    val connected by viewModel.connected.collectAsStateWithLifecycle()
    val playing by viewModel.playing.collectAsStateWithLifecycle()
    val searching by viewModel.searching.collectAsStateWithLifecycle()
    val targets by viewModel.targets.collectAsStateWithLifecycle()
    val selected by viewModel.selected.collectAsStateWithLifecycle()

    // Ушли с вкладки или свернули приложение — поиск останавливаем: частый скан тратит батарею.
    DisposableEffect(Unit) { onDispose { viewModel.stopSearch() } }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.stopSearch() }

    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.find_title)) }) }) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionTitle(stringResource(R.string.find_sound_section))
            SoundCard(connected, playing, onPlay = viewModel::play, onStop = viewModel::stopSound)

            SectionTitle(stringResource(R.string.find_nearby_section))
            NearbyCard(searching, targets, selected, viewModel::startSearch, viewModel::stopSearch, viewModel::select)

            SectionTitle(stringResource(R.string.find_place_section))
            PlacesCard(places, connected)

            SectionTitle(stringResource(R.string.find_apple_section))
            AppleFindMyCard()
        }
    }
}

// ---------- Звук ----------

@Composable
private fun SoundCard(
    connected: List<ConnectedPods>,
    playing: SoundSide?,
    onPlay: (SoundSide) -> Boolean,
    onStop: () -> Unit,
) {
    var side by rememberSaveable { mutableStateOf(SoundSide.BOTH) }
    var notConnected by remember { mutableStateOf(false) }
    SettingsGroup {
        row {
            BlockRow {
                if (connected.isEmpty()) {
                    Text(stringResource(R.string.find_sound_not_connected), style = MaterialTheme.typography.bodyMedium)
                } else {
                    Text(
                        stringResource(R.string.find_sound_connected, connected.joinToString { it.name }),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        stringResource(R.string.find_sound_warning),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Choice(
                        options = SoundSide.entries,
                        selected = playing ?: side,
                        label = { stringResource(it.label()) },
                        onSelect = {
                            side = it
                            // Уже звучит — сразу переключаем на выбранный наушник.
                            if (playing != null) onPlay(it)
                        },
                    )
                    if (playing == null) {
                        Button(
                            onClick = { notConnected = !onPlay(side) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.AutoMirrored.Filled.VolumeUp, null)
                            Text(stringResource(R.string.find_sound_play), Modifier.padding(start = 8.dp))
                        }
                    } else {
                        Button(
                            onClick = onStop,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error,
                                contentColor = MaterialTheme.colorScheme.onError,
                            ),
                        ) {
                            Text(stringResource(R.string.find_sound_stop))
                        }
                    }
                    if (notConnected) {
                        Text(
                            stringResource(R.string.find_sound_no_output),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
    }
}

private fun SoundSide.label() = when (this) {
    SoundSide.BOTH -> R.string.find_side_both
    SoundSide.LEFT -> R.string.find_side_left
    SoundSide.RIGHT -> R.string.find_side_right
}

// ---------- Горячо/холодно ----------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NearbyCard(
    searching: Boolean,
    targets: List<FindTarget>,
    selected: String?,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onSelect: (String) -> Unit,
) {
    SettingsGroup {
        row {
            BlockRow {
                if (!searching) {
                    Text(stringResource(R.string.find_nearby_description), style = MaterialTheme.typography.bodyMedium)
                    Button(onClick = onStart, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.find_nearby_start))
                    }
                    return@BlockRow
                }
                val target = targets.firstOrNull { it.key == selected }
                    ?: targets.firstOrNull { it.ownName != null && it.reading != null }
                    ?: targets.firstOrNull { it.reading != null }
                if (target == null) {
                    Text(stringResource(R.string.find_nearby_listening), style = MaterialTheme.typography.bodyMedium)
                } else {
                    HeatMeter(target)
                }
                if (targets.size > 1) {
                    Text(
                        stringResource(R.string.find_nearby_choose),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        targets.forEach { t ->
                            FilterChip(
                                selected = t.key == target?.key,
                                onClick = { onSelect(t.key) },
                                label = { Text(t.title()) },
                            )
                        }
                    }
                }
                Text(
                    stringResource(R.string.find_nearby_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(onClick = onStop, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.find_nearby_stop))
                }
            }
        }
    }
}

@Composable
private fun FindTarget.title(): String {
    val modelName = model?.displayName ?: stringResource(R.string.find_unknown_model)
    return ownName ?: stringResource(R.string.find_other_pods, modelName)
}

/** Круг, который краснеет, когда наушники ближе, и синеет, когда дальше. */
@Composable
private fun HeatMeter(target: FindTarget) {
    val reading = target.reading
    val heat by animateFloatAsState(reading?.heat ?: 0f, label = "heat")
    val cold = Color(0xFF4A90E2)
    val hot = Color(0xFFE5483B)
    val color by animateColorAsState(if (reading == null) MaterialTheme.colorScheme.outline else lerp(cold, hot, heat), label = "color")
    val track = MaterialTheme.colorScheme.surfaceContainerHighest

    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(target.title(), style = MaterialTheme.typography.titleMedium)
        Box(Modifier.size(180.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val stroke = 14.dp.toPx()
                val inset = stroke / 2
                val arcSize = Size(size.width - stroke, size.height - stroke)
                drawArc(track, 135f, 270f, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
                drawArc(color, 135f, 270f * heat, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
                drawCircle(color.copy(alpha = 0.18f), radius = (size.minDimension / 2 - stroke * 1.6f) * (0.35f + 0.65f * heat))
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    if (reading == null) stringResource(R.string.find_lost) else stringResource(reading.closeness.label()),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                if (reading != null) {
                    Text(
                        stringResource(R.string.find_dbm, reading.rssi),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (reading != null) {
            val (icon, text) = when (reading.trend) {
                Trend.WARMER -> Icons.Filled.ArrowUpward to R.string.find_warmer
                Trend.COLDER -> Icons.Filled.ArrowDownward to R.string.find_colder
                Trend.STEADY -> Icons.Filled.Remove to R.string.find_steady
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(icon, null, tint = if (reading.trend == Trend.STEADY) MaterialTheme.colorScheme.onSurfaceVariant else color)
                Text(stringResource(text), style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

private fun Closeness.label() = when (this) {
    Closeness.VERY_CLOSE -> R.string.find_very_close
    Closeness.CLOSE -> R.string.find_close
    Closeness.NEAR -> R.string.find_near
    Closeness.FAR -> R.string.find_far
    Closeness.VERY_FAR -> R.string.find_very_far
}

// ---------- Последнее место ----------

@Composable
private fun PlacesCard(places: List<LastPlace>?, connected: List<ConnectedPods>) {
    val context = LocalContext.current
    var location by remember { mutableStateOf(LocationAccess.of(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { location = LocationAccess.of(context) }

    // Сначала «во время использования», затем отдельно «всегда»: Android не даёт попросить оба сразу.
    val backgroundLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        location = LocationAccess.of(context)
    }
    val foregroundLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        location = LocationAccess.of(context)
        if (location == LocationAccess.WHILE_IN_USE) backgroundLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
    }

    SettingsGroup {
        when (location) {
            LocationAccess.NONE -> row {
                BlockRow {
                    Text(stringResource(R.string.find_location_needed), style = MaterialTheme.typography.bodyMedium)
                    Button(
                        onClick = {
                            foregroundLauncher.launch(
                                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.find_location_allow)) }
                }
            }
            LocationAccess.WHILE_IN_USE -> row {
                BlockRow {
                    Text(stringResource(R.string.find_location_always_needed), style = MaterialTheme.typography.bodyMedium)
                    Button(
                        onClick = { backgroundLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.find_location_allow_always)) }
                }
            }
            LocationAccess.ALWAYS -> Unit
        }
        val list = places
        if (list != null && list.isEmpty()) {
            row {
                BlockRow {
                    Text(
                        stringResource(R.string.find_place_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        list.orEmpty().forEach { place ->
            row { PlaceRow(place, connectedNow = connected.any { it.address == place.address }) }
        }
    }
}

@Composable
private fun PlaceRow(place: LastPlace, connectedNow: Boolean) {
    val context = LocalContext.current
    BlockRow {
        Text(place.name, style = MaterialTheme.typography.titleMedium)
        val time = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(place.disconnectedAtMs))
        val ago = DateUtils.getRelativeTimeSpanString(place.disconnectedAtMs, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
        Text(
            if (connectedNow) {
                stringResource(R.string.find_place_connected_now, ago, time)
            } else {
                stringResource(R.string.find_place_disconnected, ago, time)
            },
            style = MaterialTheme.typography.bodyMedium,
        )
        val fix = place.location
        if (fix == null) {
            Text(
                stringResource(R.string.find_place_unknown),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@BlockRow
        }
        val accuracy = fix.accuracyM?.let { stringResource(R.string.find_place_accuracy, Math.round(it)) }
        // Место взято заранее (последнее известное): честно показываем, насколько оно старое.
        val stale = place.disconnectedAtMs - fix.fixedAtMs > STALE_FIX_MS
        val details = listOfNotNull(
            accuracy,
            if (stale) {
                stringResource(
                    R.string.find_place_fix_time,
                    DateUtils.getRelativeTimeSpanString(fix.fixedAtMs, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS),
                )
            } else {
                null
            },
        )
        if (details.isNotEmpty()) {
            Text(
                details.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { openMap(context, fix, place.name) }) {
                Icon(Icons.Filled.Map, null)
                Text(stringResource(R.string.find_place_map), Modifier.padding(start = 6.dp))
            }
            OutlinedButton(onClick = { openRoute(context, fix) }) {
                Icon(Icons.Filled.Directions, null)
                Text(stringResource(R.string.find_place_route), Modifier.padding(start = 6.dp))
            }
        }
    }
}

private const val STALE_FIX_MS = 5 * 60_000L

/** Точка на карте: Google Карты или любое другое приложение карт. */
private fun openMap(context: Context, fix: LastPlace.Fix, label: String) {
    val coords = "${fix.latitude},${fix.longitude}"
    val geo = Uri.parse("geo:$coords?q=$coords(${Uri.encode(label)})")
    startOrBrowser(context, Intent(Intent.ACTION_VIEW, geo), "https://www.google.com/maps/search/?api=1&query=$coords")
}

/** Маршрут пешком до точки. */
private fun openRoute(context: Context, fix: LastPlace.Fix) {
    val coords = "${fix.latitude},${fix.longitude}"
    val url = "https://www.google.com/maps/dir/?api=1&destination=$coords&travelmode=walking"
    startOrBrowser(context, Intent(Intent.ACTION_VIEW, Uri.parse(url)), url)
}

private fun startOrBrowser(context: Context, intent: Intent, fallbackUrl: String) {
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(fallbackUrl))) }
    }
}

private enum class LocationAccess {
    NONE, WHILE_IN_USE, ALWAYS;

    companion object {
        fun of(context: Context): LocationAccess {
            fun granted(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
            val any = granted(Manifest.permission.ACCESS_FINE_LOCATION) || granted(Manifest.permission.ACCESS_COARSE_LOCATION)
            return when {
                !any -> NONE
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION) -> WHILE_IN_USE
                else -> ALWAYS
            }
        }
    }
}

// ---------- Локатор Apple ----------

@Composable
private fun AppleFindMyCard() {
    val context = LocalContext.current
    SettingsGroup {
        row {
            BlockRow {
                Text(stringResource(R.string.find_apple_description), style = MaterialTheme.typography.bodyMedium)
                OutlinedButton(
                    onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(APPLE_FIND_MY_URL))) } },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.find_apple_open)) }
            }
        }
    }
}

private const val APPLE_FIND_MY_URL = "https://www.icloud.com/find"
