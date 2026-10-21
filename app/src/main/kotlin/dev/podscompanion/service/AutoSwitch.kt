package dev.podscompanion.service

import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.SystemClock
import android.view.KeyEvent
import dev.podscompanion.bluetooth.connect.TakeOverResult
import dev.podscompanion.bluetooth.scan.ConnectedAudioDevice
import dev.podscompanion.bluetooth.scan.ConnectedAudioDevices
import dev.podscompanion.data.ConnectedNameMatcher
import dev.podscompanion.data.PodsStatus
import dev.podscompanion.data.PodsTakeOver
import dev.podscompanion.data.aap.AapLog
import dev.podscompanion.data.settings.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Переключение «как у iPhone и Mac» со стороны телефона: включили музыку на телефоне, а надетые
 * наушники сейчас подключены к другому устройству (например, к ПК) — забираем их на телефон.
 * В обратную сторону то же делает программа на ПК, когда звук включают там.
 *
 * Начало звука узнаём через [AudioManager.AudioPlaybackCallback]: он не требует разрешений
 * и сообщает о любом плеере, в том числе без уведомления.
 */
internal class AutoSwitch(
    private val audioManager: AudioManager,
    private val connectedAudio: ConnectedAudioDevices,
    private val takeOver: PodsTakeOver,
    private val log: AapLog,
    private val settings: () -> AppSettings,
    /** Наушники из последнего скана рекламы. */
    private val nearby: () -> List<PodsStatus>,
    private val isWorn: (PodsStatus) -> Boolean,
    /** Разбудить скан рекламы, если он уснул. */
    private val wakeScan: () -> Unit,
) {
    private var scope: CoroutineScope? = null
    private var mediaPlaying = false
    private var busy = false
    /** До этого момента сами не забираем: после своей паузы/продолжения, неудачи или ухода наушников. */
    private var quietUntilMs = 0L
    private var hadPods = false

    private val playbackCallback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>) {
            val playing = configs.any { it.audioAttributes.usage in MEDIA_USAGES }
            val started = playing && !mediaPlaying
            mediaPlaying = playing
            if (started) onPlaybackStarted()
        }
    }

    fun run(scope: CoroutineScope) {
        this.scope = scope
        mediaPlaying = audioManager.activePlaybackConfigurations.any { it.audioAttributes.usage in MEDIA_USAGES }
        audioManager.registerAudioPlaybackCallback(playbackCallback, null)
        scope.launch {
            // Наушники только что ушли с телефона (например, их забрал ПК): музыка на телефоне могла
            // ещё доиграть секунду из динамика — это не повод тянуть их обратно.
            connectedAudio.devices().collect { devices ->
                val has = devices.orEmpty().any(::isApple)
                if (hadPods && !has) quietUntilMs = SystemClock.elapsedRealtime() + LEFT_QUIET_MS
                hadPods = has
            }
        }
    }

    fun stop() {
        runCatching { audioManager.unregisterAudioPlaybackCallback(playbackCallback) }
    }

    private fun onPlaybackStarted() {
        val scope = scope ?: return
        if (!settings().autoSwitch || busy || SystemClock.elapsedRealtime() < quietUntilMs) return
        busy = true
        scope.launch {
            try {
                // Короткие звуки (превью видео, звук уведомления через медиаканал) не повод забирать
                // наушники с ПК: ждём, что звук играет дольше пары секунд.
                delay(SUSTAINED_PLAY_MS)
                if (!mediaPlaying) {
                    log.add("звук на телефоне был коротким — наушники не забираю")
                    return@launch
                }
                // Скан мог уснуть, пока наушников не было у телефона: будим и даём ему услышать рекламу.
                val target = findTarget() ?: run {
                    wakeScan()
                    delay(SCAN_WAKE_MS)
                    findTarget()
                } ?: return@launch
                val connected = connectedAudio.devices().filterNotNull().first()
                if (connected.any(::isApple)) return@launch
                log.add("звук на телефоне, наушники надеты, но не у телефона")
                // Пока подключаемся, звук шёл бы из динамика: ставим на паузу и продолжаем уже в наушниках.
                quietUntilMs = SystemClock.elapsedRealtime() + OWN_KEYS_QUIET_MS
                sendMediaKey(KeyEvent.KEYCODE_MEDIA_PAUSE)
                val result = takeOver.takeOver(target, "звук на телефоне")
                if (result is TakeOverResult.Connected) {
                    // Даём системе переключить звук на наушники, иначе первые доли секунды сыграют из динамика.
                    delay(ROUTE_SETTLE_MS)
                    quietUntilMs = SystemClock.elapsedRealtime() + OWN_KEYS_QUIET_MS
                } else {
                    log.add("забрать (звук на телефоне): не вышло, пробую снова через ${FAILED_QUIET_MS / 60_000} мин")
                    quietUntilMs = SystemClock.elapsedRealtime() + FAILED_QUIET_MS
                }
                // Пользователь нажал «играть» — продолжаем в любом случае, даже если забрать не вышло.
                sendMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY)
            } finally {
                busy = false
            }
        }
    }

    /**
     * Свои надетые наушники, которых нет среди подключённых к телефону. Только узнанные по ключу
     * из рекламы ([PodsStatus.owner]): рядом часто чужие AirPods той же модели, и по ним
     * телефон забрал бы с ПК наши, хотя на голове сейчас не они.
     */
    private fun findTarget(): PodsStatus? = nearby()
        .firstOrNull { it.owner != null && !it.connected && it.advertised && it.earKnown && isWorn(it) }

    private fun sendMediaKey(code: Int) {
        audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }

    private fun isApple(device: ConnectedAudioDevice): Boolean =
        device.supportsAap || ConnectedNameMatcher.knownModels(listOf(device.name)).isNotEmpty()

    private companion object {
        val MEDIA_USAGES = setOf(AudioAttributes.USAGE_MEDIA, AudioAttributes.USAGE_GAME)
        const val ROUTE_SETTLE_MS = 1_000L
        const val SUSTAINED_PLAY_MS = 2_500L
        const val SCAN_WAKE_MS = 3_000L
        const val OWN_KEYS_QUIET_MS = 3_000L
        const val LEFT_QUIET_MS = 15_000L
        const val FAILED_QUIET_MS = 2 * 60_000L
    }
}
