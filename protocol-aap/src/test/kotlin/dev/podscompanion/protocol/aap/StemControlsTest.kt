package dev.podscompanion.protocol.aap

import com.google.common.truth.Truth.assertThat
import dev.podscompanion.protocol.util.Hex
import org.junit.jupiter.api.Test

class StemControlsTest {

    @Test
    fun `нажатие ножки от правого наушника`() {
        val event = AapParser.parse(Hex.decode("04 00 04 00 19 00 07 02"))
        assertThat(event).isEqualTo(AapEvent.StemPressed(StemPress.TRIPLE, left = false))
    }

    @Test
    fun `маска нажатий для телефона`() {
        val bytes = AapCommand.SetStemConfig(setOf(StemPress.SINGLE, StemPress.TRIPLE)).bytes
        assertThat(Hex.encode(bytes)).isEqualTo("04 00 04 00 09 00 39 05 00 00 00")
        val state = AapDeviceState().apply(AapParser.parse(bytes)!!)
        assertThat(state.stemConfigMask).isEqualTo(0x05)
        assertThat(AapCommand.SetStemConfig(emptySet()).bytes.let(Hex::encode)).isEqualTo("04 00 04 00 09 00 39 00 00 00 00")
    }

    @Test
    fun `управление звонком как у Max`() {
        // Max после подключения присылают 0x24 = 00 03: микрофон одним нажатием.
        val state = AapDeviceState().apply(AapParser.parse(Hex.decode("04 00 04 00 09 00 24 00 03 00 00"))!!)
        assertThat(state.callControl).isEqualTo(CallControl.MUTE_ON_SINGLE)
        assertThat(Hex.encode(AapCommand.SetCallControl(CallControl.MUTE_ON_DOUBLE).bytes)).isEqualTo("04 00 04 00 09 00 24 00 02 00 00")
    }

    @Test
    fun `новые переключатели и скорость свайпа`() {
        assertThat(Hex.encode(AapCommand.SetToggle(AapToggle.ALLOW_OFF, true).bytes)).isEqualTo("04 00 04 00 09 00 34 01 00 00 00")
        assertThat(Hex.encode(AapCommand.SetToggle(AapToggle.OPTIMIZED_CHARGING, false).bytes)).isEqualTo("04 00 04 00 09 00 3B 02 00 00 00")
        val state = AapDeviceState().apply(AapParser.parse(Hex.decode("04 00 04 00 09 00 23 00 00 00 00"))!!)
        assertThat(state.volumeSwipeSpeed).isEqualTo(VolumeSwipeSpeed.DEFAULT)
    }
}
