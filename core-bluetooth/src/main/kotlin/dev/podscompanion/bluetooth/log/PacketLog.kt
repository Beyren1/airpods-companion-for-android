package dev.podscompanion.bluetooth.log

import dev.podscompanion.protocol.util.Hex
import timber.log.Timber

/**
 * Hex-лог пакетов для реверса. Включается только в debug-сборке (см. BuildConfig.PACKET_LOGGING в :app).
 * Смотреть: `adb logcat -s PodsAdv`.
 */
object PacketLog {
    @Volatile
    var enabled: Boolean = false

    fun advertising(address: String, rssi: Int, data: ByteArray) {
        if (!enabled) return
        Timber.tag("PodsAdv").d("%s rssi=%d %s", address, rssi, Hex.encode(data))
    }
}
