package dev.podscompanion.data

import android.os.SystemClock
import dev.podscompanion.bluetooth.scan.AdvertisementEvent
import dev.podscompanion.bluetooth.scan.ConnectedAudioDevices
import dev.podscompanion.bluetooth.scan.PodsScanner
import dev.podscompanion.bluetooth.scan.ScanIntensity
import dev.podscompanion.data.aap.AapOverlay
import dev.podscompanion.data.aap.AapRepository
import dev.podscompanion.data.aap.AapSessionState
import dev.podscompanion.data.aap.AapSessions
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
    private val aap: AapRepository,
) {
    /**
     * Все наушники рядом; главные — подключённые к телефону (см. [NearbyPodsTracker.snapshot]).
     * Устройство пропадает из списка, если от него 15 с не было пакетов.
     *
     * channelFlow позволяет слить в один поток три источника: пакеты, смену подключённых
     * устройств и таймер, который выкидывает пропавшие наушники.
     */
    fun observeNearby(intensity: ScanIntensity): Flow<NearbyPods> = channelFlow {
        val tracker = NearbyPodsTracker()
        var connectedNames: List<String>? = null
        var connectedBatteries = emptyList<Int>()
        // Пока система не ответила, что подключено, ничего не показываем: иначе на долю секунды
        // главными становятся ближайшие наушники, а потом прыгают в список «рядом».
        var namesKnown = false
        fun now() = SystemClock.elapsedRealtime()
        var aapSessions = AapSessions()
        var aapSide: AapOverlay.Side? = null
        suspend fun emit() {
            if (!namesKnown) return
            val nearby = tracker.snapshot(now(), connectedNames, connectedBatteries)
            val primary = nearby.primary
            val session = aapSessions.forModel(primary?.model)
            // Прямое подключение есть только к подключённым наушникам: накладываем его только на них.
            send(
                if (session is AapSessionState.Connected && primary != null && primary.connected) {
                    val side = AapOverlay.resolveSide(primary, session.device, aapSide)
                    aapSide = side
                    nearby.copy(primary = AapOverlay.apply(primary, session.device, side.primaryIsLeftNow(session.device)))
                } else {
                    aapSide = null
                    nearby
                },
            )
        }

        launch {
            // Каждое событие AAP (вынули наушник) сразу даёт новое состояние, без ожидания рекламы.
            aap.state.collect {
                aapSessions = it
                emit()
            }
        }

        launch {
            connectedAudio.devices().collect { devices ->
                connectedNames = devices?.map { it.name }
                connectedBatteries = devices?.mapNotNull { it.batteryPercent }.orEmpty()
                namesKnown = true
                emit()
            }
        }
        launch {
            while (true) {
                delay(TICK_MS)
                emit()
            }
        }
        scanner.scan(intensity).collect { event ->
            tracker.onPacket(event.address, event.fingerprint(), caseCache.apply(event.toStatus()), event.elapsedRealtimeMs)
            emit()
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
        primaryIsLeft = message.primaryIsLeft,
    )

    private companion object {
        const val TICK_MS = 3_000L
    }
}
