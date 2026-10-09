package dev.podscompanion.data.battery

import com.google.common.truth.Truth.assertThat
import dev.podscompanion.data.PodsStatus
import dev.podscompanion.protocol.advertising.BatteryLevel
import dev.podscompanion.protocol.advertising.PodState
import dev.podscompanion.protocol.advertising.PodsModel
import org.junit.jupiter.api.Test

class LowBatteryWatcherTest {
    private val thresholds = listOf(20, 10)
    private val stable = LowBatteryWatcher.STABLE_MS

    private fun pod(percent: Int, charging: Boolean = false) = PodState(BatteryLevel(percent), charging, inEar = true)

    private fun buds(left: Int, right: Int = 80, leftCharging: Boolean = false, connected: Boolean = true) = PodsStatus(
        model = PodsModel.AIRPODS_PRO_2_USB_C, modelId = 0x2420,
        left = pod(left, leftCharging), right = pod(right), primary = pod(left, leftCharging),
        caseBattery = null, caseCharging = false,
        lidCounter = 0, colorCode = 0, rssi = -50, lastSeenMs = 0, rawHex = "", connected = connected,
    )

    private fun max(percent: Int) = PodsStatus(
        model = PodsModel.AIRPODS_MAX_USB_C, modelId = 0x1F20,
        left = pod(percent), right = pod(percent), primary = pod(percent),
        caseBattery = null, caseCharging = false,
        lidCounter = 0, colorCode = 0, rssi = -50, lastSeenMs = 0, rawHex = "", connected = true,
    )

    /** Подать одно и то же состояние дважды с интервалом [stable] и вернуть первое событие. */
    private fun LowBatteryWatcher.hold(status: PodsStatus, start: Long): LowBatteryEvent? =
        onStatus(status, thresholds, start) ?: onStatus(status, thresholds, start + stable)

    @Test
    fun `предупреждает на 20 и на 10, но по одному разу`() {
        val w = LowBatteryWatcher()
        assertThat(w.hold(buds(50), 0)).isNull()
        val first = w.hold(buds(19), 100_000)
        assertThat(first).isInstanceOf(LowBatteryEvent.Alert::class.java)
        assertThat((first as LowBatteryEvent.Alert).parts.map { it.part }).containsExactly(BatteryPart.LEFT)
        assertThat(w.hold(buds(15), 200_000)).isNull()
        assertThat(w.hold(buds(10), 300_000)).isInstanceOf(LowBatteryEvent.Alert::class.java)
        assertThat(w.hold(buds(5), 400_000)).isNull()
    }

    @Test
    fun `короткий скачок не вызывает предупреждения`() {
        val w = LowBatteryWatcher()
        assertThat(w.onStatus(buds(15), thresholds, 0)).isNull()
        assertThat(w.onStatus(buds(80), thresholds, 3_000)).isNull()
        assertThat(w.onStatus(buds(15), thresholds, stable)).isNull()
    }

    @Test
    fun `после зарядки выше порога предупреждает снова`() {
        val w = LowBatteryWatcher()
        assertThat(w.hold(buds(18), 0)).isInstanceOf(LowBatteryEvent.Alert::class.java)
        // На зарядке уведомление убираем.
        assertThat(w.hold(buds(30, leftCharging = true), 100_000)).isInstanceOf(LowBatteryEvent.Dismiss::class.java)
        w.hold(buds(60), 200_000)
        assertThat(w.hold(buds(19), 300_000)).isInstanceOf(LowBatteryEvent.Alert::class.java)
    }

    @Test
    fun `заряд около порога не звенит повторно`() {
        val w = LowBatteryWatcher()
        assertThat(w.hold(buds(20), 0)).isInstanceOf(LowBatteryEvent.Alert::class.java)
        w.hold(buds(22), 100_000)
        assertThat(w.hold(buds(20), 200_000)).isNull()
    }

    @Test
    fun `о чужих наушниках не предупреждает`() {
        val w = LowBatteryWatcher()
        assertThat(w.hold(buds(5, connected = false), 0)).isNull()
    }

    @Test
    fun `у Max один заряд`() {
        val w = LowBatteryWatcher()
        val event = w.hold(max(9), 0) as LowBatteryEvent.Alert
        assertThat(event.parts.map { it.part }).containsExactly(BatteryPart.SINGLE)
    }
}
