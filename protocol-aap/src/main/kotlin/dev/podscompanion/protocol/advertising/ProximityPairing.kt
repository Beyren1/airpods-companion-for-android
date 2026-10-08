package dev.podscompanion.protocol.advertising

import dev.podscompanion.protocol.util.u8

/** Константы Apple Continuity в BLE advertising. */
object AppleAdvertising {
    /** Bluetooth SIG company ID Apple. В пакете идёт little-endian: 4C 00. */
    const val COMPANY_ID = 0x004C
    const val TYPE_PROXIMITY_PAIRING = 0x07
    const val PROXIMITY_PAIRING_LENGTH = 0x19 // 25 байт полезной нагрузки
}

/** Уровень заряда: 0..100 с шагом 10, или null, если наушник не на связи (nibble 0xF). */
@JvmInline
value class BatteryLevel(val percent: Int)

data class PodState(
    val battery: BatteryLevel?,
    val charging: Boolean,
    val inEar: Boolean,
)

/**
 * Разобранный пакет Proximity Pairing.
 *
 * Раскладка (индексы от байта типа 0x07; Android отдаёт manufacturer data уже без 4C 00):
 * ```
 * 0      тип = 0x07
 * 1      длина = 0x19
 * 2      префикс
 * 3..4   model ID (big-endian)
 * 5      статус (ухо, какой наушник основной)
 * 6      заряд: младший nibble = основной наушник, старший = второй
 * 7      старший nibble = флаги зарядки, младший = заряд кейса
 * 8      счётчик открытий крышки
 * 9      цвет
 * 10     суффикс
 * 11..26 зашифрованная часть (16 байт, без ключа не читаем)
 * ```
 */
data class ProximityPairingMessage(
    val modelId: Int,
    val model: PodsModel?,
    val primaryIsLeft: Boolean,
    val left: PodState,
    val right: PodState,
    val caseBattery: BatteryLevel?,
    val caseCharging: Boolean,
    val lidCounter: Int,
    val colorCode: Int,
    val raw: ByteArray,
) {
    // ByteArray сравнивается по ссылке, поэтому equals/hashCode переопределены по содержимому.
    override fun equals(other: Any?): Boolean =
        other is ProximityPairingMessage &&
            modelId == other.modelId && primaryIsLeft == other.primaryIsLeft &&
            left == other.left && right == other.right &&
            caseBattery == other.caseBattery && caseCharging == other.caseCharging &&
            lidCounter == other.lidCounter && colorCode == other.colorCode &&
            raw.contentEquals(other.raw)

    override fun hashCode(): Int = raw.contentHashCode()
}

object ProximityPairingParser {
    private const val STATUS_PRIMARY_IN_EAR = 0x02
    private const val STATUS_SECONDARY_IN_EAR = 0x08
    private const val STATUS_PRIMARY_IS_LEFT = 0x20

    private const val CHARGING_PRIMARY = 0x1
    private const val CHARGING_SECONDARY = 0x2
    private const val CHARGING_CASE = 0x4

    /**
     * @param data manufacturer-specific data для company ID 0x004C (без самих байт 4C 00),
     *   как её возвращает `ScanRecord.getManufacturerSpecificData(0x004C)`.
     * @return null, если это не Proximity Pairing (Apple шлёт и другие типы: iBeacon, Handoff, ...).
     */
    fun parse(data: ByteArray): ProximityPairingMessage? {
        if (data.size < 2 + AppleAdvertising.PROXIMITY_PAIRING_LENGTH) return null
        if (data.u8(0) != AppleAdvertising.TYPE_PROXIMITY_PAIRING) return null
        if (data.u8(1) != AppleAdvertising.PROXIMITY_PAIRING_LENGTH) return null

        val modelId = (data.u8(3) shl 8) or data.u8(4)
        val status = data.u8(5)
        val batteries = data.u8(6)
        val chargeAndCase = data.u8(7)

        val primaryIsLeft = status and STATUS_PRIMARY_IS_LEFT != 0
        val chargingFlags = chargeAndCase shr 4

        val primary = PodState(
            battery = nibbleToBattery(batteries and 0x0F),
            charging = chargingFlags and CHARGING_PRIMARY != 0,
            inEar = status and STATUS_PRIMARY_IN_EAR != 0,
        )
        val secondary = PodState(
            battery = nibbleToBattery(batteries shr 4),
            charging = chargingFlags and CHARGING_SECONDARY != 0,
            inEar = status and STATUS_SECONDARY_IN_EAR != 0,
        )

        return ProximityPairingMessage(
            modelId = modelId,
            model = PodsModel.fromId(modelId),
            primaryIsLeft = primaryIsLeft,
            left = if (primaryIsLeft) primary else secondary,
            right = if (primaryIsLeft) secondary else primary,
            caseBattery = nibbleToBattery(chargeAndCase and 0x0F),
            caseCharging = chargingFlags and CHARGING_CASE != 0,
            lidCounter = data.u8(8),
            colorCode = data.u8(9),
            raw = data.copyOf(),
        )
    }

    /** 0..10 означает 0..100 %, 15 означает «нет данных». Остальное считаем мусором. */
    private fun nibbleToBattery(nibble: Int): BatteryLevel? =
        if (nibble in 0..10) BatteryLevel(nibble * 10) else null
}
