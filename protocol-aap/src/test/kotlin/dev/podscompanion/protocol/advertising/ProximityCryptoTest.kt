package dev.podscompanion.protocol.advertising

import com.google.common.truth.Truth.assertThat
import dev.podscompanion.protocol.aap.Aap
import dev.podscompanion.protocol.aap.AapEvent
import dev.podscompanion.protocol.aap.AapParser
import dev.podscompanion.protocol.util.Hex
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import org.junit.jupiter.api.Test

class ProximityCryptoTest {
    // Пример из спецификации Bluetooth Core (том 3, часть H, D.7): IRK, prand 708194 → hash 0DFBAA.
    private val irkMsbFirst = Hex.decode("ec0234a357c8ad05341010a60a397d9b")
    private val irk = irkMsbFirst.reversedArray()

    @Test
    fun `функция ah как в спецификации`() {
        assertThat(Hex.encode(ProximityCrypto.ah(irk, Hex.decode("708194"))).replace(" ", "").lowercase()).isEqualTo("0dfbaa")
    }

    @Test
    fun `свой адрес узнаём, чужой нет`() {
        assertThat(ProximityCrypto.resolves("70:81:94:0D:FB:AA", irk)).isTrue()
        assertThat(ProximityCrypto.resolves("70:81:94:0D:FB:AB", irk)).isFalse()
        assertThat(ProximityCrypto.resolves("70:81:94:0D:FB:AA", ByteArray(16))).isFalse()
    }

    @Test
    fun `точный заряд из зашифрованной части`() {
        val key = ByteArray(16) { (it * 7).toByte() }
        val plain = ByteArray(16).also { it[1] = 87; it[2] = (0x80 or 64).toByte(); it[3] = 0xFF.toByte() }
        val encrypted = Cipher.getInstance("AES/ECB/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES")); doFinal(plain)
        }
        val message = ByteArray(11) + encrypted

        val battery = ProximityCrypto.exactBattery(message, key)!!

        assertThat(battery.primary).isEqualTo(87)
        assertThat(battery.primaryCharging).isFalse()
        assertThat(battery.secondary).isEqualTo(64)
        assertThat(battery.secondaryCharging).isTrue()
        assertThat(battery.case).isNull()
    }

    @Test
    fun `запрос и ответ с ключами AAP`() {
        assertThat(Hex.encode(Aap.REQUEST_PROXIMITY_KEYS).replace(" ", "")).isEqualTo("0400040030000500")
        val irkBytes = ByteArray(16) { 1 }
        val encBytes = ByteArray(16) { 4 }
        val packet = Hex.decode("04000400310002") +
            Hex.decode("01001000") + irkBytes + Hex.decode("04001000") + encBytes

        val event = AapParser.parse(packet) as AapEvent.ProximityKeys

        assertThat(event.irk).isEqualTo(irkBytes)
        assertThat(event.encryptionKey).isEqualTo(encBytes)
    }
}
