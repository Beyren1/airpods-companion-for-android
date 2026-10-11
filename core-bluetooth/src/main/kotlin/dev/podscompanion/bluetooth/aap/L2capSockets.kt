package dev.podscompanion.bluetooth.aap

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.os.Build
import android.os.ParcelUuid
import dev.podscompanion.protocol.aap.Aap
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
 * Пробуем по очереди несколько способов: их набор отличается между версиями Android и прошивками.
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
        val uuid = ParcelUuid.fromString(Aap.SERVICE_UUID)
        val int = Int::class.javaPrimitiveType!!
        val bool = Boolean::class.javaPrimitiveType!!
        // Сначала защищённый сокет (auth + encrypt) с UUID сервиса AAP: так подключается LibrePods,
        // и так работает на Samsung A56, где незащищённый connect() висел без ответа.
        // Набор конструкторов меняется между версиями Android, поэтому пробуем все известные.
        val attempts = listOf<Pair<String, () -> BluetoothSocket>>(
            // Android 16 QPR3: BluetoothSocket(adapter, device, type, auth, encrypt, port, uuid)
            "secure(adapter, device, …)" to {
                @Suppress("DEPRECATION")
                val adapter = BluetoothAdapter.getDefaultAdapter()
                constructor(BluetoothAdapter::class.java, BluetoothDevice::class.java, int, bool, bool, int, ParcelUuid::class.java)
                    .newInstance(adapter, device, TYPE_L2CAP, true, true, psm, uuid)
            },
            // Android 14+: BluetoothSocket(device, type, auth, encrypt, port, uuid)
            "secure(device, …)" to {
                constructor(BluetoothDevice::class.java, int, bool, bool, int, ParcelUuid::class.java)
                    .newInstance(device, TYPE_L2CAP, true, true, psm, uuid)
            },
            // Вариант с fd после типа (встречается в прошивках производителей).
            "secure(device, type, fd, …)" to {
                constructor(BluetoothDevice::class.java, int, int, bool, bool, int, ParcelUuid::class.java)
                    .newInstance(device, TYPE_L2CAP, -1, true, true, psm, uuid)
            },
            // Android 10–13: BluetoothSocket(type, fd, auth, encrypt, device, port, uuid)
            "secure(type, fd, …)" to {
                constructor(int, int, bool, bool, BluetoothDevice::class.java, int, ParcelUuid::class.java)
                    .newInstance(TYPE_L2CAP, -1, true, true, device, psm, uuid)
            },
            "createL2capSocket" to {
                BluetoothDevice::class.java.getMethod("createL2capSocket", int).invoke(device, psm) as BluetoothSocket
            },
            // Незащищённые: работали на Pixel до перехода на защищённые, оставлены запасными.
            "createInsecureL2capSocket" to {
                BluetoothDevice::class.java.getMethod("createInsecureL2capSocket", int).invoke(device, psm) as BluetoothSocket
            },
            "insecure(device, …)" to {
                constructor(BluetoothDevice::class.java, int, bool, bool, int, ParcelUuid::class.java)
                    .newInstance(device, TYPE_L2CAP, false, false, psm, null)
            },
            "insecure(type, fd, …)" to {
                constructor(int, int, bool, bool, BluetoothDevice::class.java, int, ParcelUuid::class.java)
                    .newInstance(TYPE_L2CAP, -1, false, false, device, psm, null)
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
