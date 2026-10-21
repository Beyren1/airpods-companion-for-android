package dev.podscompanion.protocol.link

import com.google.common.truth.Truth.assertThat
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
    fun ignoresOtherText() {
        assertThat(LinkMessage.parse("PCSW")).isNull()
        assertThat(LinkMessage.parse("PCLINK 2 hello 1 pc - X")).isNull()
        assertThat(LinkMessage.parse("PCLINK 1 jump 1 pc - X")).isNull()
        assertThat(LinkMessage.parse("PCLINK 1 hello 1 pc bad X")).isNull()
    }
}
