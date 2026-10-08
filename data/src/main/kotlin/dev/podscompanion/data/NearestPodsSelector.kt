package dev.podscompanion.data

import kotlin.math.abs

/**
 * Признаки, по которым два пакета с разных MAC-адресов считаются одной парой наушников.
 * Каждый наушник рекламирует себя сам и со своего адреса, а «отправитель» меняется,
 * например, когда наушник вынимают из уха. Поэтому привязываться к одному адресу нельзя.
 */
data class PairFingerprint(
    val modelId: Int,
    val colorCode: Int,
    val leftPercent: Int?,
    val rightPercent: Int?,
)

/**
 * Рядом могут быть чужие AirPods. Пока нет ключа из AAP (которым Apple шифрует часть пакета),
 * свои отличаем эвристикой: берём самые близкие и дальше принимаем все пакеты той же пары
 * (та же модель, цвет и заряд L/R), с какого бы адреса они ни пришли и каким бы слабым ни был сигнал.
 *
 * Чистый класс без Android: время передаётся снаружи, поэтому легко тестируется.
 */
class NearestPodsSelector(
    private val minRssi: Int = DEFAULT_MIN_RSSI,
    private val stickinessDb: Int = DEFAULT_STICKINESS_DB,
    private val staleAfterMs: Long = DEFAULT_STALE_AFTER_MS,
) {
    private var current: Candidate? = null

    private data class Candidate(
        val addresses: Set<String>,
        val fingerprint: PairFingerprint,
        val rssi: Int,
        val seenAtMs: Long,
    )

    /** @return true, если пакет нужно показать. */
    fun accept(address: String, rssi: Int, fingerprint: PairFingerprint, nowMs: Long): Boolean {
        val cur = current
        val samePair = cur != null && (address in cur.addresses || cur.fingerprint.matches(fingerprint))

        val take = when {
            // Свои наушники в ушах и телефон в кармане: сигнал бывает −85 dBm и слабее, порог не применяем.
            samePair -> true
            rssi < minRssi -> false
            cur == null || nowMs - cur.seenAtMs > staleAfterMs -> true
            else -> rssi > cur.rssi + stickinessDb
        }
        if (take) {
            val addresses = if (samePair) cur!!.addresses + address else setOf(address)
            current = Candidate(addresses.toList().takeLast(MAX_ADDRESSES).toSet(), fingerprint, rssi, nowMs)
        }
        return take
    }

    private fun PairFingerprint.matches(other: PairFingerprint): Boolean =
        modelId == other.modelId &&
            colorCode == other.colorCode &&
            close(leftPercent, other.leftPercent) &&
            close(rightPercent, other.rightPercent)

    // null = наушник не на связи в одном из пакетов: по нему не судим.
    private fun close(a: Int?, b: Int?) = a == null || b == null || abs(a - b) <= 10

    companion object {
        const val DEFAULT_MIN_RSSI = -80
        const val DEFAULT_STICKINESS_DB = 8
        const val DEFAULT_STALE_AFTER_MS = 10_000L
        private const val MAX_ADDRESSES = 6
    }
}
