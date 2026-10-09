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
    fun `разный заряд с разных наушников одной пары — одна пара с достоверным зарядом`() {
        packet("A", status(PodsModel.AIRPODS_PRO_2_USB_C, rssi = -50, left = 100, right = 100), 0)
        packet("B", status(PodsModel.AIRPODS_PRO_2_USB_C, rssi = -72, left = null, right = 70), 500)

        val nearby = tracker.snapshot(500, emptyList())

        assertThat(nearby.others).hasSize(1)
        assertThat(nearby.others[0].left.battery?.percent).isEqualTo(100)
        assertThat(nearby.others[0].right.battery?.percent).isEqualTo(100)
    }

    @Test
    fun `общее имя AirPods — главными остаются надетые, а не пропадают`() {
        packet("FOUR", status(PodsModel.AIRPODS_4_ANC, rssi = -60), 0)
        assertThat(tracker.snapshot(0, listOf("AirPods")).primary?.model).isEqualTo(PodsModel.AIRPODS_4_ANC)

        val max = status(PodsModel.AIRPODS_MAX_USB_C, rssi = -40, left = 90, right = null)
        packet("MAX", max.copy(primary = max.primary.copy(inEar = false), left = max.left.copy(inEar = false)), 500)
        val nearby = tracker.snapshot(500, listOf("AirPods"))

        assertThat(nearby.primary?.model).isEqualTo(PodsModel.AIRPODS_4_ANC)
        assertThat(nearby.primary?.connected).isTrue()
    }

    @Test
    fun `подключили AirPods 4 — Max и Pro 2 рядом главными не становятся`() {
        packet("FOUR", status(PodsModel.AIRPODS_4_ANC, rssi = -70), 0)
        packet("MAX", status(PodsModel.AIRPODS_MAX_USB_C, rssi = -40, left = 90, right = null), 0)
        packet("PRO", status(PodsModel.AIRPODS_PRO_2_USB_C, rssi = -45), 0)

        val nearby = tracker.snapshot(0, listOf("AirPods"))

        assertThat(nearby.primary?.model).isEqualTo(PodsModel.AIRPODS_4_ANC)
    }

    @Test
    fun `две пары с именем AirPods — главные те, чей заряд сообщили по прямому подключению`() {
        packet("THREE", status(PodsModel.AIRPODS_3, rssi = -40, left = 30, right = 30), 0)
        packet("FOUR", status(PodsModel.AIRPODS_4_ANC, rssi = -70, left = 80, right = 80), 0)

        val nearby = tracker.snapshot(0, listOf("AirPods"), connectedBatteries = listOf(87, 85))

        assertThat(nearby.primary?.model).isEqualTo(PodsModel.AIRPODS_4_ANC)
    }

    @Test
    fun `свои Max запоминаются и не путаются с чужими Max рядом`() {
        val known = HashMap<String, NearbyPodsTracker.KnownPair>()
        val own = status(PodsModel.AIRPODS_MAX_USB_C, rssi = -70, left = 90, right = null)
        val other = status(PodsModel.AIRPODS_MAX, rssi = -40, left = 40, right = null)
        fun feed(t: NearbyPodsTracker, now: Long) {
            t.onPacket("OWN", PairFingerprint(own.modelId, own.colorCode, 90, null), own, now)
            t.onPacket("OTHER", PairFingerprint(other.modelId, other.colorCode, 40, null), other, now)
        }
        val first = NearbyPodsTracker(knownPairs = known)
        feed(first, 0)
        // Заряд от прямого подключения однозначно указывает на свои.
        assertThat(first.snapshot(0, listOf("AirPods Max"), connectedBatteries = listOf(99)).primary?.model)
            .isEqualTo(PodsModel.AIRPODS_MAX_USB_C)

        // Скан перезапустили, заряда ещё нет, чужие ближе — всё равно свои.
        val second = NearbyPodsTracker(knownPairs = known)
        feed(second, 1_000)
        assertThat(second.snapshot(1_000, listOf("AirPods Max")).primary?.model).isEqualTo(PodsModel.AIRPODS_MAX_USB_C)
    }

    @Test
    fun `две одинаковые пары Max — свои узнаём по ключу`() {
        val own = status(PodsModel.AIRPODS_MAX_USB_C, rssi = -75, left = 90, right = null).copy(owner = "AA:AA")
        val other = status(PodsModel.AIRPODS_MAX_USB_C, rssi = -40, left = 90, right = null)
        tracker.onPacket("OWN", PairFingerprint(own.modelId, own.colorCode, 90, null, owner = "AA:AA"), own, 0)
        tracker.onPacket("OTHER", PairFingerprint(other.modelId, other.colorCode, 90, null), other, 0)

        val nearby = tracker.snapshot(0, listOf("AirPods Max"), connectedAddresses = setOf("AA:AA"))

        assertThat(nearby.primary?.owner).isEqualTo("AA:AA")
        assertThat(nearby.primary?.connected).isTrue()
        assertThat(nearby.others).hasSize(1)
    }

    @Test
    fun `свои Pro 2 не подключены — подключёнными их не считаем`() {
        val pro = status(PodsModel.AIRPODS_PRO_2_USB_C, rssi = -40).copy(owner = "PRO")
        tracker.onPacket("P", PairFingerprint(pro.modelId, pro.colorCode, 90, 100, owner = "PRO"), pro, 0)

        val nearby = tracker.snapshot(0, listOf("AirPods Pro"), connectedAddresses = setOf("OTHER"))

        assertThat(nearby.primary).isNull()
    }

    @Test
    fun `70 и 100 от одной пары — показываем заряд, совпадающий с сообщённым телефону`() {
        packet("A", status(PodsModel.AIRPODS_PRO_2_USB_C, rssi = -66, left = 70, right = 70), 0)
        packet("B", status(PodsModel.AIRPODS_PRO_2_USB_C, rssi = -68, left = 100, right = 100), 0)

        val nearby = tracker.snapshot(0, listOf("AirPods Pro"), connectedBatteries = listOf(100))

        assertThat(nearby.primary?.left?.battery?.percent).isEqualTo(100)
        assertThat(nearby.primary?.connected).isTrue()
        assertThat(nearby.others).isEmpty()
    }

    @Test
    fun `пакет 70 с адреса пары 100 не портит заряд в карточке`() {
        packet("A", status(PodsModel.AIRPODS_PRO_2_USB_C, rssi = -66, left = 100, right = 100), 0)
        packet("A", status(PodsModel.AIRPODS_PRO_2_USB_C, rssi = -66, left = 70, right = 70), 500)

        val nearby = tracker.snapshot(500, listOf("AirPods Pro"), connectedBatteries = listOf(100))

        assertThat(nearby.primary?.left?.battery?.percent).isEqualTo(100)
        assertThat(nearby.others).isEmpty()
    }
}
