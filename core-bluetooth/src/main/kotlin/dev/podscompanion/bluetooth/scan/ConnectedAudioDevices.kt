package dev.podscompanion.bluetooth.scan

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.podscompanion.protocol.aap.Aap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.shareIn

/**
 * Имена Bluetooth-наушников, подключённых к телефону для звука (профиль A2DP).
 *
 * MAC из BLE-рекламы случайный и с классическим адресом не связан, поэтому «какие из
 * наушников рядом подключены» определяем по имени («AirPods Max», «AirPods Pro Beyren»).
 * Нужно разрешение BLUETOOTH_CONNECT; без него поток отдаёт null («неизвестно»).
 */
/**
 * Подключённые наушники: имя и заряд, который Android получил от них по HFP (null — неизвестно).
 * [supportsAap] — в SDP-записи есть сервис AAP, значит это AirPods/Beats и к ним можно подключиться напрямую.
 */
data class ConnectedAudioDevice(
    val name: String,
    val batteryPercent: Int?,
    val device: BluetoothDevice,
    val supportsAap: Boolean,
)

@Singleton
class ConnectedAudioDevices @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Один источник на всё приложение: экран, сервис, соединения AAP и музыка подписываются на него
     * одновременно. Раньше у каждого был свой прокси A2DP и свой приёмник рассылок, и на каждое
     * изменение заряда список устройств (с reflection и SDP-записями) собирался пять раз.
     * Без подписчиков источник засыпает через 5 с; новый подписчик сразу получает последний список.
     */
    private val shared: Flow<List<ConnectedAudioDevice>?> by lazy {
        observe().shareIn(scope, SharingStarted.WhileSubscribed(5_000), replay = 1)
    }

    fun devices(): Flow<List<ConnectedAudioDevice>?> = shared

    @SuppressLint("MissingPermission")
    private fun observe(): Flow<List<ConnectedAudioDevice>?> = callbackFlow<List<ConnectedAudioDevice>?> {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null) {
            trySend(null)
            awaitClose { }
            return@callbackFlow
        }
        // Источник общий и живёт долго: разрешение могут выдать, пока он работает, — дождёмся его.
        if (!hasConnectPermission()) {
            trySend(null)
            while (!hasConnectPermission()) delay(PERMISSION_POLL_MS)
        }

        var proxy: BluetoothA2dp? = null
        // Заряд из системной рассылки BATTERY_LEVEL_CHANGED, по адресу устройства.
        val batteryByAddress = mutableMapOf<String, Int>()
        fun refresh() {
            val devices = runCatching {
                proxy?.connectedDevices.orEmpty().mapNotNull { device ->
                    val name = device.displayName() ?: return@mapNotNull null
                    ConnectedAudioDevice(
                        name,
                        batteryByAddress[device.address] ?: device.batteryLevelOrNull(),
                        device,
                        supportsAap = device.uuids.orEmpty().any { it.uuid.toString().equals(Aap.SERVICE_UUID, ignoreCase = true) },
                    )
                }
            }.getOrDefault(emptyList())
            trySend(devices)
        }

        val listener = object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, p: BluetoothProfile) {
                proxy = p as BluetoothA2dp
                refresh()
            }

            override fun onServiceDisconnected(profile: Int) {
                proxy = null
                refresh()
            }
        }
        adapter.getProfileProxy(context, listener, BluetoothProfile.A2DP)

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == ACTION_BATTERY_LEVEL_CHANGED) {
                    val device = intent.bluetoothDevice()
                    val level = intent.getIntExtra(EXTRA_BATTERY_LEVEL, -1)
                    if (device != null) {
                        if (level in 0..100) batteryByAddress[device.address] = level else batteryByAddress.remove(device.address)
                    }
                }
                refresh()
            }
        }
        val filter = IntentFilter().apply {
            addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_ALIAS_CHANGED)
            addAction(ACTION_BATTERY_LEVEL_CHANGED)
        }
        // Рассылки шлёт система (Bluetooth-процесс), поэтому приёмник должен быть EXPORTED.
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)

        awaitClose {
            runCatching { context.unregisterReceiver(receiver) }
            proxy?.let { runCatching { adapter.closeProfileProxy(BluetoothProfile.A2DP, it) } }
        }
    }.distinctUntilChanged()

    /**
     * Заряд, который наушники сообщают телефону (у AirPods это стандартное расширение HFP).
     * Метод скрыт из SDK, поэтому вызываем через reflection; если система не даст, вернём null.
     */
    private fun BluetoothDevice.batteryLevelOrNull(): Int? = runCatching {
        (BluetoothDevice::class.java.getMethod("getBatteryLevel").invoke(this) as Int).takeIf { it in 0..100 }
    }.getOrNull()

    private fun Intent.bluetoothDevice(): BluetoothDevice? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }

    /** Имя, которое пользователь видит в настройках Bluetooth (с учётом переименования). */
    @SuppressLint("MissingPermission")
    private fun BluetoothDevice.displayName(): String? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) alias ?: name else name

    private fun hasConnectPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    private companion object {
        const val PERMISSION_POLL_MS = 1_000L

        // Системные константы, скрытые из публичного SDK (@SystemApi), значения стабильны с Android 8.
        const val ACTION_BATTERY_LEVEL_CHANGED = "android.bluetooth.device.action.BATTERY_LEVEL_CHANGED"
        const val EXTRA_BATTERY_LEVEL = "android.bluetooth.device.extra.BATTERY_LEVEL"
    }
}
