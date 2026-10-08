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
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.awaitClose
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
     */
    @SuppressLint("MissingPermission")
    fun scan(intensity: ScanIntensity): Flow<AdvertisementEvent> = callbackFlow {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        val scanner = adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner
        if (scanner == null) {
            close(BluetoothUnavailableException())
            return@callbackFlow
        }

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) = handle(result)

            override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach(::handle)

            override fun onScanFailed(errorCode: Int) {
                close(ScanFailedException(errorCode))
            }

            private fun handle(result: ScanResult) {
                val data = result.scanRecord?.getManufacturerSpecificData(AppleAdvertising.COMPANY_ID) ?: return
                PacketLog.advertising(result.device.address, result.rssi, data)
                val message = ProximityPairingParser.parse(data) ?: return
                trySend(AdvertisementEvent(result.device.address, result.rssi, message, SystemClock.elapsedRealtime()))
            }
        }

        // Фильтр по Apple + первые байты "07 19" выполняется в контроллере Bluetooth:
        // телефон не просыпается из-за чужих пакетов, а с выключенным экраном скан без фильтра не работает.
        val filter = ScanFilter.Builder()
            .setManufacturerData(
                AppleAdvertising.COMPANY_ID,
                byteArrayOf(AppleAdvertising.TYPE_PROXIMITY_PAIRING.toByte(), AppleAdvertising.PROXIMITY_PAIRING_LENGTH.toByte()),
                byteArrayOf(0xFF.toByte(), 0xFF.toByte()),
            )
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(intensity.androidMode)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .build()

        scanner.startScan(listOf(filter), settings, callback)
        // awaitClose держит Flow открытым; блок выполнится при отмене (аналог деструктора/RAII).
        awaitClose { runCatching { scanner.stopScan(callback) } }
    }
}
