package dev.podscompanion.protocol.aap

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class AapDeviceInfoTest {

    private fun packet(prefix: List<Int>, vararg fields: String): ByteArray {
        val body = fields.joinToString("\u0000").toByteArray(Charsets.UTF_8)
        return Aap.packet(Opcode.DEVICE_INFO, *prefix.toIntArray()) + body + byteArrayOf(0)
    }

    @Test
    fun `поля считаются от производителя, служебные байты в начале не мешают`() {
        val data = packet(
            listOf(0x02, 0xED, 0x00, 0x04, 0x00),
            "Мои AirPods", "A2699", "Apple Inc.", "H6XPQ1ABCD12", "7E93", "7E93", "1.0.0",
            "com.apple.accessory.updater", "GX1LEFT00001", "GX1RIGHT0001",
        )
        val event = AapParser.parse(data) as AapEvent.DeviceInfo
        assertThat(event.info).isEqualTo(
            AapDeviceInfo(
                name = "Мои AirPods",
                modelNumber = "A2699",
                manufacturer = "Apple Inc.",
                serialNumber = "H6XPQ1ABCD12",
                firmware = "7E93",
                leftSerialNumber = "GX1LEFT00001",
                rightSerialNumber = "GX1RIGHT0001",
            ),
        )
    }

    @Test
    fun `пустое поле не сдвигает следующие`() {
        val data = packet(listOf(0x01), "AirPods Max", "A3184", "Apple Inc.", "", "7E101")
        val info = (AapParser.parse(data) as AapEvent.DeviceInfo).info
        assertThat(info.serialNumber).isNull()
        assertThat(info.firmware).isEqualTo("7E101")
        assertThat(info.leftSerialNumber).isNull()
    }

    @Test
    fun `пакет без узнаваемых полей остаётся неизвестным`() {
        val data = Aap.packet(Opcode.DEVICE_INFO, 0x01, 0x02, 0x03)
        assertThat(AapParser.parse(data)).isInstanceOf(AapEvent.Unknown::class.java)
    }

    @Test
    fun `паспорт попадает в состояние наушников`() {
        val data = packet(listOf(0x00), "AirPods", "A3056", "Apple Inc.", "SERIAL1234", "7A305")
        val state = AapDeviceState().apply(AapParser.parse(data)!!)
        assertThat(state.info?.modelNumber).isEqualTo("A3056")
    }
}
