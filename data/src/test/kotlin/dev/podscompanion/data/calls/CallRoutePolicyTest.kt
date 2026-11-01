package dev.podscompanion.data.calls

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class CallRoutePolicyTest {

    private val policy = CallRoutePolicy(settleMs = 4_000)
    private var now = 0L

    private fun step(offHead: Boolean?, onHeadphones: Boolean, afterMs: Long = 1_000): CallRoute? {
        now += afterMs
        return policy.onUpdate(offHead, onHeadphones, now)
    }

    @Test
    fun `звонок при снятых наушниках идёт в телефон, надели — обратно в наушники`() {
        assertThat(step(offHead = true, onHeadphones = true)).isEqualTo(CallRoute.PHONE)
        assertThat(step(offHead = true, onHeadphones = false)).isNull()
        assertThat(step(offHead = false, onHeadphones = false)).isEqualTo(CallRoute.HEADPHONES)
        assertThat(step(offHead = false, onHeadphones = true)).isNull()
    }

    @Test
    fun `наушники на голове — звонок не трогаем`() {
        assertThat(step(offHead = false, onHeadphones = true)).isNull()
        assertThat(step(offHead = false, onHeadphones = true)).isNull()
    }

    @Test
    fun `звук в телефоне не по нашей воле — надевание его не переносит`() {
        assertThat(step(offHead = false, onHeadphones = false)).isNull()
        assertThat(step(offHead = true, onHeadphones = false)).isNull()
        assertThat(step(offHead = false, onHeadphones = false)).isNull()
    }

    @Test
    fun `система вернула звук в наушники сразу после ответа — переводим снова`() {
        assertThat(step(offHead = true, onHeadphones = true)).isEqualTo(CallRoute.PHONE)
        now += 10_000
        policy.onCallStateChanged(now)
        assertThat(step(offHead = true, onHeadphones = true, afterMs = 500)).isEqualTo(CallRoute.PHONE)
    }

    @Test
    fun `человек сам вернул звук в наушники — не спорим, пока их не наденут`() {
        assertThat(step(offHead = true, onHeadphones = true)).isEqualTo(CallRoute.PHONE)
        assertThat(step(offHead = true, onHeadphones = true, afterMs = 10_000)).isNull()
        assertThat(step(offHead = true, onHeadphones = true)).isNull()
        // Надели и снова сняли: снова в телефон.
        assertThat(step(offHead = false, onHeadphones = true)).isNull()
        assertThat(step(offHead = true, onHeadphones = true)).isEqualTo(CallRoute.PHONE)
    }

    @Test
    fun `неизвестное состояние ничего не меняет`() {
        assertThat(step(offHead = null, onHeadphones = true)).isNull()
    }

    @Test
    fun `после завершения звонка всё начинается заново`() {
        assertThat(step(offHead = true, onHeadphones = true)).isEqualTo(CallRoute.PHONE)
        policy.reset()
        assertThat(step(offHead = false, onHeadphones = false)).isNull()
    }
}
