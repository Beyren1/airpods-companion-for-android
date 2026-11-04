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
        request.onReported(ListeningMode.TRANSPARENCY, nowMs = 50)
        assertThat(request.shown(transparency).listeningMode).isEqualTo(ListeningMode.NOISE_CANCELLATION)
    }

    @Test
    fun `подтверждение снимает запрос и повторов нет`() {
        val request = ModeRequest()
        request.request(ListeningMode.NOISE_CANCELLATION, nowMs = 0)
        request.onReported(ListeningMode.NOISE_CANCELLATION, nowMs = 200)
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
        request.onReported(ListeningMode.NOISE_CANCELLATION, nowMs = 200)
        assertThat(request.pending).isEqualTo(ListeningMode.OFF)
    }

    @Test
    fun `нажатие кнопки на наушниках во время ожидания не перебивается повтором`() {
        val request = ModeRequest(retryMs = 700)
        request.onReported(ListeningMode.NOISE_CANCELLATION, nowMs = 0)
        request.request(ListeningMode.OFF, nowMs = 1_000)
        // Кнопкой включили прозрачность: третий режим — это нажатие, запрос снимается.
        request.onReported(ListeningMode.TRANSPARENCY, nowMs = 1_300)
        assertThat(request.pending).isNull()
        assertThat(request.due(nowMs = 1_700)).isNull()
    }

    @Test
    fun `старый режим сразу после команды пропускаем, а позже — это нажатие`() {
        val request = ModeRequest(retryMs = 700)
        request.onReported(ListeningMode.NOISE_CANCELLATION, nowMs = 0)
        request.request(ListeningMode.TRANSPARENCY, nowMs = 1_000)
        request.onReported(ListeningMode.NOISE_CANCELLATION, nowMs = 1_100)
        assertThat(request.pending).isEqualTo(ListeningMode.TRANSPARENCY)
        // Команда повторена, наушники всё ещё в старом режиме, а потом кнопкой вернули его же.
        assertThat(request.due(nowMs = 1_700)).isEqualTo(ListeningMode.TRANSPARENCY)
        request.onReported(ListeningMode.NOISE_CANCELLATION, nowMs = 2_500)
        assertThat(request.pending).isNull()
    }
}
