package dev.podscompanion.data

import kotlin.math.abs

/**
 * Признаки, по которым два пакета с разных MAC-адресов считаются одной парой наушников.
 * Каждый наушник рекламирует себя сам и со своего адреса, а «отправитель» меняется,
 * например, когда наушник вынимают из уха.
 */
data class PairFingerprint(
    val modelId: Int,
    val colorCode: Int,
    val leftPercent: Int?,
    val rightPercent: Int?,
)

/** Все наушники рядом: [primary] показывается крупно, для него работают уведомление и автопауза. */
data class NearbyPods(
    val primary: PodsStatus?,
    val others: List<PodsStatus>,
) {
    companion object {
        val EMPTY = NearbyPods(null, emptyList())
    }
}

/**
 * Собирает пакеты в устройства и выбирает главное.
 *
 * Главное — подключённое к телефону (по имени A2DP-устройства, см. [ConnectedNameMatcher]).
 * Если подключённых нет или имя не помогло, главное — ближайшее, с «прилипанием», чтобы карточка
 * не прыгала между двумя парами с близким сигналом. Чужие наушники со слабым сигналом не показываем.
 *
 * Чистый класс без Android: время передаётся снаружи, поэтому легко тестируется.
 */
class NearbyPodsTracker(
    private val minRssi: Int = DEFAULT_MIN_RSSI,
    private val stickinessDb: Int = DEFAULT_STICKINESS_DB,
    private val staleAfterMs: Long = DEFAULT_STALE_AFTER_MS,
) {
    private class Device(
        val addresses: MutableSet<String>,
        var fingerprint: PairFingerprint,
        var status: PodsStatus,
        var seenAtMs: Long,
        /** Сглаженный сигнал: растёт сразу, падает медленно (сигнал скачет от пакета к пакету и между наушниками). */
        var rssi: Int,
        /** Время последних пакетов: из них считаем, как часто наушники шлют advertising. */
        val packetTimes: ArrayDeque<Long> = ArrayDeque(),
    ) {
        fun addPacket(nowMs: Long) {
            packetTimes.addLast(nowMs)
            if (packetTimes.size > INTERVAL_WINDOW) packetTimes.removeFirst()
        }

        fun averageIntervalMs(): Long? =
            if (packetTimes.size < 2) null else (packetTimes.last() - packetTimes.first()) / (packetTimes.size - 1)
    }

    private val devices = mutableListOf<Device>()
    private var primary: Device? = null

    fun onPacket(address: String, fingerprint: PairFingerprint, status: PodsStatus, nowMs: Long) {
        val device = devices.firstOrNull { address in it.addresses || it.fingerprint.matches(fingerprint) }
        if (device == null) {
            devices += Device(mutableSetOf(address), fingerprint, status, nowMs, status.rssi).apply { addPacket(nowMs) }
        } else {
            device.addPacket(nowMs)
            device.addresses += address
            if (device.addresses.size > MAX_ADDRESSES) device.addresses.remove(device.addresses.first())
            device.fingerprint = fingerprint
            device.status = status
            device.seenAtMs = nowMs
            device.rssi = if (status.rssi > device.rssi) status.rssi else (device.rssi * 3 + status.rssi) / 4
        }
    }

    /**
     * @param connectedNames имена наушников, подключённых к телефону по A2DP;
     *   пустой список — ничего не подключено, тогда главных нет, все наушники идут в «рядом»;
     *   null — неизвестно (нет разрешения BLUETOOTH_CONNECT), тогда главные — ближайшие.
     *   Если подключено что-то, но по имени не совпало (переименовали), тоже берём ближайшие.
     */
    fun snapshot(nowMs: Long, connectedNames: List<String>?): NearbyPods {
        devices.removeAll { nowMs - it.seenAtMs > staleAfterMs }
        if (primary !in devices) primary = null

        val connectedModel = connectedNames?.let { names ->
            ConnectedNameMatcher.bestMatch(devices.mapNotNull { it.status.model }, names)
        }
        val connected = connectedModel?.let { model -> devices.filter { it.status.model == model }.maxByOrNull { it.rssi } }

        primary = when {
            connected != null -> connected
            connectedNames != null && connectedNames.isEmpty() -> null
            else -> pickNearest()
        }
        val main = primary

        val others = devices
            .filter { it !== main && it.rssi >= minRssi }
            .sortedByDescending { it.rssi }
            .map { it.status }
        val status = main?.status?.copy(connected = main === connected, packetIntervalMs = main.averageIntervalMs())
        return NearbyPods(status, others)
    }

    private fun pickNearest(): Device? {
        val current = primary
        val strongest = devices.filter { it.rssi >= minRssi }.maxByOrNull { it.rssi }
        return when {
            // Свои наушники в ушах при телефоне в кармане дают слабый сигнал: уже выбранные не бросаем.
            current == null -> strongest
            strongest == null || strongest === current -> current
            strongest.rssi > current.rssi + stickinessDb -> strongest
            else -> current
        }
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
        const val DEFAULT_STALE_AFTER_MS = 15_000L
        private const val MAX_ADDRESSES = 6
        private const val INTERVAL_WINDOW = 10
    }
}
