package dev.podscompanion.data.autopause

import com.google.common.truth.Truth.assertThat
import dev.podscompanion.protocol.aap.AapBattery
import dev.podscompanion.protocol.aap.AapDeviceState
import dev.podscompanion.protocol.aap.BatteryComponent
import dev.podscompanion.protocol.aap.EarState
import org.junit.jupiter.api.Test

class WornStateTest {

    private fun buds(primary: EarState, secondary: EarState) = AapDeviceState(primaryEar = primary, secondaryEar = secondary)

    @Test
    fun `уха ещё не было — не знаем`() {
        assertThat(WornState.fromAap(AapDeviceState(), "AirPods")).isNull()
    }

    @Test
    fun `вкладыши надеты только когда оба в ушах`() {
        assertThat(WornState.fromAap(buds(EarState.IN_EAR, EarState.IN_EAR), "AirPods Pro")).isTrue()
        assertThat(WornState.fromAap(buds(EarState.IN_EAR, EarState.OUT_OF_EAR), "AirPods Pro")).isFalse()
        assertThat(WornState.fromAap(buds(EarState.OUT_OF_EAR, EarState.IN_EAR), "AirPods")).isFalse()
        assertThat(WornState.fromAap(buds(EarState.IN_CASE, EarState.IN_CASE), "AirPods")).isFalse()
    }

    @Test
    fun `состояние второго наушника неизвестно — не решаем`() {
        assertThat(WornState.fromAap(buds(EarState.IN_EAR, EarState.UNKNOWN), "AirPods")).isNull()
    }

    @Test
    fun `Max — по одной чашке`() {
        val max = AapDeviceState(
            single = AapBattery(BatteryComponent.SINGLE, 80, charging = false, connected = true),
            primaryEar = EarState.IN_EAR,
            secondaryEar = EarState.OUT_OF_EAR,
        )
        assertThat(WornState.fromAap(max, "AirPods Max")).isTrue()
        assertThat(WornState.fromAap(max.copy(primaryEar = EarState.OUT_OF_EAR), "AirPods Max")).isFalse()
    }
}
