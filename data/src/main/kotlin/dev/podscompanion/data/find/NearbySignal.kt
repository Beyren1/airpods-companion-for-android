package dev.podscompanion.data.find

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.podscompanion.bluetooth.scan.PodsScanner
import dev.podscompanion.bluetooth.scan.ScanIntensity
import dev.podscompanion.data.aap.OwnPodsKeys
import dev.podscompanion.data.aap.ProximityKeyStore
import dev.podscompanion.protocol.advertising.PodsModel
import dev.podscompanion.protocol.advertising.ProximityCrypto
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch

/** Один пакет рекламы для «горячо/холодно». */
data class SignalSample(
    /** По нему пакеты собираются в одни наушники: адрес своих наушников или «модель:цвет» для прочих. */
    val key: String,
    val model: PodsModel?,
    /** Постоянный адрес своих наушников (узнали ключом IRK); null — чужие или ключа ещё нет. */
    val ownerAddress: String?,
    /** Имя своих наушников из настроек Bluetooth. */
    val ownerName: String?,
    val rssi: Int,
    val atMs: Long,
)

/**
 * Сигнал наушников рядом для поиска. Свой скан с максимальной частотой: он идёт, только пока
 * открыт поиск. Свои наушники узнаём ключом IRK (его наушники дают при прямом подключении),
 * поэтому чужие AirPods той же модели с ними не путаются.
 */
@Singleton
class NearbySignal @Inject constructor(
    @ApplicationContext private val context: Context,
    private val scanner: PodsScanner,
    private val keyStore: ProximityKeyStore,
) {
    fun observe(): Flow<SignalSample> = channelFlow {
        var keys = emptyList<OwnPodsKeys>()
        val owners = HashMap<String, String?>()
        val names = HashMap<String, String>()
        launch {
            keyStore.keys.collect {
                keys = it
                owners.clear()
            }
        }
        scanner.scan(ScanIntensity.LOW_LATENCY).collect { event ->
            if (owners.size > MAX_CACHE) owners.clear()
            // AES считаем один раз на адрес: адрес рекламы меняется раз в несколько минут.
            val owner = if (owners.containsKey(event.address)) {
                owners[event.address]
            } else {
                keys.firstOrNull { k -> k.irk != null && ProximityCrypto.resolves(event.address, k.irk) }?.address
                    .also { owners[event.address] = it }
            }
            val message = event.message
            send(
                SignalSample(
                    key = owner ?: "%04x:%02x".format(message.modelId, message.colorCode),
                    model = message.model,
                    ownerAddress = owner,
                    ownerName = owner?.let { names[it] ?: bondedName(it)?.also { name -> names[it] = name } },
                    rssi = event.rssi,
                    atMs = event.elapsedRealtimeMs,
                ),
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun bondedName(address: String): String? {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return null
        return runCatching {
            val device = adapter.getRemoteDevice(address)
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) device.alias else null) ?: device.name
        }.getOrNull()
    }

    private companion object {
        const val MAX_CACHE = 256
    }
}
