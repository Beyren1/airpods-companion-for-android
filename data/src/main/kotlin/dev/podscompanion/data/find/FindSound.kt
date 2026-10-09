package dev.podscompanion.data.find

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber

/** В какой наушник звук: в оба или в один, если второй уже нашёлся. */
enum class SoundSide { BOTH, LEFT, RIGHT }

/**
 * Звук для поиска наушников, как «Воспроизвести звук» в «Локаторе» iPhone: громкие высокие
 * сигналы, которые слышно из наушника, лежащего под подушкой.
 *
 * Отдельной команды «пищать» в протоколе AAP публично не известно (в описаниях LibrePods такой нет),
 * а «Локатор» шлёт её через сеть Apple, недоступную Android. Поэтому звук играет сам телефон
 * и отправляет его в подключённые наушники, как обычную музыку. Значит, работает, только пока
 * наушники подключены к телефону: вне кейса и рядом они обычно подключаются сами.
 *
 * Пока звучит, громкость музыки на максимуме (вернём прежнюю после остановки), а музыка на паузе.
 * Звук сам выключается через [MAX_PLAY_MS], как на iPhone.
 */
@Singleton
class FindSound @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    /** Метка последнего запуска: старый звук, доигрывая, не сбрасывает состояние нового. */
    @Volatile private var current: Any? = null

    private val _playing = MutableStateFlow<SoundSide?>(null)

    /** Что сейчас играет; null — тихо. */
    val playing: StateFlow<SoundSide?> = _playing.asStateFlow()

    /** Bluetooth-выход для музыки (A2DP, на Android 12+ ещё LE Audio); null — наушники не подключены. */
    fun output(): AudioDeviceInfo? =
        audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.type in BLUETOOTH_MEDIA_TYPES }

    fun play(side: SoundSide) {
        val device = output() ?: return
        val previous = job
        val token = Any()
        current = token
        _playing.value = side
        job = scope.launch {
            // Прошлый звук сначала доиграет свой finally (вернёт громкость), иначе запомним уже максимальную.
            previous?.cancelAndJoin()
            val savedVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(attributes)
                .build()
            var track: AudioTrack? = null
            try {
                audioManager.requestAudioFocus(focus)
                runCatching {
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC), 0)
                }.onFailure { Timber.w(it, "Громкость не поднялась") }
                val buffer = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
                val t = AudioTrack.Builder()
                    .setAudioAttributes(attributes)
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setSampleRate(SAMPLE_RATE)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                            .build(),
                    )
                    .setBufferSizeInBytes(buffer * 4)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
                track = t
                // Только в наушники, даже если система решит иначе.
                t.setPreferredDevice(device)
                t.play()
                val started = System.currentTimeMillis()
                var round = 0
                while (isActive && System.currentTimeMillis() - started < MAX_PLAY_MS) {
                    // Первые сигналы тише, дальше громче: если наушники в ухе, это не так оглушает.
                    val loudness = min(1f, START_LOUDNESS + round * LOUDNESS_STEP)
                    val pcm = FindTone.pattern(SAMPLE_RATE, side, loudness)
                    var written = 0
                    while (isActive && written < pcm.size) {
                        val n = t.write(pcm, written, min(CHUNK, pcm.size - written))
                        if (n < 0) error("AudioTrack.write: $n")
                        written += n
                    }
                    round++
                }
            } catch (e: Exception) {
                Timber.w(e, "Звук поиска не сыграл")
            } finally {
                runCatching { track?.pause(); track?.flush(); track?.release() }
                runCatching { audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, savedVolume, 0) }
                audioManager.abandonAudioFocusRequest(focus)
                if (current === token) _playing.value = null
            }
        }
    }

    fun stop() {
        current = null
        job?.cancel()
        job = null
        _playing.value = null
    }

    private companion object {
        const val SAMPLE_RATE = 44_100
        const val CHUNK = 4_096
        const val MAX_PLAY_MS = 2 * 60_000L
        const val START_LOUDNESS = 0.4f
        const val LOUDNESS_STEP = 0.15f

        val BLUETOOTH_MEDIA_TYPES = buildSet {
            add(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(AudioDeviceInfo.TYPE_BLE_HEADSET)
        }
    }
}

/** Сам звук: три коротких сигнала с растущей высотой и пауза. Чистая функция, тестируется без Android. */
object FindTone {
    private val FREQUENCIES = listOf(2_400.0, 3_000.0, 3_600.0)
    private const val BEEP_MS = 110
    private const val GAP_MS = 50
    private const val PAUSE_MS = 550
    private const val FADE_MS = 6

    /** Стерео 16 бит, L R L R…; в выключенный канал идут нули. */
    fun pattern(sampleRate: Int, side: SoundSide, loudness: Float): ShortArray {
        val beep = sampleRate * BEEP_MS / 1000
        val gap = sampleRate * GAP_MS / 1000
        val pause = sampleRate * PAUSE_MS / 1000
        val fade = sampleRate * FADE_MS / 1000
        val frames = FREQUENCIES.size * (beep + gap) + pause
        val out = ShortArray(frames * 2)
        val left = side != SoundSide.RIGHT
        val right = side != SoundSide.LEFT
        val peak = Short.MAX_VALUE * loudness.coerceIn(0f, 1f) * 0.95
        FREQUENCIES.forEachIndexed { index, frequency ->
            val start = index * (beep + gap)
            for (i in 0 until beep) {
                // Плавные края, иначе на стыках щелчки.
                val envelope = min(1.0, min(i, beep - 1 - i).toDouble() / fade)
                val value = (sin(2 * PI * frequency * i / sampleRate) * envelope * peak).toInt().toShort()
                val frame = (start + i) * 2
                if (left) out[frame] = value
                if (right) out[frame + 1] = value
            }
        }
        return out
    }
}
