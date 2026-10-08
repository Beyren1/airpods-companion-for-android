package dev.podscompanion.data

import dev.podscompanion.protocol.advertising.PodsModel
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
        /** Последний пакет с каждого адреса, для отладки. */
        val rawByAddress: LinkedHashMap<String, String> = LinkedHashMap(),
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
        // Только по признакам, не по адресу: один и тот же адрес Pro 2 шлёт то 100/100, то 70/70,
        // и при поиске по адресу заряд в карточке прыгал на секунду.
        val device = devices.firstOrNull { it.fingerprint.matches(fingerprint) }
        if (device == null) {
            devices += Device(mutableSetOf(address), fingerprint, status, nowMs, status.rssi).apply {
                addPacket(nowMs)
                rawByAddress[address] = status.rawHex
            }
        } else {
            device.addPacket(nowMs)
            device.rawByAddress[address] = status.rawHex
            device.addresses += address
            if (device.addresses.size > MAX_ADDRESSES) device.addresses.remove(device.addresses.first())
            device.rawByAddress.keys.retainAll(device.addresses)
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
     *   Если имя подключённого устройства не похоже ни на одну модель (переименовали), тоже ближайшие.
     */
    fun snapshot(nowMs: Long, connectedNames: List<String>?, connectedBatteries: List<Int> = emptyList()): NearbyPods {
        devices.removeAll { nowMs - it.seenAtMs > staleAfterMs }
        if (primary !in devices) primary = null

        val connected = connectedNames?.let { pickConnected(ConnectedNameMatcher.knownModels(it), connectedBatteries) }

        primary = when {
            connected != null -> connected
            connectedNames == null -> pickNearest()
            connectedNames.isEmpty() -> null
            // Подключена известная модель, но её пакетов нет (Max сняли, они «уснули»): чужие не показываем.
            ConnectedNameMatcher.knownModels(connectedNames).isNotEmpty() -> null
            // Имя не похоже ни на одну модель (переименовали): берём ближайшие.
            else -> pickNearest()
        }
        val main = primary

        // Один наушник подключённой пары иногда рекламирует другой заряд (Pro 2: 70/70 при реальных 100),
        // и такой «двойник» той же модели и цвета показывался в «Рядом». Пока пара подключена, скрываем его.
        fun isTwinOfConnected(device: Device) = connected != null && main === connected &&
            device.fingerprint.modelId == main.fingerprint.modelId &&
            device.fingerprint.colorCode == main.fingerprint.colorCode
        val others = devices
            .filter { it !== main && it.rssi >= minRssi && !isTwinOfConnected(it) }
            .sortedByDescending { it.rssi }
            .map { it.status.copy(rawByAddress = it.rawByAddress.toMap()) }
        val status = main?.status?.copy(
            connected = main === connected,
            packetIntervalMs = main.averageIntervalMs(),
            rawByAddress = main.rawByAddress.toMap(),
        )
        return NearbyPods(status, others)
    }

    /**
     * Наушники рядом, похожие на подключённые по имени. Если имя общее («AirPods») и подходят
     * несколько пар, выбираем надетые, затем с зарядом ближе к тому, что наушники сообщили телефону
     * (у Pro 2 один наушник рекламировал 70/70 при реальных 100), затем выбранные раньше,
     * затем с самым сильным сигналом.
     * Без этого при общем имени главная карточка появлялась на полсекунды, пока были видны
     * только одни наушники, и пропадала, когда приходили пакеты от вторых.
     */
    private fun pickConnected(models: Set<PodsModel>, batteries: List<Int>): Device? {
        val candidates = devices.filter { it.status.model in models }
        if (candidates.size <= 1) return candidates.firstOrNull()
        var best = candidates.filter { it.status.isWorn() }.ifEmpty { candidates }
        if (batteries.isNotEmpty()) {
            val distances = best.associateWith { batteryDistance(it.status, batteries) }
            val min = distances.values.filterNotNull().minOrNull()
            if (min != null) best = best.filter { distances[it] == min }
        }
        return primary?.takeIf { it in best } ?: best.maxByOrNull { it.rssi }
    }

    /** Насколько заряд в рекламе далёк от заряда, сообщённого телефону; null — сравнить не с чем. */
    private fun batteryDistance(status: PodsStatus, batteries: List<Int>): Int? {
        val advertised = listOfNotNull(status.left.battery, status.right.battery, status.primary.battery).map { it.percent }
        if (advertised.isEmpty()) return null
        // В рекламе шаг 10 % с округлением вниз: 99 % приходит как 90.
        return batteries.minOf { real -> advertised.minOf { abs(real / 10 * 10 - it) } }
    }

    private fun PodsStatus.isWorn() = left.inEar || right.inEar || primary.inEar

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

    // Заряд сравниваем обязательно: рядом часто бывают чужие наушники той же модели и цвета
    // (у Pro 2 так и было: своя пара 100/100, чужие 80/80 и 30/20).
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
