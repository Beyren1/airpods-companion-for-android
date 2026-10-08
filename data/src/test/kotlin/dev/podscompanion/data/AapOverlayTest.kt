package dev.podscompanion.data

import com.google.common.truth.Truth.assertThat
import dev.podscompanion.data.aap.AapOverlay
import dev.podscompanion.protocol.aap.AapBattery
import dev.podscompanion.protocol.aap.AapDeviceState
import dev.podscompanion.protocol.aap.AapEvent
import dev.podscompanion.protocol.aap.BatteryComponent
import dev.podscompanion.protocol.aap.EarState
import dev.podscompanion.protocol.advertising.BatteryLevel
import dev.podscompanion.protocol.advertising.PodState
import dev.podscompanion.protocol.advertising.PodsModel
import org.junit.jupiter.api.Test

class AapOverlayTest {
    private val worn = PodState(BatteryLevel(90), charging = false, inEar = true)
    private val advertised = PodsStatus(
        model = PodsModel.AIRPODS_PRO_2_USB_C, modelId = 0x2420,
        left = worn, right = worn, primary = worn,
        caseBattery = BatteryLevel(50), caseCharging = false, caseBatteryRemembered = true,
        lidCounter = 0, colorCode = 0, rssi = -50, lastSeenMs = 0, rawHex = "",
        primaryIsLeft = false,
    )

    @Test
    fun `точный заряд и ухо из AAP заменяют рекламу`() {
        val aap = AapDeviceState(
            left = AapBattery(BatteryComponent.LEFT, 97, charging = false, connected = true),
            right = AapBattery(BatteryComponent.RIGHT, 100, charging = false, connected = true),
            case = AapBattery(BatteryComponent.CASE, 51, charging = true, connected = true),
            primaryEar = EarState.IN_EAR, // primary = правый (primaryIsLeft = false)
            secondaryEar = EarState.OUT_OF_EAR,
        )

        val result = AapOverlay.apply(advertised, aap)

        assertThat(result.left.battery?.percent).isEqualTo(97)
        assertThat(result.left.inEar).isFalse()
        assertThat(result.right.inEar).isTrue()
        assertThat(result.caseBattery?.percent).isEqualTo(51)
        assertThat(result.caseBatteryRemembered).isFalse()
        assertThat(result.exactBattery).isTrue()
        assertThat(result.left.battery?.displayText(result.exactBattery)).isEqualTo("97%")
    }

    @Test
    fun `пока AAP ничего не прислал — остаётся реклама`() {
        val result = AapOverlay.apply(advertised, AapDeviceState())
        assertThat(result.left).isEqualTo(worn)
        assertThat(result.caseBattery?.percent).isEqualTo(50)
    }

    @Test
    fun `сторона primary берётся из рекламы, а не из прыгающего отправителя`() {
        val aap = AapDeviceState(primaryEar = EarState.IN_EAR, secondaryEar = EarState.OUT_OF_EAR)
        val leftOut = advertised.copy(left = worn.copy(inEar = false), primaryIsLeft = true)

        // Реклама: левый вынут, правый в ухе → primary (в ухе) — правый, хотя отправитель левый.
        val side = AapOverlay.resolveSide(leftOut, aap, previous = null)
        assertThat(side.primaryIsLeftNow(aap)).isFalse()
        val result = AapOverlay.apply(leftOut, aap, side.primaryIsLeftNow(aap))
        assertThat(result.right.inEar).isTrue()
        assertThat(result.left.inEar).isFalse()

        // Следующий пакет от другого наушника, реклама ещё не обновилась (оба в ухе): решение не меняется.
        assertThat(AapOverlay.resolveSide(advertised.copy(primaryIsLeft = true), aap, previous = side).primaryIsLeftNow(aap)).isFalse()
    }

    @Test
    fun `смена ролей переворачивает сторону до прихода рекламы`() {
        // Оба в ухе, primary — левый (из рекламы).
        var aap = AapDeviceState().apply(AapEvent.EarDetection(EarState.IN_EAR, EarState.IN_EAR))
        val side = AapOverlay.resolveSide(advertised.copy(primaryIsLeft = true), aap, previous = null)

        // Вынули левый (primary): сначала (вынут, в ухе), затем роль переходит к правому — (в ухе, вынут).
        aap = aap.apply(AapEvent.EarDetection(EarState.OUT_OF_EAR, EarState.IN_EAR))
        aap = aap.apply(AapEvent.EarDetection(EarState.IN_EAR, EarState.OUT_OF_EAR))

        // Реклама ещё старая (оба в ухе), а показываем уже верно: левый вынут.
        val now = AapOverlay.resolveSide(advertised.copy(primaryIsLeft = true), aap, side)
        val result = AapOverlay.apply(advertised, aap, now.primaryIsLeftNow(aap))
        assertThat(result.left.inEar).isFalse()
        assertThat(result.right.inEar).isTrue()
    }
}
