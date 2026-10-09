package dev.podscompanion.bluetooth.aap

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import dev.podscompanion.protocol.aap.Aap
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch

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
                for (packet in listOf(Aap.HANDSHAKE, Aap.SET_FEATURES, Aap.REQUEST_NOTIFICATIONS, Aap.REQUEST_PROXIMITY_KEYS)) {
                    output.write(packet)
                    output.flush()
                    send(AapIo.Sent(packet))
                }
                launch {
                    for (command in outgoing) {
                        output.write(command)
                        output.flush()
                        send(AapIo.Sent(command))
                    }
                }
                val input = socket.inputStream
                val buffer = ByteArray(MAX_PACKET)
                while (true) {
                    // L2CAP сохраняет границы пакетов: один read() — один пакет AAP.
                    val n = input.read(buffer)
                    if (n < 0) throw IOException("наушники закрыли соединение")
                    if (n > 0) send(AapIo.Received(buffer.copyOf(n)))
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
    }
}
