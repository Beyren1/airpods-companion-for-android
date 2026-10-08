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
import dev.podscompanion.protocol.advertising.PodsModel

/**
 * Свои упрощённые рисунки наушников: вкладыш-«капля», кейс и накладные.
 * Это не изображения Apple. Цвет задаёт tint у Icon, поэтому все фигуры одноцветные,
 * а «дырки» (сетка динамика, индикатор кейса) вырезаны правилом EvenOdd.
 */
object PodsArt {
    /** Рисунок и его реальный размер в миллиметрах: по нему части рисуются в одном масштабе. */
    data class Art(val image: ImageVector, val widthMm: Float, val heightMm: Float)

    val LeftBud get() = Art(BudLeft, 23f, 31f)
    val RightBud get() = Art(BudRight, 23f, 31f)
    val OverEarArt get() = Art(OverEar, 44f, 44f)

    /** Кейс в настоящих пропорциях: у AirPods 4 маленький и почти квадратный, у Pro широкий. */
    fun caseFor(model: PodsModel?): Art = when (model) {
        PodsModel.AIRPODS_4, PodsModel.AIRPODS_4_ANC -> Art(Case4, 50.1f, 46.2f)
        PodsModel.AIRPODS_PRO, PodsModel.AIRPODS_PRO_2, PodsModel.AIRPODS_PRO_2_USB_C, PodsModel.AIRPODS_PRO_3 ->
            Art(CasePro, 60.6f, 45.2f)
        else -> Art(Case, 54f, 47f)
    }

    /** Кейс Pro: 60,6 × 45,2 мм, крышка около трети высоты, индикатор спереди. */
    private val CasePro: ImageVector by lazy {
        icon("CasePro", 60.6f, 45.2f) {
            fill("M14 0h32.6a14 13 0 0 1 14 13H0a14 13 0 0 1 14-13z")
            fill("M0 14.4h60.6V31.2a14 14 0 0 1-14 14H14a14 14 0 0 1-14-14z" + "M31.5 22a1.2 1.2 0 1 0-2.4 0a1.2 1.2 0 1 0 2.4 0z")
        }
    }

    /** Кейс AirPods 4: 50,1 × 46,2 мм, сильнее скруглён, без кнопки. */
    private val Case4: ImageVector by lazy {
        icon("Case4", 50.1f, 46.2f) {
            fill("M18 0h14.1a18 15 0 0 1 18 15H0a18 15 0 0 1 18-15z")
            fill("M0 16.4h50.1V28.2a18 18 0 0 1-18 18H18a18 18 0 0 1-18-18z" + "M26.25 25a1.2 1.2 0 1 0-2.4 0a1.2 1.2 0 1 0 2.4 0z")
        }
    }

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

    /**
     * Полноразмерные: жёсткая дуга, под ней сетчатый оголовок (полупрозрачный), короткие штанги,
     * вытянутые чашки с полоской амбушюры с внутренней стороны и колёсико на правой чашке.
     */
    val OverEar: ImageVector by lazy {
        icon("OverEar", 64f, 64f) {
            stroke("M14 31V24a18 18 0 0 1 36 0v7", width = 3.5f)
            stroke("M18 31a14 13 0 0 1 28 0", width = 4.5f, alpha = 0.55f)
            fill("M12.5 28h3v5h-3z")
            fill("M48.5 28h3v5h-3z")
            fill("M10 32h8a5 5 0 0 1 5 5v16a5 5 0 0 1-5 5h-8a5 5 0 0 1-5-5V37a5 5 0 0 1 5-5z" + "M18.5 36h1.6v18h-1.6z")
            fill("M46 32h8a5 5 0 0 1 5 5v16a5 5 0 0 1-5 5h-8a5 5 0 0 1-5-5V37a5 5 0 0 1 5-5z" + "M43.9 36h1.6v18h-1.6z")
            fill("M54 28.5h3.5v3.5h-3.5z")
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

        fun stroke(d: String, width: Float, alpha: Float = 1f) {
            builder.addPath(
                pathData = PathParser().parsePathString(d).toNodes(),
                stroke = SolidColor(Color.Black),
                strokeAlpha = alpha,
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
