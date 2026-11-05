package dev.podscompanion.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.SystemClock
import android.view.KeyEvent
import androidx.core.content.ContextCompat
import dev.podscompanion.data.aap.AapLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Смена пользователя (профиля) на телефоне.
 *
 * При переключении профиля Android перезапускает Bluetooth: наушники отключаются и подключаются
 * заново уже после возврата. В эти секунды наше приложение не должно включать музыку само
 * (автопродолжение, автозапуск плеера), а если какой-то плеер сам заиграл через динамик, пока
 * наушники ещё не вернулись, ставим его на паузу.
 *
 * Пока наш профиль в фоне, автопаузу и перевод звука не трогаем вообще: телефоном пользуется
 * другой профиль.
 */
internal class UserSwitchGuard(
    private val context: Context,
    private val audioManager: AudioManager,
    private val aapLog: AapLog,
    /** Подключены ли сейчас наушники Apple к звуку (A2DP). */
    private val appleAudioConnected: () -> Boolean,
    /** Профиль снова наш: сбросить всё, что помнили до переключения. */
    private val onReturned: () -> Unit,
) {
    /** Наш профиль сейчас не на экране. */
    @Volatile var background = false
        private set

    @Volatile private var returnedAtMs = Long.MIN_VALUE / 2
    private var podsWereConnected = false
    private var watchJob: Job? = null
    private var scope: CoroutineScope? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_USER_BACKGROUND -> onBackground()
                Intent.ACTION_USER_FOREGROUND -> onForeground()
            }
        }
    }

    /** Сразу после возврата в профиль: музыку сами не включаем. */
    fun quiet(nowMs: Long = SystemClock.elapsedRealtime()): Boolean =
        background || nowMs - returnedAtMs < QUIET_MS

    fun register(scope: CoroutineScope) {
        this.scope = scope
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_USER_BACKGROUND)
            addAction(Intent.ACTION_USER_FOREGROUND)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    fun unregister() {
        runCatching { context.unregisterReceiver(receiver) }
        watchJob?.cancel()
    }

    private fun onBackground() {
        background = true
        podsWereConnected = appleAudioConnected()
        watchJob?.cancel()
        aapLog.add("профиль: переключились на другой профиль" + if (podsWereConnected) " (наушники были подключены)" else "")
    }

    private fun onForeground() {
        background = false
        returnedAtMs = SystemClock.elapsedRealtime()
        aapLog.add("профиль: вернулись в этот профиль, ${QUIET_MS / 1000} с музыку сами не включаем")
        onReturned()
        if (!podsWereConnected) return
        podsWereConnected = false
        watchJob?.cancel()
        watchJob = scope?.launch { pauseSpeakerPlayback() }
    }

    /**
     * До переключения музыка шла в наушники. Пока Bluetooth перезапускается, плеер может сам
     * заиграть через динамик: ставим паузу, пока наушники не вернулись.
     */
    private suspend fun pauseSpeakerPlayback() {
        val until = returnedAtMs + QUIET_MS
        while (SystemClock.elapsedRealtime() < until) {
            if (audioManager.mode == AudioManager.MODE_NORMAL && audioManager.isMusicActive && mediaOnSpeaker()) {
                aapLog.add("профиль: музыка заиграла через динамик, пока наушники переподключаются → пауза")
                audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE))
                audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE))
                delay(PAUSE_GAP_MS)
            }
            delay(POLL_MS)
        }
    }

    /** Музыка сейчас выводится в динамик телефона. */
    private fun mediaOnSpeaker(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build()
            val out = runCatching { audioManager.getAudioDevicesForAttributes(attrs) }.getOrNull()
            if (out != null) return out.any { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
        }
        // Старые Android: ни Bluetooth, ни проводных наушников нет — значит, динамик.
        return audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).none { it.type in NON_SPEAKER_TYPES }
    }

    private companion object {
        const val QUIET_MS = 20_000L
        const val POLL_MS = 500L
        const val PAUSE_GAP_MS = 2_000L
        val NON_SPEAKER_TYPES = setOf(
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_USB_HEADSET,
        )
    }
}
