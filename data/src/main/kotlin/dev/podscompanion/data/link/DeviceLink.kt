package dev.podscompanion.data.link

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.podscompanion.bluetooth.connect.PodsConnector
import dev.podscompanion.bluetooth.scan.ConnectedAudioDevices
import dev.podscompanion.data.aap.AapLog
import dev.podscompanion.protocol.advertising.PcSignal
import dev.podscompanion.protocol.link.LinkMessage
import dev.podscompanion.protocol.link.LinkPods
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

/** ПК с программой Pods Companion, которого слышно в сети. [pods] — какие наушники у него подключены. */
data class LinkedPc(
    val id: String,
    val name: String,
    val address: InetAddress,
    val pods: List<LinkPods>,
    val lastSeenMs: Long,
) {
    fun has(hash: String): Boolean = pods.any { it.hash == hash && it.here }
}

/**
 * Постоянная связь с программой на ПК по домашней сети (UDP, порт [LinkMessage.PORT]).
 * Телефон сообщает ПК, какие наушники подключены к нему, и узнаёт то же о ПК: так оба
 * приложения показывают, где наушники, и переключают их сразу, а не перетягивают друг у друга.
 *
 * На тот же порт приходит сигнал «забери наушники» ([PcSignal]) от кнопки «Отдать телефону»: его отдаём в [takeSignals].
 * Работает, пока запущен фоновый сервис; в интернет ничего не отправляет.
 */
@Singleton
class DeviceLink @Inject constructor(
    @ApplicationContext private val context: Context,
    private val connectedAudio: ConnectedAudioDevices,
    private val connector: PodsConnector,
    private val log: AapLog,
) {
    private val id = "%08x".format(Random.nextInt())
    private val _peers = MutableStateFlow<List<LinkedPc>>(emptyList())

    /** ПК на связи (слышно за последние [PEER_TIMEOUT_MS]). */
    val peers: StateFlow<List<LinkedPc>> = _peers.asStateFlow()

    private val _takeSignals = MutableSharedFlow<PcSignal>(extraBufferCapacity = 8)
    val takeSignals: SharedFlow<PcSignal> = _takeSignals

    private val _taking = MutableSharedFlow<String>(extraBufferCapacity = 8)

    /** ПК предупреждает, что забирает наушники с этим хэшем. */
    val taking: SharedFlow<String> = _taking

    @Volatile private var socket: DatagramSocket? = null

    fun run(scope: CoroutineScope) {
        scope.launch(Dispatchers.IO) { receiveLoop(scope) }
        scope.launch {
            // Подключились или отключились наушники — сразу сообщаем ПК. Первое значение — при запуске:
            // широковещательное «hi», чтобы ПК ответил и мы узнали о нём, не дожидаясь его минуты.
            var first = true
            connectedAudio.devices().filterNotNull()
                .map { list -> list.map { it.device.address }.toSet() }
                .distinctUntilChanged()
                .collect {
                    say(if (first) LinkMessage.Type.HI else LinkMessage.Type.HELLO)
                    first = false
                }
        }
        scope.launch {
            while (isActive) {
                delay(CLEANUP_MS)
                val now = SystemClock.elapsedRealtime()
                _peers.update { list -> list.filter { now - it.lastSeenMs < PEER_TIMEOUT_MS } }
            }
        }
    }

    /**
     * Перед тем как забрать наушники: если они у ПК, просим ПК отпустить их и ждём, пока отпустит
     * (не дольше [RELEASE_WAIT_MS]). Так телефону не приходится перетягивать их у ПК.
     */
    suspend fun askRelease(address: String) {
        val hash = PcSignal.hashHex(address)
        val holders = _peers.value.filter { it.has(hash) }
        if (holders.isEmpty()) return
        val message = message(LinkMessage.Type.RELEASE, listOf(LinkPods(hash, false)))
        holders.forEach { pc ->
            log.add("прошу ПК ${pc.name} отпустить наушники")
            send(message, pc.address)
        }
        val released = withTimeoutOrNull(RELEASE_WAIT_MS) { _peers.first { list -> list.none { it.has(hash) } } } != null
        log.add(if (released) "ПК отпустил наушники" else "ПК не ответил, забираю сам")
    }

    private fun message(type: LinkMessage.Type, pods: List<LinkPods>) =
        LinkMessage(type, id, LinkMessage.Kind.PHONE, pods, deviceName())

    @SuppressLint("MissingPermission")
    private suspend fun ownPods(): List<LinkPods> {
        val connected = connectedAudio.devices().first().orEmpty().map { it.device.address }.toSet()
        return connector.bondedPods().map { LinkPods(PcSignal.hashHex(it.address), it.address in connected) }
    }

    /** Широковещательно и каждому известному ПК напрямую. */
    private suspend fun say(type: LinkMessage.Type) {
        val message = message(type, ownPods())
        send(message, BROADCAST)
        _peers.value.forEach { send(message, it.address) }
    }

    private suspend fun send(message: LinkMessage, to: InetAddress) = withContext(Dispatchers.IO) {
        val bytes = message.encode().toByteArray(Charsets.UTF_8)
        runCatching {
            // Пока сокет приёма не открыт, отправляем с временного: ответ ПК всё равно придёт на наш порт.
            val own = socket
            if (own != null) {
                own.send(DatagramPacket(bytes, bytes.size, to, LinkMessage.PORT))
            } else {
                DatagramSocket().use { it.broadcast = true; it.send(DatagramPacket(bytes, bytes.size, to, LinkMessage.PORT)) }
            }
        }.onFailure { Timber.d(it, "link send") }
    }

    private suspend fun receiveLoop(scope: CoroutineScope) {
        while (scope.isActive) {
            try {
                DatagramSocket(null).use { s ->
                    s.reuseAddress = true
                    s.broadcast = true
                    s.bind(InetSocketAddress(LinkMessage.PORT))
                    // receive() отмену не слышит: просыпаемся раз в секунду и проверяем, не пора ли выйти.
                    s.soTimeout = 1_000
                    socket = s
                    val buffer = ByteArray(512)
                    while (scope.isActive) {
                        val packet = DatagramPacket(buffer, buffer.size)
                        try {
                            s.receive(packet)
                        } catch (_: SocketTimeoutException) {
                            continue
                        }
                        onPacket(scope, packet.data.copyOf(packet.length), packet.address)
                    }
                }
            } catch (e: Exception) {
                Timber.w(e, "link receive")
            } finally {
                socket = null
            }
            if (scope.isActive) delay(RETRY_MS)
        }
    }

    private fun onPacket(scope: CoroutineScope, data: ByteArray, from: InetAddress) {
        PcSignal.parse(data)?.let {
            _takeSignals.tryEmit(it)
            return
        }
        val message = LinkMessage.parse(data.toString(Charsets.UTF_8)) ?: return
        if (message.id == id || message.kind != LinkMessage.Kind.PC) return
        val now = SystemClock.elapsedRealtime()
        var isNew = false
        _peers.update { list ->
            isNew = list.none { it.id == message.id }
            list.filter { it.id != message.id } + LinkedPc(message.id, message.name, from, message.pods, now)
        }
        if (isNew) log.add("ПК на связи: ${message.name}")
        when (message.type) {
            LinkMessage.Type.HI -> scope.launch { send(message(LinkMessage.Type.HELLO, ownPods()), from) }
            LinkMessage.Type.TAKING -> message.pods.forEach { _taking.tryEmit(it.hash) }
            else -> Unit
        }
    }

    private fun deviceName(): String =
        Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)?.takeIf { it.isNotBlank() } ?: Build.MODEL

    private companion object {
        val BROADCAST: InetAddress = InetAddress.getByName("255.255.255.255")
        const val PEER_TIMEOUT_MS = 150_000L
        const val CLEANUP_MS = 30_000L
        const val RELEASE_WAIT_MS = 3_000L
        const val RETRY_MS = 30_000L
    }
}
