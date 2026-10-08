package dev.podscompanion.protocol.advertising

import com.google.common.truth.Truth.assertThat
import dev.podscompanion.protocol.util.Hex
import org.junit.jupiter.api.Test

/**
 * Пакеты ниже собраны вручную по документированной раскладке (СИНТЕТИЧЕСКИЕ).
 * Когда пришлёте реальные дампы из nRF Connect, добавим их в [RealDumpsTest] с подписью состояния.
 */
class ProximityPairingParserTest {

    private val encrypted = "00".repeat(16)

    private fun packet(
        model: String, status: String, batt: String, chargeCase: String,
        lid: String = "31", color: String = "00",
    ) = Hex.decode("07 19 01 $model $status $batt $chargeCase $lid $color 00 $encrypted")

    @Test
    fun `AirPods 4 ANC в кейсе, оба заряжаются, левый основной`() {
        // статус 0x20: левый основной, никто не в ухе; заряд 0x98: второй(правый)=90%, основной(левый)=80%
        // 0x75: зарядка = основной+второй+кейс (0b111), кейс = 50%
        val msg = ProximityPairingParser.parse(packet("1B 20", "20", "98", "75"))!!

        assertThat(msg.model).isEqualTo(PodsModel.AIRPODS_4_ANC)
        assertThat(msg.primaryIsLeft).isTrue()
        assertThat(msg.left).isEqualTo(PodState(BatteryLevel(80), charging = true, inEar = false))
        assertThat(msg.right).isEqualTo(PodState(BatteryLevel(90), charging = true, inEar = false))
        assertThat(msg.caseBattery).isEqualTo(BatteryLevel(50))
        assertThat(msg.caseCharging).isTrue()
    }

    @Test
    fun `правый основной, оба в ушах`() {
        // статус 0x0A = 0x02 (основной в ухе) | 0x08 (второй в ухе), бит 0x20 сброшен → основной правый
        val msg = ProximityPairingParser.parse(packet("14 20", "0A", "7A", "0F"))!!

        assertThat(msg.model).isEqualTo(PodsModel.AIRPODS_PRO_2)
        assertThat(msg.primaryIsLeft).isFalse()
        assertThat(msg.right).isEqualTo(PodState(BatteryLevel(100), charging = false, inEar = true))
        assertThat(msg.left).isEqualTo(PodState(BatteryLevel(70), charging = false, inEar = true))
        assertThat(msg.caseBattery).isNull() // 0xF = кейс не на связи
    }

    @Test
    fun `второй наушник не на связи`() {
        val msg = ProximityPairingParser.parse(packet("1B 20", "22", "F6", "05"))!!

        assertThat(msg.left.battery).isEqualTo(BatteryLevel(60))
        assertThat(msg.left.inEar).isTrue()
        assertThat(msg.right.battery).isNull()
    }

    @Test
    fun `AirPods Max распознаётся`() {
        val msg = ProximityPairingParser.parse(packet("0A 20", "22", "F8", "0F", color = "02"))!!

        assertThat(msg.model).isEqualTo(PodsModel.AIRPODS_MAX)
        assertThat(msg.colorCode).isEqualTo(0x02)
    }

    @Test
    fun `неизвестная модель не теряется`() {
        val msg = ProximityPairingParser.parse(packet("99 20", "20", "55", "05"))!!

        assertThat(msg.model).isNull()
        assertThat(msg.modelId).isEqualTo(0x9920)
    }

    @Test
    fun `чужие типы Apple и короткие пакеты отбрасываются`() {
        // 0x10 = Nearby Info, 0x02 = iBeacon
        assertThat(ProximityPairingParser.parse(Hex.decode("10 05 01 18 1C 4E 3B"))).isNull()
        assertThat(ProximityPairingParser.parse(Hex.decode("02 15" + "00".repeat(21)))).isNull()
        assertThat(ProximityPairingParser.parse(Hex.decode("07 19 01 1B 20"))).isNull()
        assertThat(ProximityPairingParser.parse(ByteArray(0))).isNull()
    }

    @Test
    fun `hex туда и обратно`() {
        val bytes = Hex.decode("07:19-01 0e")
        assertThat(Hex.encode(bytes)).isEqualTo("07 19 01 0E")
    }
}
