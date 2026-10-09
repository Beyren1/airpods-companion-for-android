package dev.podscompanion.data

import dev.podscompanion.protocol.aap.AapDeviceState
import dev.podscompanion.protocol.advertising.BatteryLevel
import dev.podscompanion.protocol.advertising.PodState
import dev.podscompanion.protocol.advertising.PodsModel

/** Состояние наушников для UI. Позже сюда же будут сливаться данные из AAP. */
data class PodsStatus(
    val model: PodsModel?,
    val modelId: Int,
    val left: PodState,
    val right: PodState,
    /** Наушник-отправитель пакета; для Max это единственный источник заряда. */
    val primary: PodState,
    val caseBattery: BatteryLevel?,
    val caseCharging: Boolean,
    /** Заряд кейса не из этого пакета, а последний запомненный (крышка закрыта). */
    val caseBatteryRemembered: Boolean = false,
    val lidCounter: Int,
    val colorCode: Int,
    val rssi: Int,
    val lastSeenMs: Long,
    val rawHex: String,
    /** Эти наушники подключены к телефону (определено по имени A2DP-устройства). */
    val connected: Boolean = false,
    /** Средний интервал между пакетами этих наушников (для отладки задержек), null — мало данных. */
    val packetIntervalMs: Long? = null,
    /** Последний пакет с каждого MAC-адреса этой пары (для отладки). */
    val rawByAddress: Map<String, String> = emptyMap(),
    /** Отправитель пакета — левый наушник: нужно, чтобы разложить «primary/secondary» из AAP по сторонам. */
    val primaryIsLeft: Boolean = true,
    /** Данные прямого подключения (AAP), если оно есть: точный заряд, ухо без задержки, режим шумоподавления. */
    val aap: AapDeviceState? = null,
    /**
     * Свои наушники: постоянный адрес Bluetooth Classic пары, чей ключ IRK подошёл к адресу рекламы.
     * null — чужие или ключа ещё нет.
     */
    val owner: String? = null,
    /** Заряд расшифрован из рекламы ключом наушников: точный, а не десятками. */
    val exactFromAdvert: Boolean = false,
) {
    /** Заряд точный (из AAP или расшифрованной рекламы), а не десятками. */
    val exactBattery: Boolean get() = exactFromAdvert ||
        aap != null && (aap.left != null || aap.right != null || aap.single != null)
}
