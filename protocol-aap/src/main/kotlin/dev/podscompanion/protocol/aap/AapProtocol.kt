package dev.podscompanion.protocol.aap

import dev.podscompanion.protocol.util.u8

/**
 * Apple Accessory Protocol (AAP) поверх Bluetooth Classic L2CAP, PSM 0x1001.
 *
 * Формат описан по публичному реверсу (документация LibrePods и др.), код написан с нуля.
 * Почти все пакеты выглядят так: `04 00 04 00 <opcode LE16> <payload>`.
 * Значения, в которых мы не уверены, помечены «проверить по дампам»: журнал AAP в отладке
 * покажет реальные байты с ваших наушников.
 */
object Aap {
    const val PSM = 0x1001

    /** Сервис AAP в SDP-записи AirPods/Beats: по нему отличаем наушники Apple от прочих. */
    const val SERVICE_UUID = "74ec2172-0bad-4d01-8f77-997b2be0722a"

    private val HEADER = byteArrayOf(0x04, 0x00, 0x04, 0x00)

    /** Первый пакет после подключения: без него наушники не отвечают. */
    val HANDSHAKE: ByteArray = bytes(0x00, 0x00, 0x04, 0x00, 0x01, 0x00, 0x02, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)

    /**
     * Включает расширенные функции: адаптацию к разговору во время музыки, адаптивную прозрачность.
     * Флаги 0xD7 — как в приложении LibrePods, где датчики головы работают (0xFF с Mac не помог).
     */
    val SET_FEATURES: ByteArray = packet(Opcode.SET_FEATURES, 0xD7, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)

    /** Подписка на все уведомления: заряд, ухо, режим шумоподавления. */
    val REQUEST_NOTIFICATIONS: ByteArray = packet(Opcode.REQUEST_NOTIFICATIONS, 0xFF, 0xFF, 0xFF, 0xFF)

    /**
     * Попросить у наушников их ключи для рекламы: IRK (0x01) и ключ шифрования (0x04), 0x01 + 0x04 = 0x05.
     * Те же ключи получает iPhone при сопряжении. С IRK узнаём свои наушники по случайному адресу
     * в рекламе, ключом шифрования открываем точный заряд в её зашифрованной части.
     */
    val REQUEST_PROXIMITY_KEYS: ByteArray = packet(Opcode.PROXIMITY_KEYS_REQUEST, 0x05, 0x00)

    /** Сменить режим: 04 00 04 00 09 00 0D <режим> 00 00 00. Наушники ответят тем же пакетом-уведомлением. */
    fun setListeningMode(mode: ListeningMode): ByteArray {
        val code = when (mode) {
            ListeningMode.OFF -> 0x01
            ListeningMode.NOISE_CANCELLATION -> 0x02
            ListeningMode.TRANSPARENCY -> 0x03
            ListeningMode.ADAPTIVE -> 0x04
            ListeningMode.UNKNOWN -> error("Нельзя выбрать неизвестный режим")
        }
        return packet(Opcode.CONTROL, 0x0D, code, 0x00, 0x00, 0x00)
    }

    /** Включить поток датчиков головы: пакет из описания LibrePods (поток 14). См. [AapStreams]. */
    val START_HEAD_TRACKING: ByteArray = AapStreams.request(289, AapStreams.DOCUMENTED_HEAD_STREAM, on = true)

    val STOP_HEAD_TRACKING: ByteArray = AapStreams.request(126, AapStreams.DOCUMENTED_HEAD_STREAM, on = false)

    /**
     * Переименовать: `04 00 04 00 1A 00 01 <длина> 00 <имя UTF-8>`. Длину ограничиваем 32 байтами
     * и не режем букву пополам.
     */
    fun rename(name: String): ByteArray {
        val bytes = utf8Prefix(name.trim(), MAX_NAME_BYTES)
        return packet(Opcode.RENAME, 0x01, bytes.size, 0x00) + bytes
    }

    const val MAX_NAME_BYTES = 32

    internal fun utf8Prefix(text: String, maxBytes: Int): ByteArray {
        var end = text.length
        while (end > 0 && text.substring(0, end).toByteArray(Charsets.UTF_8).size > maxBytes) {
            end--
            if (end > 0 && Character.isLowSurrogate(text[end])) end--
        }
        return text.substring(0, end).toByteArray(Charsets.UTF_8)
    }

    fun packet(opcode: Int, vararg payload: Int): ByteArray =
        HEADER + byteArrayOf((opcode and 0xFF).toByte(), (opcode shr 8 and 0xFF).toByte()) + bytes(*payload)

    internal fun isAapPacket(data: ByteArray): Boolean =
        data.size >= 6 && data[0] == HEADER[0] && data[1] == HEADER[1] && data[2] == HEADER[2] && data[3] == HEADER[3]

    internal fun opcode(data: ByteArray): Int = data.u8(4) or (data.u8(5) shl 8)

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }
}

object Opcode {
    const val BATTERY = 0x0004
    const val EAR_DETECTION = 0x0006
    const val CONTROL = 0x0009
    const val REQUEST_NOTIFICATIONS = 0x000F
    const val HEAD_TRACKING = 0x0017
    const val RENAME = 0x001A
    const val PROXIMITY_KEYS_REQUEST = 0x0030
    const val PROXIMITY_KEYS = 0x0031
    const val SET_FEATURES = 0x004D
}

/** Положение одного наушника по AAP. */
enum class EarState { IN_EAR, OUT_OF_EAR, IN_CASE, UNKNOWN;

    companion object {
        fun of(code: Int) = when (code) {
            0x00 -> IN_EAR
            0x01 -> OUT_OF_EAR
            0x02 -> IN_CASE
            else -> UNKNOWN
        }
    }
}

enum class BatteryComponent { SINGLE, RIGHT, LEFT, CASE, UNKNOWN;

    companion object {
        fun of(code: Int) = when (code) {
            0x01 -> SINGLE
            0x02 -> RIGHT
            0x04 -> LEFT
            0x08 -> CASE
            else -> UNKNOWN
        }
    }
}

/** Заряд одного компонента с точностью 1 %. */
data class AapBattery(val component: BatteryComponent, val percent: Int, val charging: Boolean, val connected: Boolean)

enum class ListeningMode { OFF, NOISE_CANCELLATION, TRANSPARENCY, ADAPTIVE, UNKNOWN;

    companion object {
        fun of(code: Int) = when (code) {
            0x01 -> OFF
            0x02 -> NOISE_CANCELLATION
            0x03 -> TRANSPARENCY
            0x04 -> ADAPTIVE
            else -> UNKNOWN
        }
    }
}

/** Разобранное сообщение от наушников. */
sealed interface AapEvent {
    data class Battery(val components: List<AapBattery>) : AapEvent

    /** primary — наушник, через который идёт связь с телефоном; у Max только primary. */
    data class EarDetection(val primary: EarState, val secondary: EarState) : AapEvent

    data class ListeningModeChanged(val mode: ListeningMode) : AapEvent

    data class ConversationalAwarenessChanged(val enabled: Boolean) : AapEvent

    /** Любая другая настройка из пакета CONTROL: [id] из [ControlId], значение — 4 байта. */
    data class ControlChanged(val id: Int, val value: List<Int>) : AapEvent

    /**
     * Датчики головы: одна или несколько записей из потока (наушники склеивают до 8 записей в пакет).
     * В каждой записи шесть чисел, знаковые 16 бит: скорость поворота по трём осям (гироскоп)
     * и направление силы тяжести (акселерометр, 1024 ≈ 1 g). Какое число — кивок, а какое —
     * покачивание, узнаём калибровкой (см. HeadCalibrator).
     */
    data class HeadMotion(val samples: List<List<Int>>) : AapEvent {
        /** Последняя запись пакета. */
        val axes: List<Int> get() = samples.last()
    }

    /** Ключи рекламы этих наушников (см. [Aap.REQUEST_PROXIMITY_KEYS]); null — наушники такой не прислали. */
    class ProximityKeys(val irk: ByteArray?, val encryptionKey: ByteArray?) : AapEvent {
        override fun equals(other: Any?) = other is ProximityKeys &&
            irk.contentEquals(other.irk) && encryptionKey.contentEquals(other.encryptionKey)
        override fun hashCode() = 31 * irk.contentHashCode() + encryptionKey.contentHashCode()
        // Ключи не печатаем: журнал показывают на скриншотах.
        override fun toString() = "ProximityKeys(irk=${irk != null}, encryptionKey=${encryptionKey != null})"
    }

    /** Всё, что пока не разбираем: попадёт в журнал AAP для реверса. */
    data class Unknown(val opcode: Int, val raw: ByteArray) : AapEvent {
        override fun equals(other: Any?) = other is Unknown && opcode == other.opcode && raw.contentEquals(other.raw)
        override fun hashCode() = 31 * opcode + raw.contentHashCode()
    }
}

object AapParser {
    /** null — не AAP-пакет (например, ответ на handshake другого формата). */
    fun parse(data: ByteArray): AapEvent? {
        if (!Aap.isAapPacket(data)) return null
        val opcode = Aap.opcode(data)
        return runCatching {
            when (opcode) {
                Opcode.BATTERY -> parseBattery(data)
                Opcode.EAR_DETECTION -> AapEvent.EarDetection(EarState.of(data.u8(6)), EarState.of(data.u8(7)))
                Opcode.CONTROL -> parseControl(data)
                Opcode.HEAD_TRACKING -> parseHeadMotion(data)
                Opcode.PROXIMITY_KEYS -> parseProximityKeys(data)
                else -> null
            }
        }.getOrNull() ?: AapEvent.Unknown(opcode, data)
    }

    // 04 00 04 00 04 00 <count> { <component> 01 <percent> <status> 01 } * count
    private fun parseBattery(data: ByteArray): AapEvent.Battery? {
        val count = data.u8(6)
        val components = (0 until count).map { i ->
            val offset = 7 + i * 5
            val status = data.u8(offset + 3)
            AapBattery(
                component = BatteryComponent.of(data.u8(offset)),
                percent = data.u8(offset + 2),
                charging = status == 0x01,
                connected = status != 0x04,
            )
        }
        return AapEvent.Battery(components.filter { it.connected && it.percent in 0..100 })
    }

    /*
     * Пакет потока: 04 00 04 00 17 00 00 00 10 00 <длина LE16> 08 <seq> 10 03, дальше записи
     * 3A 3E 08 <поток> 1A 3A <58 байт>. В записи (смещения от её начала, little-endian со знаком):
     * 26/28/30 — гироскоп, 44/46/48 — сила тяжести. Сверено по журналу Pro 2 (поток 16): в покое
     * гироскоп около нуля, а сила тяжести даёт длину вектора ≈ 1024.
     * На опкоде 0x17 идут и другие сообщения (список блоков AirPods 4 с байтом 8 = 0x04, подтверждения
     * без записей): они остаются Unknown.
     */
    private fun parseHeadMotion(data: ByteArray): AapEvent? {
        if (data.size < 12 || data.u8(8) != HEAD_STREAM) return null
        val samples = ArrayList<List<Int>>()
        var i = 12
        while (i + 6 <= data.size) {
            val isRecord = data.u8(i) == 0x3A && data.u8(i + 2) == 0x08 && data.u8(i + 4) == 0x1A
            if (!isRecord) { i++; continue }
            val start = i + 6
            val length = data.u8(i + 5)
            if (length >= SAMPLE_SIZE && start + length <= data.size) {
                samples += SAMPLE_OFFSETS.map { data.s16(start + it) }
            }
            i = start + length
        }
        return if (samples.isEmpty()) null else AapEvent.HeadMotion(samples)
    }

    private fun ByteArray.s16(index: Int): Int = ((u8(index) or (u8(index + 1) shl 8)).toShort()).toInt()

    private const val HEAD_STREAM = 0x10
    private const val SAMPLE_SIZE = 50
    private val SAMPLE_OFFSETS = listOf(26, 28, 30, 44, 46, 48)

    // 04 00 04 00 31 00 <count> { <type> <length BE16> 00 <key> } * count; type 0x01 — IRK, 0x04 — ключ шифрования.
    private fun parseProximityKeys(data: ByteArray): AapEvent.ProximityKeys? {
        val keys = HashMap<Int, ByteArray>()
        var offset = 7
        repeat(data.u8(6)) {
            if (offset + 4 > data.size) return null
            val type = data.u8(offset)
            val length = (data.u8(offset + 1) shl 8) or data.u8(offset + 2)
            offset += 4
            if (offset + length > data.size) return null
            keys[type] = data.copyOfRange(offset, offset + length)
            offset += length
        }
        val irk = keys[KEY_IRK]?.takeIf { it.size == 16 }
        val enc = keys[KEY_ENCRYPTION]?.takeIf { it.size == 16 }
        return if (irk == null && enc == null) null else AapEvent.ProximityKeys(irk, enc)
    }

    private const val KEY_IRK = 0x01
    private const val KEY_ENCRYPTION = 0x04

    // 04 00 04 00 09 00 <id> <value> 00 00 00
    private fun parseControl(data: ByteArray): AapEvent = when (val id = data.u8(6)) {
        ControlId.LISTENING_MODE -> AapEvent.ListeningModeChanged(ListeningMode.of(data.u8(7)))
        ControlId.CONVERSATIONAL_AWARENESS -> AapEvent.ConversationalAwarenessChanged(data.u8(7) == 0x01)
        else -> AapEvent.ControlChanged(id, (7 until minOf(data.size, 11)).map { data.u8(it) })
    }
}
