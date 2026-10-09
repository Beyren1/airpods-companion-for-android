package dev.podscompanion.ui.battery

import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothStatusCodes
import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.BluetoothDeviceFilter
import android.companion.CompanionDeviceManager
import android.content.Context
import android.content.IntentSender
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/** Чем закончилась смена имени в Android. */
enum class AliasResult { DONE, DECLINED, FAILED, UNSUPPORTED }

/**
 * Меняет имя наушников в самом Android (то, что видно в настройках Bluetooth).
 *
 * Команда переименования уходит в наушники, но Android запоминает имя при сопряжении и сам его
 * не перечитывает. Своё имя (alias) Android разрешает задать только приложению, связанному с
 * устройством через Companion Device Manager. Поэтому в первый раз система покажет окно
 * «Разрешить Pods Companion управлять …», дальше имя меняется сразу. Нужен Android 12+.
 */
@Composable
fun rememberAliasSetter(onResult: (AliasResult) -> Unit): (address: String, name: String) -> Unit {
    val context = LocalContext.current
    val pending = remember { arrayOfNulls<Pair<String, String>>(1) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        val (address, name) = pending[0] ?: return@rememberLauncherForActivityResult
        pending[0] = null
        onResult(
            if (result.resultCode == Activity.RESULT_OK && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setAlias(context, address, name)
            } else {
                AliasResult.DECLINED
            },
        )
    }
    return remember(context) {
        { address: String, name: String ->
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                onResult(AliasResult.UNSUPPORTED)
            } else if (isAssociated(context, address)) {
                onResult(setAlias(context, address, name))
            } else {
                pending[0] = address to name
                associate(
                    context, address,
                    onSender = { launcher.launch(IntentSenderRequest.Builder(it).build()) },
                    onFailure = { pending[0] = null; onResult(AliasResult.FAILED) },
                )
            }
        }
    }
}

private fun cdm(context: Context) = context.getSystemService(CompanionDeviceManager::class.java)

@Suppress("DEPRECATION")
private fun isAssociated(context: Context, address: String): Boolean = runCatching {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        cdm(context).myAssociations.any { it.deviceMacAddress?.toString().equals(address, ignoreCase = true) }
    } else {
        cdm(context).associations.any { it.equals(address, ignoreCase = true) }
    }
}.getOrDefault(false)

/** Просит систему связать приложение с наушниками: система вернёт окно подтверждения. */
@Suppress("DEPRECATION")
private fun associate(context: Context, address: String, onSender: (IntentSender) -> Unit, onFailure: () -> Unit) {
    val request = AssociationRequest.Builder()
        .addDeviceFilter(BluetoothDeviceFilter.Builder().setAddress(address).build())
        .setSingleDevice(true)
        .build()
    val callback = object : CompanionDeviceManager.Callback() {
        // До Android 13 окно приходит сюда, с 13-го — в onAssociationPending.
        @Deprecated("Deprecated in Java")
        override fun onDeviceFound(intentSender: IntentSender) = onSender(intentSender)
        override fun onAssociationPending(intentSender: IntentSender) = onSender(intentSender)
        override fun onAssociationCreated(associationInfo: AssociationInfo) {}
        override fun onFailure(error: CharSequence?) = onFailure()
    }
    runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            cdm(context).associate(request, context.mainExecutor, callback)
        } else {
            cdm(context).associate(request, callback, Handler(Looper.getMainLooper()))
        }
    }.onFailure { onFailure() }
}

@RequiresApi(Build.VERSION_CODES.S)
@SuppressLint("MissingPermission") // BLUETOOTH_CONNECT выдано на первом экране
private fun setAlias(context: Context, address: String, name: String): AliasResult = runCatching {
    val device: BluetoothDevice = context.getSystemService(BluetoothManager::class.java).adapter.getRemoteDevice(address)
    if (device.setAlias(name) == BluetoothStatusCodes.SUCCESS) AliasResult.DONE else AliasResult.FAILED
}.getOrDefault(AliasResult.FAILED)
