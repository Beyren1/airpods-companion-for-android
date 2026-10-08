package dev.podscompanion.data

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class NearestPodsSelectorTest {

    private val selector = NearestPodsSelector(minRssi = -75, stickinessDb = 8, staleAfterMs = 10_000)

    @Test
    fun `слабый сигнал игнорируется`() {
        assertThat(selector.accept("A", rssi = -90, nowMs = 0)).isFalse()
    }

    @Test
    fun `прилипаем к первым наушникам, пока чужие не станут заметно ближе`() {
        assertThat(selector.accept("MINE", -60, 0)).isTrue()
        assertThat(selector.accept("OTHER", -55, 100)).isFalse() // лучше всего на 5 дБ: не переключаемся
        assertThat(selector.accept("MINE", -61, 200)).isTrue()
        assertThat(selector.accept("OTHER", -45, 300)).isTrue() // лучше на 16 дБ: переключаемся
    }

    @Test
    fun `пропавшие наушники заменяются любыми подходящими`() {
        assertThat(selector.accept("MINE", -50, 0)).isTrue()
        assertThat(selector.accept("OTHER", -70, 5_000)).isFalse()
        assertThat(selector.accept("OTHER", -70, 11_000)).isTrue()
    }
}
