package dev.podscompanion.data

/**
 * Рядом могут быть чужие AirPods. Пока нет ключа из AAP (которым Apple шифрует часть пакета),
 * отличить свои можно только эвристикой: берём самый сильный сигнал и «прилипаем» к нему,
 * чтобы карточка не прыгала между двумя парами с близким RSSI.
 *
 * Чистый класс без Android: время передаётся снаружи, поэтому легко тестируется.
 */
class NearestPodsSelector(
    private val minRssi: Int = DEFAULT_MIN_RSSI,
    private val stickinessDb: Int = DEFAULT_STICKINESS_DB,
    private val staleAfterMs: Long = DEFAULT_STALE_AFTER_MS,
) {
    private var current: Candidate? = null

    private data class Candidate(val address: String, val rssi: Int, val seenAtMs: Long)

    /** @return true, если пакет от этого адреса нужно показать. */
    fun accept(address: String, rssi: Int, nowMs: Long): Boolean {
        if (rssi < minRssi) return false
        val cur = current
        val take = cur == null ||
            cur.address == address ||
            nowMs - cur.seenAtMs > staleAfterMs ||
            rssi > cur.rssi + stickinessDb
        if (take) current = Candidate(address, rssi, nowMs)
        return take
    }

    companion object {
        /** Примерно 1–2 метра. Подберём по реальным измерениям на Pixel 9. */
        const val DEFAULT_MIN_RSSI = -75
        const val DEFAULT_STICKINESS_DB = 8
        const val DEFAULT_STALE_AFTER_MS = 10_000L
    }
}
