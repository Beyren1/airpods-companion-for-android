package dev.podscompanion.protocol.advertising

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class PodsModelTest {

    @Test
    fun `model ID уникальны`() {
        val ids = PodsModel.entries.map { it.modelId }
        assertThat(ids).containsNoDuplicates()
    }

    @Test
    fun `подтверждённые по дампам модели`() {
        assertThat(PodsModel.fromId(0x1B20)).isEqualTo(PodsModel.AIRPODS_4_ANC)
        assertThat(PodsModel.fromId(0x1F20)).isEqualTo(PodsModel.AIRPODS_MAX_USB_C)
    }

    @Test
    fun `полноразмерные и проводные показывают одно значение заряда`() {
        listOf(PodsModel.AIRPODS_MAX, PodsModel.BEATS_STUDIO_PRO, PodsModel.BEATS_FLEX).forEach {
            assertThat(it.capabilities).doesNotContain(Capability.STEREO_BUDS)
        }
    }
}
