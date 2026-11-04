package dev.podscompanion.protocol.advertising

import com.google.common.truth.Truth.assertThat
import dev.podscompanion.protocol.util.Hex
import org.junit.jupiter.api.Test

class PcSignalTest {
    // Тот же пакет собирает тест PhoneSignal_SameBytesAsPhone программы для Windows.
    private val packet = Hex.decode("50 43 53 57 01 01 E5 D3 F5 16 12 34")

    @Test
    fun parsesPacketFromPc() {
        val signal = PcSignal.parse(packet)!!
        assertThat(signal.nonce).isEqualTo(0x1234)
        assertThat(signal.isFor("70:F9:4A:9D:33:92")).isTrue()
        assertThat(signal.isFor("70:f9:4a:9d:33:92")).isTrue()
        assertThat(signal.isFor("70:F9:4A:9D:33:93")).isFalse()
    }

    @Test
    fun ignoresOtherData() {
        assertThat(PcSignal.parse(packet.copyOf(11))).isNull()
        assertThat(PcSignal.parse(packet.copyOf().also { it[0] = 0 })).isNull()
        assertThat(PcSignal.parse(packet.copyOf().also { it[5] = 2 })).isNull()
    }
}
