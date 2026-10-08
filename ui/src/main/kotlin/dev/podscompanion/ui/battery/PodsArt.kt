package dev.podscompanion.ui.battery

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.podscompanion.protocol.aap.ListeningMode

/**
 * Свои упрощённые рисунки наушников: вкладыш-«капля», кейс и накладные.
 * Это не изображения Apple. Цвет задаёт tint у Icon, поэтому все фигуры одноцветные,
 * а «дырки» (сетка динамика, индикатор кейса) вырезаны правилом EvenOdd.
 */
object PodsArt {
    val BudLeft: ImageVector by lazy {
        icon("BudLeft", 48f, 64f) {
            fill(
                "M14 6c8-4 20 0 20 12 0 7-3 11-7 13v27a4 4 0 0 1-8 0V31c-6-1-12-6-12-13 0-6 3-10 7-12z" +
                    "M29 17a4 4 0 1 0-8 0a4 4 0 1 0 8 0z",
            )
        }
    }

    val BudRight: ImageVector by lazy {
        icon("BudRight", 48f, 64f) {
            fill(
                "M34 6c-8-4-20 0-20 12 0 7 3 11 7 13v27a4 4 0 0 0 8 0V31c6-1 12-6 12-13 0-6-3-10-7-12z" +
                    "M19 17a4 4 0 1 1 8 0a4 4 0 1 1-8 0z",
            )
        }
    }

    val Case: ImageVector by lazy {
        icon("Case", 64f, 56f) {
            fill("M20 6h24a16 16 0 0 1 15.5 14H4.5A16 16 0 0 1 20 6z")
            fill("M4 23h56v11a16 16 0 0 1-16 16H20A16 16 0 0 1 4 34z" + "M34.5 36a2.5 2.5 0 1 0-5 0a2.5 2.5 0 1 0 5 0z")
        }
    }

    val OverEar: ImageVector by lazy {
        icon("OverEar", 48f, 48f) {
            stroke("M8 28a16 16 0 0 1 32 0", width = 4f)
            fill("M9 25h1a5 5 0 0 1 5 5v7a5 5 0 0 1-5 5h-1a5 5 0 0 1-5-5v-7a5 5 0 0 1 5-5z")
            fill("M38 25h1a5 5 0 0 1 5 5v7a5 5 0 0 1-5 5h-1a5 5 0 0 1-5-5v-7a5 5 0 0 1 5-5z")
        }
    }

    private class Builder(val builder: ImageVector.Builder) {
        fun fill(d: String) {
            builder.addPath(
                pathData = PathParser().parsePathString(d).toNodes(),
                pathFillType = PathFillType.EvenOdd,
                fill = SolidColor(Color.Black),
            )
        }

        fun stroke(d: String, width: Float) {
            builder.addPath(
                pathData = PathParser().parsePathString(d).toNodes(),
                stroke = SolidColor(Color.Black),
                strokeLineWidth = width,
                strokeLineCap = StrokeCap.Round,
            )
        }
    }

    private fun icon(name: String, width: Float, height: Float, block: Builder.() -> Unit): ImageVector {
        val builder = ImageVector.Builder(
            name = name,
            defaultWidth = width.dp,
            defaultHeight = height.dp,
            viewportWidth = width,
            viewportHeight = height,
        )
        Builder(builder).block()
        return builder.build()
    }
}

/**
 * Значок режима шумоподавления, нарисованный кодом (пунктир в векторных ресурсах Android не умеет):
 * выкл — пустой круг, прозрачность — точка и пунктирные круги (звук проходит),
 * адаптивный — наполовину сплошной, наполовину пунктирный, шумоподавление — сплошной круг.
 */
@Composable
fun ModeIcon(mode: ListeningMode, color: Color, cutout: Color, size: Dp = 24.dp) {
    Canvas(Modifier.size(size)) {
        val c = Offset(this.size.width / 2, this.size.height / 2)
        val unit = this.size.minDimension / 24f
        val dash = PathEffect.dashPathEffect(floatArrayOf(2f * unit, 2.4f * unit))
        when (mode) {
            ListeningMode.OFF -> drawCircle(color, 8f * unit, c, style = Stroke(2f * unit))
            ListeningMode.TRANSPARENCY -> {
                drawCircle(color, 3f * unit, c)
                drawCircle(color, 6.5f * unit, c, style = Stroke(1.6f * unit, pathEffect = dash))
                drawCircle(color, 10f * unit, c, style = Stroke(1.6f * unit, pathEffect = dash))
            }
            ListeningMode.ADAPTIVE -> {
                val r = 9.5f * unit
                val topLeft = Offset(c.x - r, c.y - r)
                drawCircle(color, 3.2f * unit, c)
                drawArc(color, -90f, 180f, false, topLeft, Size(2 * r, 2 * r), style = Stroke(2f * unit))
                drawArc(color, 90f, 180f, false, topLeft, Size(2 * r, 2 * r), style = Stroke(1.6f * unit, pathEffect = dash))
            }
            ListeningMode.NOISE_CANCELLATION -> {
                drawCircle(color, 9.5f * unit, c)
                drawCircle(cutout, 5f * unit, c, alpha = 0.35f)
            }
            ListeningMode.UNKNOWN -> drawCircle(color, 2f * unit, c)
        }
    }
}
