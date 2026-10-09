package dev.podscompanion.data.find

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class SignalMeterTest {

    @Test
    fun `нет пакетов — нет показаний`() {
        assertThat(SignalMeter().reading(1_000)).isNull()
    }

    @Test
    fun `пропали из эфира — показаний нет`() {
        val meter = SignalMeter(windowMs = 2_500)
        meter.add(-60, 0)
        assertThat(meter.reading(1_000)).isNotNull()
        assertThat(meter.reading(5_000)).isNull()
    }

    @Test
    fun `сигнал растёт — теплее`() {
        val meter = SignalMeter()
        for (t in 0L..4_000L step 500) meter.add(-80, t)
        for (t in 4_500L..8_000L step 500) meter.add(-65, t)
        val reading = meter.reading(8_000)!!
        assertThat(reading.trend).isEqualTo(Trend.WARMER)
        assertThat(reading.closeness).isEqualTo(Closeness.CLOSE)
    }

    @Test
    fun `сигнал падает — холоднее`() {
        val meter = SignalMeter()
        for (t in 0L..4_000L step 500) meter.add(-60, t)
        for (t in 4_500L..8_000L step 500) meter.add(-75, t)
        assertThat(meter.reading(8_000)!!.trend).isEqualTo(Trend.COLDER)
    }

    @Test
    fun `скачки вокруг одного уровня — без изменений`() {
        val meter = SignalMeter()
        var t = 0L
        repeat(20) { i ->
            meter.add(if (i % 2 == 0) -68 else -72, t)
            t += 400
        }
        assertThat(meter.reading(t - 400)!!.trend).isEqualTo(Trend.STEADY)
    }

    @Test
    fun `шкала тепла`() {
        assertThat(SignalMeter.heatOf(-100.0)).isEqualTo(0f)
        assertThat(SignalMeter.heatOf(-40.0)).isEqualTo(1f)
        assertThat(SignalMeter.heatOf(-70.0)).isWithin(0.01f).of(0.5f)
    }
}
