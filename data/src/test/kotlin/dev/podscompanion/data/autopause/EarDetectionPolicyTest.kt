package dev.podscompanion.data.autopause

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class EarDetectionPolicyTest {

    private val policy = EarDetectionPolicy(confirmations = 2)

    private fun feed(worn: Boolean?, playing: Boolean, times: Int = 2): MediaAction? {
        var last: MediaAction? = null
        repeat(times) { policy.onUpdate(worn, playing)?.let { last = it } }
        return last
    }

    @Test
    fun `вынули наушник во время музыки — пауза, вставили — продолжение`() {
        assertThat(feed(worn = true, playing = true)).isNull()
        assertThat(feed(worn = false, playing = true)).isEqualTo(MediaAction.PAUSE)
        assertThat(feed(worn = true, playing = false)).isEqualTo(MediaAction.RESUME)
    }

    @Test
    fun `одиночный мигнувший пакет игнорируется`() {
        feed(worn = true, playing = true)
        assertThat(policy.onUpdate(false, true)).isNull()
        assertThat(policy.onUpdate(true, true)).isNull()
        assertThat(policy.onUpdate(true, true)).isNull()
    }

    @Test
    fun `без музыки паузы нет, и потом не включаем то, что не ставили на паузу`() {
        feed(worn = true, playing = false)
        assertThat(feed(worn = false, playing = false)).isNull()
        assertThat(feed(worn = true, playing = false)).isNull()
    }

    @Test
    fun `первое состояние после появления наушников ничего не делает`() {
        assertThat(feed(worn = false, playing = true)).isNull()
    }

    @Test
    fun `наушники пропали — сброс, после возвращения не продолжаем`() {
        feed(worn = true, playing = true)
        feed(worn = false, playing = true) // пауза
        feed(worn = null, playing = false)
        assertThat(feed(worn = true, playing = false)).isNull()
    }
}
