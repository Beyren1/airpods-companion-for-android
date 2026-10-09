package dev.podscompanion.data.autopause

import dev.podscompanion.data.ConnectedNameMatcher
import dev.podscompanion.data.PodsStatus
import dev.podscompanion.protocol.aap.AapDeviceState
import dev.podscompanion.protocol.aap.EarState
import dev.podscompanion.protocol.advertising.Capability
import dev.podscompanion.protocol.advertising.PodsModel

/**
 * Надеты ли наушники — для автопаузы. Главный источник — прямое подключение (AAP): наушники
 * сами присылают «вынут» в ту же долю секунды и не зависят от того, слышит ли телефон их рекламу.
 * Реклама — запасной вариант, пока AAP не подключился или не прислал ухо.
 */
object WornState {

    /** Надеты ли наушники по AAP; null — AAP ещё не прислал состояние уха. */
    fun fromAap(device: AapDeviceState, deviceName: String): Boolean? {
        if (!device.earKnown) return null
        val primaryIn = device.primaryEar == EarState.IN_EAR
        val models = ConnectedNameMatcher.modelsForName(deviceName)
        val oneEarpiece = device.single != null ||
            (models.isNotEmpty() && models.none { Capability.STEREO_BUDS in it.capabilities })
        // Второго наушника нет в пакете (один в кейсе с закрытой крышкой и т. п.): судим по первому.
        if (oneEarpiece || device.secondaryEar == EarState.UNKNOWN) return primaryIn
        return primaryIn && device.secondaryEar == EarState.IN_EAR
    }

    /** Надеты ли наушники по рекламе: у вкладышей нужны оба в ушах, у Max — на голове. */
    fun fromAdvertising(status: PodsStatus): Boolean {
        val model: PodsModel? = status.model
        return if (model != null && Capability.STEREO_BUDS !in model.capabilities) {
            status.primary.inEar
        } else {
            status.left.inEar && status.right.inEar
        }
    }
}
