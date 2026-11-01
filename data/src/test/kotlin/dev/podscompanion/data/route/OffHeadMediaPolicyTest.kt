package dev.podscompanion.data.route

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class OffHeadMediaPolicyTest {

    private val policy = OffHeadMediaPolicy(offDelayMs = 1_500, armWindowMs = 30_000)
    private var now = 0L

    private fun step(offHead: Boolean?, podsActive: Boolean = true, busy: Boolean = false, afterMs: Long = 1_000): MediaRoute? {
        now += afterMs
        return policy.onUpdate(offHead, podsActive, busy, now)
    }

    @Test
    fun `сняли — звук в телефон после задержки, надели — обратно`() {
        assertThat(step(offHead = false)).isNull()
        assertThat(step(offHead = true)).isNull() // только что сняли
        assertThat(step(offHead = true, afterMs = 1_000)).isNull() // 1 с — ещё ждём
        assertThat(step(offHead = true, afterMs = 600)).isEqualTo(MediaRoute.PHONE)
        assertThat(step(offHead = true, podsActive = false)).isNull()
        assertThat(step(offHead = false, podsActive = false)).isEqualTo(MediaRoute.HEADPHONES)
        assertThat(step(offHead = false)).isNull()
    }

    @Test
    fun `сняли на долю секунды — ничего не делаем`() {
        assertThat(step(offHead = true)).isNull()
        assertThat(step(offHead = false, afterMs = 300)).isNull()
        assertThat(step(offHead = false)).isNull()
    }

    @Test
    fun `звук не в наушниках — не трогаем и не возвращаем`() {
        assertThat(step(offHead = true, podsActive = false)).isNull()
        assertThat(step(offHead = true, podsActive = false, afterMs = 2_000)).isNull()
        assertThat(step(offHead = false, podsActive = false)).isNull()
    }

    @Test
    fun `человек сам вернул звук в снятые наушники — не спорим`() {
        step(offHead = true)
        assertThat(step(offHead = true, afterMs = 2_000)).isEqualTo(MediaRoute.PHONE)
        assertThat(step(offHead = true, podsActive = true, afterMs = 5_000)).isNull()
        assertThat(step(offHead = true, podsActive = true, afterMs = 5_000)).isNull()
    }

    @Test
    fun `только что подключённые наушники становятся активными позже — тоже уводим`() {
        assertThat(step(offHead = true, podsActive = false)).isNull()
        assertThat(step(offHead = true, podsActive = false, afterMs = 3_000)).isNull()
        assertThat(step(offHead = true, podsActive = true, afterMs = 3_000)).isEqualTo(MediaRoute.PHONE)
    }

    @Test
    fun `давно снятые наушники больше не трогаем`() {
        assertThat(step(offHead = true, podsActive = false)).isNull()
        assertThat(step(offHead = true, podsActive = true, afterMs = 60_000)).isNull()
        assertThat(policy.waiting(now)).isFalse()
    }

    @Test
    fun `во время звонка ждём, после звонка уводим`() {
        step(offHead = true)
        assertThat(step(offHead = true, busy = true, afterMs = 2_000)).isNull()
        assertThat(step(offHead = true, busy = false)).isEqualTo(MediaRoute.PHONE)
    }

    @Test
    fun `не вышло увести — до следующего снятия не пробуем`() {
        step(offHead = true)
        assertThat(step(offHead = true, afterMs = 2_000)).isEqualTo(MediaRoute.PHONE)
        policy.onMoveFailed()
        assertThat(step(offHead = true)).isNull()
        assertThat(policy.waiting(now)).isFalse()
        assertThat(step(offHead = false)).isNull()
        step(offHead = true)
        assertThat(step(offHead = true, afterMs = 2_000)).isEqualTo(MediaRoute.PHONE)
    }

    @Test
    fun `возврат перед автопродолжением забирает решение у политики`() {
        step(offHead = true)
        assertThat(step(offHead = true, afterMs = 2_000)).isEqualTo(MediaRoute.PHONE)
        assertThat(policy.takeRestore()).isTrue()
        assertThat(policy.takeRestore()).isFalse()
        assertThat(step(offHead = false)).isNull()
    }

    @Test
    fun `ухо неизвестно — ничего не меняем`() {
        step(offHead = true)
        assertThat(step(offHead = true, afterMs = 2_000)).isEqualTo(MediaRoute.PHONE)
        assertThat(step(offHead = null)).isNull()
        assertThat(policy.movedByUs).isTrue()
        assertThat(step(offHead = false)).isEqualTo(MediaRoute.HEADPHONES)
    }
}
