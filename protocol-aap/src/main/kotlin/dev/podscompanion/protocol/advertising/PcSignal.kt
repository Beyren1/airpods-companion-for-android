package dev.podscompanion.protocol.advertising

import java.security.MessageDigest

/**
 * Сигнал от программы на ПК: «забери наушники». Кнопка «Отдать телефону» на ПК несколько секунд
 * рассылает его как BLE-рекламу и как UDP-пакет на порт [UDP_PORT] в локальной сети (PhoneSignal.cs).
 *
 * Данные производителя [COMPANY_ID] (или весь UDP-пакет): «PCSW», версия, команда, 4 байта SHA-256 от адреса наушников
 * в виде «70:F9:4A:9D:33:92», 2 байта номера нажатия (чтобы не выполнить одно нажатие дважды).
 */
data class PcSignal(val addressHash: List<Byte>, val nonce: Int) {
    /** Сигнал про эти наушники ([address] — как его отдаёт Android). */
    fun isFor(address: String): Boolean = addressHash == hash(address)

    companion object {
        /** 0xFFFF зарезервирован Bluetooth SIG для тестов: ничьих устройств с ним нет. */
        const val COMPANY_ID = 0xFFFF
        const val LENGTH = 12
        const val UDP_PORT = 47_820
        val MAGIC = byteArrayOf('P'.code.toByte(), 'C'.code.toByte(), 'S'.code.toByte(), 'W'.code.toByte())
        private const val VERSION: Byte = 1
        private const val COMMAND_TAKE: Byte = 1

        fun parse(data: ByteArray): PcSignal? {
            if (data.size < LENGTH || !data.copyOfRange(0, 4).contentEquals(MAGIC)) return null
            if (data[4] != VERSION || data[5] != COMMAND_TAKE) return null
            val nonce = ((data[10].toInt() and 0xFF) shl 8) or (data[11].toInt() and 0xFF)
            return PcSignal(data.copyOfRange(6, 10).toList(), nonce)
        }

        /** [hash] как 8 hex-символов, для [dev.podscompanion.protocol.link.LinkMessage]. */
        fun hashHex(address: String): String = hash(address).joinToString("") { "%02x".format(it) }

        fun hash(address: String): List<Byte> =
            MessageDigest.getInstance("SHA-256").digest(address.uppercase().toByteArray(Charsets.US_ASCII)).copyOfRange(0, 4).toList()
    }
}
