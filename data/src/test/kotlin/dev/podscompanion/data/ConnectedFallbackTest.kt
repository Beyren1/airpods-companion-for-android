package dev.podscompanion.data

import com.google.common.truth.Truth.assertThat
import dev.podscompanion.protocol.aap.AapBattery
import dev.podscompanion.protocol.aap.AapDeviceState
import dev.podscompanion.protocol.aap.BatteryComponent
import dev.podscompanion.protocol.aap.EarState
import dev.podscompanion.protocol.advertising.PodsModel
import org.junit.jupiter.api.Test

class ConnectedFallbackTest {

    @Test
    fun `имя AirPods Pro — первое поколение, а не Pro 2`() {
        assertThat(ConnectedFallback.modelFor("AirPods Pro")).isEqualTo(PodsModel.AIRPODS_PRO)
        assertThat(ConnectedFallback.modelFor("AirPods Max")).isEqualTo(PodsModel.AIRPODS_MAX)
        assertThat(ConnectedFallback.modelFor("Мои уши")).isNull()
    }

    @Test
    fun `без AAP — заряд из системы, ухо неизвестно`() {
        val status = ConnectedFallback.status("AirPods Pro", systemBattery = 70, aap = null, nowMs = 0)

        assertThat(status.model).isEqualTo(PodsModel.AIRPODS_PRO)
        assertThat(status.connected).isTrue()
        assertThat(status.advertised).isFalse()
        assertThat(status.earKnown).isFalse()
        assertThat(status.left.battery?.percent).isEqualTo(70)
        assertThat(status.right.battery?.percent).isEqualTo(70)
    }

    @Test
    fun `с AAP — точный заряд и ухо`() {
        val aap = AapDeviceState(
            left = AapBattery(BatteryComponent.LEFT, 83, charging = false, connected = true),
            right = AapBattery(BatteryComponent.RIGHT, 91, charging = false, connected = true),
            primaryEar = EarState.IN_EAR,
            secondaryEar = EarState.IN_EAR,
            batteryPrimaryIsLeft = true,
        )
        val status = ConnectedFallback.status("AirPods Pro", systemBattery = 80, aap = aap, nowMs = 0)

        assertThat(status.left.battery?.percent).isEqualTo(83)
        assertThat(status.right.battery?.percent).isEqualTo(91)
        assertThat(status.left.inEar && status.right.inEar).isTrue()
        assertThat(status.earKnown).isTrue()
    }

    @Test
    fun `второй наушник по AAP неизвестен — по уху не судим`() {
        val aap = AapDeviceState(primaryEar = EarState.IN_EAR, secondaryEar = EarState.UNKNOWN)
        val status = ConnectedFallback.status("AirPods Pro", systemBattery = null, aap = aap, nowMs = 0)
        assertThat(status.earKnown).isFalse()
    }
}
