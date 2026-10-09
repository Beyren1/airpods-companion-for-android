package dev.podscompanion.protocol.aap

import com.google.common.truth.Truth.assertThat
import dev.podscompanion.protocol.util.Hex
import org.junit.jupiter.api.Test

class AapStreamsTest {
    @Test
    fun `запросы совпадают с пакетами из описания`() {
        assertThat(Hex.encode(Aap.START_HEAD_TRACKING))
            .isEqualTo("04 00 04 00 17 00 00 00 10 00 10 00 08 A1 02 42 0B 08 0E 10 02 1A 05 01 40 9C 00 00")
        assertThat(Hex.encode(Aap.STOP_HEAD_TRACKING))
            .isEqualTo("04 00 04 00 17 00 00 00 10 00 11 00 08 7E 10 02 42 0B 08 4E 10 02 1A 05 01 00 00 00 00")
    }

    @Test
    fun `объявленные потоки Pro 2`() {
        val first = Hex.decode("04 00 04 00 17 00 00 00 10 00 10 00 08 09 10 03 62 02 08 10 62 02 08 0D 62 02 08 12")
        val second = Hex.decode("04 00 04 00 17 00 00 00 10 00 08 00 08 0A 10 03 62 02 08 13")
        assertThat(AapStreams.announced(first)).containsExactly(16, 13, 18).inOrder()
        assertThat(AapStreams.announced(second)).containsExactly(19)
        // Ответ на запрос (поле 9) — не объявление.
        assertThat(AapStreams.announced(Hex.decode("04 00 04 00 17 00 00 00 10 00 08 00 08 0B 10 03 4A 02 08 0E"))).isEmpty()
    }

    @Test
    fun `поток 16 совпадает с альтернативным пакетом LibrePods`() {
        assertThat(Hex.encode(AapStreams.request(0x73, AapStreams.ALTERNATE_HEAD_STREAM, on = true)))
            .isEqualTo("04 00 04 00 17 00 00 00 10 00 0F 00 08 73 42 0B 08 10 10 02 1A 05 01 40 9C 00 00")
    }
}
