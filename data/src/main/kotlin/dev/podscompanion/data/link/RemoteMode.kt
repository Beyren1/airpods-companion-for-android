package dev.podscompanion.data.link

import android.bluetooth.BluetoothDevice
import dev.podscompanion.data.aap.AapLog
import dev.podscompanion.data.aap.AapRepository
import dev.podscompanion.data.aap.AapSessionState
import dev.podscompanion.protocol.aap.AapCommand
import dev.podscompanion.protocol.aap.ListeningMode
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Включить режим шумоподавления по просьбе ПК. В Windows нет доступа к каналу AAP без драйвера,
 * поэтому режим шлёт телефон — но только по уже открытому каналу, пока наушники у телефона.
 * Открыть канал, когда наушники у ПК, нельзя: AirPods Max от этого уходят с ПК (проверено 21.10.2026).
 */
@Singleton
class RemoteMode @Inject constructor(
    private val aap: AapRepository,
    private val log: AapLog,
) {
    /** Режим, который наушники подтвердили; null — не вышло или наушники не у телефона. */
    suspend fun set(device: BluetoothDevice, mode: ListeningMode): ListeningMode? {
        if (aap.state.value.sessions.none { it is AapSessionState.Connected && it.address == device.address }) {
            log.add("режим для ПК: наушники не у телефона — не трогаю, иначе они уйдут с ПК")
            return null
        }
        aap.send(device.address, AapCommand.SetListeningMode(mode))
        val confirmed = withTimeoutOrNull(CONFIRM_MS) {
            aap.state.mapNotNull { s ->
                (s.sessions.firstOrNull { it.address == device.address } as? AapSessionState.Connected)?.device?.listeningMode
            }.first { it == mode }
        }
        log.add(if (confirmed != null) "режим для ПК: $confirmed" else "режим для ПК: наушники не подтвердили")
        return confirmed
    }

    private companion object {
        const val CONFIRM_MS = 5_000L
    }
}
