package dev.podscompanion.data.aap

import dev.podscompanion.bluetooth.aap.AapClient
import dev.podscompanion.bluetooth.aap.AapIo
import dev.podscompanion.bluetooth.aap.L2capUnavailableException
import dev.podscompanion.bluetooth.scan.ConnectedAudioDevice
import dev.podscompanion.bluetooth.scan.ConnectedAudioDevices
import dev.podscompanion.data.ConnectedNameMatcher
import dev.podscompanion.protocol.aap.Aap
import dev.podscompanion.protocol.aap.AapDeviceState
import dev.podscompanion.protocol.aap.AapEvent
import dev.podscompanion.protocol.aap.AapParser
import dev.podscompanion.protocol.aap.ListeningMode
import dev.podscompanion.protocol.util.Hex
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withTimeoutOrNull

/** Состояние прямого подключения к наушникам (расширенный режим). */
sealed interface AapSessionState {
    /** Нет разрешения «Устройства поблизости»: не знаем, что подключено. */
    data object NoPermission : AapSessionState

    /** К телефону не подключены наушники Apple. */
    data object NoDevice : AapSessionState

    data class Connecting(val deviceName: String, val attempt: Int) : AapSessionState

    data class Connected(val deviceName: String, val method: String, val device: AapDeviceState) : AapSessionState

    data class Failed(val deviceName: String, val reason: FailureReason, val details: String, val retryInSec: Int) :
        AapSessionState
}

enum class FailureReason {
    /** Android не дал создать L2CAP-сокет: на этой прошивке без root не работает. */
    SOCKET_BLOCKED,

    /** Сокет есть, но наушники не приняли подключение или оборвали его. */
    CONNECTION_FAILED,
}

/**
 * Держит одно AAP-соединение с подключёнными наушниками Apple и переподключается при обрыве.
 * Состояние общее для сервиса и экрана (stateIn), поэтому сокет открывается один, сколько бы
 * подписчиков ни было. Без подписчиков соединение закрывается через 5 с.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class AapRepository @Inject constructor(
    connectedAudio: ConnectedAudioDevices,
    private val client: AapClient,
    private val log: AapLog,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val retryRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    /** Команды для текущего соединения. CONFLATED: важна только последняя (два быстрых нажатия → один режим). */
    private val outgoing = Channel<ByteArray>(Channel.CONFLATED)

    private sealed interface Target {
        data object NoPermission : Target
        data object None : Target
        data class Device(val device: ConnectedAudioDevice) : Target
    }

    val state: StateFlow<AapSessionState> = connectedAudio.devices()
        .map { devices ->
            when (val target = devices?.let(::pickTarget)) {
                null -> if (devices == null) Target.NoPermission else Target.None
                else -> Target.Device(target)
            }
        }
        // Заряд по HFP меняется часто, а переподключаться из-за этого не нужно: сравниваем адрес.
        .distinctUntilChanged { a, b ->
            if (a is Target.Device && b is Target.Device) a.device.device.address == b.device.device.address else a == b
        }
        .flatMapLatest { target ->
            when (target) {
                Target.NoPermission -> flowOf(AapSessionState.NoPermission)
                Target.None -> flowOf(AapSessionState.NoDevice)
                is Target.Device -> session(target.device)
            }
        }
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), AapSessionState.NoDevice)

    /** Кнопка «Проверить расширенный режим»: не ждать паузы между попытками. */
    fun retryNow() {
        retryRequests.tryEmit(Unit)
    }

    /** Переключить шумоподавление. Ничего не делает, если прямого подключения сейчас нет. */
    fun setListeningMode(mode: ListeningMode) {
        if (state.value is AapSessionState.Connected) {
            log.add("выбран режим $mode")
            outgoing.trySend(Aap.setListeningMode(mode))
        }
    }

    private fun pickTarget(devices: List<ConnectedAudioDevice>): ConnectedAudioDevice? =
        devices.firstOrNull { it.supportsAap }
            ?: devices.firstOrNull { ConnectedNameMatcher.knownModels(listOf(it.name)).isNotEmpty() }

    private fun session(target: ConnectedAudioDevice): Flow<AapSessionState> = flow {
        var attempt = 0
        while (true) {
            attempt++
            emit(AapSessionState.Connecting(target.name, attempt))
            log.add("подключение к ${target.name}, попытка $attempt")
            var device = AapDeviceState()
            var connected = false
            var method = "?"
            val failure = runCatching {
                client.connect(target.device, outgoing).collect { io ->
                    when (io) {
                        is AapIo.Connected -> {
                            connected = true
                            attempt = 0
                            method = io.method
                            log.add("подключено (${io.method})")
                            emit(AapSessionState.Connected(target.name, io.method, device))
                        }
                        is AapIo.Sent -> log.add("→ ${Hex.encode(io.data)}")
                        is AapIo.Received -> {
                            val event = AapParser.parse(io.data)
                            log.add("← ${Hex.encode(io.data)}" + (event?.let { " · ${it.label()}" } ?: ""))
                            if (event != null) {
                                val updated = device.apply(event)
                                if (updated != device) {
                                    device = updated
                                    emit(AapSessionState.Connected(target.name, method, device))
                                }
                            }
                        }
                    }
                }
            }.exceptionOrNull()

            val reason = if (failure is L2capUnavailableException) FailureReason.SOCKET_BLOCKED else FailureReason.CONNECTION_FAILED
            val details = (failure?.cause ?: failure)?.let { "${it::class.simpleName}: ${it.message}" } ?: "соединение закрыто"
            log.add("ошибка: $details")
            val delaySec = if (connected) 1 else BACKOFF_SEC[(attempt - 1).coerceIn(0, BACKOFF_SEC.lastIndex)]
            emit(AapSessionState.Failed(target.name, reason, details, delaySec))
            withTimeoutOrNull(delaySec * 1_000L) { retryRequests.first() }
        }
    }

    private fun AapEvent.label(): String = when (this) {
        is AapEvent.Battery -> "заряд " + components.joinToString { "${it.component}=${it.percent}%" + if (it.charging) "⚡" else "" }
        is AapEvent.EarDetection -> "ухо $primary/$secondary"
        is AapEvent.ListeningModeChanged -> "режим $mode"
        is AapEvent.ConversationalAwarenessChanged -> "адаптация к разговору ${if (enabled) "вкл" else "выкл"}"
        is AapEvent.Unknown -> "неизвестный 0x%04X".format(opcode)
    }

    private companion object {
        val BACKOFF_SEC = intArrayOf(3, 10, 30, 60)
    }
}
