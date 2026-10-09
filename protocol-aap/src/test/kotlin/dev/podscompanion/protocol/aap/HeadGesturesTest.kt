package dev.podscompanion.protocol.aap

import com.google.common.truth.Truth.assertThat
import dev.podscompanion.protocol.util.Hex
import kotlin.math.PI
import kotlin.math.sin
import org.junit.jupiter.api.Test

class HeadGesturesTest {
    /** Синусоида по одной оси: [periods] колебаний за [durationMs], шаг 20 мс (50 Гц). */
    private fun motion(axis: Int, amplitude: Int, periods: Double, durationMs: Long = 1_000, base: IntArray = intArrayOf(1000, -2000, 500)) =
        (0..durationMs step 20).map { t ->
            val v = (amplitude * sin(2 * PI * periods * t / durationMs)).toInt()
            t to List(3) { if (it == axis) base[it] + v else base[it] + (t % 7).toInt() }
        }

    private val calibration = HeadCalibration(nodAxis = 1, shakeAxis = 0, nodThreshold = 800, shakeThreshold = 800)

    @Test
    fun `калибровка находит ось кивка и ось поворота`() {
        val nod = AxisRecorder().apply { motion(axis = 1, amplitude = 1500, periods = 2.0).forEach { add(it.second) } }
        val shake = AxisRecorder().apply { motion(axis = 2, amplitude = 1200, periods = 2.0).forEach { add(it.second) } }
        val result = HeadCalibrator.calibrate(nod, shake)!!
        assertThat(result.nodAxis).isEqualTo(1)
        assertThat(result.shakeAxis).isEqualTo(2)
        assertThat(result.nodThreshold).isAtLeast(1400)
    }

    @Test
    fun `без движения калибровки нет`() {
        val still = AxisRecorder().apply { repeat(50) { add(listOf(10, 20, 30)) } }
        assertThat(HeadCalibrator.calibrate(still, still)).isNull()
    }

    @Test
    fun `кивок и покачивание распознаются`() {
        val detector = HeadGestureDetector(calibration)
        val nod = motion(axis = 1, amplitude = 1200, periods = 1.0).mapNotNull { (t, o) -> detector.onSample(t, o) }
        assertThat(nod).containsExactly(HeadGesture.NOD)

        val detector2 = HeadGestureDetector(calibration)
        val shake = motion(axis = 0, amplitude = 1200, periods = 1.5).mapNotNull { (t, o) -> detector2.onSample(t, o) }
        assertThat(shake).containsExactly(HeadGesture.SHAKE)
    }

    @Test
    fun `простой поворот головы и мелкие движения не жест`() {
        val detector = HeadGestureDetector(calibration)
        // Повернул голову и оставил: движение в одну сторону.
        val turn = (0L..1_000L step 20).map { t -> t to listOf((t * 3).toInt(), 0, 0) }
        assertThat(turn.mapNotNull { (t, o) -> detector.onSample(t, o) }).isEmpty()
        // Мелкое покачивание при ходьбе.
        val walking = HeadGestureDetector(calibration)
        val small = motion(axis = 1, amplitude = 200, periods = 3.0)
        assertThat(small.mapNotNull { (t, o) -> walking.onSample(t, o) }).isEmpty()
    }

    @Test
    fun `переход угла через край 16 бит не ломает размах`() {
        val recorder = AxisRecorder()
        recorder.add(listOf(32_700, 0, 0))
        recorder.add(listOf(-32_700, 0, 0)) // на самом деле +136
        assertThat(recorder.ranges()[0]).isEqualTo(136)
    }

    @Test
    fun `пакет датчиков и переименование`() {
        val data = ByteArray(60).also {
            Hex.decode("04 00 04 00 17 00 00 00 10 00").copyInto(it)
            it[43] = 0x10; it[44] = 0x00       // 16
            it[45] = 0xF0.toByte(); it[46] = 0xFF.toByte() // −16
            it[51] = 0x02; it[52] = 0x01       // 258
        }
        val event = AapParser.parse(data) as AapEvent.HeadMotion
        assertThat(event.orientation).containsExactly(16, -16, 0).inOrder()
        assertThat(event.horizontal).isEqualTo(258)

        // Список блоков от AirPods 4 сразу после подключения — не датчики.
        val directory = Hex.decode(
            "04 00 04 00 17 00 00 00 04 00 00 34 00 06 00 00 06 41 50 00 00 00 80 00 00 41 4F 50 " +
                "00 00 80 00 00 52 54 50 00 00 80 00 00 42 54 4D 00 00 80 00 00 44 53 50 31 00 80 00 00 " +
                "44 53 50 32 00 80 00 00",
        )
        assertThat(AapParser.parse(directory)).isInstanceOf(AapEvent.Unknown::class.java)

        assertThat(Hex.encode(Aap.rename("Мои"))).isEqualTo("04 00 04 00 1A 00 01 06 00 D0 9C D0 BE D0 B8")
        assertThat(Aap.rename("x".repeat(40)).size).isEqualTo(9 + Aap.MAX_NAME_BYTES)
        assertThat(Aap.utf8Prefix("ййй", 5).toString(Charsets.UTF_8)).isEqualTo("йй")
    }
}
