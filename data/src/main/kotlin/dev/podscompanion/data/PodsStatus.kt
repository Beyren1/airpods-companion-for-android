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
    val caseBattery: BatteryLevel?,
    val caseCharging: Boolean,
    val lidCounter: Int,
    val colorCode: Int,
    val rssi: Int,
    val lastSeenMs: Long,
    val rawHex: String,
)
