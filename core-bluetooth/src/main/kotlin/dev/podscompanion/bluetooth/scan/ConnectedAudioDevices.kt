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
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Имена Bluetooth-наушников, подключённых к телефону для звука (профиль A2DP).
 *
 * MAC из BLE-рекламы случайный и с классическим адресом не связан, поэтому «какие из
 * наушников рядом подключены» определяем по имени («AirPods Max», «AirPods Pro Beyren»).
 * Нужно разрешение BLUETOOTH_CONNECT; без него поток отдаёт пустой список.
 */
@Singleton
class ConnectedAudioDevices @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    @SuppressLint("MissingPermission")
    fun names(): Flow<List<String>> = callbackFlow {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null || !hasConnectPermission()) {
            trySend(emptyList())
            awaitClose { }
            return@callbackFlow
        }

        var proxy: BluetoothA2dp? = null
        fun refresh() {
            val names = runCatching { proxy?.connectedDevices.orEmpty().mapNotNull { it.displayName() } }
                .getOrDefault(emptyList())
            trySend(names)
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
            override fun onReceive(context: Context, intent: Intent) = refresh()
        }
        val filter = IntentFilter().apply {
            addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_ALIAS_CHANGED)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)

        awaitClose {
            runCatching { context.unregisterReceiver(receiver) }
            proxy?.let { runCatching { adapter.closeProfileProxy(BluetoothProfile.A2DP, it) } }
        }
    }.distinctUntilChanged()

    /** Имя, которое пользователь видит в настройках Bluetooth (с учётом переименования). */
    @SuppressLint("MissingPermission")
    private fun BluetoothDevice.displayName(): String? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) alias ?: name else name

    private fun hasConnectPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED
}
