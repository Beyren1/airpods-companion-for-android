package dev.podscompanion.ui.battery

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.podscompanion.data.media.NowPlaying
import dev.podscompanion.ui.R

/** Кнопки карточки трека. */
enum class MediaButton { PREVIOUS, PLAY_PAUSE, NEXT }

/**
 * Текущий трек: обложка, название, исполнитель, приложение и кнопки «назад», «пауза», «вперёд».
 * [container] — цвет фона: на главном экране как у остальных карточек, в окне кейса чуть темнее.
 */
@Composable
fun NowPlayingCard(
    track: NowPlaying,
    onButton: (MediaButton) -> Unit,
    modifier: Modifier = Modifier,
    container: Color = MaterialTheme.colorScheme.surfaceContainer,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = container),
    ) {
        Row(
            Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Cover(track)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    track.title ?: stringResource(R.string.now_playing_unknown),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                track.artist?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    track.appName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { onButton(MediaButton.PREVIOUS) }, enabled = track.canSkipPrevious) {
                    Icon(Icons.Filled.SkipPrevious, stringResource(R.string.media_previous))
                }
                FilledIconButton(onClick = { onButton(MediaButton.PLAY_PAUSE) }) {
                    if (track.playing) Icon(Icons.Filled.Pause, stringResource(R.string.media_pause))
                    else Icon(Icons.Filled.PlayArrow, stringResource(R.string.media_play))
                }
                IconButton(onClick = { onButton(MediaButton.NEXT) }, enabled = track.canSkipNext) {
                    Icon(Icons.Filled.SkipNext, stringResource(R.string.media_next))
                }
            }
        }
    }
}

@Composable
private fun Cover(track: NowPlaying) {
    val art = track.art
    val image = remember(art) { art?.asImageBitmap() }
    Box(
        Modifier
            .size(56.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        if (image != null) {
            Image(image, null, contentScale = ContentScale.Crop, modifier = Modifier.size(56.dp))
        } else {
            Icon(Icons.Filled.MusicNote, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
