package dev.podscompanion.bluetooth.connect

import android.annotation.SuppressLint
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.Intent
import android.media.MediaRouter2
import android.provider.Settings
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.podscompanion.bluetooth.aap.L2capSockets
import dev.podscompanion.bluetooth.scan.ConnectedAudioDevices
import dev.podscompanion.protocol.aap.Aap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.lsposed.hiddenapibypass.HiddenApiBypass
import kotlin.coroutines.resume

/** Чем закончилась попытка забрать наушники на телефон. */
sealed interface TakeOverResult {
    /** Наушники подключились для звука; [method] — какой способ сработал. */
    data class Connected(val method: String) : TakeOverResult

    /** Ни один способ не сработал: остаётся системное окно выбора устройства. */
    data object Failed : TakeOverResult
}

/**
 * Подключает к телефону наушники, которые сейчас подключены к другому устройству (например, к ПК).
 *
 * Обычным приложениям Android не даёт подключить звук (A2DP) к наушникам: это системная функция.
 * Поэтому пробуем по очереди несколько обходных путей и записываем в журнал, какой сработал.
 * Каждый способ даёт наушникам [WAIT_MS] на подключение звука.
 */
@Singleton
class PodsConnector @Inject constructor(
    @ApplicationContext private val context: Context,
    private val connectedAudio: ConnectedAudioDevices,
) {
    @SuppressLint("MissingPermission")
    suspend fun takeOver(device: BluetoothDevice, log: (String) -> Unit): TakeOverResult {
        if (isConnected(device)) return TakeOverResult.Connected("уже подключены")
        exemptHiddenApis()
        val methods = listOf<Pair<String, suspend () -> Unit>>(
            // Скрытый connect у прокси A2DP первым: на Pixel 9 без root он подключает звук примерно за 4 с.
            "A2DP.connect" to suspend { profileConnect(BluetoothProfile.A2DP, device) },
            "HFP.connect" to suspend { profileConnect(BluetoothProfile.HEADSET, device) },
            // Требует связи с наушниками через CompanionDeviceManager, без неё сразу SecurityException.
            "BluetoothDevice.connect" to suspend {
                // Возвращает код BluetoothStatusCodes: 0 — принято, остальное — отказ.
                val code = BluetoothDevice::class.java.getMethod("connect").invoke(device)
                if (code is Int && code != 0) error("код $code")
            },
            // Прямое соединение AAP последним: звук им подключить не вышло, а ждать его дольше всех.
            AAP_METHOD to suspend { aapPing(device) },
        )
        for ((name, method) in methods) {
            val error = runCatching { method() }.exceptionOrNull()
            val cause = (error as? java.lang.reflect.InvocationTargetException)?.targetException ?: error
            if (cause != null) {
                log("забрать: $name — ошибка ${cause.javaClass.simpleName}: ${cause.message}")
                if (isConnected(device)) return TakeOverResult.Connected(name)
                continue
            }
            log("забрать: $name — вызван, жду звук")
            // Канал AAP уже держали открытым, пока ждали звук: второй раз ждать незачем.
            if (if (name == AAP_METHOD) isConnected(device) else waitConnected(device)) {
                log("забрать: $name — наушники подключились")
                return TakeOverResult.Connected(name)
            }
            log("забрать: $name — звук не подключился за ${WAIT_MS / 1_000} с")
        }
        return TakeOverResult.Failed
    }

    /** Сопряжённые наушники Apple/Beats: в их SDP-записи есть сервис AAP. */
    @SuppressLint("MissingPermission")
    fun bondedPods(): List<BluetoothDevice> = runCatching {
        adapter()?.bondedDevices.orEmpty().filter { device ->
            device.uuids.orEmpty().any { it.uuid.toString().equals(Aap.SERVICE_UUID, ignoreCase = true) }
        }
    }.getOrDefault(emptyList())

    /** Имя сопряжённого устройства, как его видит пользователь. */
    @SuppressLint("MissingPermission")
    fun nameOf(device: BluetoothDevice): String =
        (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) device.alias else null) ?: device.name.orEmpty()

    fun device(address: String): BluetoothDevice? = runCatching { adapter()?.getRemoteDevice(address) }.getOrNull()

    /**
     * Запасной путь, когда сами подключить не смогли: системное окно «Где воспроизводить»
     * (Android 14+), в нём наушники подключаются одним касанием. На старых версиях — настройки Bluetooth.
     */
    fun openOutputSwitcher() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            runCatching { MediaRouter2.getInstance(context).showSystemOutputSwitcher() }.getOrDefault(false)
        ) return
        runCatching {
            context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    private fun adapter() = context.getSystemService(BluetoothManager::class.java)?.adapter

    private suspend fun isConnected(device: BluetoothDevice): Boolean =
        connectedAudio.devices().filterNotNull().first().any { it.device.address == device.address }

    private suspend fun waitConnected(device: BluetoothDevice): Boolean = withTimeoutOrNull(WAIT_MS) {
        connectedAudio.devices().filterNotNull().first { list -> list.any { it.device.address == device.address } }
    } != null

    /** Скрытый connect(device) у прокси профиля (A2DP или HFP). */
    private suspend fun profileConnect(profile: Int, device: BluetoothDevice) {
        val adapter = adapter() ?: error("нет Bluetooth")
        val proxy = withTimeoutOrNull(PROXY_TIMEOUT_MS) {
            suspendCancellableCoroutine<BluetoothProfile> { cont ->
                adapter.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
                    override fun onServiceConnected(p: Int, proxy: BluetoothProfile) {
                        if (cont.isActive) cont.resume(proxy)
                    }

                    override fun onServiceDisconnected(p: Int) = Unit
                }, profile)
            }
        } ?: error("профиль недоступен")
        try {
            val type = if (profile == BluetoothProfile.A2DP) BluetoothA2dp::class.java else BluetoothHeadset::class.java
            val ok = type.getMethod("connect", BluetoothDevice::class.java).invoke(proxy, device)
            if (ok == false) error("система ответила false")
        } finally {
            runCatching { adapter.closeProfileProxy(profile, proxy) }
        }
    }

    /** Открывает и держит канал AAP, пока ждём звук. */
    @SuppressLint("MissingPermission")
    private suspend fun aapPing(device: BluetoothDevice) {
        val socket = L2capSockets.create(device, Aap.PSM)
        try {
            // connect() блокирует поток и отмену не слышит; прервать его можно только закрытием сокета.
            val connected = coroutineScope {
                val timeout = launch {
                    delay(WAIT_MS)
                    runCatching { socket.close() }
                }
                withContext(Dispatchers.IO) { runCatching { socket.connect() }.isSuccess }.also { timeout.cancel() }
            }
            if (!connected) error("канал AAP не открылся")
            withContext(Dispatchers.IO) { socket.outputStream.write(Aap.HANDSHAKE) }
            waitConnected(device)
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun exemptHiddenApis() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching { HiddenApiBypass.addHiddenApiExemptions("L") }
        }
    }

    private companion object {
        const val WAIT_MS = 6_000L
        const val PROXY_TIMEOUT_MS = 3_000L
        const val AAP_METHOD = "AAP L2CAP"
    }
}
