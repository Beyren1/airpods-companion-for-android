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
import dev.podscompanion.data.aap.OwnPodsKeys
import dev.podscompanion.data.aap.ProximityKeyStore
import dev.podscompanion.protocol.aap.EarState
import dev.podscompanion.protocol.advertising.BatteryLevel
import dev.podscompanion.protocol.advertising.Capability
import dev.podscompanion.protocol.advertising.PodState
import dev.podscompanion.protocol.advertising.ProximityCrypto
import dev.podscompanion.protocol.util.Hex
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch

@Singleton
class PodsRepository @Inject constructor(
    private val scanner: PodsScanner,
    private val connectedAudio: ConnectedAudioDevices,
    private val caseCache: CaseBatteryCache,
    private val aap: AapRepository,
    private val keyStore: ProximityKeyStore,
) {
    /**
     * Разбор пакетов (AES, выбор главных наушников) идёт здесь, а не в главном потоке: при скане
     * с экраном пакеты приходят много раз в секунду, и в главном потоке это подтормаживало интерфейс.
     * Один поток на всех: [knownPairs] и кеш кейса общие для экрана и сервиса и не потокобезопасны.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val worker = Dispatchers.Default.limitedParallelism(1)

    /** Какие наушники подключены под каким именем: переживает перезапуск скана. */
    private val knownPairs = HashMap<String, NearbyPodsTracker.KnownPair>()

    /**
     * Все наушники рядом; главные — подключённые к телефону (см. [NearbyPodsTracker.snapshot]).
     * Устройство пропадает из списка, если от него 15 с не было пакетов.
     *
     * channelFlow позволяет слить в один поток три источника: пакеты, смену подключённых
     * устройств и таймер, который выкидывает пропавшие наушники.
     */
    fun observeNearby(intensity: ScanIntensity): Flow<NearbyPods> = channelFlow {
        val tracker = NearbyPodsTracker(knownPairs = knownPairs)
        var connectedNames: List<String>? = null
        var connectedBatteries = emptyList<Int>()
        // Точный заряд от прямого подключения: по нему среди нескольких пар «AirPods» рядом
        // находим ту, что подключена (заряд из системы приходит не всегда и с опозданием).
        var aapBatteries = emptyList<Int>()
        var connectedAddresses = emptySet<String>()
        var ownKeys = emptyList<OwnPodsKeys>()
        // Адрес рекламы → чьи это наушники. AES считаем один раз на адрес, а он меняется раз в несколько минут.
        val owners = HashMap<String, String?>()
        // Пока система не ответила, что подключено, ничего не показываем: иначе на долю секунды
        // главными становятся ближайшие наушники, а потом прыгают в список «рядом».
        var namesKnown = false
        fun now() = SystemClock.elapsedRealtime()
        var aapSessions = AapSessions()
        var aapSide: AapOverlay.Side? = null
        val lastEar = HashMap<String, Pair<EarState, EarState>>()
        val earChangedAt = HashMap<String, Long>()
        suspend fun emit() {
            if (!namesKnown) return
            val nearby = tracker.snapshot(now(), connectedNames, connectedBatteries + aapBatteries, connectedAddresses)
            val primary = nearby.primary
            val session = aapSessions.forModel(primary?.model)
            // Прямое подключение есть только к подключённым наушникам: накладываем его только на них.
            send(
                if (session is AapSessionState.Connected && primary != null && primary.connected) {
                    val earAt = earChangedAt[session.address]
                    val adFresh = earAt == null || primary.lastSeenMs > earAt + AD_AFTER_EAR_MS
                    val side = AapOverlay.resolveSide(primary, session.device, aapSide, adFresh)
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
                // Когда по AAP последний раз менялось ухо: реклама старше этого момента устарела.
                it.sessions.filterIsInstance<AapSessionState.Connected>().forEach { session ->
                    val ear = session.device.primaryEar to session.device.secondaryEar
                    if (lastEar[session.address] != ear) {
                        lastEar[session.address] = ear
                        earChangedAt[session.address] = now()
                    }
                }
                aapSessions = it
                aapBatteries = it.sessions.filterIsInstance<AapSessionState.Connected>().flatMap { session ->
                    listOfNotNull(session.device.left, session.device.right, session.device.single).map { battery -> battery.percent }
                }
                emit()
            }
        }

        launch {
            connectedAudio.devices().collect { devices ->
                connectedNames = devices?.map { it.name }
                connectedBatteries = devices?.mapNotNull { it.batteryPercent }.orEmpty()
                connectedAddresses = devices?.map { it.device.address }.orEmpty().toSet()
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
        launch {
            keyStore.keys.collect {
                ownKeys = it
                owners.clear()
            }
        }
        scanner.scan(intensity).collect { event ->
            if (owners.size > MAX_OWNER_CACHE) owners.clear()
            val owner = if (owners.containsKey(event.address)) {
                owners[event.address]
            } else {
                ownKeys.firstOrNull { k -> k.irk != null && ProximityCrypto.resolves(event.address, k.irk) }?.address
                    .also { owners[event.address] = it }
            }
            val keys = ownKeys.firstOrNull { it.address == owner }
            val status = caseCache.apply(event.toStatus(owner, keys))
            tracker.onPacket(event.address, event.fingerprint(owner), status, event.elapsedRealtimeMs)
            emit()
        }
    }.distinctUntilChanged().flowOn(worker)

    private fun AdvertisementEvent.fingerprint(owner: String?): PairFingerprint {
        // У Max «сторона» отправителя меняется вместе с зарядом L/R, поэтому сравниваем только модель и цвет.
        val stereo = message.model?.capabilities?.contains(Capability.STEREO_BUDS) ?: true
        return PairFingerprint(
            modelId = message.modelId,
            colorCode = message.colorCode,
            leftPercent = if (stereo) message.left.battery?.percent else null,
            rightPercent = if (stereo) message.right.battery?.percent else null,
            owner = owner,
        )
    }

    /**
     * Пакет своих наушников с ключом шифрования: заряд берём точный из зашифрованной части.
     * Только для наушников-вкладышей: у Max раскладку расшифрованных байт ещё не проверяли.
     */
    private fun AdvertisementEvent.toStatus(owner: String?, keys: OwnPodsKeys?): PodsStatus {
        val status = toStatus().copy(owner = owner)
        val stereo = message.model?.capabilities?.contains(Capability.STEREO_BUDS) ?: false
        val exact = keys?.encryptionKey?.takeIf { stereo }?.let { ProximityCrypto.exactBattery(message.raw, it) } ?: return status
        fun PodState.with(percent: Int?, charging: Boolean) =
            if (percent == null) this else copy(battery = BatteryLevel(percent), charging = charging)
        val primary = status.primary.with(exact.primary, exact.primaryCharging)
        val secondary = (if (message.primaryIsLeft) status.right else status.left).with(exact.secondary, exact.secondaryCharging)
        return status.copy(
            primary = primary,
            left = if (message.primaryIsLeft) primary else secondary,
            right = if (message.primaryIsLeft) secondary else primary,
            // Кейс уточняем, только если он есть в открытой части: при закрытой крышке его там нет,
            // и по этому окно «кейс открыт» и запомненный заряд понимают, что крышка закрыта.
            caseBattery = if (status.caseBattery != null) exact.case?.let(::BatteryLevel) ?: status.caseBattery else null,
            caseCharging = if (status.caseBattery != null && exact.case != null) exact.caseCharging else status.caseCharging,
            exactFromAdvert = exact.primary != null || exact.secondary != null,
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

        /** Наушникам нужно немного времени, чтобы новое положение попало в рекламу. */
        const val AD_AFTER_EAR_MS = 1_000L
        const val MAX_OWNER_CACHE = 256
    }
}
