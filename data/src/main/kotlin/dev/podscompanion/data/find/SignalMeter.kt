package dev.podscompanion.data.find

/** Насколько близко наушники по силе сигнала. Очень грубо: стены и тело сильно гасят сигнал. */
enum class Closeness { VERY_CLOSE, CLOSE, NEAR, FAR, VERY_FAR }

/** Сигнал за последние секунды стал сильнее (теплее), слабее (холоднее) или почти не изменился. */
enum class Trend { WARMER, COLDER, STEADY }

/** Что показать в «горячо/холодно». */
data class SignalReading(
    /** Сглаженный сигнал, дБм (от −100 — еле слышно, до −40 — вплотную). */
    val rssi: Int,
    val closeness: Closeness,
    val trend: Trend,
    /** 0..1 для шкалы: 0 — холодно, 1 — горячо. */
    val heat: Float,
)

/**
 * «Горячо/холодно» по силе сигнала рекламы наушников. Сигнал скачет от пакета к пакету на 5–10 дБ,
 * поэтому берём среднее за последние [windowMs], а «теплее/холоднее» решаем, сравнивая его со
 * средним [compareBackMs] назад: так шаг-другой в нужную сторону уже заметен, а случайный скачок нет.
 *
 * Чистый класс без Android: время передаётся снаружи, поэтому легко тестируется.
 */
class SignalMeter(
    private val windowMs: Long = 2_500,
    private val compareBackMs: Long = 4_000,
    private val trendDb: Double = 3.0,
) {
    private val samples = ArrayDeque<Pair<Long, Int>>()

    fun add(rssi: Int, nowMs: Long) {
        samples.addLast(nowMs to rssi)
        while (samples.isNotEmpty() && nowMs - samples.first().first > windowMs + compareBackMs) samples.removeFirst()
    }

    fun reset() = samples.clear()

    /** null — сигнала не было последние [windowMs] (наушники пропали из эфира). */
    fun reading(nowMs: Long): SignalReading? {
        val current = average(nowMs - windowMs, nowMs) ?: return null
        val before = average(nowMs - windowMs - compareBackMs, nowMs - compareBackMs)
        val trend = when {
            before == null -> Trend.STEADY
            current - before >= trendDb -> Trend.WARMER
            before - current >= trendDb -> Trend.COLDER
            else -> Trend.STEADY
        }
        val rssi = Math.round(current).toInt()
        return SignalReading(rssi, closenessOf(rssi), trend, heatOf(current))
    }

    private fun average(fromMs: Long, toMs: Long): Double? {
        val values = samples.filter { it.first in fromMs..toMs }.map { it.second }
        return if (values.isEmpty()) null else values.average()
    }

    companion object {
        fun closenessOf(rssi: Int): Closeness = when {
            rssi >= -55 -> Closeness.VERY_CLOSE
            rssi >= -65 -> Closeness.CLOSE
            rssi >= -75 -> Closeness.NEAR
            rssi >= -85 -> Closeness.FAR
            else -> Closeness.VERY_FAR
        }

        /** −95 дБм и слабее — 0, −45 и сильнее — 1. */
        fun heatOf(rssi: Double): Float = ((rssi + 95) / 50).coerceIn(0.0, 1.0).toFloat()
    }
}
