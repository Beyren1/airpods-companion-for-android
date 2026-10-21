package dev.podscompanion.data.link

import android.bluetooth.BluetoothDevice
import dev.podscompanion.bluetooth.aap.AapClient
import dev.podscompanion.bluetooth.aap.AapIo
import dev.podscompanion.data.aap.AapLog
import dev.podscompanion.data.aap.AapRepository
import dev.podscompanion.data.aap.AapSessionState
import dev.podscompanion.protocol.aap.AapCommand
import dev.podscompanion.protocol.aap.AapEvent
import dev.podscompanion.protocol.aap.AapParser
import dev.podscompanion.protocol.aap.ListeningMode
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Включить режим шумоподавления по просьбе ПК. В Windows нет доступа к каналу AAP без драйвера,
 * а у телефона есть, даже когда звук наушников идёт на ПК: телефон ненадолго открывает канал AAP,
 * отправляет режим, дожидается подтверждения и закрывает канал. Звук при этом остаётся на ПК.
 */
@Singleton
class RemoteMode @Inject constructor(
    private val client: AapClient,
    private val aap: AapRepository,
    private val log: AapLog,
) {
    /** Режим, который наушники подтвердили; null — не вышло. */
    suspend fun set(device: BluetoothDevice, mode: ListeningMode): ListeningMode? {
        // Наушники у телефона: шлём через уже открытое соединение.
        if (aap.state.value.sessions.any { it is AapSessionState.Connected && it.address == device.address }) {
            aap.send(device.address, AapCommand.SetListeningMode(mode))
            return withTimeoutOrNull(CONFIRM_MS) {
                aap.state.mapNotNull { s ->
                    (s.sessions.firstOrNull { it.address == device.address } as? AapSessionState.Connected)?.device?.listeningMode
                }.first { it == mode }
            }
        }
        val commands = Channel<ByteArray>(Channel.UNLIMITED)
        commands.trySend(AapCommand.SetListeningMode(mode).bytes)
        val confirmed = runCatching {
            withTimeoutOrNull(ONE_SHOT_MS) {
                client.connect(device, commands)
                    .onEach { if (it is AapIo.Connected) log.add("режим для ПК: канал AAP открыт") }
                    .filterIsInstance<AapIo.Received>()
                    .mapNotNull { (AapParser.parse(it.data) as? AapEvent.ListeningModeChanged)?.mode }
                    .first { it == mode }
            }
        }.getOrNull()
        commands.close()
        log.add(if (confirmed != null) "режим для ПК: $confirmed" else "режим для ПК: наушники не подтвердили")
        return confirmed
    }

    private companion object {
        const val CONFIRM_MS = 5_000L
        /** Подключение, приветствие и подтверждение режима: у Max около 2–4 с. */
        const val ONE_SHOT_MS = 12_000L
    }
}
