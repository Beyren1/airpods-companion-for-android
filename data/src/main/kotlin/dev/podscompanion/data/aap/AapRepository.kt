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
import dev.podscompanion.protocol.aap.ModeRequest
import dev.podscompanion.protocol.aap.Opcode
import dev.podscompanion.protocol.aap.ControlId
import dev.podscompanion.protocol.util.Hex
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
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
     * Всё, что относится к одним наушникам и переживает переподключение: очередь команд
     * и выбранный, но ещё не подтверждённый режим шумоподавления.
     */
    private class Link {
        /** Буфер на 16 команд: несколько настроек подряд не затирают друг друга; лишние — самые старые — выбрасываются. */
        val commands = Channel<ByteArray>(16, BufferOverflow.DROP_OLDEST)
        val mode = ModeRequest()
        /** Разбудить таймер повторов: появился запрос режима. */
        val wake = Channel<Unit>(Channel.CONFLATED)
    }

    private val links = ConcurrentHashMap<String, Link>()

    private fun link(address: String): Link = links.getOrPut(address) { Link() }

    /**
     * Соединения живут независимо: когда подключаются или отключаются одни наушники, соединение
     * с другими не рвётся. Раньше любое изменение списка (в том числе другой порядок адресов)
     * переподключало все наушники, и выбор режима в эти секунды терялся.
     */
    val state: StateFlow<AapSessions> = connectedAudio.devices()
        .map { devices -> devices?.filter(::isApple) }
        // Заряд по HFP меняется часто, а переподключаться из-за этого не нужно: сравниваем набор адресов.
        .distinctUntilChanged { a, b -> a?.map { it.device.address }?.toSet() == b?.map { it.device.address }?.toSet() }
        .let(::sessionsFor)
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), AapSessions())

    private fun sessionsFor(targets: Flow<List<ConnectedAudioDevice>?>): Flow<AapSessions> = channelFlow {
        val jobs = HashMap<String, Job>()
        val byAddress = MutableStateFlow<Map<String, AapSessionState>>(emptyMap())
        // null — нет разрешения, иначе адреса в порядке системы.
        val order = MutableStateFlow<List<String>?>(emptyList())
        launch {
            combine(order, byAddress) { addresses, states ->
                if (addresses == null) AapSessions(noPermission = true)
                else AapSessions(sessions = addresses.mapNotNull(states::get))
            }.distinctUntilChanged().collect { send(it) }
        }
        targets.collect { list ->
            val addresses = list?.map { it.device.address }
            (jobs.keys - addresses.orEmpty().toSet()).forEach { gone ->
                jobs.remove(gone)?.cancel()
                byAddress.update { it - gone }
            }
            list?.forEach { target ->
                val address = target.device.address
                if (address !in jobs) {
                    jobs[address] = launch { session(target).collect { s -> byAddress.update { it + (address to s) } } }
                }
            }
            order.value = addresses
        }
    }

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
     * Отправить команду наушникам [address]. Пока соединение устанавливается, команда ждёт в очереди;
     * если оно оборвалось, переподключаемся сразу, не дожидаясь паузы между попытками.
     * Режим шумоподавления сразу показывается на экране и повторяется, пока наушники его не подтвердят
     * (см. [ModeRequest]); остальные настройки обновятся ответным уведомлением наушников.
     */
    fun send(address: String, command: AapCommand) {
        val session = state.value.sessions.firstOrNull { it.address == address } ?: return
        val link = link(address)
        if (command is AapCommand.SetListeningMode) {
            link.mode.request(command.mode, SystemClock.elapsedRealtime())
            link.wake.trySend(Unit)
        }
        log.add("${session.deviceName}: ${command.label}")
        when (session) {
            is AapSessionState.Connected, is AapSessionState.Connecting ->
                packets(address, command).forEach { link.commands.trySend(it) }
            // Режим отправится после переподключения (ModeRequest.onReconnected), прочее — устарело бы.
            is AapSessionState.Failed -> retryNow()
        }
    }

    /** Потоки 0x17, которые наушники объявили после подключения (см. [AapStreams]). */
    private val announcedStreams = ConcurrentHashMap<String, Set<Int>>()
    private var streamSeq = 1

    /**
     * После включения датчиков пишем в журнал первые [STREAM_LOG_AFTER_START] пакетов каждого потока:
     * иначе журнал забивает самый частый поток, и пакеты с углами в него не попадают.
     */
    private val streamLogCounts = ConcurrentHashMap<String, ConcurrentHashMap<Int, Int>>()

    /** Сколько пакетов датчиков после включения записать в журнал целиком (для разбора формата). */
    private val motionLogBudget = ConcurrentHashMap<String, Int>()

    /**
     * Датчики головы: кроме потока из описания LibrePods просим и все объявленные наушниками —
     * на наших прошивках поток 14 подтверждается, но данных не шлёт.
     */
    private fun packets(address: String, command: AapCommand): List<ByteArray> {
        val on = when (command) {
            AapCommand.StartHeadTracking -> true.also {
                motionLogBudget[address] = MOTION_LOG_FULL
                streamLogCounts[address] = ConcurrentHashMap()
            }
            AapCommand.StopHeadTracking -> false
            else -> return listOf(command.bytes)
        }
        val streams = listOf(AapStreams.ALTERNATE_HEAD_STREAM, AapStreams.DOCUMENTED_HEAD_STREAM) + announcedStreams[address].orEmpty().sorted()
        return streams.distinct().map { AapStreams.request(streamSeq++ % 120 + 1, it, on) }
    }

    /** Номер потока в пакете данных: поле 7 (байт 0x3A), внутри поле 1 (0x08). -1 — не нашли. */
    private fun streamId(data: ByteArray): Int {
        for (i in STREAM_PROTO_START until data.size - 3) {
            if (data[i] == 0x3A.toByte() && data[i + 2] == 0x08.toByte()) return data[i + 3].toInt() and 0xFF
        }
        return -1
    }

    private fun isApple(device: ConnectedAudioDevice): Boolean =
        device.supportsAap || ConnectedNameMatcher.knownModels(listOf(device.name)).isNotEmpty()

    /** Что пришло в цикл соединения: событие сокета или проверка повтора режима. */
    private sealed interface Step {
        class Io(val io: AapIo) : Step
        data object Tick : Step
    }

    /** Пока ждём подтверждения режима — проверяем каждые [MODE_TICK_MS], иначе спим до нового запроса. */
    private fun modeTicks(link: Link): Flow<Step> = flow {
        while (true) {
            if (link.mode.pending == null) link.wake.receive() else delay(MODE_TICK_MS)
            emit(Step.Tick)
        }
    }

    private fun session(target: ConnectedAudioDevice): Flow<AapSessionState> = channelFlow {
        val address = target.device.address
        val link = link(address)
        val commands = link.commands
        // Команды, оставшиеся от прошлого подключения этих наушников, уже не актуальны.
        while (commands.tryReceive().isSuccess) Unit
        var attempt = 0
        while (true) {
            attempt++
            send(AapSessionState.Connecting(target.name, address, attempt))
            log.add("${target.name}: подключение, попытка $attempt")
            var device = AapDeviceState()
            var connected = false
            var method = "?"
            var motionPackets = 0
            var streamPackets = 0
            var shown: AapDeviceState? = null
            // Отправляем экрану только изменения; режим поверх — выбранный, пока наушники не подтвердили.
            suspend fun publish() {
                if (!connected) return
                val next = link.mode.shown(device)
                if (next == shown) return
                shown = next
                send(AapSessionState.Connected(target.name, address, method, next))
            }
            val failure = try {
                merge(client.connect(target.device, commands).map { Step.Io(it) }, modeTicks(link)).collect { step ->
                    val io = (step as? Step.Io)?.io
                    if (io == null) {
                        // Пока соединение устанавливается, команда и так ждёт в очереди: повторы не тратим.
                        if (connected) link.mode.due(SystemClock.elapsedRealtime())?.let { mode ->
                            log.add("${target.name}: нет подтверждения, режим $mode ещё раз")
                            commands.trySend(AapCommand.SetListeningMode(mode).bytes)
                        }
                        publish()
                        return@collect
                    }
                    when (io) {
                        is AapIo.Connected -> {
                            connected = true
                            attempt = 0
                            method = io.method
                            log.add("${target.name}: подключено (${io.method})")
                            // Выбор режима, сделанный перед обрывом, не теряется.
                            link.mode.onReconnected(SystemClock.elapsedRealtime())?.let { mode ->
                                commands.trySend(AapCommand.SetListeningMode(mode).bytes)
                            }
                            publish()
                        }
                        is AapIo.Sent -> {
                            log.add("→ ${Hex.encode(io.data)}")
                            // Настройку показываем сразу после отправки, не дожидаясь ответа:
                            // некоторые наушники подтверждают её только через несколько секунд.
                            val event = AapParser.parse(io.data)
                            if (event.isSetting()) {
                                device = device.apply(event!!)
                                publish()
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
                                if (motionPackets++ % MOTION_LOG_EVERY == 0) log.add("${target.name}: датчики головы ${event.axes}")
                                // В пакете бывает до 8 записей: отдаём каждую отдельно, детектору нужен ряд значений.
                                val now = SystemClock.elapsedRealtime()
                                event.samples.forEach { _headMotion.tryEmit(HeadSample(address, now, AapEvent.HeadMotion(listOf(it)))) }
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
                            if (isStreamData) {
                                val stream = streamId(io.data)
                                val counts = streamLogCounts[address]
                                val logged = when {
                                    counts != null -> {
                                        val n = counts.merge(stream, 1, Int::plus) ?: 1
                                        n <= STREAM_LOG_AFTER_START
                                    }
                                    else -> streamPackets++ < STREAM_LOG_FIRST
                                }
                                if (logged) log.add("← ${Hex.encode(io.data)} · поток $stream")
                                return@collect
                            }
                            if (event is AapEvent.DeviceInfo) log.add("${target.name}: ${event.label()}")
                            else log.add("← ${Hex.encode(io.data)}" + (event?.let { " · ${it.label()}" } ?: ""))
                            if (event is AapEvent.ListeningModeChanged) link.mode.onReported(event.mode)
                            if (event != null) device = device.apply(event)
                            publish()
                        }
                    }
                }
                null
            } catch (e: CancellationException) {
                // Наушники отключили или подписчиков нет: выходим, а не переподключаемся.
                throw e
            } catch (e: Throwable) {
                e
            }

            val reason = if (failure is L2capUnavailableException) FailureReason.SOCKET_BLOCKED else FailureReason.CONNECTION_FAILED
            val details = (failure?.cause ?: failure)?.let { "${it::class.simpleName}: ${it.message}" } ?: "соединение закрыто"
            log.add("${target.name}: ошибка: $details")
            // Оборвалось рабочее соединение — сразу пробуем снова: обычно наушники просто на миг пропали.
            val delayMs = if (connected) RECONNECT_AFTER_DROP_MS else BACKOFF_SEC[(attempt - 1).coerceIn(0, BACKOFF_SEC.lastIndex)] * 1_000L
            send(AapSessionState.Failed(target.name, address, reason, details, ((delayMs + 999) / 1_000).toInt()))
            withTimeoutOrNull(delayMs) { retryRequests.first() }
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
        // Серийные номера в журнал не пишем: его показывают на скриншотах.
        is AapEvent.DeviceInfo -> "паспорт: ${info.modelNumber ?: "?"}, прошивка ${info.firmware ?: "?"}"
        is AapEvent.Unknown -> "неизвестный 0x%04X".format(opcode)
    }

    private companion object {
        const val MODE_TICK_MS = 200L
        const val RECONNECT_AFTER_DROP_MS = 300L
        val BACKOFF_SEC = intArrayOf(3, 10, 30, 60)
        const val MOTION_LOG_EVERY = 100
        const val STREAM_DATA_MIN = 32
        const val STREAM_LOG_FIRST = 20
        const val MOTION_LOG_FULL = 5
        const val STREAM_LOG_AFTER_START = 5
        const val STREAM_PROTO_START = 12
    }
}
