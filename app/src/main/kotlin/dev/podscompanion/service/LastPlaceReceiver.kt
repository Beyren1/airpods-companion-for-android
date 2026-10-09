package dev.podscompanion.service

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import dagger.hilt.android.AndroidEntryPoint
import dev.podscompanion.data.find.LastPlaceRecorder
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * «Bluetooth-устройство отключилось»: если это AirPods, запоминаем, где телефон был в этот момент.
 * Рассылку система доставляет и закрытому приложению, фоновый режим для этого не нужен.
 */
@AndroidEntryPoint
class LastPlaceReceiver : BroadcastReceiver() {

    @Inject lateinit var recorder: LastPlaceRecorder

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != BluetoothDevice.ACTION_ACL_DISCONNECTED) return
        val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        } ?: return
        // goAsync: место определяется несколько секунд, а onReceive должен вернуться сразу.
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                recorder.onDisconnected(device)
            } finally {
                pending.finish()
            }
        }
    }
}
