package dev.podscompanion.data.autopause

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class EarDetectionPolicyTest {

    private val policy = EarDetectionPolicy(cooldownMs = 3_000)
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
    fun `пауза с первого же пакета «сняты»`() {
        feed(worn = true, playing = true)
        assertThat(feed(worn = false, playing = true, times = 1)).isEqualTo(MediaAction.PAUSE)
    }

    @Test
    fun `с двумя подтверждениями одиночный пакет «надеты» музыку не включает`() {
        val policy = EarDetectionPolicy(confirmationsToPause = 1, confirmationsToResume = 2, cooldownMs = 3_000)
        var now = 0L
        fun step(worn: Boolean, playing: Boolean): MediaAction? { now += 1_000; return policy.onUpdate(worn, playing, now) }
        step(true, true); step(true, true)
        assertThat(step(false, true)).isEqualTo(MediaAction.PAUSE)
        now += 5_000
        assertThat(step(true, false)).isNull()
        assertThat(step(true, false)).isEqualTo(MediaAction.RESUME)
    }

    @Test
    fun `пауза и продолжение по одному пакету`() {
        val policy = EarDetectionPolicy()
        assertThat(policy.onUpdate(true, true, 0)).isNull()
        assertThat(policy.onUpdate(false, true, 5_000)).isEqualTo(MediaAction.PAUSE)
        assertThat(policy.onUpdate(true, false, 10_000)).isEqualTo(MediaAction.RESUME)
    }

    @Test
    fun `без музыки паузы нет, и потом не включаем то, что не ставили на паузу`() {
        feed(worn = true, playing = false)
        assertThat(feed(worn = false, playing = false)).isNull()
        assertThat(feed(worn = true, playing = false)).isNull()
    }

    @Test
    fun `первое состояние после появления наушников ничего не делает`() {
        assertThat(feed(worn = false, playing = true, times = 1)).isNull()
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
        assertThat(feed(worn = false, playing = true, times = 1)).isNull()
    }
}
