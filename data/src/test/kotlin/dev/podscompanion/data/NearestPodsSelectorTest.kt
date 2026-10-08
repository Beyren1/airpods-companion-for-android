package dev.podscompanion.data

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class NearestPodsSelectorTest {

    private val selector = NearestPodsSelector(minRssi = -80, stickinessDb = 8, staleAfterMs = 10_000)

    private val mine = PairFingerprint(modelId = 0x1B20, colorCode = 0, leftPercent = 90, rightPercent = 100)
    private val other = PairFingerprint(modelId = 0x1420, colorCode = 0, leftPercent = 40, rightPercent = 50)

    @Test
    fun `слабый сигнал незнакомых наушников игнорируется`() {
        assertThat(selector.accept("A", rssi = -90, fingerprint = mine, nowMs = 0)).isFalse()
    }

    @Test
    fun `прилипаем к своим, пока чужие не станут заметно ближе`() {
        assertThat(selector.accept("MINE", -60, mine, 0)).isTrue()
        assertThat(selector.accept("OTHER", -55, other, 100)).isFalse()
        assertThat(selector.accept("MINE", -61, mine, 200)).isTrue()
        assertThat(selector.accept("OTHER", -45, other, 300)).isTrue()
    }

    @Test
    fun `второй наушник с другого адреса принимается сразу, даже со слабым сигналом`() {
        assertThat(selector.accept("LEFT", -60, mine, 0)).isTrue()
        assertThat(selector.accept("RIGHT", -88, mine.copy(rightPercent = null), 500)).isTrue()
        assertThat(selector.accept("LEFT", -86, mine, 1_000)).isTrue()
    }

    @Test
    fun `пропавшие наушники заменяются любыми подходящими`() {
        assertThat(selector.accept("MINE", -50, mine, 0)).isTrue()
        assertThat(selector.accept("OTHER", -70, other, 5_000)).isFalse()
        assertThat(selector.accept("OTHER", -70, other, 11_000)).isTrue()
    }
}
