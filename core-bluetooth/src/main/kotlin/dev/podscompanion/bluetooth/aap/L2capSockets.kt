package dev.podscompanion.bluetooth.aap

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.os.Build
import android.os.ParcelUuid
import org.lsposed.hiddenapibypass.HiddenApiBypass
import timber.log.Timber

/** Система не дала создать классический L2CAP-сокет: на этой прошивке AAP без root недоступен. */
class L2capUnavailableException(cause: Throwable?) :
    IllegalStateException("Android не разрешил открыть L2CAP-сокет", cause)

/**
 * Классический (BR/EDR) L2CAP-сокет к PSM AAP. В публичном SDK есть только BLE-вариант
 * (createL2capChannel), поэтому вызываем скрытые методы Android. HiddenApiBypass снимает
 * ограничение на скрытые API для нашего процесса; root для этого не нужен.
 *
 * Пробуем по очереди несколько способов: их набор отличается между версиями Android.
 */
internal object L2capSockets {
    private const val TYPE_L2CAP = 3

    @Volatile
    private var exempted = false

    /** Какой способ сработал в последний раз (показываем в отладке). */
    @Volatile
    var lastMethod: String? = null
        private set

    @SuppressLint("PrivateApi", "DiscouragedPrivateApi")
    fun create(device: BluetoothDevice, psm: Int): BluetoothSocket {
        exemptHiddenApis()
        val attempts = listOf<Pair<String, () -> BluetoothSocket>>(
            "createInsecureL2capSocket" to {
                BluetoothDevice::class.java.getMethod("createInsecureL2capSocket", Int::class.javaPrimitiveType)
                    .invoke(device, psm) as BluetoothSocket
            },
            "createL2capSocket" to {
                BluetoothDevice::class.java.getMethod("createL2capSocket", Int::class.javaPrimitiveType)
                    .invoke(device, psm) as BluetoothSocket
            },
            // Android 14+: BluetoothSocket(device, type, auth, encrypt, port, uuid)
            "BluetoothSocket(device, …)" to {
                constructor(
                    BluetoothDevice::class.java, Int::class.javaPrimitiveType!!, Boolean::class.javaPrimitiveType!!,
                    Boolean::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!, ParcelUuid::class.java,
                ).newInstance(device, TYPE_L2CAP, false, false, psm, null)
            },
            // Android 10–13: BluetoothSocket(type, fd, auth, encrypt, device, port, uuid)
            "BluetoothSocket(type, fd, …)" to {
                constructor(
                    Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!, Boolean::class.javaPrimitiveType!!,
                    Boolean::class.javaPrimitiveType!!, BluetoothDevice::class.java, Int::class.javaPrimitiveType!!,
                    ParcelUuid::class.java,
                ).newInstance(TYPE_L2CAP, -1, false, false, device, psm, null)
            },
        )
        var lastError: Throwable? = null
        for ((name, attempt) in attempts) {
            try {
                return attempt().also { lastMethod = name }
            } catch (e: Throwable) {
                Timber.d(e, "L2CAP: %s не сработал", name)
                lastError = e
            }
        }
        throw L2capUnavailableException(lastError)
    }

    private fun constructor(vararg types: Class<*>) =
        BluetoothSocket::class.java.getDeclaredConstructor(*types).apply { isAccessible = true }

    private fun exemptHiddenApis() {
        if (exempted || Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        // "L" — префикс всех классов: снимаем ограничение целиком, но только в нашем процессе.
        exempted = runCatching { HiddenApiBypass.addHiddenApiExemptions("L") }.getOrDefault(false)
    }
}
