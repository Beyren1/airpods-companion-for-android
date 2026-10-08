package dev.podscompanion.data

import com.google.common.truth.Truth.assertThat
import dev.podscompanion.protocol.advertising.BatteryLevel
import dev.podscompanion.protocol.advertising.PodState
import dev.podscompanion.protocol.advertising.PodsModel
import org.junit.jupiter.api.Test

class NearbyPodsTrackerTest {

    private val tracker = NearbyPodsTracker(minRssi = -80, stickinessDb = 8, staleAfterMs = 15_000)

    private fun status(model: PodsModel, rssi: Int, left: Int? = 90, right: Int? = 100) = PodsStatus(
        model = model, modelId = model.modelId,
        left = PodState(left?.let(::BatteryLevel), charging = false, inEar = true),
        right = PodState(right?.let(::BatteryLevel), charging = false, inEar = true),
        primary = PodState(left?.let(::BatteryLevel), charging = false, inEar = true),
        caseBattery = null, caseCharging = false,
        lidCounter = 0, colorCode = 0, rssi = rssi, lastSeenMs = 0, rawHex = "",
    )

    private fun packet(address: String, s: PodsStatus, now: Long) = tracker.onPacket(
        address,
        PairFingerprint(s.modelId, s.colorCode, s.left.battery?.percent, s.right.battery?.percent),
        s,
        now,
    )

    @Test
    fun `подключённые главнее ближайших`() {
        packet("MAX", status(PodsModel.AIRPODS_MAX_USB_C, rssi = -40, left = 90, right = null), 0)
        packet("PRO", status(PodsModel.AIRPODS_PRO_2_USB_C, rssi = -70), 0)

        val nearby = tracker.snapshot(0, connectedNames = listOf("AirPods Pro"))

        assertThat(nearby.primary?.model).isEqualTo(PodsModel.AIRPODS_PRO_2_USB_C)
        assertThat(nearby.primary?.connected).isTrue()
        assertThat(nearby.others.map { it.model }).containsExactly(PodsModel.AIRPODS_MAX_USB_C)
    }

    @Test
    fun `ничего не подключено — главных нет, все в списке рядом`() {
        packet("MAX", status(PodsModel.AIRPODS_MAX_USB_C, rssi = -40, left = 90, right = null), 0)
        packet("PRO", status(PodsModel.AIRPODS_PRO_2_USB_C, rssi = -70), 0)

        val nearby = tracker.snapshot(0, connectedNames = emptyList())

        assertThat(nearby.primary).isNull()
        assertThat(nearby.others.map { it.model })
            .containsExactly(PodsModel.AIRPODS_MAX_USB_C, PodsModel.AIRPODS_PRO_2_USB_C).inOrder()
    }

    @Test
    fun `без разрешения на подключённые главные — ближайшие`() {
        packet("MAX", status(PodsModel.AIRPODS_MAX_USB_C, rssi = -40, left = 90, right = null), 0)
        packet("PRO", status(PodsModel.AIRPODS_PRO_2_USB_C, rssi = -70), 0)

        val nearby = tracker.snapshot(0, connectedNames = null)

        assertThat(nearby.primary?.model).isEqualTo(PodsModel.AIRPODS_MAX_USB_C)
        assertThat(nearby.primary?.connected).isFalse()
    }

    @Test
    fun `второй наушник с другого адреса — то же устройство`() {
        packet("LEFT", status(PodsModel.AIRPODS_4_ANC, rssi = -60), 0)
        packet("RIGHT", status(PodsModel.AIRPODS_4_ANC, rssi = -88, right = null), 500)

        val nearby = tracker.snapshot(500, null)

        assertThat(nearby.others).isEmpty()
        assertThat(nearby.primary?.rssi).isEqualTo(-88)
    }

    @Test
    fun `выбранные наушники со слабым сигналом не бросаем, чужие слабые не показываем`() {
        packet("MINE", status(PodsModel.AIRPODS_4_ANC, rssi = -60), 0)
        tracker.snapshot(0, null)
        packet("MINE", status(PodsModel.AIRPODS_4_ANC, rssi = -88), 1_000)
        packet("FAR", status(PodsModel.AIRPODS_PRO_2, rssi = -90), 1_000)

        val nearby = tracker.snapshot(1_000, null)

        assertThat(nearby.primary?.model).isEqualTo(PodsModel.AIRPODS_4_ANC)
        assertThat(nearby.others).isEmpty()
    }

    @Test
    fun `пропавшие через 15 с исчезают`() {
        packet("MINE", status(PodsModel.AIRPODS_4_ANC, rssi = -60), 0)
        assertThat(tracker.snapshot(16_000, null)).isEqualTo(NearbyPods.EMPTY)
    }

    @Test
    fun `считает средний интервал между пакетами`() {
        val max = status(PodsModel.AIRPODS_MAX_USB_C, rssi = -45, left = 90, right = null)
        packet("A", max, 0)
        packet("A", max, 5_000)
        packet("A", max, 10_000)

        assertThat(tracker.snapshot(10_000, null).primary?.packetIntervalMs).isEqualTo(5_000)
    }

    @Test
    fun `подключённые Max не видны — других наушников главными не делаем`() {
        packet("PRO", status(PodsModel.AIRPODS_PRO_2_USB_C, rssi = -50), 0)

        val nearby = tracker.snapshot(0, connectedNames = listOf("AirPods Max"))

        assertThat(nearby.primary).isNull()
        assertThat(nearby.others.map { it.model }).containsExactly(PodsModel.AIRPODS_PRO_2_USB_C)
    }

    @Test
    fun `чужие наушники той же модели с другим зарядом — отдельная пара`() {
        packet("A", status(PodsModel.AIRPODS_PRO_2_USB_C, rssi = -50, left = 100, right = 100), 0)
        packet("B", status(PodsModel.AIRPODS_PRO_2_USB_C, rssi = -55, left = 30, right = 20), 500)

        val nearby = tracker.snapshot(500, null)

        assertThat(nearby.primary?.left?.battery?.percent).isEqualTo(100)
        assertThat(nearby.others).hasSize(1)
    }
}
