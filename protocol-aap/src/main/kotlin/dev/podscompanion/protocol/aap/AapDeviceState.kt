package dev.podscompanion.protocol.aap

/**
 * Всё, что известно о наушниках по AAP. Неизменяемый: каждое событие даёт новое состояние
 * (как reducer: state + event → state), поэтому легко тестировать и безопасно отдавать в UI.
 */
data class AapDeviceState(
    val left: AapBattery? = null,
    val right: AapBattery? = null,
    val case: AapBattery? = null,
    /** У Max и наушников с одной батареей. */
    val single: AapBattery? = null,
    val primaryEar: EarState = EarState.UNKNOWN,
    val secondaryEar: EarState = EarState.UNKNOWN,
    val listeningMode: ListeningMode? = null,
    val conversationalAwareness: Boolean? = null,
    /**
     * Сколько раз наушники поменялись ролями primary/secondary. Когда вынимают primary, роль переходит
     * к наушнику в ухе, и следующий пакет уха приходит «зеркальным»: (вынут, в ухе) → (в ухе, вынут).
     * По счётчику [dev.podscompanion.data.aap.AapOverlay] переворачивает сторону, не дожидаясь рекламы.
     */
    val primarySwaps: Int = 0,
    /**
     * Primary — левый? По порядку наушников в пакете заряда: первым идёт primary
     * (лог Pro 2: «левый, правый, кейс», и вынутый левый оставался primary). null — заряда ещё не было.
     */
    val batteryPrimaryIsLeft: Boolean? = null,
    /** [primarySwaps] на момент пакета заряда, из которого взят [batteryPrimaryIsLeft]. */
    val batteryPrimarySwaps: Int = 0,
) {
    fun apply(event: AapEvent): AapDeviceState = when (event) {
        is AapEvent.Battery -> event.components.fold(withBatteryOrder(event)) { state, battery ->
            when (battery.component) {
                BatteryComponent.LEFT -> state.copy(left = battery)
                BatteryComponent.RIGHT -> state.copy(right = battery)
                BatteryComponent.CASE -> state.copy(case = battery)
                BatteryComponent.SINGLE -> state.copy(single = battery)
                BatteryComponent.UNKNOWN -> state
            }
        }
        is AapEvent.EarDetection -> copy(
            primaryEar = event.primary,
            secondaryEar = event.secondary,
            primarySwaps = if (isMirrorOf(event)) primarySwaps + 1 else primarySwaps,
        )
        is AapEvent.ListeningModeChanged -> copy(listeningMode = event.mode)
        is AapEvent.ConversationalAwarenessChanged -> copy(conversationalAwareness = event.enabled)
        is AapEvent.Unknown -> this
    }

    private fun withBatteryOrder(event: AapEvent.Battery): AapDeviceState {
        val first = event.components.firstOrNull {
            it.component == BatteryComponent.LEFT || it.component == BatteryComponent.RIGHT
        } ?: return this
        return copy(batteryPrimaryIsLeft = first.component == BatteryComponent.LEFT, batteryPrimarySwaps = primarySwaps)
    }

    private fun isMirrorOf(event: AapEvent.EarDetection): Boolean =
        primaryEar != secondaryEar && primaryEar != EarState.UNKNOWN && secondaryEar != EarState.UNKNOWN &&
            event.primary == secondaryEar && event.secondary == primaryEar

    /** Ухо уже пришло хотя бы раз: можно доверять ему больше, чем рекламным пакетам. */
    val earKnown: Boolean get() = primaryEar != EarState.UNKNOWN
}
