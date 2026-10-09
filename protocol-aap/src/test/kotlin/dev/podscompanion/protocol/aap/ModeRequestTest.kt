package dev.podscompanion.protocol.aap

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class ModeRequestTest {

    private val transparency = AapDeviceState(listeningMode = ListeningMode.TRANSPARENCY)

    @Test
    fun `пока наушники не ответили, показываем выбранный режим`() {
        val request = ModeRequest()
        request.request(ListeningMode.NOISE_CANCELLATION, nowMs = 0)
        // Наушники успели прислать старый режим: экран не прыгает назад.
        request.onReported(ListeningMode.TRANSPARENCY)
        assertThat(request.shown(transparency).listeningMode).isEqualTo(ListeningMode.NOISE_CANCELLATION)
    }

    @Test
    fun `подтверждение снимает запрос и повторов нет`() {
        val request = ModeRequest()
        request.request(ListeningMode.NOISE_CANCELLATION, nowMs = 0)
        request.onReported(ListeningMode.NOISE_CANCELLATION)
        assertThat(request.pending).isNull()
        assertThat(request.due(nowMs = 5_000)).isNull()
    }

    @Test
    fun `без подтверждения команда повторяется, потом запрос снимается`() {
        val request = ModeRequest(retryMs = 700, maxAttempts = 3)
        request.request(ListeningMode.NOISE_CANCELLATION, nowMs = 0)
        assertThat(request.due(nowMs = 300)).isNull()
        assertThat(request.due(nowMs = 700)).isEqualTo(ListeningMode.NOISE_CANCELLATION)
        assertThat(request.due(nowMs = 1_000)).isNull()
        assertThat(request.due(nowMs = 1_400)).isEqualTo(ListeningMode.NOISE_CANCELLATION)
        // Три попытки сделаны: наушники не согласны, верим им.
        assertThat(request.due(nowMs = 2_100)).isNull()
        assertThat(request.pending).isNull()
        assertThat(request.shown(transparency)).isEqualTo(transparency)
    }

    @Test
    fun `после переподключения свежий выбор отправляется снова, старый забывается`() {
        val fresh = ModeRequest(ttlMs = 10_000)
        fresh.request(ListeningMode.ADAPTIVE, nowMs = 0)
        assertThat(fresh.onReconnected(nowMs = 2_000)).isEqualTo(ListeningMode.ADAPTIVE)

        val stale = ModeRequest(ttlMs = 10_000)
        stale.request(ListeningMode.ADAPTIVE, nowMs = 0)
        assertThat(stale.onReconnected(nowMs = 20_000)).isNull()
        assertThat(stale.pending).isNull()
    }

    @Test
    fun `новый выбор заменяет предыдущий`() {
        val request = ModeRequest()
        request.request(ListeningMode.NOISE_CANCELLATION, nowMs = 0)
        request.request(ListeningMode.OFF, nowMs = 100)
        request.onReported(ListeningMode.NOISE_CANCELLATION)
        assertThat(request.pending).isEqualTo(ListeningMode.OFF)
    }
}
