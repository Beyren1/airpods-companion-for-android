package dev.podscompanion.protocol.aap

import com.google.common.truth.Truth.assertThat
import dev.podscompanion.protocol.util.Hex
import org.junit.jupiter.api.Test

class AapParserTest {

    @Test
    fun `заряд левого, правого и кейса с точностью 1 процент`() {
        val event = AapParser.parse(
            Hex.decode("04 00 04 00 04 00 03  02 01 64 02 01  04 01 63 01 01  08 01 33 02 01"),
        )
        assertThat(event).isEqualTo(
            AapEvent.Battery(
                listOf(
                    AapBattery(BatteryComponent.RIGHT, 100, charging = false, connected = true),
                    AapBattery(BatteryComponent.LEFT, 99, charging = true, connected = true),
                    AapBattery(BatteryComponent.CASE, 51, charging = false, connected = true),
                ),
            ),
        )
    }

    @Test
    fun `отключённый компонент отбрасывается`() {
        val event = AapParser.parse(Hex.decode("04 00 04 00 04 00 02  02 01 64 02 01  08 01 00 04 01")) as AapEvent.Battery
        assertThat(event.components.map { it.component }).containsExactly(BatteryComponent.RIGHT)
    }

    @Test
    fun `ухо`() {
        assertThat(AapParser.parse(Hex.decode("04 00 04 00 06 00 00 01")))
            .isEqualTo(AapEvent.EarDetection(EarState.IN_EAR, EarState.OUT_OF_EAR))
        assertThat(AapParser.parse(Hex.decode("04 00 04 00 06 00 02 02")))
            .isEqualTo(AapEvent.EarDetection(EarState.IN_CASE, EarState.IN_CASE))
    }

    @Test
    fun `режим шумоподавления и адаптация к разговору`() {
        assertThat(AapParser.parse(Hex.decode("04 00 04 00 09 00 0D 03 00 00 00")))
            .isEqualTo(AapEvent.ListeningModeChanged(ListeningMode.TRANSPARENCY))
        assertThat(AapParser.parse(Hex.decode("04 00 04 00 09 00 28 01 00 00 00")))
            .isEqualTo(AapEvent.ConversationalAwarenessChanged(true))
    }

    @Test
    fun `прочие настройки сохраняются и читаются`() {
        val event = AapParser.parse(Hex.decode("04 00 04 00 09 00 16 05 01 00 00"))
        assertThat(event).isEqualTo(AapEvent.ControlChanged(ControlId.PRESS_AND_HOLD, listOf(0x05, 0x01, 0x00, 0x00)))

        val state = AapDeviceState()
            .apply(event!!)
            .apply(AapParser.parse(Hex.decode("04 00 04 00 09 00 01 02 00 00 00"))!!)
            .apply(AapParser.parse(Hex.decode("04 00 04 00 09 00 1A 0E 00 00 00"))!!)
            .apply(AapParser.parse(Hex.decode("04 00 04 00 09 00 28 02 00 00 00"))!!)
        assertThat(state.pressAndHold).isEqualTo(PressAndHold(right = PressAction.NOISE_CONTROL, left = PressAction.VOICE_ASSISTANT))
        assertThat(state.micMode).isEqualTo(MicMode.ALWAYS_LEFT)
        assertThat(state.modeCycle).containsExactly(
            ListeningMode.NOISE_CANCELLATION, ListeningMode.TRANSPARENCY, ListeningMode.ADAPTIVE,
        )
        assertThat(state.toggle(AapToggle.CONVERSATIONAL_AWARENESS)).isFalse()
        assertThat(state.toggle(AapToggle.EAR_DETECTION)).isNull()
    }

    @Test
    fun `команды настроек`() {
        assertThat(Hex.encode(AapCommand.SetToggle(AapToggle.CONVERSATIONAL_AWARENESS, true).bytes))
            .isEqualTo("04 00 04 00 09 00 28 01 00 00 00")
        assertThat(Hex.encode(AapCommand.SetMicMode(MicMode.ALWAYS_RIGHT).bytes))
            .isEqualTo("04 00 04 00 09 00 01 01 00 00 00")
        assertThat(Hex.encode(AapCommand.SetPressAndHold(PressAction.NOISE_CONTROL, PressAction.VOICE_ASSISTANT).bytes))
            .isEqualTo("04 00 04 00 09 00 16 05 01 00 00")
        assertThat(Hex.encode(AapCommand.SetModeCycle(setOf(ListeningMode.OFF, ListeningMode.NOISE_CANCELLATION)).bytes))
            .isEqualTo("04 00 04 00 09 00 1A 03 00 00 00")
    }

    @Test
    fun `неизвестное и битое — Unknown, не AAP — null`() {
        assertThat(AapParser.parse(Hex.decode("04 00 04 00 2B 00 01"))).isInstanceOf(AapEvent.Unknown::class.java)
        assertThat(AapParser.parse(Hex.decode("04 00 04 00 04 00 05 02"))).isInstanceOf(AapEvent.Unknown::class.java)
        assertThat(AapParser.parse(Hex.decode("01 00 04 00"))).isNull()
    }

    @Test
    fun `команды собираются с opcode в little-endian`() {
        assertThat(Hex.encode(Aap.REQUEST_NOTIFICATIONS)).isEqualTo("04 00 04 00 0F 00 FF FF FF FF")
        assertThat(Hex.encode(Aap.SET_FEATURES)).isEqualTo("04 00 04 00 4D 00 FF 00 00 00 00 00 00 00")
        assertThat(Hex.encode(Aap.setListeningMode(ListeningMode.TRANSPARENCY))).isEqualTo("04 00 04 00 09 00 0D 03 00 00 00")
        // Команда и уведомление совпадают по формату: свой же пакет разбирается обратно.
        assertThat(AapParser.parse(Aap.setListeningMode(ListeningMode.ADAPTIVE)))
            .isEqualTo(AapEvent.ListeningModeChanged(ListeningMode.ADAPTIVE))
    }
}

class AapDeviceStateTest {
    @Test
    fun `события накапливаются в состоянии`() {
        val state = listOf(
            "04 00 04 00 04 00 02  02 01 64 02 01  04 01 50 02 01",
            "04 00 04 00 06 00 00 01",
            "04 00 04 00 09 00 0D 02 00 00 00",
            "04 00 04 00 04 00 01  08 01 33 01 01",
        ).mapNotNull { AapParser.parse(Hex.decode(it)) }
            .fold(AapDeviceState()) { s, e -> s.apply(e) }

        assertThat(state.right?.percent).isEqualTo(100)
        assertThat(state.left?.percent).isEqualTo(80)
        assertThat(state.case?.percent).isEqualTo(51)
        assertThat(state.case?.charging).isTrue()
        assertThat(state.secondaryEar).isEqualTo(EarState.OUT_OF_EAR)
        assertThat(state.listeningMode).isEqualTo(ListeningMode.NOISE_CANCELLATION)
        assertThat(state.earKnown).isTrue()
    }
}
