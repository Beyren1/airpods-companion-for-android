package dev.podscompanion.service

import android.content.Context
import android.net.wifi.WifiManager
import dev.podscompanion.bluetooth.connect.PodsConnector
import dev.podscompanion.bluetooth.connect.TakeOverResult
import dev.podscompanion.bluetooth.scan.ConnectedAudioDevices
import dev.podscompanion.bluetooth.scan.PcSignalScanner
import dev.podscompanion.data.PodsTakeOver
import dev.podscompanion.data.aap.AapLog
import dev.podscompanion.protocol.advertising.PcSignal
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Кнопка «Отдать телефону» в программе на ПК: ПК отключает наушники и просит телефон забрать их.
 * Сам ПК подключить их к телефону не может. Просьба приходит двумя путями сразу, срабатывает
 * первый дошедший: BLE-реклама ([PcSignalScanner]) и UDP-пакет в домашней сети Wi-Fi.
 * В обоих один и тот же [PcSignal], в интернет телефон ничего не отправляет.
 *
 * Слушаем, только пока наушники Apple не подключены к телефону: только тогда их могут отдать.
 */
internal class PcLink(
    context: Context,
    private val connectedAudio: ConnectedAudioDevices,
    private val scanner: PcSignalScanner,
    private val connector: PodsConnector,
    private val takeOver: PodsTakeOver,
    private val log: AapLog,
) {
    private val wifi = context.applicationContext.getSystemService(WifiManager::class.java)

    /** С выключенным экраном многие телефоны отбрасывают широковещательные пакеты Wi-Fi без этого разрешения. */
    private val multicastLock = wifi?.createMulticastLock("pods-pc")?.apply { setReferenceCounted(false) }
    private var lastNonce = -1

    @OptIn(ExperimentalCoroutinesApi::class)
    fun run(scope: CoroutineScope) {
        scope.launch {
            connectedAudio.devices()
                .map { devices -> devices != null && devices.none { it.supportsAap } }
                .distinctUntilChanged()
                .flatMapLatest { listen ->
                    runCatching { if (listen) multicastLock?.acquire() else multicastLock?.release() }
                    if (!listen) emptyFlow()
                    // Bluetooth выключили, Wi-Fi пропал или порт занят: пробуем позже, сервис продолжает работать.
                    else merge(
                        scanner.signals().retryWhen { _, _ -> delay(RETRY_MS); true },
                        wifiSignals().retryWhen { e, _ -> Timber.w(e, "pc link wifi"); delay(RETRY_MS); true },
                    )
                }
                .collect { signal ->
                    // ПК повторяет одно нажатие несколько секунд и обоими путями: выполняем его один раз.
                    if (signal.nonce == lastNonce) return@collect
                    val device = connector.bondedPods().firstOrNull { signal.isFor(it.address) } ?: return@collect
                    lastNonce = signal.nonce
                    log.add("ПК отдаёт наушники телефону: ${connector.nameOf(device)}")
                    val result = takeOver.takeOverAddress(device.address, "ПК отдал")
                    if (result !is TakeOverResult.Connected) log.add("забрать (ПК отдал): не вышло")
                }
        }
    }

    fun stop() {
        runCatching { multicastLock?.release() }
    }

    /** UDP-пакеты от ПК в локальной сети. */
    private fun wifiSignals(): Flow<PcSignal> = flow {
        DatagramSocket(null).use { socket ->
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(PcSignal.UDP_PORT))
            // receive() отмену не слышит: просыпаемся раз в секунду и проверяем, не пора ли выйти.
            socket.soTimeout = 1_000
            val buffer = ByteArray(64)
            while (currentCoroutineContext().isActive) {
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                } catch (_: SocketTimeoutException) {
                    continue
                }
                PcSignal.parse(packet.data.copyOf(packet.length))?.let { emit(it) }
            }
        }
    }.flowOn(Dispatchers.IO)

    private companion object {
        const val RETRY_MS = 30_000L
    }
}
