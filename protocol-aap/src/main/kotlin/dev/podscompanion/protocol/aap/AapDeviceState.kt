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
) {
    fun apply(event: AapEvent): AapDeviceState = when (event) {
        is AapEvent.Battery -> event.components.fold(this) { state, battery ->
            when (battery.component) {
                BatteryComponent.LEFT -> state.copy(left = battery)
                BatteryComponent.RIGHT -> state.copy(right = battery)
                BatteryComponent.CASE -> state.copy(case = battery)
                BatteryComponent.SINGLE -> state.copy(single = battery)
                BatteryComponent.UNKNOWN -> state
            }
        }
        is AapEvent.EarDetection -> copy(primaryEar = event.primary, secondaryEar = event.secondary)
        is AapEvent.ListeningModeChanged -> copy(listeningMode = event.mode)
        is AapEvent.ConversationalAwarenessChanged -> copy(conversationalAwareness = event.enabled)
        is AapEvent.Unknown -> this
    }

    /** Ухо уже пришло хотя бы раз: можно доверять ему больше, чем рекламным пакетам. */
    val earKnown: Boolean get() = primaryEar != EarState.UNKNOWN
}
