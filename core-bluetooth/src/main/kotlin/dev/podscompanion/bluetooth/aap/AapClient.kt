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
        launch(Dispatchers.IO) {
            try {
                socket.connect()
                send(AapIo.Connected(L2capSockets.lastMethod ?: "?"))
                val output = socket.outputStream
                for (packet in listOf(Aap.HANDSHAKE, Aap.SET_FEATURES, Aap.REQUEST_NOTIFICATIONS)) {
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
    }
}
