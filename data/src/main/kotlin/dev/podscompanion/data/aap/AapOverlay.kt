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
    /** Решение «primary — левый», принятое при [swaps] сменах ролей (см. [AapDeviceState.primarySwaps]). */
    data class Side(val primaryIsLeft: Boolean, val swaps: Int, val confirmed: Boolean = false) {
        /** Сторона сейчас: каждая смена ролей после решения переворачивает её. */
        fun primaryIsLeftNow(aap: AapDeviceState): Boolean =
            if ((aap.primarySwaps - swaps) % 2 == 0) primaryIsLeft else !primaryIsLeft
    }

    /**
     * Какой наушник AAP называет primary — левый? AAP сообщает ухо как primary/secondary без сторон.
     * Источники по надёжности:
     * 1. По AAP и по рекламе ровно один наушник в ухе — сопоставляем их ([Side.confirmed]).
     * 2. Прошлое подтверждённое решение с поправкой на смены ролей.
     * 3. Порядок наушников в пакете заряда AAP ([AapDeviceState.batteryPrimaryIsLeft]).
     * 4. Бит стороны из рекламы. Он ненадёжен: у Pro 2 при обоих наушниках в ушах он указывал на
     *    другой наушник, и после вынимания ~5 с, до свежего пакета, вынутым показывался не тот.
     */
    /**
     * @param adFresh реклама пришла уже после последней смены уха по AAP. Старая реклама может
     *   показывать вынутым другой наушник (вынули правый, вставили, сразу вынули левый — реклама
     *   ещё про правый): сверяться с ней нельзя, иначе стороны переворачиваются и так и остаются.
     */
    fun resolveSide(status: PodsStatus, aap: AapDeviceState, previous: Side?, adFresh: Boolean = true): Side {
        val primaryIn = aap.primaryEar == EarState.IN_EAR
        val secondaryIn = aap.secondaryEar == EarState.IN_EAR
        val aapDiffers = primaryIn != secondaryIn && aap.secondaryEar != EarState.UNKNOWN
        val adDiffers = status.left.inEar != status.right.inEar
        val fromBattery = aap.batteryPrimaryIsLeft
        return when {
            aapDiffers && adDiffers && adFresh -> Side(primaryIn == status.left.inEar, aap.primarySwaps, confirmed = true)
            previous != null && previous.confirmed -> previous
            fromBattery != null -> Side(fromBattery, aap.batteryPrimarySwaps)
            previous != null -> previous
            else -> Side(status.primaryIsLeft, aap.primarySwaps)
        }
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
