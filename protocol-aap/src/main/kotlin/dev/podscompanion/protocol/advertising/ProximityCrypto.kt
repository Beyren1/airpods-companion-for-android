package dev.podscompanion.protocol.advertising

import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * Ключи рекламы одних наушников, полученные от них самих по прямому подключению
 * (см. Aap.REQUEST_PROXIMITY_KEYS). Байты в том порядке, в каком их прислали наушники.
 */
class ProximityKeys(val irk: ByteArray?, val encryptionKey: ByteArray?)

/** Точный заряд из зашифрованной части рекламы, в процентах; null — неизвестно. */
data class ExactBattery(
    val primary: Int?, val primaryCharging: Boolean,
    val secondary: Int?, val secondaryCharging: Boolean,
    val case: Int?, val caseCharging: Boolean,
)

/**
 * Узнаём свои наушники в рекламе. Это стандартный механизм Bluetooth LE, не обход защиты:
 * адрес в рекламе случайный (Resolvable Private Address) и меняется каждые несколько минут,
 * а владелец ключа IRK может проверить, что адрес выдали именно эти наушники.
 */
object ProximityCrypto {

    /**
     * Адрес [address] («AA:BB:CC:DD:EE:FF») выдан наушниками с этим [irk].
     * Bluetooth Core, том 3, часть H, 2.2.2: адрес = prand (старшие 3 байта) || hash,
     * hash = ah(IRK, prand) = младшие 24 бита AES-128(IRK, 0…0 || prand).
     */
    fun resolves(address: String, irk: ByteArray): Boolean {
        val bytes = address.split(':').map { it.toInt(16).toByte() }
        if (bytes.size != 6 || irk.size != 16) return false
        // Resolvable private address: два старших бита — 01.
        if (bytes[0].toInt() and 0xC0 != 0x40) return false
        val prand = bytes.subList(0, 3).toByteArray()
        val hash = bytes.subList(3, 6).toByteArray()
        return ah(irk, prand).contentEquals(hash)
    }

    /** ah() из спецификации; [irk] — как присылают наушники (младший байт первым), [prand] и результат — старший первым. */
    internal fun ah(irk: ByteArray, prand: ByteArray): ByteArray {
        val block = ByteArray(16).also { prand.copyInto(it, 13) }
        val encrypted = aes(Cipher.ENCRYPT_MODE, irk.reversedArray(), block)
        return encrypted.copyOfRange(13, 16)
    }

    /**
     * Точный заряд из последних 16 байт рекламы (AES-128 ECB ключом шифрования наушников).
     * Расшифровка: байт 1 — primary, 2 — второй наушник, 3 — кейс; старший бит — заряжается, 0xFF — неизвестно.
     */
    fun exactBattery(message: ByteArray, encryptionKey: ByteArray): ExactBattery? {
        if (message.size < MIN_SIZE || encryptionKey.size != 16) return null
        val plain = aes(Cipher.DECRYPT_MODE, encryptionKey, message.copyOfRange(message.size - 16, message.size))
        fun level(i: Int): Int? = (plain[i].toInt() and 0x7F).takeIf { plain[i].toInt() and 0xFF != 0xFF && it <= 100 }
        fun charging(i: Int) = plain[i].toInt() and 0xFF != 0xFF && plain[i].toInt() and 0x80 != 0
        return ExactBattery(level(1), charging(1), level(2), charging(2), level(3), charging(3))
    }

    private fun aes(mode: Int, key: ByteArray, block: ByteArray): ByteArray =
        Cipher.getInstance("AES/ECB/NoPadding").run {
            init(mode, SecretKeySpec(key, "AES"))
            doFinal(block)
        }

    // Тип 0x07, длина 0x19 и 25 байт данных.
    private const val MIN_SIZE = 27
}
