package dev.podscompanion.data

import com.google.common.truth.Truth.assertThat
import dev.podscompanion.data.aap.AapSessionState
import dev.podscompanion.data.aap.AapSessions
import dev.podscompanion.protocol.aap.AapDeviceState
import dev.podscompanion.protocol.advertising.PodsModel
import org.junit.jupiter.api.Test

class AapSessionsTest {
    private fun connected(name: String, address: String) = AapSessionState.Connected(name, address, "test", AapDeviceState())

    @Test
    fun `две пары подключены — каждой карточке своя сессия`() {
        val sessions = AapSessions(sessions = listOf(connected("AirPods Max", "MAX"), connected("AirPods Pro", "PRO")))

        assertThat(sessions.forModel(PodsModel.AIRPODS_PRO_2_USB_C)?.address).isEqualTo("PRO")
        assertThat(sessions.forModel(PodsModel.AIRPODS_MAX_USB_C)?.address).isEqualTo("MAX")
    }

    @Test
    fun `общее имя AirPods уступает точному`() {
        val sessions = AapSessions(sessions = listOf(connected("AirPods", "FOUR"), connected("AirPods Pro", "PRO")))

        assertThat(sessions.forModel(PodsModel.AIRPODS_PRO_2_USB_C)?.address).isEqualTo("PRO")
        assertThat(sessions.forModel(PodsModel.AIRPODS_4_ANC)?.address).isEqualTo("FOUR")
    }

    @Test
    fun `чужая модель не получает сессию`() {
        val sessions = AapSessions(sessions = listOf(connected("AirPods Max", "MAX")))
        assertThat(sessions.forModel(PodsModel.AIRPODS_PRO_2_USB_C)).isNull()
    }
}
