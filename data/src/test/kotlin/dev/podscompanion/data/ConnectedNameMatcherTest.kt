package dev.podscompanion.data

import com.google.common.truth.Truth.assertThat
import dev.podscompanion.protocol.advertising.PodsModel
import org.junit.jupiter.api.Test

class ConnectedNameMatcherTest {

    private val nearby = listOf(PodsModel.AIRPODS_MAX_USB_C, PodsModel.AIRPODS_PRO_2_USB_C, PodsModel.AIRPODS_4_ANC)

    @Test
    fun `стандартные имена`() {
        assertThat(ConnectedNameMatcher.bestMatch(nearby, listOf("AirPods Pro"))).isEqualTo(PodsModel.AIRPODS_PRO_2_USB_C)
        assertThat(ConnectedNameMatcher.bestMatch(nearby, listOf("AirPods Max"))).isEqualTo(PodsModel.AIRPODS_MAX_USB_C)
        assertThat(ConnectedNameMatcher.bestMatch(nearby, listOf("AirPods 4"))).isEqualTo(PodsModel.AIRPODS_4_ANC)
    }

    @Test
    fun `имя с владельцем`() {
        assertThat(ConnectedNameMatcher.bestMatch(nearby, listOf("AirPods Pro — Beyren"))).isEqualTo(PodsModel.AIRPODS_PRO_2_USB_C)
    }

    @Test
    fun `имя AirPods без линейки — не Pro и не Max`() {
        assertThat(ConnectedNameMatcher.bestMatch(nearby, listOf("AirPods"))).isEqualTo(PodsModel.AIRPODS_4_ANC)
        assertThat(ConnectedNameMatcher.modelsForName("AirPods")).containsNoneOf(
            PodsModel.AIRPODS_MAX_USB_C, PodsModel.AIRPODS_PRO_2_USB_C, PodsModel.AIRPODS_PRO,
        )
    }

    @Test
    fun `переименованное имя не совпадает`() {
        assertThat(ConnectedNameMatcher.bestMatch(nearby, listOf("Мои уши"))).isNull()
        assertThat(ConnectedNameMatcher.bestMatch(nearby, emptyList())).isNull()
    }

    @Test
    fun `подключены Max, но их не видно — Pro 2 рядом не считаются подключёнными`() {
        assertThat(ConnectedNameMatcher.bestMatch(listOf(PodsModel.AIRPODS_PRO_2_USB_C), listOf("AirPods Max"))).isNull()
        assertThat(ConnectedNameMatcher.knownModels(listOf("AirPods Max"))).contains(PodsModel.AIRPODS_MAX_USB_C)
    }
}
