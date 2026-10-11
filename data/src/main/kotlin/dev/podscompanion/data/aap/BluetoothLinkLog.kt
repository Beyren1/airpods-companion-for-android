package dev.podscompanion.data.aap

import android.annotation.SuppressLint
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.core.content.ContextCompat
import dev.podscompanion.data.ConnectedNameMatcher
import dev.podscompanion.protocol.aap.Aap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Пишет в журнал соединения, что происходит с самой Bluetooth-связью наушников Apple:
 * связь с телефоном (ACL) появилась или пропала, подключился ли звук (A2DP) и звонки (HFP).
 * По этим строкам видно, рвут ли наушники связь целиком (например, Max, когда их сняли)
 * или пропадает только наше прямое подключение.
 */
@Singleton
class BluetoothLinkLog @Inject constructor(private val log: AapLog) {

    private var registeredIn: Context? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val device = intent.device() ?: return
            if (!device.isApple()) return
            val what = when (intent.action) {
                BluetoothDevice.ACTION_ACL_CONNECTED -> "связь с телефоном есть (ACL)"
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> "связь с телефоном пропала (ACL)"
                BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED -> "звук (A2DP): ${state(intent)}"
                BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED -> "звонки (HFP): ${state(intent)}"
                BluetoothA2dp.ACTION_PLAYING_STATE_CHANGED ->
                    if (intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1) == BluetoothA2dp.STATE_PLAYING) "звук идёт" else "звук остановлен"
                else -> return
            }
            log.add("Bluetooth ${device.label()}: $what")
        }
    }

    fun start(context: Context) {
        if (registeredIn != null) return
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
            addAction(BluetoothA2dp.ACTION_PLAYING_STATE_CHANGED)
            addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
        }
        // Рассылки шлёт Bluetooth-процесс системы, поэтому приёмник должен быть EXPORTED.
        runCatching { ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED) }
            .onSuccess { registeredIn = context }
    }

    fun stop() {
        registeredIn?.let { runCatching { it.unregisterReceiver(receiver) } }
        registeredIn = null
    }

    private fun state(intent: Intent): String = when (intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1)) {
        BluetoothProfile.STATE_CONNECTED -> "подключено"
        BluetoothProfile.STATE_CONNECTING -> "подключается"
        BluetoothProfile.STATE_DISCONNECTING -> "отключается"
        BluetoothProfile.STATE_DISCONNECTED -> "отключено"
        else -> "?"
    }

    // Без разрешения BLUETOOTH_CONNECT имя и UUID недоступны: тогда такие устройства просто не пишем.
    @SuppressLint("MissingPermission")
    private fun BluetoothDevice.isApple(): Boolean = runCatching {
        uuids.orEmpty().any { it.uuid.toString().equals(Aap.SERVICE_UUID, ignoreCase = true) } ||
            ConnectedNameMatcher.modelsForName(label()).isNotEmpty()
    }.getOrDefault(false)

    @SuppressLint("MissingPermission")
    private fun BluetoothDevice.label(): String =
        runCatching { (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) alias else null) ?: name }.getOrNull() ?: "?"

    private fun Intent.device(): BluetoothDevice? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }
}
