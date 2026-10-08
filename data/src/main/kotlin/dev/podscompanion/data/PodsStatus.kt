package dev.podscompanion.data

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
)
