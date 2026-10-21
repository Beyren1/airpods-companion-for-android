package dev.podscompanion.service

import dev.podscompanion.bluetooth.connect.PodsConnector
import dev.podscompanion.bluetooth.connect.TakeOverResult
import dev.podscompanion.bluetooth.scan.ConnectedAudioDevices
import dev.podscompanion.bluetooth.scan.PcSignalScanner
import dev.podscompanion.data.PodsTakeOver
import dev.podscompanion.data.aap.AapLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.launch

/**
 * Кнопка «Отдать телефону» в программе на ПК: ПК отключает наушники и по Bluetooth
 * просит телефон забрать их ([PcSignalScanner]). Сам ПК подключить их к телефону не может.
 *
 * Слушаем, только пока наушники Apple не подключены к телефону: только тогда их могут отдать.
 */
internal class PcLink(
    private val connectedAudio: ConnectedAudioDevices,
    private val scanner: PcSignalScanner,
    private val connector: PodsConnector,
    private val takeOver: PodsTakeOver,
    private val log: AapLog,
) {
    private var lastNonce = -1

    @OptIn(ExperimentalCoroutinesApi::class)
    fun run(scope: CoroutineScope) {
        scope.launch {
            connectedAudio.devices()
                .map { devices -> devices != null && devices.none { it.supportsAap } }
                .distinctUntilChanged()
                .flatMapLatest { listen ->
                    if (!listen) emptyFlow()
                    // Bluetooth выключили или скан не запустился: пробуем позже, сервис продолжает работать.
                    else scanner.signals().retryWhen { _, _ -> delay(RETRY_MS); true }
                }
                .collect { signal ->
                    // ПК рассылает одно нажатие несколько секунд: выполняем его один раз.
                    if (signal.nonce == lastNonce) return@collect
                    val device = connector.bondedPods().firstOrNull { signal.isFor(it.address) } ?: return@collect
                    lastNonce = signal.nonce
                    log.add("ПК отдаёт наушники телефону: ${connector.nameOf(device)}")
                    val result = takeOver.takeOverAddress(device.address, "ПК отдал")
                    if (result !is TakeOverResult.Connected) log.add("забрать (ПК отдал): не вышло")
                }
        }
    }

    private companion object {
        const val RETRY_MS = 30_000L
    }
}
