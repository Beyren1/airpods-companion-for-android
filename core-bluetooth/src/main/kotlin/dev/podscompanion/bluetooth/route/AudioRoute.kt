package dev.podscompanion.bluetooth.route

import android.annotation.SuppressLint
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.Intent
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaRouter2
import android.os.Build
import android.provider.Settings
import dagger.hilt.android.qualifiers.ApplicationContext
import java.lang.reflect.InvocationTargetException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.lsposed.hiddenapibypass.HiddenApiBypass
import kotlin.coroutines.resume

/** Похоже ли устройство на машину: так о себе сообщают магнитолы и автомобильные гарнитуры. */
@SuppressLint("MissingPermission")
fun BluetoothDevice.looksLikeCar(): Boolean = runCatching {
    when (bluetoothClass?.deviceClass) {
        BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO, BluetoothClass.Device.AUDIO_VIDEO_HANDSFREE -> true
        else -> false
    }
}.getOrDefault(false)

/**
 * Куда Android выводит звук, когда к телефону подключено несколько Bluetooth-устройств.
 *
 * Звук идёт только в одно «активное» устройство, обычно в подключённое последним. Выбрать его
 * обычному приложению Android не даёт (setActiveDevice — системная функция), поэтому пробуем
 * по очереди скрытые вызовы, а если система их не пустит, отключаем наушники от звука и звонков:
 * тогда Android сам переводит звук на оставшееся устройство. Каждый шаг пишем в журнал.
 */
@Singleton
class AudioRoute @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val audioManager = context.getSystemService(AudioManager::class.java)

    /**
     * Сейчас звук идёт в [device]? Android показывает приложениям только активное устройство A2DP.
     * Адрес у приложений бывает скрыт, тогда сравниваем по имени.
     */
    @SuppressLint("MissingPermission")
    fun isActive(device: BluetoothDevice): Boolean {
        val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .filter { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }
        if (outputs.isEmpty()) return false
        val name = runCatching { device.displayName() }.getOrNull()
        return outputs.any { out ->
            out.address.equals(device.address, ignoreCase = true) ||
                (name != null && out.productName?.toString() == name)
        }
    }

    /** Попросить систему сделать [device] активным для музыки и звонков. true — система согласилась. */
    @SuppressLint("MissingPermission")
    suspend fun makeActive(device: BluetoothDevice, log: (String) -> Unit): Boolean {
        exemptHiddenApis()
        val attempts = listOf<Pair<String, suspend () -> Any?>>(
            // ACTIVE_DEVICE_ALL = 2: и музыка, и звонки.
            "Adapter.setActiveDevice" to {
                val adapter = adapter() ?: error("нет Bluetooth")
                BluetoothAdapter::class.java
                    .getMethod("setActiveDevice", BluetoothDevice::class.java, Int::class.javaPrimitiveType)
                    .invoke(adapter, device, ACTIVE_DEVICE_ALL)
            },
            "A2DP.setActiveDevice" to { profileCall(BluetoothProfile.A2DP, device, "setActiveDevice") },
            "HFP.setActiveDevice" to { profileCall(BluetoothProfile.HEADSET, device, "setActiveDevice") },
        )
        var any = false
        for ((name, attempt) in attempts) {
            val result = runCatching { attempt() }
            val error = result.exceptionOrNull()?.unwrap()
            when {
                error != null -> log("$name — ${error.javaClass.simpleName}: ${error.message}")
                result.getOrNull() == false -> log("$name — система ответила false")
                else -> {
                    log("$name — принято")
                    any = true
                }
            }
        }
        return any
    }

    /**
     * Отключить наушники от музыки и звонков (сопряжение остаётся). Скрытый disconnect у профилей,
     * потом BluetoothDevice.disconnect: его Android разрешает, если приложение связано с наушниками
     * (то же окно «Разрешить управлять», что и при смене имени). true — хоть один способ принят.
     */
    @SuppressLint("MissingPermission")
    suspend fun disconnectAudio(device: BluetoothDevice, log: (String) -> Unit): Boolean {
        exemptHiddenApis()
        var any = false
        for ((name, profile) in listOf("A2DP" to BluetoothProfile.A2DP, "HFP" to BluetoothProfile.HEADSET)) {
            val result = runCatching { profileCall(profile, device, "disconnect") }
            val error = result.exceptionOrNull()?.unwrap()
            when {
                error != null -> log("$name.disconnect — ${error.javaClass.simpleName}: ${error.message}")
                result.getOrNull() == false -> log("$name.disconnect — система ответила false")
                else -> {
                    log("$name.disconnect — принято")
                    any = true
                }
            }
        }
        if (any) return true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Возвращает код BluetoothStatusCodes: 0 — принято.
            val result = runCatching { BluetoothDevice::class.java.getMethod("disconnect").invoke(device) }
            val error = result.exceptionOrNull()?.unwrap()
            val code = result.getOrNull()
            when {
                error != null -> log("Device.disconnect — ${error.javaClass.simpleName}: ${error.message}")
                code is Int && code != 0 -> log("Device.disconnect — код $code")
                else -> {
                    log("Device.disconnect — принято")
                    any = true
                }
            }
        }
        return any
    }

    /** Вернуть наушникам звук (после машины). Тот же набор скрытых вызовов, только connect. */
    @SuppressLint("MissingPermission")
    suspend fun reconnectAudio(device: BluetoothDevice, log: (String) -> Unit): Boolean {
        exemptHiddenApis()
        val attempts = listOf<Pair<String, suspend () -> Any?>>(
            "A2DP.connect" to { profileCall(BluetoothProfile.A2DP, device, "connect") },
            "HFP.connect" to { profileCall(BluetoothProfile.HEADSET, device, "connect") },
            "Device.connect" to {
                val code = BluetoothDevice::class.java.getMethod("connect").invoke(device)
                if (code is Int && code != 0) error("код $code") else true
            },
        )
        for ((name, attempt) in attempts) {
            val result = runCatching { attempt() }
            val error = result.exceptionOrNull()?.unwrap()
            when {
                error != null -> log("$name — ${error.javaClass.simpleName}: ${error.message}")
                result.getOrNull() == false -> log("$name — система ответила false")
                else -> {
                    log("$name — принято")
                    return true
                }
            }
        }
        return false
    }

    /**
     * Убрать звук (музыку и видео) с наушников, не отключая их: Android переводит его в динамик
     * телефона. Звонки не трогаем. Скрытые вызовы, каждый пишем в журнал. true — система согласилась.
     */
    @SuppressLint("MissingPermission")
    suspend fun releaseMedia(log: (String) -> Unit): Boolean {
        exemptHiddenApis()
        val attempts = listOf<Pair<String, suspend () -> Any?>>(
            // ACTIVE_DEVICE_AUDIO = 0: только музыка, звонки остаются как есть.
            "Adapter.removeActiveDevice" to {
                val adapter = adapter() ?: error("нет Bluetooth")
                BluetoothAdapter::class.java
                    .getMethod("removeActiveDevice", Int::class.javaPrimitiveType)
                    .invoke(adapter, ACTIVE_DEVICE_AUDIO)
            },
            "A2DP.setActiveDevice(null)" to { profileCall(BluetoothProfile.A2DP, null, "setActiveDevice") },
        )
        return runAll(attempts, log, stopOnSuccess = false)
    }

    /** Вернуть музыку в [device] после [releaseMedia]: сделать его активным только для музыки. */
    @SuppressLint("MissingPermission")
    suspend fun restoreMedia(device: BluetoothDevice, log: (String) -> Unit): Boolean {
        exemptHiddenApis()
        val attempts = listOf<Pair<String, suspend () -> Any?>>(
            "A2DP.setActiveDevice" to { profileCall(BluetoothProfile.A2DP, device, "setActiveDevice") },
            "Adapter.setActiveDevice" to {
                val adapter = adapter() ?: error("нет Bluetooth")
                BluetoothAdapter::class.java
                    .getMethod("setActiveDevice", BluetoothDevice::class.java, Int::class.javaPrimitiveType)
                    .invoke(adapter, device, ACTIVE_DEVICE_AUDIO)
            },
        )
        return runAll(attempts, log, stopOnSuccess = true)
    }

    /** Отключить от наушников только музыку (A2DP); звонки и прямое подключение остаются. */
    @SuppressLint("MissingPermission")
    suspend fun disconnectMedia(device: BluetoothDevice, log: (String) -> Unit): Boolean {
        exemptHiddenApis()
        val attempts = listOf<Pair<String, suspend () -> Any?>>(
            "A2DP.disconnect" to { profileCall(BluetoothProfile.A2DP, device, "disconnect") },
        )
        return runAll(attempts, log, stopOnSuccess = true)
    }

    private suspend fun runAll(
        attempts: List<Pair<String, suspend () -> Any?>>,
        log: (String) -> Unit,
        stopOnSuccess: Boolean,
    ): Boolean {
        var any = false
        for ((name, attempt) in attempts) {
            val result = runCatching { attempt() }
            val error = result.exceptionOrNull()?.unwrap()
            when {
                error != null -> log("$name — ${error.javaClass.simpleName}: ${error.message}")
                result.getOrNull() == false -> log("$name — система ответила false")
                else -> {
                    log("$name — принято")
                    any = true
                    if (stopOnSuccess) return true
                }
            }
        }
        return any
    }

    /**
     * Системное окно «Где воспроизводить» (Android 14+): выбор устройства одним касанием.
     * На старых версиях — настройки Bluetooth.
     */
    fun showOutputSwitcher() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            runCatching { MediaRouter2.getInstance(context).showSystemOutputSwitcher() }.getOrDefault(false)
        ) return
        runCatching {
            context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    private fun adapter() = context.getSystemService(BluetoothManager::class.java)?.adapter

    /** Скрытый метод профиля (A2DP или HFP) с одним аргументом-устройством. */
    private suspend fun profileCall(profile: Int, device: BluetoothDevice?, method: String): Any? {
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
            return type.getMethod(method, BluetoothDevice::class.java).invoke(proxy, *arrayOf<Any?>(device))
        } finally {
            runCatching { adapter.closeProfileProxy(profile, proxy) }
        }
    }

    private fun Throwable.unwrap(): Throwable = (this as? InvocationTargetException)?.targetException ?: this

    @SuppressLint("MissingPermission")
    private fun BluetoothDevice.displayName(): String? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) alias ?: name else name

    private fun exemptHiddenApis() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching { HiddenApiBypass.addHiddenApiExemptions("L") }
        }
    }

    private companion object {
        const val ACTIVE_DEVICE_AUDIO = 0
        const val ACTIVE_DEVICE_ALL = 2
        const val PROXY_TIMEOUT_MS = 3_000L
    }
}
