package dev.podscompanion.data.stem

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.view.KeyEvent
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.podscompanion.data.aap.AapLog
import dev.podscompanion.data.aap.AapRepository
import dev.podscompanion.data.aap.AapSessionState
import dev.podscompanion.data.settings.AppSettings
import dev.podscompanion.data.settings.SettingsRepository
import dev.podscompanion.data.settings.StemAction
import dev.podscompanion.protocol.aap.AapCommand
import dev.podscompanion.protocol.aap.ControlId
import dev.podscompanion.protocol.aap.ListeningMode
import dev.podscompanion.protocol.aap.StemPress
import dev.podscompanion.protocol.aap.modeCycle
import dev.podscompanion.protocol.aap.stemConfigMask
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Свои действия на нажатия ножки и затвор камеры.
 *
 * Наушникам говорим, какие нажатия не выполнять самим, а присылать телефону ([AapCommand.SetStemConfig]),
 * и выполняем выбранное действие. Работает, пока запущен фоновый сервис: когда он останавливается,
 * [release] возвращает наушникам обычное поведение, иначе нажатия ничего бы не делали.
 */
@Singleton
class StemActionController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    private val aap: AapRepository,
    private val log: AapLog,
) {
    @Volatile private var current = AppSettings()

    /** Что уже отправили каждым подключённым наушникам; сбрасывается, когда соединение пропало. */
    private val sent = HashMap<String, Set<StemPress>>()

    /** Запускается сервисом и живёт, пока работает фоновый режим. */
    fun run(scope: CoroutineScope) {
        scope.launch {
            combine(settings.settings, aap.state, CameraBridge.cameraOpen) { s, sessions, camera ->
                current = s
                Triple(s, sessions, camera)
            }.collect { (s, sessions, camera) ->
                val wanted = wantedPresses(s, camera)
                val connected = sessions.sessions.filterIsInstance<AapSessionState.Connected>().filter(::hasStem)
                // Новое подключение начинается с обычного поведения наушников: маску отправляем заново.
                sent.keys.retainAll(connected.map { it.address }.toSet())
                connected.forEach { session ->
                    if (sent[session.address] == wanted) return@forEach
                    // Обычное поведение после подключения отправлять не нужно.
                    if (sent[session.address] == null && wanted.isEmpty() && session.device.stemConfigMask == 0) return@forEach
                    sent[session.address] = wanted
                    aap.send(session.address, AapCommand.SetStemConfig(wanted))
                }
            }
        }
        scope.launch {
            aap.stemPresses.collect { sample -> handle(sample.address, sample.event.press) }
        }
    }

    /** Сервис останавливается: пусть наушники снова сами выполняют все нажатия. */
    fun release() {
        sent.clear()
        aap.state.value.sessions.filterIsInstance<AapSessionState.Connected>()
            .filter { it.device.stemConfigMask != 0 }
            .forEach { aap.send(it.address, AapCommand.SetStemConfig(emptySet())) }
    }

    private fun wantedPresses(s: AppSettings, cameraOpen: Boolean): Set<StemPress> =
        s.stemActions.keys + if (cameraOpen && s.cameraShutter) setOf(StemPress.SINGLE) else emptySet()

    /** Ножка есть у наушников, которые сообщают настройку зажатия (у Max её нет). */
    private fun hasStem(session: AapSessionState.Connected) = ControlId.PRESS_AND_HOLD in session.device.controls

    private fun handle(address: String, press: StemPress) {
        val s = current
        if (press == StemPress.SINGLE && s.cameraShutter && CameraBridge.cameraOpen.value) {
            val shot = CameraBridge.shutter?.invoke() == true
            log.add(if (shot) "камера: снимок" else "камера: не нашёл кнопку съёмки")
            if (shot) return
        }
        val action = s.stemActions[press] ?: run {
            // Нажатие пришло, хотя своего действия нет (например, камеру только что закрыли): делаем обычное.
            defaultFor(press)?.let(::sendMediaKey)
            return
        }
        log.add("нажатие $press: $action")
        when (action) {
            StemAction.PLAY_PAUSE -> sendMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            StemAction.NEXT_TRACK -> sendMediaKey(KeyEvent.KEYCODE_MEDIA_NEXT)
            StemAction.PREVIOUS_TRACK -> sendMediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
            StemAction.ASSISTANT -> startAssistant()
            StemAction.NOISE_MODE -> nextMode(address)
        }
    }

    private fun defaultFor(press: StemPress): Int? = when (press) {
        StemPress.SINGLE -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
        StemPress.DOUBLE -> KeyEvent.KEYCODE_MEDIA_NEXT
        StemPress.TRIPLE -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
        StemPress.LONG -> null
    }

    private fun sendMediaKey(code: Int) {
        val audio = context.getSystemService(AudioManager::class.java)
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }

    /** Голосовой помощник. Из фона Android открывает его, только если есть разрешение «Поверх других приложений». */
    private fun startAssistant() {
        val intent = Intent(Intent.ACTION_VOICE_COMMAND).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }.onFailure { log.add("ассистент не открылся: ${it.message}") }
    }

    /** Следующий режим из тех, что перебирает зажатие ножки. */
    private fun nextMode(address: String) {
        val device = (aap.state.value.sessions.firstOrNull { it.address == address } as? AapSessionState.Connected)?.device ?: return
        val cycle = ORDER.filter { it in (device.modeCycle ?: DEFAULT_CYCLE) }.ifEmpty { DEFAULT_CYCLE.toList() }
        val next = cycle[(cycle.indexOf(device.listeningMode) + 1) % cycle.size]
        aap.send(address, AapCommand.SetListeningMode(next))
    }

    private companion object {
        val ORDER = listOf(ListeningMode.OFF, ListeningMode.NOISE_CANCELLATION, ListeningMode.TRANSPARENCY, ListeningMode.ADAPTIVE)
        val DEFAULT_CYCLE = setOf(ListeningMode.NOISE_CANCELLATION, ListeningMode.TRANSPARENCY)
    }
}
