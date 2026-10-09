package dev.podscompanion.protocol.aap

import com.google.common.truth.Truth.assertThat
import dev.podscompanion.protocol.util.Hex
import org.junit.jupiter.api.Test

class MaxControlsTest {

    @Test
    fun `команда Digital Crown и её разбор обратно`() {
        val bytes = AapCommand.SetCrownDirection(CrownDirection.FRONT_TO_BACK).bytes
        assertThat(Hex.encode(bytes)).isEqualTo("04 00 04 00 09 00 1C 02 00 00 00")
        val state = AapDeviceState().apply(AapParser.parse(bytes)!!)
        assertThat(state.crownDirection).isEqualTo(CrownDirection.FRONT_TO_BACK)
    }

    @Test
    fun `заводские значения Max, пока наушники молчат`() {
        val state = AapDeviceState().withDefaults(MAX_DEFAULT_CONTROLS)
        assertThat(state.modeCycle).containsExactly(ListeningMode.NOISE_CANCELLATION, ListeningMode.TRANSPARENCY)
        assertThat(state.toggle(AapToggle.EAR_DETECTION)).isTrue()
        assertThat(state.crownDirection).isEqualTo(CrownDirection.BACK_TO_FRONT)
    }

    @Test
    fun `присланное наушниками важнее заводского`() {
        val reported = AapDeviceState().apply(AapParser.parse(Hex.decode("04 00 04 00 09 00 0A 02 00 00 00"))!!)
        val state = reported.withDefaults(MAX_DEFAULT_CONTROLS)
        assertThat(state.toggle(AapToggle.EAR_DETECTION)).isFalse()
        assertThat(state.crownDirection).isEqualTo(CrownDirection.BACK_TO_FRONT)
    }

    @Test
    fun `настройки из журнала Max после подключения`() {
        val state = listOf(
            "04 00 04 00 09 00 0D 03 00 00 00",
            "04 00 04 00 09 00 1B 02 00 00 00",
            "04 00 04 00 09 00 24 00 03 00 00",
            "04 00 04 00 09 00 18 00 00 00 00",
            "04 00 04 00 09 00 17 00 00 00 00",
            "04 00 04 00 09 00 1F 50 50 00 00",
        ).mapNotNull { AapParser.parse(Hex.decode(it)) }
            .fold(AapDeviceState()) { s, e -> s.apply(e) }

        assertThat(state.listeningMode).isEqualTo(ListeningMode.TRANSPARENCY)
        assertThat(state.pressSpeed).isEqualTo(PressSpeed.DEFAULT)
        assertThat(state.holdDuration).isEqualTo(HoldDuration.DEFAULT)
        assertThat(state.toneVolume).isEqualTo(80)
    }

    @Test
    fun `команды скорости, зажатия и громкости сигналов`() {
        assertThat(Hex.encode(AapCommand.SetPressSpeed(PressSpeed.SLOWEST).bytes)).isEqualTo("04 00 04 00 09 00 17 02 00 00 00")
        assertThat(Hex.encode(AapCommand.SetHoldDuration(HoldDuration.SHORTER).bytes)).isEqualTo("04 00 04 00 09 00 18 01 00 00 00")
        assertThat(Hex.encode(AapCommand.SetToneVolume(100).bytes)).isEqualTo("04 00 04 00 09 00 1F 64 64 00 00")
    }

    @Test
    fun `известные настройки подписаны в журнале`() {
        assertThat(ControlId.name(0x1C)).isEqualTo("направление Digital Crown")
        assertThat(ControlId.name(0x20)).isEqualTo("автоподключение (0x20)")
        assertThat(ControlId.name(0x7F)).isEqualTo("настройка 0x7F")
    }
}
