package dev.podscompanion.service

import android.content.Context
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.view.KeyEvent
import dev.podscompanion.bluetooth.connect.PodsConnector
import dev.podscompanion.bluetooth.connect.TakeOverResult
import dev.podscompanion.bluetooth.scan.ConnectedAudioDevices
import dev.podscompanion.bluetooth.scan.PcSignalScanner
import dev.podscompanion.data.PodsTakeOver
import dev.podscompanion.data.aap.AapLog
import dev.podscompanion.data.link.DeviceLink
import dev.podscompanion.protocol.advertising.PcSignal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.launch

/**
 * Что телефон делает по просьбам программы на ПК:
 * - «Отдать телефону» на ПК: ПК отключает наушники и просит телефон забрать их ([PcSignal]).
 *   Просьба приходит двумя путями сразу, срабатывает первый: BLE-реклама ([PcSignalScanner])
 *   и UDP-пакет в домашней сети ([DeviceLink.takeSignals]). Слушаем, пока наушники не у телефона.
 * - ПК забирает наушники ([DeviceLink.taking]): заранее ставим музыку на паузу, чтобы она
 *   не заиграла из динамика телефона, пока наушники уходят.
 */
internal class PcLink(
    context: Context,
    private val audioManager: AudioManager,
    private val connectedAudio: ConnectedAudioDevices,
    private val scanner: PcSignalScanner,
    private val link: DeviceLink,
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
        link.run(scope)
        scope.launch {
            connectedAudio.devices()
                .map { devices -> devices != null && devices.none { it.supportsAap } }
                .distinctUntilChanged()
                .flatMapLatest { listen ->
                    runCatching { if (listen) multicastLock?.acquire() else multicastLock?.release() }
                    if (!listen) emptyFlow()
                    // Bluetooth выключили или скан не запустился: пробуем позже, сервис продолжает работать.
                    else merge(scanner.signals().retryWhen { _, _ -> delay(RETRY_MS); true }, link.takeSignals)
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
        scope.launch {
            link.taking.collect { hash ->
                val here = connectedAudio.devices().filterNotNull().first().any { PcSignal.hashHex(it.device.address) == hash }
                if (!here || !audioManager.isMusicActive) return@collect
                log.add("ПК забирает наушники: пауза на телефоне")
                audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE))
                audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE))
            }
        }
    }

    fun stop() {
        runCatching { multicastLock?.release() }
    }

    private companion object {
        const val RETRY_MS = 30_000L
    }
}
