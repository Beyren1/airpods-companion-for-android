package dev.podscompanion.ui.stats

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.podscompanion.data.stats.PairUsage
import dev.podscompanion.protocol.advertising.Capability
import dev.podscompanion.protocol.advertising.PodsModel
import dev.podscompanion.ui.R
import dev.podscompanion.ui.battery.PodsArt
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/** Сколько дней в столбиках на карточке. */
private const val WEEK = 7

/**
 * Вкладка «Статистика»: на каждую пару наушников своя карточка с картинкой и именем.
 * В ней — сколько сегодня наушники были в ушах, сколько играла музыка и шли разговоры,
 * и столбики «в ушах» за последние 7 дней.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(viewModel: StatsViewModel) {
    val pairs by viewModel.pairs.collectAsStateWithLifecycle()
    val backgroundEnabled by viewModel.backgroundEnabled.collectAsStateWithLifecycle()
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    val today = LocalDate.now()

    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.stats_title)) }) }) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!backgroundEnabled) {
                Text(
                    stringResource(R.string.stats_needs_background),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
            }
            val list = pairs ?: return@Column
            if (list.isEmpty()) {
                EmptyCard()
            } else {
                list.forEach { PairCard(it, today) }
                TextButton(onClick = { confirmClear = true }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                    Text(stringResource(R.string.stats_clear))
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.stats_clear_title)) },
            text = { Text(stringResource(R.string.stats_clear_text)) },
            confirmButton = {
                TextButton(onClick = { confirmClear = false; viewModel.clear() }) { Text(stringResource(R.string.stats_clear_confirm)) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun EmptyCard() {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(
            Modifier.fillMaxWidth().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(Icons.Filled.BarChart, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(36.dp))
            Text(
                stringResource(R.string.stats_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** Карточка одной пары: картинка, имя, сегодня и неделя. */
@Composable
private fun PairCard(pair: PairUsage, today: LocalDate) {
    val model = pair.modelId?.let(PodsModel::fromId)
    val stereo = model?.let { Capability.STEREO_BUDS in it.capabilities } ?: true
    val todayTotals = pair.on(today)
    val week = pair.lastDays(today, WEEK)

    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHighest, modifier = Modifier.size(52.dp)) {
                    Box(contentAlignment = Alignment.Center) {
                        Image(
                            painterResource(if (stereo) PodsArt.caseFor(model).image else PodsArt.OverEarArt.image), null,
                            modifier = Modifier.size(34.dp),
                        )
                    }
                }
                Column(Modifier.weight(1f)) {
                    Text(pair.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    // Модель под именем, если пару переименовали или имя общее («AirPods»).
                    if (model != null && model.displayName != pair.name) {
                        Text(model.displayName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            Text(stringResource(R.string.stats_today), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Tile(Icons.Filled.Hearing, stringResource(R.string.stats_worn), todayTotals.wornSec, Modifier.weight(1f))
                Tile(Icons.Filled.MusicNote, stringResource(R.string.stats_music), todayTotals.musicSec, Modifier.weight(1f))
                Tile(Icons.Filled.Call, stringResource(R.string.stats_calls), todayTotals.callSec, Modifier.weight(1f))
            }

            Text(stringResource(R.string.stats_week_worn), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            WeekBars(pair, today)
            Text(
                stringResource(
                    R.string.stats_week_totals,
                    duration(week.wornSec), duration(week.musicSec), duration(week.callSec),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Tile(icon: ImageVector, label: String, seconds: Long, modifier: Modifier) {
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest, modifier = modifier) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            Text(duration(seconds), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Столбики «в ушах» за 7 дней, сегодня справа и ярче. Высота — от самого долгого дня. */
@Composable
private fun WeekBars(pair: PairUsage, today: LocalDate) {
    val days = (WEEK - 1 downTo 0).map { today.minusDays(it.toLong()) }
    val values = days.map { pair.on(it).wornSec }
    val max = values.maxOrNull()?.takeIf { it > 0 } ?: 1L
    val locale = Locale.getDefault()
    val colors = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth().height(88.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            days.forEachIndexed { i, day ->
                Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.BottomCenter) {
                    // Пустой день — тонкая полоска, чтобы было видно, что он есть.
                    val fraction = (values[i].toFloat() / max).coerceIn(0.03f, 1f)
                    Box(
                        Modifier
                            .fillMaxWidth(0.7f)
                            .fillMaxHeight(fraction)
                            .clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp))
                            .background(
                                when {
                                    values[i] == 0L -> colors.surfaceContainerHighest
                                    day == today -> colors.primary
                                    else -> colors.primary.copy(alpha = 0.55f)
                                },
                            ),
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            days.forEach { day ->
                Text(
                    day.dayOfWeek.getDisplayName(TextStyle.SHORT, locale),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (day == today) colors.primary else colors.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** «2 ч 15 мин», «40 мин», «0 мин». Секунды не показываем: счётчик идёт шагами по 10 с. */
@Composable
private fun duration(seconds: Long): String {
    val minutes = seconds / 60
    val hours = minutes / 60
    return if (hours > 0) {
        stringResource(R.string.stats_hours_minutes, hours.toInt(), (minutes % 60).toInt())
    } else {
        stringResource(R.string.stats_minutes, minutes.toInt())
    }
}
