package dev.podscompanion.data.aap

import android.os.SystemClock
import dev.podscompanion.bluetooth.aap.AapClient
import dev.podscompanion.bluetooth.aap.AapIo
import dev.podscompanion.bluetooth.aap.L2capUnavailableException
import dev.podscompanion.bluetooth.scan.ConnectedAudioDevice
import dev.podscompanion.bluetooth.scan.ConnectedAudioDevices
import dev.podscompanion.data.ConnectedNameMatcher
import dev.podscompanion.protocol.aap.AapCommand
import dev.podscompanion.protocol.aap.AapDeviceState
import dev.podscompanion.protocol.aap.AapEvent
import dev.podscompanion.protocol.aap.AapParser
import dev.podscompanion.protocol.aap.AapStreams
import dev.podscompanion.protocol.aap.Opcode
import dev.podscompanion.protocol.aap.ControlId
import dev.podscompanion.protocol.util.Hex
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Держит AAP-соединения со всеми подключёнными наушниками Apple и переподключается при обрыве.
 * Состояние общее для сервиса и экрана (stateIn), поэтому на каждые наушники открывается один
 * сокет, сколько бы подписчиков ни было. Без подписчиков соединения закрываются через 5 с.
 */
/** Один пакет датчиков головы: от каких наушников и когда пришёл. */
data class HeadSample(val address: String, val timeMs: Long, val motion: AapEvent.HeadMotion)

@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class AapRepository @Inject constructor(
    connectedAudio: ConnectedAudioDevices,
    private val client: AapClient,
    private val log: AapLog,
    private val keyStore: ProximityKeyStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val retryRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /**
     * Команды для каждого соединения (по адресу). Буфер на 16 команд: несколько настроек подряд
     * не должны затирать друг друга; при переполнении выбрасываются самые старые.
     */
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

    private val _headMotion = MutableSharedFlow<HeadSample>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /**
     * Поток датчиков головы от всех наушников (после [AapCommand.StartHeadTracking]).
     * Идёт десятки раз в секунду, поэтому в состояние и журнал не попадает.
     */
    val headMotion: SharedFlow<HeadSample> = _headMotion

    /** Кнопка «Проверить расширенный режим»: не ждать паузы между попытками. */
    fun retryNow() {
        retryRequests.tryEmit(Unit)
    }

    /**
     * Отправить команду наушникам [address]. Ничего не делает без прямого подключения.
     * Новое значение придёт от наушников ответным уведомлением, по нему и обновится экран.
     */
    fun send(address: String, command: AapCommand) {
        val session = state.value.sessions.firstOrNull { it.address == address }
        if (session is AapSessionState.Connected) {
            log.add("${session.deviceName}: ${command.label}")
            packets(address, command).forEach { outgoing[address]?.trySend(it) }
        }
    }

    /** Потоки 0x17, которые наушники объявили после подключения (см. [AapStreams]). */
    private val announcedStreams = ConcurrentHashMap<String, Set<Int>>()
    private var streamSeq = 1

    /** Сколько пакетов датчиков после включения записать в журнал целиком (для разбора формата). */
    private val motionLogBudget = ConcurrentHashMap<String, Int>()

    /**
     * Датчики головы: кроме потока из описания LibrePods просим и все объявленные наушниками —
     * на наших прошивках поток 14 подтверждается, но данных не шлёт.
     */
    private fun packets(address: String, command: AapCommand): List<ByteArray> {
        val on = when (command) {
            AapCommand.StartHeadTracking -> true.also { motionLogBudget[address] = MOTION_LOG_FULL }
            AapCommand.StopHeadTracking -> false
            else -> return listOf(command.bytes)
        }
        val streams = listOf(AapStreams.DOCUMENTED_HEAD_STREAM) + announcedStreams[address].orEmpty().sorted()
        return streams.distinct().map { AapStreams.request(streamSeq++ % 120 + 1, it, on) }
    }

    private fun isApple(device: ConnectedAudioDevice): Boolean =
        device.supportsAap || ConnectedNameMatcher.knownModels(listOf(device.name)).isNotEmpty()

    private fun session(target: ConnectedAudioDevice): Flow<AapSessionState> = flow {
        val address = target.device.address
        val commands = Channel<ByteArray>(16, BufferOverflow.DROP_OLDEST).also { outgoing[address] = it }
        var attempt = 0
        while (true) {
            attempt++
            emit(AapSessionState.Connecting(target.name, address, attempt))
            log.add("${target.name}: подключение, попытка $attempt")
            var device = AapDeviceState()
            var connected = false
            var method = "?"
            var motionPackets = 0
            var streamPackets = 0
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
                        is AapIo.Sent -> {
                            log.add("→ ${Hex.encode(io.data)}")
                            // Настройку показываем сразу после отправки, не дожидаясь ответа:
                            // некоторые наушники подтверждают её только через несколько секунд.
                            val event = AapParser.parse(io.data)
                            if (event.isSetting()) {
                                device = device.apply(event!!)
                                emit(AapSessionState.Connected(target.name, address, method, device))
                            }
                        }
                        is AapIo.Received -> {
                            val event = AapParser.parse(io.data)
                            if (event is AapEvent.HeadMotion) {
                                // Первые пакеты после включения — целиком: по ним проверяем, где в них лежат углы.
                                val budget = motionLogBudget[address] ?: 0
                                if (budget > 0) {
                                    motionLogBudget[address] = budget - 1
                                    log.add("← ${Hex.encode(io.data)} · датчики головы")
                                }
                                if (motionPackets++ % MOTION_LOG_EVERY == 0) log.add("${target.name}: датчики головы ${event.orientation}")
                                _headMotion.tryEmit(HeadSample(address, SystemClock.elapsedRealtime(), event))
                                return@collect
                            }
                            if (event is AapEvent.ProximityKeys) {
                                // Сами ключи в журнал не пишем: его показывают на скриншотах.
                                log.add("${target.name}: ${event.label()}")
                                scope.launch { keyStore.save(OwnPodsKeys(address, event.irk, event.encryptionKey)) }
                                return@collect
                            }
                            AapStreams.announced(io.data).takeIf { it.isNotEmpty() }?.let { streams ->
                                announcedStreams.merge(address, streams.toSet()) { a, b -> a + b }
                                log.add("${target.name}: потоки ${announcedStreams[address]?.sorted()}")
                            }
                            // Данные потоков идут десятки раз в секунду: в журнал — только первые.
                            val isStreamData = event is AapEvent.Unknown && event.opcode == Opcode.HEAD_TRACKING && io.data.size > STREAM_DATA_MIN
                            if (isStreamData && streamPackets++ >= STREAM_LOG_FIRST) return@collect
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

    private fun AapEvent?.isSetting(): Boolean =
        this is AapEvent.ControlChanged || this is AapEvent.ListeningModeChanged || this is AapEvent.ConversationalAwarenessChanged

    private fun AapEvent.label(): String = when (this) {
        is AapEvent.Battery -> "заряд " + components.joinToString { "${it.component}=${it.percent}%" + if (it.charging) "⚡" else "" }
        is AapEvent.EarDetection -> "ухо $primary/$secondary"
        is AapEvent.ListeningModeChanged -> "режим $mode"
        is AapEvent.ConversationalAwarenessChanged -> "адаптация к разговору ${if (enabled) "вкл" else "выкл"}"
        is AapEvent.HeadMotion -> "датчики головы"
        is AapEvent.ProximityKeys -> "ключи рекламы получены" + if (encryptionKey == null) " (без ключа шифрования)" else ""
        is AapEvent.ControlChanged -> "${ControlId.name(id)} = " + value.joinToString(" ") { "%02X".format(it) }
        is AapEvent.Unknown -> "неизвестный 0x%04X".format(opcode)
    }

    private companion object {
        val BACKOFF_SEC = intArrayOf(3, 10, 30, 60)
        const val MOTION_LOG_EVERY = 100
        const val STREAM_DATA_MIN = 32
        const val STREAM_LOG_FIRST = 20
        const val MOTION_LOG_FULL = 5
    }
}
