package dev.podscompanion.service

import android.bluetooth.BluetoothDevice
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.SystemClock
import dev.podscompanion.bluetooth.route.AudioRoute
import dev.podscompanion.bluetooth.scan.ConnectedAudioDevice
import dev.podscompanion.bluetooth.scan.ConnectedAudioDevices
import dev.podscompanion.data.ConnectedNameMatcher
import dev.podscompanion.data.PodsStatus
import dev.podscompanion.data.aap.AapLog
import dev.podscompanion.data.isOffHead
import dev.podscompanion.data.popup.LiveStatus
import dev.podscompanion.data.route.MediaRoute
import dev.podscompanion.data.route.OffHeadMediaPolicy
import dev.podscompanion.data.settings.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * «Музыка и видео в телефон, если наушники сняты».
 *
 * Сняли наушники — просим Android перестать выводить звук в них ([AudioRoute.releaseMedia]):
 * подключение остаётся, а звук идёт в динамик телефона. Если система не пускает — отключаем
 * от наушников только музыку (A2DP); звонки и прямое подключение (по нему видно уши) остаются.
 * Надели — возвращаем звук тем же путём. Когда решать, говорит [OffHeadMediaPolicy].
 * Каждый шаг пишется в журнал соединения с пометкой «снятые:».
 */
internal class OffHeadAudio(
    private val audioManager: AudioManager,
    private val connectedAudio: ConnectedAudioDevices,
    private val route: AudioRoute,
    private val liveStatus: LiveStatus,
    private val aapLog: AapLog,
) {
    /** Как увели звук: по этому выбираем, как вернуть. */
    private enum class Moved { NONE, RELEASED }

    private val policy = OffHeadMediaPolicy()
    private val lock = Mutex()
    private var enabled = false
    private var lastOffHead: Boolean? = null

    @Volatile private var moved = Moved.NONE
    private var movedDevice: BluetoothDevice? = null

    /** Звук сейчас в телефоне из-за нас: сервису нельзя засыпать, иначе не заметит, что наушники надели. */
    val holding: Boolean get() = moved != Moved.NONE || switching

    /** Идёт перевод: звук из наушников уже ушёл, а [moved] ещё не записан. */
    @Volatile private var switching = false

    fun run(scope: CoroutineScope, settingsFlow: Flow<AppSettings>) {
        scope.launch {
            combine(
                liveStatus.status,
                connectedAudio.devices(),
                settingsFlow.map { it.mediaToPhone }.distinctUntilChanged(),
            ) { status, devices, on -> Triple(status, devices, on) }
                .collectLatest { (status, devices, on) ->
                    evaluate(status, devices.orEmpty(), on)
                    // Пока наушники сняты, а звук ещё не уведён, проверяем раз в секунду:
                    // задержка снятия и только что подключённые наушники ждут без новых событий.
                    while (on && (policy.waiting(SystemClock.elapsedRealtime()))) {
                        delay(TICK_MS)
                        evaluate(status, devices.orEmpty(), on)
                    }
                }
        }
    }

    /**
     * Наушники надели и сейчас пойдёт автопродолжение: сначала вернуть звук в наушники,
     * иначе первые секунды сыграют из динамика.
     */
    suspend fun restoreNow() = withContext(NonCancellable) {
        lock.withLock {
            if (policy.takeRestore()) restore()
        }
    }

    private suspend fun evaluate(status: PodsStatus?, devices: List<ConnectedAudioDevice>, on: Boolean) =
        withContext(NonCancellable) {
            lock.withLock {
                if (!on) {
                    if (enabled) log("выключено")
                    enabled = false
                    if (policy.takeRestore()) restore()
                    policy.reset()
                    return@withLock
                }
                if (!enabled) log("включено")
                enabled = true

                val pods = findPods(status, devices)
                // Наушники отключились от звука (убрали в кейс): возвращать нечего,
                // при следующем подключении Android сам пустит звук в них.
                if (pods == null) {
                    if (moved != Moved.NONE) log("наушники отключились")
                    clearMoved()
                    policy.reset()
                    lastOffHead = null
                    return@withLock
                }
                val now = SystemClock.elapsedRealtime()
                val ours = status?.takeIf { it.earKnown && it.connected }
                val offHead = ours?.isOffHead
                if (offHead != null && offHead != lastOffHead) log(if (offHead) "наушники сняты" else "наушники надеты")
                if (offHead != null) lastOffHead = offHead

                val active = pods != null && route.isActive(pods)
                val busy = audioManager.mode != AudioManager.MODE_NORMAL
                when (policy.onUpdate(offHead, active, busy, now)) {
                    MediaRoute.PHONE -> if (pods != null) moveToPhone(pods) else policy.onMoveFailed()
                    MediaRoute.HEADPHONES -> restore()
                    null -> Unit
                }
            }
        }

    private suspend fun moveToPhone(pods: BluetoothDevice) {
        switching = true
        try {
            tryMoveToPhone(pods)
        } finally {
            switching = false
        }
    }

    private suspend fun tryMoveToPhone(pods: BluetoothDevice) {
        log("перевожу звук в телефон")
        if (route.releaseMedia(::log)) {
            if (waitFor(SWITCH_WAIT_MS) { !route.isActive(pods) }) {
                setMoved(Moved.RELEASED, pods)
                log("звук в телефоне")
                return
            }
            log("система приняла запрос, но звук остался в наушниках")
        }
        // Отключать наушники от музыки нельзя: без связи с телефоном AirPods уходят к другому
        // устройству Apple (iPhone, Mac, ПК) и возвращаются только через несколько секунд.
        log("перевести не вышло, наушники не трогаю")
        policy.onMoveFailed()
    }

    private suspend fun restore() {
        val device = movedDevice
        val how = moved
        clearMoved()
        if (device == null || how == Moved.NONE) return
        // Пока наушники лежали, звук мог уйти в машину или другую колонку: его не отбираем.
        if (otherBluetoothActive(device)) {
            log("звук уже в другом Bluetooth-устройстве — не трогаю")
            return
        }
        log("возвращаю звук в наушники")
        val ok = route.restoreMedia(device, ::log) || route.makeActive(device, ::log)
        val back = ok && waitFor(SWITCH_WAIT_MS) { route.isActive(device) }
        log(if (back) "звук в наушниках" else "вернуть не вышло — выберите наушники в окне вывода звука")
    }

    /** Свои наушники среди подключённых к звуку: по адресу из ключа, иначе первые наушники Apple. */
    private fun findPods(status: PodsStatus?, devices: List<ConnectedAudioDevice>): BluetoothDevice? {
        val owner = status?.owner
        if (owner != null) {
            devices.firstOrNull { it.device.address.equals(owner, ignoreCase = true) }?.let { return it.device }
        }
        return devices.firstOrNull(::isApple)?.device
    }

    private fun isApple(device: ConnectedAudioDevice): Boolean =
        device.supportsAap || ConnectedNameMatcher.knownModels(listOf(device.name)).isNotEmpty()

    private fun otherBluetoothActive(pods: BluetoothDevice): Boolean =
        audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP } &&
            !route.isActive(pods)

    private suspend fun waitFor(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val until = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < until) {
            if (condition()) return true
            delay(POLL_MS)
        }
        return condition()
    }

    private fun setMoved(how: Moved, device: BluetoothDevice) {
        movedDevice = device
        moved = how
    }

    private fun clearMoved() {
        moved = Moved.NONE
        movedDevice = null
    }

    private fun log(message: String) = aapLog.add("снятые: $message")

    private companion object {
        const val TICK_MS = 1_000L
        const val POLL_MS = 250L
        const val SWITCH_WAIT_MS = 2_000L
    }
}
