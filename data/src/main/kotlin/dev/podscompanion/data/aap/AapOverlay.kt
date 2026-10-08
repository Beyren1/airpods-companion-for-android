package dev.podscompanion.data.aap

import dev.podscompanion.data.PodsStatus
import dev.podscompanion.protocol.aap.AapBattery
import dev.podscompanion.protocol.aap.AapDeviceState
import dev.podscompanion.protocol.aap.EarState
import dev.podscompanion.protocol.advertising.BatteryLevel
import dev.podscompanion.protocol.advertising.PodState

/**
 * Накладывает данные AAP на состояние из рекламы: AAP точнее (заряд 1 %) и быстрее (ухо сразу),
 * поэтому всё, что пришло по AAP, заменяет рекламное. Чего AAP не прислал — остаётся из рекламы.
 */
object AapOverlay {
    fun apply(status: PodsStatus, aap: AapDeviceState): PodsStatus {
        val primaryEar = aap.primaryEar
        val secondaryEar = aap.secondaryEar
        val leftEar = if (status.primaryIsLeft) primaryEar else secondaryEar
        val rightEar = if (status.primaryIsLeft) secondaryEar else primaryEar

        val left = status.left.with(aap.left, leftEar)
        val right = status.right.with(aap.right, rightEar)
        val primarySource = aap.single ?: if (status.primaryIsLeft) aap.left else aap.right
        val primary = status.primary.with(primarySource, primaryEar)
        return status.copy(
            left = left,
            right = right,
            primary = primary,
            caseBattery = aap.case?.let { BatteryLevel(it.percent) } ?: status.caseBattery,
            caseCharging = aap.case?.charging ?: status.caseCharging,
            caseBatteryRemembered = if (aap.case != null) false else status.caseBatteryRemembered,
            aap = aap,
        )
    }

    private fun PodState.with(battery: AapBattery?, ear: EarState): PodState {
        val withBattery = if (battery != null) copy(battery = BatteryLevel(battery.percent), charging = battery.charging) else this
        return when (ear) {
            EarState.IN_EAR -> withBattery.copy(inEar = true, inCase = false)
            EarState.OUT_OF_EAR -> withBattery.copy(inEar = false, inCase = false)
            EarState.IN_CASE -> withBattery.copy(inEar = false, inCase = true)
            EarState.UNKNOWN -> withBattery
        }
    }
}
