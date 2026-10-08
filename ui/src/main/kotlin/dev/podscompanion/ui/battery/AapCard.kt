package dev.podscompanion.ui.battery

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.podscompanion.data.aap.AapSessionState
import dev.podscompanion.data.aap.FailureReason
import dev.podscompanion.protocol.aap.ListeningMode
import dev.podscompanion.protocol.advertising.Capability
import dev.podscompanion.protocol.advertising.PodsModel
import dev.podscompanion.ui.R

/**
 * Прямое подключение ещё не работает: подключаемся, не вышло или нет разрешения.
 * Когда подключение есть, карточки нет: об этом говорит подзаголовок «Подключены напрямую».
 */
@Composable
fun AapStatusCard(state: AapSessionState?, noPermission: Boolean, onCheck: () -> Unit) {
    if (state is AapSessionState.Connected || (state == null && !noPermission)) return
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                when (state) {
                    is AapSessionState.Connecting -> CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    is AapSessionState.Failed -> Icon(Icons.Filled.LinkOff, null, tint = MaterialTheme.colorScheme.error)
                    else -> Icon(Icons.Filled.ErrorOutline, null, tint = MaterialTheme.colorScheme.error)
                }
                Text(statusText(state), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            }
            if (state is AapSessionState.Failed) {
                Text(
                    state.details,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FilledTonalButton(onClick = onCheck) { Text(stringResource(R.string.aap_check)) }
            }
        }
    }
}

/**
 * Режимы шумоподавления крупными плитками. Выделен режим, о котором сообщили наушники.
 * Значки нарисованы в [ModeIcon].
 */
@Composable
fun ModeTiles(modes: List<ListeningMode>, current: ListeningMode?, onSelect: (ListeningMode) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        modes.forEach { mode ->
            val selected = mode == current
            val colors = MaterialTheme.colorScheme
            Surface(
                onClick = { if (!selected) onSelect(mode) },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(22.dp),
                color = if (selected) colors.primaryContainer else colors.surfaceContainer,
                contentColor = if (selected) colors.onPrimaryContainer else colors.onSurfaceVariant,
            ) {
                Column(
                    Modifier.padding(horizontal = 4.dp, vertical = 14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Surface(
                        shape = CircleShape,
                        color = if (selected) colors.primary else colors.surfaceContainerHighest,
                        modifier = Modifier.size(44.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            ModeIcon(
                                mode,
                                color = if (selected) colors.onPrimary else colors.onSurface,
                                cutout = if (selected) colors.primary else colors.surfaceContainerHighest,
                            )
                        }
                    }
                    Text(
                        stringResource(mode.title()),
                        style = MaterialTheme.typography.labelMedium.copy(hyphens = Hyphens.Auto),
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                    )
                }
            }
        }
    }
}

/** Какие режимы показывать: у моделей без шумоподавления — никаких, Adaptive — только где он есть. */
internal fun availableModes(model: PodsModel?): List<ListeningMode> {
    val caps = model?.capabilities ?: return emptyList()
    if (Capability.NOISE_CONTROL !in caps) return emptyList()
    return buildList {
        add(ListeningMode.OFF)
        add(ListeningMode.TRANSPARENCY)
        if (Capability.ADAPTIVE_AUDIO in caps) add(ListeningMode.ADAPTIVE)
        add(ListeningMode.NOISE_CANCELLATION)
    }
}

internal fun ListeningMode.title() = when (this) {
    ListeningMode.OFF -> R.string.mode_tile_off
    ListeningMode.NOISE_CANCELLATION -> R.string.mode_tile_nc
    ListeningMode.TRANSPARENCY -> R.string.mode_tile_transparency
    ListeningMode.ADAPTIVE -> R.string.mode_tile_adaptive
    ListeningMode.UNKNOWN -> R.string.mode_unknown
}

@Composable
private fun statusText(state: AapSessionState?): String = when (state) {
    null, is AapSessionState.Connected -> stringResource(R.string.aap_no_permission)
    is AapSessionState.Connecting -> stringResource(R.string.aap_connecting, state.deviceName)
    is AapSessionState.Failed -> when (state.reason) {
        FailureReason.SOCKET_BLOCKED -> stringResource(R.string.aap_socket_blocked)
        FailureReason.CONNECTION_FAILED -> stringResource(R.string.aap_connection_failed, state.retryInSec)
    }
}
