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
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.VectorPath
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

    /** Левый и правый наушник этой модели: Pro — с амбушюрой, AirPods 1–2 — с длинной ножкой. */
    fun budFor(model: PodsModel?, left: Boolean): Art = when (model) {
        PodsModel.AIRPODS_PRO, PodsModel.AIRPODS_PRO_2, PodsModel.AIRPODS_PRO_2_USB_C, PodsModel.AIRPODS_PRO_3 ->
            Art(if (left) BudProLeft else BudProRight, 22f, 31f)
        PodsModel.AIRPODS_1, PodsModel.AIRPODS_2 -> Art(if (left) BudClassicLeft else BudClassicRight, 21f, 40.5f)
        else -> Art(if (left) Bud4Left else Bud4Right, 21f, 29.5f)
    }

    val OverEarArt get() = Art(MaxArt, 44f, 44f)

    /** Кейс в настоящих пропорциях: у AirPods 4 почти квадратный, у Pro широкий, у AirPods 1–2 высокий. */
    fun caseFor(model: PodsModel?): Art = when (model) {
        PodsModel.AIRPODS_PRO, PodsModel.AIRPODS_PRO_2, PodsModel.AIRPODS_PRO_2_USB_C, PodsModel.AIRPODS_PRO_3 ->
            Art(CasePro, 60.6f, 45.2f)
        PodsModel.AIRPODS_1, PodsModel.AIRPODS_2 -> Art(CaseClassic, 44.3f, 53.5f)
        else -> Art(Case4, 50.1f, 46.2f)
    }

    // ---------- Цветные рисунки: светлый корпус, тёмный контур, серые тени ----------
    // Сгенерированы из одного описания (контуры в миллиметрах), левые наушники — зеркало правых.

    private val BODY = Color(0xFFF3F4F6)
    private val SHADE = Color(0xFFD6DAE0)
    private val SHADE2 = Color(0xFFAEB4BD)
    private val TIP = Color(0xFFC8CDD4)
    private val DARK = Color(0xFF3B4048)
    private val LINE = Color(0xFF2F333A)
    private val MESH = Color(0xFF5A616B)

    private val BudProRight: ImageVector by lazy {
        art("BudProRight", 22f, 31f) {
            fill("M1.3 9a4.2 5 0 1 0 8.4 0a4.2 5 0 1 0-8.4 0z", TIP)
            stroke("M1.3 9a4.2 5 0 1 0 8.4 0a4.2 5 0 1 0-8.4 0z", LINE, 0.7f)
            fill("M3.9 9a1.3 2.3 0 1 0 2.6 0a1.3 2.3 0 1 0-2.6 0z", DARK)
            fill("M8 6.5C9 2.5 13 1 16.5 1.8C20 2.6 21.4 6 21 9.5C20.6 12.6 18.8 14.6 17.2 15.4L17.2 28C17.2 29.8 14.2 29.8 14.2 28L14 16C10.6 15.6 7.4 12.6 7.6 9.4Z", BODY)
            fill("M17.6 3C20.2 4.2 21.2 7 20.8 9.6C20.4 12.2 19 13.9 17.2 14.9C18.8 12.4 19.4 8.6 17.6 3Z", SHADE)
            fill("M16 16.2h1.2V28c0 .9-.5 1.3-1.2 1.4z", SHADE)
            stroke("M8 6.5C9 2.5 13 1 16.5 1.8C20 2.6 21.4 6 21 9.5C20.6 12.6 18.8 14.6 17.2 15.4L17.2 28C17.2 29.8 14.2 29.8 14.2 28L14 16C10.6 15.6 7.4 12.6 7.6 9.4Z", LINE, 0.75f)
            fill("M12.6 5.4a1.2 .75 0 1 0 2.4 0a1.2 .75 0 1 0-2.4 0z", DARK)
            stroke("M14.3 26.2h2.8", LINE, 0.45f)
        }
    }

    private val Bud4Right: ImageVector by lazy {
        art("Bud4Right", 21f, 29.5f) {
            fill("M3 8C3 3.5 7 1.2 11.5 1.5C16.5 1.8 20 5 19.6 9.4C19.3 12.6 17.6 14.6 16 15.4L16 27C16 28.8 13 28.8 13 27L12.8 16.2C7.5 16 3 13 3 8Z", BODY)
            fill("M15.6 2.8C18.8 4.2 20 7 19.7 9.4C19.4 12 17.9 14 16 15C17.8 11.8 18.1 7 15.6 2.8Z", SHADE)
            fill("M14.8 16.2H16V27c0 .9-.5 1.3-1.2 1.4z", SHADE)
            stroke("M3 8C3 3.5 7 1.2 11.5 1.5C16.5 1.8 20 5 19.6 9.4C19.3 12.6 17.6 14.6 16 15.4L16 27C16 28.8 13 28.8 13 27L12.8 16.2C7.5 16 3 13 3 8Z", LINE, 0.75f)
            fill("M4.4 8.3a3.1 3.9 0 1 0 6.2 0a3.1 3.9 0 1 0-6.2 0z", DARK)
            fill("M5.6 8.3a1.9 2.7 0 1 0 3.8 0a1.9 2.7 0 1 0-3.8 0z", MESH)
            stroke("M13.05 25.4h2.9", LINE, 0.45f)
        }
    }

    private val BudClassicRight: ImageVector by lazy {
        art("BudClassicRight", 21f, 40.5f) {
            fill("M3 8C3 3.5 7 1.2 11.5 1.5C16.5 1.8 20 5 19.6 9.4C19.3 12.6 17.6 14.6 16 15.4L16 38C16 39.8 13 39.8 13 38L12.8 16.2C7.5 16 3 13 3 8Z", BODY)
            fill("M15.6 2.8C18.8 4.2 20 7 19.7 9.4C19.4 12 17.9 14 16 15C17.8 11.8 18.1 7 15.6 2.8Z", SHADE)
            fill("M14.8 16.2H16V38c0 .9-.5 1.3-1.2 1.4z", SHADE)
            stroke("M3 8C3 3.5 7 1.2 11.5 1.5C16.5 1.8 20 5 19.6 9.4C19.3 12.6 17.6 14.6 16 15.4L16 38C16 39.8 13 39.8 13 38L12.8 16.2C7.5 16 3 13 3 8Z", LINE, 0.75f)
            fill("M4.4 8.3a3.1 3.9 0 1 0 6.2 0a3.1 3.9 0 1 0-6.2 0z", DARK)
            fill("M5.6 8.3a1.9 2.7 0 1 0 3.8 0a1.9 2.7 0 1 0-3.8 0z", MESH)
            stroke("M13.05 36.4h2.9", LINE, 0.45f)
        }
    }

    private val CasePro: ImageVector by lazy {
        art("CasePro", 60.6f, 45.2f) {
            fill("M9.5 .5H51.1A9 9 0 0 1 60.1 9.5V32.7A12 12 0 0 1 48.1 44.7H12.5A12 12 0 0 1 .5 32.7V9.5A9 9 0 0 1 9.5 .5z", BODY)
            fill("M.5 29.700000000000003C1.5 42.7 7.199999999999999 43.900000000000006 12.5 43.900000000000006H48.1C53.400000000000006 43.900000000000006 59.1 42.7 60.1 29.700000000000003V32.7A12 12 0 0 1 48.1 44.7H12.5A12 12 0 0 1 .5 32.7z", SHADE)
            stroke("M58.2 11V32.2", SHADE, 2.2f)
            stroke("M9.5 .5H51.1A9 9 0 0 1 60.1 9.5V32.7A12 12 0 0 1 48.1 44.7H12.5A12 12 0 0 1 .5 32.7V9.5A9 9 0 0 1 9.5 .5z", LINE, 0.9f)
            stroke("M.6 14.5H60.0", LINE, 0.6f)
            fill("M.9 14.9H59.7V15.9H.9z", SHADE)
            fill("M29.400000000000002 26.166a.9 .9 0 1 0 1.8 0a.9 .9 0 1 0-1.8 0z", SHADE2)
        }
    }

    private val Case4: ImageVector by lazy {
        art("Case4", 50.1f, 46.2f) {
            fill("M10.5 .5H39.6A10 10 0 0 1 49.6 10.5V32.7A13 13 0 0 1 36.6 45.7H13.5A13 13 0 0 1 .5 32.7V10.5A10 10 0 0 1 10.5 .5z", BODY)
            fill("M.5 29.700000000000003C1.5 43.7 7.8 44.900000000000006 13.5 44.900000000000006H36.6C42.300000000000004 44.900000000000006 48.6 43.7 49.6 29.700000000000003V32.7A13 13 0 0 1 36.6 45.7H13.5A13 13 0 0 1 .5 32.7z", SHADE)
            stroke("M47.7 12V32.2", SHADE, 2.2f)
            stroke("M10.5 .5H39.6A10 10 0 0 1 49.6 10.5V32.7A13 13 0 0 1 36.6 45.7H13.5A13 13 0 0 1 .5 32.7V10.5A10 10 0 0 1 10.5 .5z", LINE, 0.9f)
            stroke("M.6 15.5H49.5", LINE, 0.6f)
            fill("M.9 15.9H49.2V16.9H.9z", SHADE)
            fill("M24.150000000000002 27.166a.9 .9 0 1 0 1.8 0a.9 .9 0 1 0-1.8 0z", SHADE2)
        }
    }

    private val CaseClassic: ImageVector by lazy {
        art("CaseClassic", 44.3f, 53.5f) {
            fill("M8.5 .5H35.8A8 8 0 0 1 43.8 8.5V42.0A11 11 0 0 1 32.8 53.0H11.5A11 11 0 0 1 .5 42.0V8.5A8 8 0 0 1 8.5 .5z", BODY)
            fill("M.5 39.0C1.5 51.0 6.6 52.2 11.5 52.2H32.8C37.699999999999996 52.2 42.8 51.0 43.8 39.0V42.0A11 11 0 0 1 32.8 53.0H11.5A11 11 0 0 1 .5 42.0z", SHADE)
            stroke("M41.9 10V41.5", SHADE, 2.2f)
            stroke("M8.5 .5H35.8A8 8 0 0 1 43.8 8.5V42.0A11 11 0 0 1 32.8 53.0H11.5A11 11 0 0 1 .5 42.0V8.5A8 8 0 0 1 8.5 .5z", LINE, 0.9f)
            stroke("M.6 15.5H43.699999999999996", LINE, 0.6f)
            fill("M.9 15.9H43.4V16.9H.9z", SHADE)
            fill("M21.25 29.939999999999998a.9 .9 0 1 0 1.8 0a.9 .9 0 1 0-1.8 0z", SHADE2)
        }
    }

    private val MaxArt: ImageVector by lazy {
        art("MaxArt", 64f, 64f) {
            stroke("M12.5 31V22A19.5 19.5 0 0 1 51.5 22V31", LINE, 4.4f)
            stroke("M12.5 31V22A19.5 19.5 0 0 1 51.5 22V31", SHADE, 2.8f)
            stroke("M18 30.5a14 13.5 0 0 1 28 0", LINE, 5.6f)
            stroke("M18 30.5a14 13.5 0 0 1 28 0", TIP, 4.2f)
            stroke("M20.5 25.5l2 1.6M24.5 21.5l1.4 2.1M29.5 19.3l.6 2.4M34.5 19.3l-.6 2.4M39.5 21.5l-1.4 2.1M43.5 25.5l-2 1.6", SHADE2, 0.6f)
            fill("M10.8 27h3.4v6h-3.4z", SHADE2)
            stroke("M10.8 27h3.4v6h-3.4z", LINE, 0.7f)
            fill("M49.8 27h3.4v6h-3.4z", SHADE2)
            stroke("M49.8 27h3.4v6h-3.4z", LINE, 0.7f)
            fill("M8 32h8a5 5 0 0 1 5 5v16a6 6 0 0 1-6 6H9a6 6 0 0 1-6-6V37a5 5 0 0 1 5-5z", BODY)
            fill("M17.5 32.4c2 .6 3.5 2.3 3.5 4.6v16c0 3-2.2 5.5-5 5.9c.9-1.4 1.5-3.4 1.5-5.9z", SHADE2)
            fill("M4 50c.4 4 2.6 6.3 5 6.6h3.5c-3 0-5.4-2.5-5.7-6.6z", SHADE)
            stroke("M8 32h8a5 5 0 0 1 5 5v16a6 6 0 0 1-6 6H9a6 6 0 0 1-6-6V37a5 5 0 0 1 5-5z", LINE, 0.8f)
            fill("M48 32h8a5 5 0 0 1 5 5v16a6 6 0 0 1-6 6h-6a6 6 0 0 1-6-6V37a5 5 0 0 1 5-5z", BODY)
            fill("M46.5 32.4c-2 .6-3.5 2.3-3.5 4.6v16c0 3 2.2 5.5 5 5.9c-.9-1.4-1.5-3.4-1.5-5.9z", SHADE2)
            fill("M60 50c-.4 4-2.6 6.3-5 6.6h-3.5c3 0 5.4-2.5 5.7-6.6z", SHADE)
            stroke("M48 32h8a5 5 0 0 1 5 5v16a6 6 0 0 1-6 6h-6a6 6 0 0 1-6-6V37a5 5 0 0 1 5-5z", LINE, 0.8f)
            fill("M55 28.6h3.2v3.6H55z", SHADE2)
            stroke("M55 28.6h3.2v3.6H55z", LINE, 0.6f)
        }
    }

    private val BudProLeft: ImageVector by lazy { mirrored("BudProLeft", 22f, 31f, BudProRight) }
    private val Bud4Left: ImageVector by lazy { mirrored("Bud4Left", 21f, 29.5f, Bud4Right) }
    private val BudClassicLeft: ImageVector by lazy { mirrored("BudClassicLeft", 21f, 40.5f, BudClassicRight) }

    /** Одноцветные значки (для мест, где нужен цвет темы): */

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
        fun fill(d: String, color: Color = Color.Black) {
            builder.addPath(
                pathData = PathParser().parsePathString(d).toNodes(),
                pathFillType = PathFillType.EvenOdd,
                fill = SolidColor(color),
            )
        }

        fun stroke(d: String, color: Color = Color.Black, width: Float, alpha: Float = 1f) {
            builder.addPath(
                pathData = PathParser().parsePathString(d).toNodes(),
                stroke = SolidColor(color),
                strokeAlpha = alpha,
                strokeLineWidth = width,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }

    }

    private fun icon(name: String, width: Float, height: Float, block: Builder.() -> Unit): ImageVector = art(name, width, height, block)

    private fun art(name: String, width: Float, height: Float, block: Builder.() -> Unit): ImageVector {
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

    /** Левый наушник — зеркальная копия правого: рисуем те же контуры в отражённой группе. */
    private fun mirrored(name: String, width: Float, height: Float, source: ImageVector): ImageVector {
        val builder = ImageVector.Builder(
            name = name,
            defaultWidth = width.dp,
            defaultHeight = height.dp,
            viewportWidth = width,
            viewportHeight = height,
        )
        builder.addGroup(name = "mirror", pivotX = width / 2, scaleX = -1f)
        for (node in source.root) if (node is VectorPath) {
            builder.addPath(
                pathData = node.pathData,
                pathFillType = node.pathFillType,
                fill = node.fill,
                stroke = node.stroke,
                strokeAlpha = node.strokeAlpha,
                strokeLineWidth = node.strokeLineWidth,
                strokeLineCap = node.strokeLineCap,
                strokeLineJoin = node.strokeLineJoin,
            )
        }
        builder.clearGroup()
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
