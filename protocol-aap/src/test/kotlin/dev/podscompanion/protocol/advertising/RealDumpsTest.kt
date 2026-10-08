package dev.podscompanion.protocol.advertising

import com.google.common.truth.Truth.assertThat
import dev.podscompanion.protocol.util.Hex
import org.junit.jupiter.api.Test

/**
 * Реальные пакеты с устройств автора (Pixel 9, 2026-10-08), состояние подтверждено вручную.
 * Зашифрованный хвост (16 байт) сохранён как есть: без ключа он ничего не раскрывает.
 * Заряд сверялся только с тем, что показало приложение; проценты из iOS ещё не сверены.
 */
class RealDumpsTest {

    private fun parse(hex: String) = ProximityPairingParser.parse(Hex.decode(hex))!!

    @Test
    fun `AirPods 4 ANC, оба в ушах, кейс закрыт`() {
        val msg = parse("07 19 01 1B 20 2B AA 8F 10 00 05 FF 16 AA 81 5B 82 AC 9D 48 35 32 15 4C 62 EF 55")

        assertThat(msg.model).isEqualTo(PodsModel.AIRPODS_4_ANC)
        assertThat(msg.left).isEqualTo(PodState(BatteryLevel(100), charging = false, inEar = true))
        assertThat(msg.right).isEqualTo(PodState(BatteryLevel(100), charging = false, inEar = true))
        assertThat(msg.caseBattery).isNull()
    }

    @Test
    fun `AirPods 4 ANC, оба в ушах, левый 90`() {
        val msg = parse("07 19 01 1B 20 2B A9 8F 10 00 04 7C 5C 30 C2 D2 DC 20 4B A9 E2 05 5D F4 77 48 BE")

        assertThat(msg.primaryIsLeft).isTrue()
        assertThat(msg.left).isEqualTo(PodState(BatteryLevel(90), charging = false, inEar = true))
        assertThat(msg.right).isEqualTo(PodState(BatteryLevel(100), charging = false, inEar = true))
    }

    @Test
    fun `AirPods 4 ANC, левый в открытом кейсе заряжается, правый в ухе`() {
        val msg = parse("07 19 01 1B 20 13 9A AF 11 00 04 36 17 ED 81 1D 3D F5 44 4A 97 4D 85 D9 19 93 8A")

        // Основным стал правый (бит 0x20 сброшен), поэтому nibble'ы заряда поменялись местами.
        assertThat(msg.primaryIsLeft).isFalse()
        assertThat(msg.left).isEqualTo(PodState(BatteryLevel(90), charging = true, inEar = false, inCase = true))
        assertThat(msg.right).isEqualTo(PodState(BatteryLevel(100), charging = false, inEar = true))
    }

    @Test
    fun `AirPods 4 ANC, правый в открытом кейсе заряжается, левый вне кейса`() {
        // Пакет шлёт правый: бит 0x40 «отправитель в кейсе», при этом бит 0x02 «в ухе» ещё стоит.
        val msg = parse("07 19 01 1B 20 53 9A 95 3A 00 04 25 2D 6E 55 47 30 EC 44 3F 48 99 E3 6D F8 D6 4F")

        assertThat(msg.right).isEqualTo(PodState(BatteryLevel(100), charging = true, inEar = false, inCase = true))
        assertThat(msg.left).isEqualTo(PodState(BatteryLevel(90), charging = false, inEar = false))
        assertThat(msg.caseBattery).isEqualTo(BatteryLevel(50))
    }

    @Test
    fun `AirPods 4 ANC, левый не передаёт заряд, правый в ухе`() {
        val msg = parse("07 19 01 1B 20 02 FA 8F 11 00 04 49 C4 B6 58 83 83 E7 92 6F 1A 47 09 1D CE 09 3E")

        assertThat(msg.left.battery).isNull()
        assertThat(msg.right).isEqualTo(PodState(BatteryLevel(100), charging = false, inEar = true))
    }

    @Test
    fun `AirPods Max USB-C на голове`() {
        val msg = parse("07 19 01 1F 20 2B 0A 80 04 56 44 6D 43 28 CB 88 F3 EE EA 7D 1C 82 BE C6 F4 1E B7")

        assertThat(msg.model).isEqualTo(PodsModel.AIRPODS_MAX_USB_C)
        assertThat(msg.primary).isEqualTo(PodState(BatteryLevel(100), charging = false, inEar = true))
    }

    @Test
    fun `AirPods Max USB-C сняты с головы`() {
        // Отправителем стал правый (бит 0x20 сброшен), второй nibble = 0: брать надо только primary.
        val msg = parse("07 19 01 1F 20 01 09 80 04 56 44 3E 0B 61 8E 7D D8 92 72 35 C2 41 8A CA 5E FE 5E")

        assertThat(msg.primary).isEqualTo(PodState(BatteryLevel(90), charging = false, inEar = false))
    }
}
