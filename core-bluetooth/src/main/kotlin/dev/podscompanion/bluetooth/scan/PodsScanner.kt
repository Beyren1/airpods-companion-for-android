package dev.podscompanion.bluetooth.scan

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.SystemClock
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.podscompanion.bluetooth.log.PacketLog
import dev.podscompanion.protocol.advertising.AppleAdvertising
import dev.podscompanion.protocol.advertising.ProximityPairingMessage
import dev.podscompanion.protocol.advertising.ProximityPairingParser
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import timber.log.Timber
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/** Один принятый пакет Proximity Pairing. */
data class AdvertisementEvent(
    /** MAC меняется каждые ~15 минут (рандомизация), поэтому как идентификатор он ненадёжен. */
    val address: String,
    val rssi: Int,
    val message: ProximityPairingMessage,
    val elapsedRealtimeMs: Long,
)

/** Чем выше интенсивность, тем быстрее реакция и больше расход батареи. */
enum class ScanIntensity(val androidMode: Int) {
    LOW_POWER(ScanSettings.SCAN_MODE_LOW_POWER),
    BALANCED(ScanSettings.SCAN_MODE_BALANCED),
    LOW_LATENCY(ScanSettings.SCAN_MODE_LOW_LATENCY),
}

class BluetoothUnavailableException : IllegalStateException("Bluetooth выключен или недоступен")
class ScanFailedException(val errorCode: Int) : IllegalStateException("BLE-скан не запустился, код $errorCode")

@Singleton
class PodsScanner @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    /**
     * Холодный Flow: скан идёт, пока на Flow кто-то подписан, и останавливается при отмене подписки.
     * Разрешение BLUETOOTH_SCAN (или геолокация на Android 10–11) проверяет UI до подписки.
     *
     * @param relaxFilter спрашивается, если долго нет ни одного пакета Apple: true — наушники
     *   подключены, значит пакеты должны быть, и фильтр контроллера, похоже, их теряет. Тогда скан
     *   перезапускается со следующим фильтром (см. [FilterLevel]); подошедший остаётся, пока идут пакеты.
     */
    @SuppressLint("MissingPermission")
    fun scan(intensity: ScanIntensity, relaxFilter: () -> Boolean = { false }): Flow<AdvertisementEvent> = callbackFlow {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        val scanner = adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner
        if (scanner == null) {
            close(BluetoothUnavailableException())
            return@callbackFlow
        }

        // Время последнего пакета Apple любого типа: по нему понимаем, что фильтр пропускает пакеты.
        // Пишется из потока Bluetooth, читается из корутины, поэтому атомарное.
        val lastAppleMs = AtomicLong(SystemClock.elapsedRealtime())

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) = handle(result)

            override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach(::handle)

            override fun onScanFailed(errorCode: Int) {
                close(ScanFailedException(errorCode))
            }

            private fun handle(result: ScanResult) {
                val data = result.scanRecord?.getManufacturerSpecificData(AppleAdvertising.COMPANY_ID) ?: return
                lastAppleMs.set(SystemClock.elapsedRealtime())
                PacketLog.advertising(result.device.address, result.rssi, data)
                val message = ProximityPairingParser.parse(data) ?: return
                trySend(AdvertisementEvent(result.device.address, result.rssi, message, SystemClock.elapsedRealtime()))
            }
        }

        val settings = ScanSettings.Builder()
            .setScanMode(intensity.androidMode)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .build()

        var level = FilterLevel.PROXIMITY_PAIRING
        scanner.startScan(listOf(level.filter()), settings, callback)
        launch {
            while (true) {
                delay(RELAX_CHECK_MS)
                if (SystemClock.elapsedRealtime() - lastAppleMs.get() < RELAX_AFTER_MS || !relaxFilter()) continue
                // По кругу: если и без фильтра пусто (экран выключен, наушники уснули), возвращаемся
                // к узкому фильтру, который работает и с выключенным экраном.
                level = FilterLevel.entries[(level.ordinal + 1) % FilterLevel.entries.size]
                Timber.i("scan: нет пакетов Apple, фильтр %s", level)
                runCatching { scanner.stopScan(callback) }
                lastAppleMs.set(SystemClock.elapsedRealtime())
                runCatching { scanner.startScan(listOf(level.filter()), settings, callback) }
                    .onFailure { close(it) }
            }
        }
        // awaitClose держит Flow открытым; блок выполнится при отмене (аналог деструктора/RAII).
        awaitClose { runCatching { scanner.stopScan(callback) } }
    }

    /**
     * Насколько узкий фильтр отдаём контроллеру Bluetooth. Узкий выполняется в самом контроллере:
     * телефон не просыпается из-за чужих пакетов, а с выключенным экраном скан без фильтра не работает.
     * Но на некоторых телефонах (подозреваем Samsung) контроллер с узким фильтром не пропускает ничего.
     */
    private enum class FilterLevel {
        /** Apple + первые байты «07 19»: только Proximity Pairing. */
        PROXIMITY_PAIRING,

        /** Любые пакеты Apple, без маски данных. */
        APPLE,

        /** Без фильтра: все пакеты, разбираем сами. Работает только при включённом экране. */
        NONE,
        ;

        private companion object {
            const val PACKET_SIZE = 2 + AppleAdvertising.PROXIMITY_PAIRING_LENGTH
        }

        fun filter(): ScanFilter = when (this) {
            // Данные и маска — во всю длину пакета (2 + 25 байт), сравниваются только первые два.
            // С маской в 2 байта Samsung A56 не пропускал ничего: похоже, его контроллер сравнивает
            // и длину. Pixel работает с обоими вариантами.
            PROXIMITY_PAIRING -> ScanFilter.Builder()
                .setManufacturerData(
                    AppleAdvertising.COMPANY_ID,
                    ByteArray(PACKET_SIZE).also {
                        it[0] = AppleAdvertising.TYPE_PROXIMITY_PAIRING.toByte()
                        it[1] = AppleAdvertising.PROXIMITY_PAIRING_LENGTH.toByte()
                    },
                    ByteArray(PACKET_SIZE).also {
                        it[0] = 0xFF.toByte()
                        it[1] = 0xFF.toByte()
                    },
                )
                .build()
            APPLE -> ScanFilter.Builder().setManufacturerData(AppleAdvertising.COMPANY_ID, byteArrayOf()).build()
            NONE -> ScanFilter.Builder().build()
        }
    }

    private companion object {
        const val RELAX_CHECK_MS = 2_000L

        /** Подключённые AirPods шлют рекламу раз в ~5 с: две пропущенные — уже подозрительно. */
        const val RELAX_AFTER_MS = 12_000L
    }
}
