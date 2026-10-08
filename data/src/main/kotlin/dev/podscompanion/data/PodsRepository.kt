package dev.podscompanion.data

import dev.podscompanion.bluetooth.scan.AdvertisementEvent
import dev.podscompanion.bluetooth.scan.PodsScanner
import dev.podscompanion.bluetooth.scan.ScanIntensity
import dev.podscompanion.protocol.advertising.Capability
import dev.podscompanion.protocol.util.Hex
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.transformLatest

@Singleton
class PodsRepository @Inject constructor(
    private val scanner: PodsScanner,
    private val caseCache: CaseBatteryCache,
) {
    /**
     * Поток состояния ближайших наушников; null, если пакетов не было [STALE] (крышка закрыта,
     * наушники далеко или выключены).
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeNearest(intensity: ScanIntensity): Flow<PodsStatus?> {
        val selector = NearestPodsSelector()
        return scanner.scan(intensity)
            .filter { selector.accept(it.address, it.rssi, it.fingerprint(), it.elapsedRealtimeMs) }
            .map { caseCache.apply(it.toStatus()) }
            // transformLatest отменяет предыдущий блок при новом пакете: если за STALE ничего
            // не пришло, delay доживает до конца и мы сообщаем «наушников нет».
            .transformLatest { status ->
                emit(status)
                delay(STALE)
                emit(null)
            }
    }

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
        val STALE = 15.seconds
    }
}
