package dev.podscompanion.ui.battery

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.podscompanion.data.aap.AapSessionState
import dev.podscompanion.data.aap.FailureReason
import dev.podscompanion.protocol.aap.ListeningMode
import dev.podscompanion.ui.R

/**
 * Расширенный режим (прямое подключение AAP): статус, режим шумоподавления и кнопка проверки.
 * Когда наушники Apple не подключены, карточку не показываем: проверять нечего.
 */
@Composable
fun AapCard(state: AapSessionState, onCheck: () -> Unit) {
    if (state is AapSessionState.NoDevice) return

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                when (state) {
                    is AapSessionState.Connecting -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    is AapSessionState.Connected -> Icon(Icons.Filled.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
                    is AapSessionState.Failed -> Icon(Icons.Filled.LinkOff, null, tint = MaterialTheme.colorScheme.error)
                    AapSessionState.NoPermission -> Icon(Icons.Filled.ErrorOutline, null, tint = MaterialTheme.colorScheme.error)
                    AapSessionState.NoDevice -> Icon(Icons.Filled.Link, null)
                }
                Text(
                    stringResource(R.string.aap_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = 12.dp),
                )
            }
            Text(
                statusText(state),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (state is AapSessionState.Connected) {
                state.device.listeningMode?.let {
                    Text(stringResource(R.string.aap_listening_mode, modeText(it)), style = MaterialTheme.typography.bodyMedium)
                }
                state.device.conversationalAwareness?.let {
                    Text(
                        stringResource(if (it) R.string.aap_ca_on else R.string.aap_ca_off),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            if (state is AapSessionState.Failed) {
                FilledTonalButton(onClick = onCheck) { Text(stringResource(R.string.aap_check)) }
            }
        }
    }
}

@Composable
private fun statusText(state: AapSessionState): String = when (state) {
    AapSessionState.NoPermission -> stringResource(R.string.aap_no_permission)
    AapSessionState.NoDevice -> ""
    is AapSessionState.Connecting -> stringResource(R.string.aap_connecting, state.deviceName)
    is AapSessionState.Connected -> stringResource(R.string.aap_connected, state.deviceName)
    is AapSessionState.Failed -> when (state.reason) {
        FailureReason.SOCKET_BLOCKED -> stringResource(R.string.aap_socket_blocked)
        FailureReason.CONNECTION_FAILED -> stringResource(R.string.aap_connection_failed, state.retryInSec)
    } + "\n" + state.details
}

@Composable
private fun modeText(mode: ListeningMode): String = stringResource(
    when (mode) {
        ListeningMode.OFF -> R.string.mode_off
        ListeningMode.NOISE_CANCELLATION -> R.string.mode_nc
        ListeningMode.TRANSPARENCY -> R.string.mode_transparency
        ListeningMode.ADAPTIVE -> R.string.mode_adaptive
        ListeningMode.UNKNOWN -> R.string.mode_unknown
    },
)
