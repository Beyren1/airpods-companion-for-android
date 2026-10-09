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
    fun `известные настройки подписаны в журнале`() {
        assertThat(ControlId.name(0x1C)).isEqualTo("направление Digital Crown")
        assertThat(ControlId.name(0x20)).isEqualTo("автоподключение (0x20)")
        assertThat(ControlId.name(0x7F)).isEqualTo("настройка 0x7F")
    }
}
