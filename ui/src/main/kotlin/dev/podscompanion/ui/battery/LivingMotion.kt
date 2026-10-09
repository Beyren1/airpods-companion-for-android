package dev.podscompanion.ui.battery

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.State
import androidx.compose.ui.graphics.Color
import dev.podscompanion.protocol.advertising.PodState
import dev.podscompanion.protocol.aap.ListeningMode

/**
 * Как «живёт» картинка части на главном экране:
 * в ухе она медленно дышит, вынутый наушник отъезжает в свою сторону и наклоняется,
 * снятые Max чуть опускаются. В кейсе картинка стоит спокойно.
 */
internal enum class PartMotion { Still, Breathe, OutLeft, OutRight, Off }

/** Движение наушника по его состоянию. [left] — с какой стороны он нарисован. */
internal fun budMotion(pod: PodState, left: Boolean): PartMotion = when {
    pod.inEar -> PartMotion.Breathe
    pod.inCase || pod.battery == null -> PartMotion.Still
    left -> PartMotion.OutLeft
    else -> PartMotion.OutRight
}

/**
 * Общий «вдох» для всех частей сразу: 0 — выдох, 1 — вдох. Один на экран,
 * чтобы левый и правый наушники дышали в такт, а не вразнобой.
 * Значение читается только внутри graphicsLayer, поэтому экран не перерисовывается целиком на каждом кадре.
 */
@Composable
internal fun rememberBreath(): State<Float> =
    rememberInfiniteTransition(label = "breath").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2_400, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "breath",
    )

/**
 * Цвета режима шумоподавления: у каждого режима свой оттенок из палитры Material You,
 * поэтому смена режима видна как плавная смена цвета.
 * [container]/[content] — фон и текст плитки, [accent]/[onAccent] — кружок со значком.
 */
@Immutable
internal data class ModeColors(val container: Color, val content: Color, val accent: Color, val onAccent: Color)

internal fun ColorScheme.forMode(mode: ListeningMode?): ModeColors = when (mode) {
    ListeningMode.NOISE_CANCELLATION -> ModeColors(primaryContainer, onPrimaryContainer, primary, onPrimary)
    ListeningMode.TRANSPARENCY -> ModeColors(tertiaryContainer, onTertiaryContainer, tertiary, onTertiary)
    ListeningMode.ADAPTIVE -> ModeColors(secondaryContainer, onSecondaryContainer, secondary, onSecondary)
    else -> ModeColors(surfaceContainerHighest, onSurface, onSurfaceVariant, surface)
}
