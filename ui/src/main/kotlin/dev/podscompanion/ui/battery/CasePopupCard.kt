package dev.podscompanion.ui.battery

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.podscompanion.data.PodsStatus
import dev.podscompanion.data.media.NowPlaying
import dev.podscompanion.ui.R

/**
 * Карточка, которая всплывает снизу, когда открыли кейс рядом с телефоном: название наушников,
 * рисунки левого, кейса и правого с зарядом, текущий трек (если включён) и кнопка «Готово».
 */
@Composable
fun CasePopupCard(
    status: PodsStatus,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    nowPlaying: NowPlaying? = null,
    onMediaButton: (MediaButton) -> Unit = {},
) {
    val model = status.model
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(32.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
    ) {
        Column(
            Modifier.padding(horizontal = 12.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                model?.displayName ?: stringResource(R.string.unknown_model, status.modelId),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Row(Modifier.fillMaxWidth()) {
                Part(
                    PodsArt.budFor(model, left = true), stringResource(R.string.left), status.left.battery, status.left.charging,
                    note = null, highlighted = false, exact = status.exactBattery, modifier = Modifier.weight(1f),
                )
                Part(
                    PodsArt.caseFor(model), stringResource(R.string.case_label), status.caseBattery, status.caseCharging,
                    note = null, highlighted = false, exact = status.aap?.case != null || status.exactFromAdvert, modifier = Modifier.weight(1f),
                )
                Part(
                    PodsArt.budFor(model, left = false), stringResource(R.string.right), status.right.battery, status.right.charging,
                    note = null, highlighted = false, exact = status.exactBattery, modifier = Modifier.weight(1f),
                )
            }
            nowPlaying?.let {
                NowPlayingCard(
                    it, onMediaButton,
                    modifier = Modifier.padding(horizontal = 8.dp),
                    container = MaterialTheme.colorScheme.surfaceContainerHighest,
                )
            }
            Button(onClick = onClose, modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                Text(stringResource(R.string.case_popup_done))
            }
        }
    }
}
