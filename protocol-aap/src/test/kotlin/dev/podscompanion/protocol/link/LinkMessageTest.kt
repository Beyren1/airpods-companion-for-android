package dev.podscompanion.protocol.link

import com.google.common.truth.Truth.assertThat
import dev.podscompanion.protocol.aap.ListeningMode
import org.junit.jupiter.api.Test

class LinkMessageTest {
    // Та же строка в тесте LinkMessage_SameTextAsPhone программы для Windows.
    private val text = "PCLINK 1 hello 3f9a12c0 pc e5d3f516:1,0a0b0c0d:0 DESKTOP ASUS"

    @Test
    fun parsesMessageFromPc() {
        val message = LinkMessage.parse(text)!!
        assertThat(message.type).isEqualTo(LinkMessage.Type.HELLO)
        assertThat(message.id).isEqualTo("3f9a12c0")
        assertThat(message.kind).isEqualTo(LinkMessage.Kind.PC)
        assertThat(message.pods).containsExactly(LinkPods("e5d3f516", true), LinkPods("0a0b0c0d", false)).inOrder()
        assertThat(message.name).isEqualTo("DESKTOP ASUS")
    }

    @Test
    fun encodesBack() {
        assertThat(LinkMessage.parse(text)!!.encode()).isEqualTo(text)
        val empty = LinkMessage(LinkMessage.Type.HI, "1", LinkMessage.Kind.PHONE, emptyList(), "Pixel 9")
        assertThat(empty.encode()).isEqualTo("PCLINK 1 hi 1 phone - Pixel 9")
        assertThat(LinkMessage.parse(empty.encode())).isEqualTo(empty)
    }

    @Test
    fun carriesBatteryAndMode() {
        // Та же строка в тесте LinkMessage_BatteryAndMode программы для Windows.
        val line = "PCLINK 1 hello 1 phone e5d3f516:1:96:anc,0a0b0c0d:0:80/-/60:- Pixel 9"
        val message = LinkMessage.parse(line)!!
        assertThat(message.pods).containsExactly(
            LinkPods("e5d3f516", true, "96", ListeningMode.NOISE_CANCELLATION),
            LinkPods("0a0b0c0d", false, "80/-/60", null),
        ).inOrder()
        assertThat(message.encode()).isEqualTo(line)
        val mode = LinkMessage.parse("PCLINK 1 mode 2 pc e5d3f516:0:-:transparency DESKTOP")!!
        assertThat(mode.type).isEqualTo(LinkMessage.Type.MODE)
        assertThat(mode.pods.single().mode).isEqualTo(ListeningMode.TRANSPARENCY)
    }

    @Test
    fun ignoresOtherText() {
        assertThat(LinkMessage.parse("PCSW")).isNull()
        assertThat(LinkMessage.parse("PCLINK 2 hello 1 pc - X")).isNull()
        assertThat(LinkMessage.parse("PCLINK 1 jump 1 pc - X")).isNull()
        assertThat(LinkMessage.parse("PCLINK 1 hello 1 pc bad X")).isNull()
    }
}
