package dev.podscompanion.bluetooth.aap

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import dev.podscompanion.protocol.aap.Aap
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/** Что происходит на соединении AAP: подключились, получили или отправили пакет. */
sealed interface AapIo {
    data class Connected(val method: String) : AapIo
    class Received(val data: ByteArray) : AapIo
    class Sent(val data: ByteArray) : AapIo
}

/**
 * connect() не ответил. Так ведёт себя стек Bluetooth на Android 16 и старше у большинства
 * производителей (Samsung A56): без root прямое подключение там невозможно, исправлено в Android 17.
 */
class AapConnectTimeoutException(seconds: Long) : IOException("наушники не ответили за $seconds с")

/**
 * Одно соединение AAP с наушниками. Flow живёт, пока соединение открыто: при отмене подписки
 * сокет закрывается, при обрыве Flow завершается ошибкой. Переподключение — забота вызывающего.
 */
@Singleton
class AapClient @Inject constructor() {

    @SuppressLint("MissingPermission")
    /** @param outgoing команды, которые нужно отправить наушникам, пока соединение открыто. */
    fun connect(device: BluetoothDevice, outgoing: ReceiveChannel<ByteArray>): Flow<AapIo> = callbackFlow {
        val socket = L2capSockets.create(device, Aap.PSM)
        // read() блокирует поток, поэтому читаем на IO. Отмена Flow закроет сокет в awaitClose,
        // и read() сразу выбросит исключение (аналог shutdown() у сокета в C++).
        // На некоторых прошивках (Samsung A56) connect() не завершается ни успехом, ни ошибкой,
        // и попытка висит вечно. Закрытый сокет прерывает connect() исключением — будет повтор.
        val connectTimeout = launch {
            delay(CONNECT_TIMEOUT_MS)
            close(AapConnectTimeoutException(CONNECT_TIMEOUT_MS / 1_000))
            runCatching { socket.close() }
        }
        launch(Dispatchers.IO) {
            try {
                socket.connect()
                connectTimeout.cancel()
                send(AapIo.Connected(L2capSockets.lastMethod ?: "?"))
                val output = socket.outputStream
                // Писать в сокет могут сразу несколько корутин (команды и повтор подписки): по очереди.
                val writeLock = Mutex()
                suspend fun write(packet: ByteArray) = writeLock.withLock {
                    output.write(packet)
                    output.flush()
                    send(AapIo.Sent(packet))
                }
                // Ответ на приветствие и первые уведомления (заряд, ухо) — их ждут корутины ниже.
                val handshakeAnswered = CompletableDeferred<Unit>()
                val stateReceived = CompletableDeferred<Unit>()
                launch {
                    // Сначала только приветствие. Если сразу за ним отправить подписку, наушники,
                    // подключённые секунду назад, иногда принимают режимы, но заряд и ухо не шлют.
                    write(Aap.HANDSHAKE)
                    withTimeoutOrNull(HANDSHAKE_WAIT_MS) { handshakeAnswered.await() }
                    for (packet in listOf(Aap.SET_FEATURES, Aap.REQUEST_NOTIFICATIONS, Aap.REQUEST_PROXIMITY_KEYS)) write(packet)
                    launch { for (command in outgoing) write(command) }
                    // Заряд и ухо так и не пришли — подписываемся ещё раз.
                    repeat(NOTIFICATION_RETRIES) {
                        if (withTimeoutOrNull(STATE_WAIT_MS) { stateReceived.await() } != null) return@launch
                        write(Aap.REQUEST_NOTIFICATIONS)
                    }
                }
                val input = socket.inputStream
                val buffer = ByteArray(MAX_PACKET)
                while (true) {
                    // L2CAP сохраняет границы пакетов: один read() — один пакет AAP.
                    val n = input.read(buffer)
                    if (n < 0) throw IOException("наушники закрыли соединение")
                    if (n == 0) continue
                    val packet = buffer.copyOf(n)
                    // Ответ на приветствие начинается с 01 00 04 00.
                    if (packet[0] == HANDSHAKE_ANSWER) handshakeAnswered.complete(Unit)
                    if (packet.size >= 6 && packet[5] == 0.toByte() && packet[4].toInt() in STATE_OPCODES) stateReceived.complete(Unit)
                    send(AapIo.Received(packet))
                }
            } catch (e: Throwable) {
                close(e)
            }
        }
        awaitClose { runCatching { socket.close() } }
    }

    private companion object {
        const val MAX_PACKET = 1024
        const val CONNECT_TIMEOUT_MS = 15_000L
        const val HANDSHAKE_WAIT_MS = 1_000L
        const val STATE_WAIT_MS = 2_000L
        const val NOTIFICATION_RETRIES = 2
        const val HANDSHAKE_ANSWER: Byte = 0x01
        // Заряд (0x04) и ухо (0x06): по ним видно, что подписка на уведомления сработала.
        val STATE_OPCODES = setOf(0x04, 0x06)
    }
}
