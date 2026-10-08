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
import java.util.concurrent.ConcurrentHashMap
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Держит AAP-соединения со всеми подключёнными наушниками Apple и переподключается при обрыве.
 * Состояние общее для сервиса и экрана (stateIn), поэтому на каждые наушники открывается один
 * сокет, сколько бы подписчиков ни было. Без подписчиков соединения закрываются через 5 с.
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

    /** Команды для каждого соединения (по адресу). CONFLATED: важна только последняя команда. */
    private val outgoing = ConcurrentHashMap<String, Channel<ByteArray>>()

    val state: StateFlow<AapSessions> = connectedAudio.devices()
        .map { devices -> devices?.filter(::isApple) }
        // Заряд по HFP меняется часто, а переподключаться из-за этого не нужно: сравниваем адреса.
        .distinctUntilChanged { a, b -> a?.map { it.device.address } == b?.map { it.device.address } }
        .flatMapLatest { targets ->
            when {
                targets == null -> flowOf(AapSessions(noPermission = true))
                targets.isEmpty() -> flowOf(AapSessions())
                else -> combine(targets.map(::session)) { AapSessions(sessions = it.toList()) }
            }
        }
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), AapSessions())

    /** Кнопка «Проверить расширенный режим»: не ждать паузы между попытками. */
    fun retryNow() {
        retryRequests.tryEmit(Unit)
    }

    /** Переключить шумоподавление у наушников [address]. Ничего не делает без прямого подключения. */
    fun setListeningMode(address: String, mode: ListeningMode) {
        val session = state.value.sessions.firstOrNull { it.address == address }
        if (session is AapSessionState.Connected) {
            log.add("${session.deviceName}: выбран режим $mode")
            outgoing[address]?.trySend(Aap.setListeningMode(mode))
        }
    }

    private fun isApple(device: ConnectedAudioDevice): Boolean =
        device.supportsAap || ConnectedNameMatcher.knownModels(listOf(device.name)).isNotEmpty()

    private fun session(target: ConnectedAudioDevice): Flow<AapSessionState> = flow {
        val address = target.device.address
        val commands = Channel<ByteArray>(Channel.CONFLATED).also { outgoing[address] = it }
        var attempt = 0
        while (true) {
            attempt++
            emit(AapSessionState.Connecting(target.name, address, attempt))
            log.add("${target.name}: подключение, попытка $attempt")
            var device = AapDeviceState()
            var connected = false
            var method = "?"
            val failure = runCatching {
                client.connect(target.device, commands).collect { io ->
                    when (io) {
                        is AapIo.Connected -> {
                            connected = true
                            attempt = 0
                            method = io.method
                            log.add("${target.name}: подключено (${io.method})")
                            emit(AapSessionState.Connected(target.name, address, io.method, device))
                        }
                        is AapIo.Sent -> log.add("→ ${Hex.encode(io.data)}")
                        is AapIo.Received -> {
                            val event = AapParser.parse(io.data)
                            log.add("← ${Hex.encode(io.data)}" + (event?.let { " · ${it.label()}" } ?: ""))
                            if (event != null) {
                                val updated = device.apply(event)
                                if (updated != device) {
                                    device = updated
                                    emit(AapSessionState.Connected(target.name, address, method, device))
                                }
                            }
                        }
                    }
                }
            }.exceptionOrNull()

            val reason = if (failure is L2capUnavailableException) FailureReason.SOCKET_BLOCKED else FailureReason.CONNECTION_FAILED
            val details = (failure?.cause ?: failure)?.let { "${it::class.simpleName}: ${it.message}" } ?: "соединение закрыто"
            log.add("${target.name}: ошибка: $details")
            val delaySec = if (connected) 1 else BACKOFF_SEC[(attempt - 1).coerceIn(0, BACKOFF_SEC.lastIndex)]
            emit(AapSessionState.Failed(target.name, address, reason, details, delaySec))
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
