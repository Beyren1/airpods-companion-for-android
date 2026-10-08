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
    /**
     * Какой наушник AAP называет primary — левый? AAP сообщает ухо как primary/secondary без сторон,
     * а «отправитель» рекламного пакета прыгает между наушниками, поэтому брать сторону из него нельзя:
     * плашка «в ухе» скакала между левым и правым. Решаем так: если по AAP и по рекламе ровно один
     * наушник в ухе, сопоставляем их; иначе оставляем прошлое решение [previous].
     */
    fun resolvePrimaryIsLeft(status: PodsStatus, aap: AapDeviceState, previous: Boolean?): Boolean {
        val primaryIn = aap.primaryEar == EarState.IN_EAR
        val secondaryIn = aap.secondaryEar == EarState.IN_EAR
        val aapDiffers = primaryIn != secondaryIn && aap.secondaryEar != EarState.UNKNOWN
        val adDiffers = status.left.inEar != status.right.inEar
        return if (aapDiffers && adDiffers) primaryIn == status.left.inEar else previous ?: status.primaryIsLeft
    }

    fun apply(status: PodsStatus, aap: AapDeviceState, primaryIsLeft: Boolean = status.primaryIsLeft): PodsStatus {
        val primaryEar = aap.primaryEar
        val secondaryEar = aap.secondaryEar
        val leftEar = if (primaryIsLeft) primaryEar else secondaryEar
        val rightEar = if (primaryIsLeft) secondaryEar else primaryEar

        val left = status.left.with(aap.left, leftEar)
        val right = status.right.with(aap.right, rightEar)
        val primarySource = aap.single ?: if (primaryIsLeft) aap.left else aap.right
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
