package dev.podscompanion.data.autopause

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class EarDetectionPolicyTest {

    private val policy = EarDetectionPolicy(confirmations = 2, cooldownMs = 3_000)
    private var now = 0L

    /** Каждый пакет через 1 с, как в реальном эфире. */
    private fun feed(worn: Boolean?, playing: Boolean, times: Int = 2): MediaAction? {
        var last: MediaAction? = null
        repeat(times) {
            now += 1_000
            policy.onUpdate(worn, playing, now)?.let { last = it }
        }
        return last
    }

    @Test
    fun `вынули наушник во время музыки — пауза, вставили — продолжение`() {
        assertThat(feed(worn = true, playing = true)).isNull()
        assertThat(feed(worn = false, playing = true)).isEqualTo(MediaAction.PAUSE)
        now += 5_000
        assertThat(feed(worn = true, playing = false)).isEqualTo(MediaAction.RESUME)
    }

    @Test
    fun `одиночный мигнувший пакет игнорируется`() {
        feed(worn = true, playing = true)
        assertThat(feed(worn = false, playing = true, times = 1)).isNull()
        assertThat(feed(worn = true, playing = true, times = 1)).isNull()
        assertThat(feed(worn = true, playing = true, times = 1)).isNull()
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

    @Test
    fun `запоздавший пакет «сняты» сразу после продолжения не ставит паузу снова`() {
        feed(worn = true, playing = true)
        feed(worn = false, playing = true) // пауза
        now += 5_000
        assertThat(feed(worn = true, playing = false)).isEqualTo(MediaAction.RESUME)
        // Второй наушник ещё шлёт старое состояние, музыка уже играет.
        assertThat(feed(worn = false, playing = true)).isNull()
    }
}
