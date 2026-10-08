package dev.podscompanion.data

import android.os.SystemClock
import dev.podscompanion.bluetooth.scan.AdvertisementEvent
import dev.podscompanion.bluetooth.scan.ConnectedAudioDevices
import dev.podscompanion.bluetooth.scan.PodsScanner
import dev.podscompanion.bluetooth.scan.ScanIntensity
import dev.podscompanion.protocol.advertising.Capability
import dev.podscompanion.protocol.util.Hex
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

@Singleton
class PodsRepository @Inject constructor(
    private val scanner: PodsScanner,
    private val connectedAudio: ConnectedAudioDevices,
    private val caseCache: CaseBatteryCache,
) {
    /**
     * Все наушники рядом; главные — подключённые к телефону, иначе ближайшие.
     * Устройство пропадает из списка, если от него 15 с не было пакетов.
     *
     * channelFlow позволяет слить в один поток три источника: пакеты, смену подключённых
     * устройств и таймер, который выкидывает пропавшие наушники.
     */
    fun observeNearby(intensity: ScanIntensity): Flow<NearbyPods> = channelFlow {
        val tracker = NearbyPodsTracker()
        var connectedNames = emptyList<String>()
        fun now() = SystemClock.elapsedRealtime()

        launch {
            connectedAudio.names().collect { names ->
                connectedNames = names
                send(tracker.snapshot(now(), connectedNames))
            }
        }
        launch {
            while (true) {
                delay(TICK_MS)
                send(tracker.snapshot(now(), connectedNames))
            }
        }
        scanner.scan(intensity).collect { event ->
            tracker.onPacket(event.address, event.fingerprint(), caseCache.apply(event.toStatus()), event.elapsedRealtimeMs)
            send(tracker.snapshot(now(), connectedNames))
        }
    }.distinctUntilChanged()

    private fun AdvertisementEvent.fingerprint(): PairFingerprint {
        // У Max «сторона» отправителя меняется вместе с зарядом L/R, поэтому сравниваем только модель и цвет.
        val stereo = message.model?.capabilities?.contains(Capability.STEREO_BUDS) ?: true
        return PairFingerprint(
            modelId = message.modelId,
            colorCode = message.colorCode,
            leftPercent = if (stereo) message.left.battery?.percent else null,
            rightPercent = if (stereo) message.right.battery?.percent else null,
        )
    }

    private fun AdvertisementEvent.toStatus() = PodsStatus(
        model = message.model,
        modelId = message.modelId,
        left = message.left,
        right = message.right,
        primary = message.primary,
        caseBattery = message.caseBattery,
        caseCharging = message.caseCharging,
        lidCounter = message.lidCounter,
        colorCode = message.colorCode,
        rssi = rssi,
        lastSeenMs = elapsedRealtimeMs,
        rawHex = Hex.encode(message.raw),
    )

    private companion object {
        const val TICK_MS = 3_000L
    }
}
