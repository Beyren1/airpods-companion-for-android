package dev.podscompanion.service

import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.SystemClock
import dev.podscompanion.bluetooth.scan.ConnectedAudioDevice
import dev.podscompanion.bluetooth.scan.ConnectedAudioDevices
import dev.podscompanion.data.ConnectedNameMatcher
import dev.podscompanion.data.PodsStatus
import dev.podscompanion.data.aap.AapRepository
import dev.podscompanion.data.aap.AapSessionState
import dev.podscompanion.data.aap.AapSessions
import dev.podscompanion.data.stats.UsageAttribution
import dev.podscompanion.data.stats.UsageStatsStore
import dev.podscompanion.protocol.aap.EarState
import dev.podscompanion.protocol.advertising.Capability
import java.time.LocalDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Статистика: раз в [TICK_MS] смотрим, какие наушники Apple подключены, в ушах ли они, играет ли
 * музыка и идёт ли разговор, и добавляем прошедшее время к счётчикам сегодняшнего дня.
 *
 * Работает внутри фонового сервиса, поэтому считается только при включённом фоновом режиме.
 * Пары различаем по адресу Bluetooth, модель берём из рекламы (по ней рисуется картинка).
 */
internal class UsageTracking(
    private val audioManager: AudioManager,
    private val connectedAudio: ConnectedAudioDevices,
    private val aap: AapRepository,
    private val store: UsageStatsStore,
    /** Все наушники рядом из последнего скана (главные и остальные). */
    private val nearby: () -> List<PodsStatus>,
) {
    private var devices: List<ConnectedAudioDevice> = emptyList()
    private var sessions = AapSessions()

    fun run(scope: CoroutineScope) {
        scope.launch { connectedAudio.devices().collect { devices = it.orEmpty() } }
        scope.launch { aap.state.collect { sessions = it } }
        scope.launch {
            store.load()
            var last = SystemClock.elapsedRealtime()
            var ticks = 0
            while (true) {
                delay(TICK_MS)
                val now = SystemClock.elapsedRealtime()
                // Если телефон надолго «уснул» и таймер опоздал, лишнее время не засчитываем.
                val seconds = ((now - last) / 1000).coerceAtMost(MAX_STEP_SEC)
                last = now
                tick(seconds)
                if (++ticks % FLUSH_EVERY == 0) store.flush()
            }
        }
    }

    /** Сервис останавливается: дописать в файл то, что ещё в памяти. */
    suspend fun flush() = store.flush()

    private suspend fun tick(seconds: Long) {
        if (seconds <= 0) return
        val apple = devices.filter { it.supportsAap || ConnectedNameMatcher.knownModels(listOf(it.name)).isNotEmpty() }
        if (apple.isEmpty()) return
        val statuses = nearby()
        val connected = apple.map { device ->
            val address = device.device.address
            val status = statusFor(address, device.name, statuses, apple.size)
            UsageAttribution.Connected(
                address = address,
                name = device.name,
                modelId = status?.modelId ?: ConnectedNameMatcher.modelsForName(device.name).singleOrNull()?.modelId,
                worn = isWorn(address, status),
            )
        }
        val call = callActive()
        val activity = UsageAttribution.attribute(connected, musicActive = audioManager.isMusicActive, callActive = call)
        store.record(activity, LocalDate.now(), seconds, System.currentTimeMillis())
    }

    /**
     * Пакет рекламы именно этой пары: свои наушники узнаём по ключу (owner = адрес пары),
     * иначе — подключённые наушники рядом той модели, на которую похоже имя.
     */
    private fun statusFor(address: String, name: String, statuses: List<PodsStatus>, connectedCount: Int): PodsStatus? {
        statuses.firstOrNull { it.owner == address }?.let { return it }
        val connected = statuses.filter { it.connected && it.owner == null }
        val models = ConnectedNameMatcher.modelsForName(name)
        return connected.filter { it.model in models }.singleOrNull()
            ?: connected.singleOrNull()?.takeIf { connectedCount == 1 }
    }

    /** В ушах хотя бы один наушник. Прямое подключение (AAP) точнее и быстрее рекламы. */
    private fun isWorn(address: String, status: PodsStatus?): Boolean {
        val session = sessions.sessions.firstOrNull { it.address == address } as? AapSessionState.Connected
        val device = session?.device
        if (device != null && device.earKnown) {
            return device.primaryEar == EarState.IN_EAR || device.secondaryEar == EarState.IN_EAR
        }
        status ?: return false
        val stereo = status.model?.capabilities?.contains(Capability.STEREO_BUDS) ?: true
        return if (stereo) status.left.inEar || status.right.inEar else status.primary.inEar
    }

    /** Идёт телефонный или интернет-звонок (Telegram, WhatsApp), и голос идёт через Bluetooth. */
    private fun callActive(): Boolean {
        val mode = audioManager.mode
        if (mode != AudioManager.MODE_IN_CALL && mode != AudioManager.MODE_IN_COMMUNICATION) return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.communicationDevice?.type.let {
                it == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it == AudioDeviceInfo.TYPE_BLE_HEADSET
            }
        } else {
            @Suppress("DEPRECATION")
            audioManager.isBluetoothScoOn
        }
    }

    private companion object {
        const val TICK_MS = 10_000L
        const val MAX_STEP_SEC = 30L
        /** Писать в файл раз в минуту (6 тиков по 10 с). */
        const val FLUSH_EVERY = 6
    }
}
