package dev.podscompanion.bluetooth.scan

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.podscompanion.protocol.advertising.PcSignal
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Слушает сигнал «забери наушники» от программы на ПК ([PcSignal]).
 * Фильтр выполняется в контроллере Bluetooth, поэтому телефон просыпается только от этого сигнала.
 */
@Singleton
class PcSignalScanner @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    @SuppressLint("MissingPermission")
    fun signals(): Flow<PcSignal> = callbackFlow {
        val scanner = context.getSystemService(BluetoothManager::class.java)?.adapter
            ?.takeIf { it.isEnabled }?.bluetoothLeScanner
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
                val data = result.scanRecord?.getManufacturerSpecificData(PcSignal.COMPANY_ID) ?: return
                PcSignal.parse(data)?.let { trySend(it) }
            }
        }
        // Маска во всю длину пакета, сравниваются только первые 4 байта «PCSW»: как в PodsScanner,
        // некоторые контроллеры (Samsung) с короткой маской не пропускают ничего.
        val filter = ScanFilter.Builder()
            .setManufacturerData(
                PcSignal.COMPANY_ID,
                PcSignal.MAGIC.copyOf(PcSignal.LENGTH),
                ByteArray(PcSignal.LENGTH) { i -> if (i < PcSignal.MAGIC.size) 0xFF.toByte() else 0 },
            )
            .build()
        // Экономный режим: ПК рассылает сигнал 8 с, телефон слушает эфир каждые несколько секунд.
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_POWER).build()
        scanner.startScan(listOf(filter), settings, callback)
        awaitClose { runCatching { scanner.stopScan(callback) } }
    }
}
