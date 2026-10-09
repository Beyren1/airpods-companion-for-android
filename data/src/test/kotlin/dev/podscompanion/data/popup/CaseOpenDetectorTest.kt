package dev.podscompanion.data.popup

import com.google.common.truth.Truth.assertThat
import dev.podscompanion.data.PodsStatus
import dev.podscompanion.protocol.advertising.BatteryLevel
import dev.podscompanion.protocol.advertising.PodState
import dev.podscompanion.protocol.advertising.PodsModel
import org.junit.jupiter.api.Test

class CaseOpenDetectorTest {
    private val inCase = PodState(BatteryLevel(80), charging = true, inEar = false, inCase = true)

    private fun status(open: Boolean, rssi: Int = -50) = PodsStatus(
        model = PodsModel.AIRPODS_PRO_2_USB_C, modelId = 0x2420,
        left = inCase, right = inCase, primary = inCase,
        caseBattery = BatteryLevel(60), caseCharging = false, caseBatteryRemembered = !open,
        lidCounter = 0, colorCode = 0, rssi = rssi, lastSeenMs = 0, rawHex = "",
    )

    @Test
    fun `окно при первом открытии крышки`() {
        val detector = CaseOpenDetector()
        assertThat(detector.onStatus(status(open = false), emptySet())).isFalse()
        assertThat(detector.onStatus(status(open = true), emptySet())).isTrue()
        assertThat(detector.onStatus(status(open = true), emptySet())).isFalse()
    }

    @Test
    fun `для знакомых наушников окна нет`() {
        val detector = CaseOpenDetector()
        assertThat(detector.onStatus(status(open = true), setOf(0x2420))).isFalse()
    }

    @Test
    fun `чужой кейс далеко не вызывает окно`() {
        val detector = CaseOpenDetector()
        assertThat(detector.onStatus(status(open = true, rssi = -85), emptySet())).isFalse()
    }
}
