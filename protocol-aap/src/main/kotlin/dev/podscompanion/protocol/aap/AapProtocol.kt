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

    /** Включает расширенные функции (Adaptive, Conversational Awareness). Флаги проверить по дампам. */
    val SET_FEATURES: ByteArray = packet(Opcode.SET_FEATURES, 0xD7, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)

    /** Подписка на все уведомления: заряд, ухо, режим шумоподавления. */
    val REQUEST_NOTIFICATIONS: ByteArray = packet(Opcode.REQUEST_NOTIFICATIONS, 0xFF, 0xFF, 0xFF, 0xFF)

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

    // 04 00 04 00 09 00 <id> <value> 00 00 00
    private fun parseControl(data: ByteArray): AapEvent = when (val id = data.u8(6)) {
        ControlId.LISTENING_MODE -> AapEvent.ListeningModeChanged(ListeningMode.of(data.u8(7)))
        ControlId.CONVERSATIONAL_AWARENESS -> AapEvent.ConversationalAwarenessChanged(data.u8(7) == 0x01)
        else -> AapEvent.ControlChanged(id, (7 until minOf(data.size, 11)).map { data.u8(it) })
    }
}
