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
        val byAddress: LinkedHashMap<String, PodsStatus> = LinkedHashMap(),
        /** Последние пакеты пары: из них выбираем достоверный заряд (один адрес шлёт то 100, то 70). */
        val recent: ArrayDeque<PodsStatus> = ArrayDeque(),
    ) {
        fun addPacket(nowMs: Long) {
            packetTimes.addLast(nowMs)
            if (packetTimes.size > INTERVAL_WINDOW) packetTimes.removeFirst()
        }

        fun remember(status: PodsStatus) {
            recent.addLast(status)
            if (recent.size > RECENT_WINDOW) recent.removeFirst()
        }

        fun averageIntervalMs(): Long? =
            if (packetTimes.size < 2) null else (packetTimes.last() - packetTimes.first()) / (packetTimes.size - 1)
    }

    private val devices = mutableListOf<Device>()
    private var primary: Device? = null

    fun onPacket(address: String, fingerprint: PairFingerprint, status: PodsStatus, nowMs: Long) {
        val device = devices.firstOrNull { it.fingerprint.matches(fingerprint) }
        if (device == null) {
            devices += Device(mutableSetOf(address), fingerprint, status, nowMs, status.rssi).apply {
                addPacket(nowMs)
                remember(status)
                byAddress[address] = status
            }
        } else {
            device.addPacket(nowMs)
            device.remember(status)
            device.byAddress.remove(address)
            device.byAddress[address] = status
            device.addresses += address
            if (device.addresses.size > MAX_ADDRESSES) device.addresses.remove(device.addresses.first())
            device.byAddress.keys.retainAll(device.addresses)
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

        val others = devices
            .filter { it !== main && it.rssi >= minRssi }
            .sortedByDescending { it.rssi }
            .map { display(it, nowMs, emptyList()) }
        val status = main?.let { display(it, nowMs, if (it === connected) connectedBatteries else emptyList()) }
            ?.copy(connected = main === connected, packetIntervalMs = main.averageIntervalMs())
        return NearbyPods(status, others)
    }

    /**
     * Что показать для пары. Положение в ухе/кейсе берём из самого свежего пакета, а заряд из самого
     * достоверного: наушники одной пары рекламируют разное (Pro 2: 100/100 с одного наушника,
     * 70/70 и «правый 70, левый неизвестно» с другого при реальных 100 %). Достоверный — ближе всего
     * к заряду, который наушники сообщили телефону; если его нет — где известно больше значений,
     * а при равенстве — где заряд выше.
     */
    private fun display(device: Device, nowMs: Long, batteries: List<Int>): PodsStatus {
        val latest = device.status
        val fresh = device.recent.filter { nowMs - it.lastSeenMs <= staleAfterMs }.ifEmpty { listOf(latest) }
        val source = if (batteries.isNotEmpty()) {
            fresh.minBy { batteryDistance(it, batteries) ?: Int.MAX_VALUE }
        } else {
            fresh.maxWith(compareBy<PodsStatus>({ knownCount(it) }, { batterySum(it) }))
        }
        return latest.copy(
            left = latest.left.copy(battery = source.left.battery, charging = source.left.charging),
            right = latest.right.copy(battery = source.right.battery, charging = source.right.charging),
            rawByAddress = device.byAddress.mapValues { it.value.rawHex },
        )
    }

    private fun knownCount(s: PodsStatus) = listOfNotNull(s.left.battery, s.right.battery).size
    private fun batterySum(s: PodsStatus) = listOfNotNull(s.left.battery, s.right.battery).sumOf { it.percent }

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

    // Заряд не сравниваем: наушники одной пары Pro 2 рекламировали 100/100, 70/70 и «70 и неизвестно»,
    // и пара распадалась на несколько. Две пары одной модели и цвета рядом редки, их покажем как одну.
    private fun PairFingerprint.matches(other: PairFingerprint): Boolean =
        modelId == other.modelId && colorCode == other.colorCode

    companion object {
        const val DEFAULT_MIN_RSSI = -80
        const val DEFAULT_STICKINESS_DB = 8
        const val DEFAULT_STALE_AFTER_MS = 15_000L
        private const val MAX_ADDRESSES = 6
        private const val INTERVAL_WINDOW = 10
        private const val RECENT_WINDOW = 12
    }
}
