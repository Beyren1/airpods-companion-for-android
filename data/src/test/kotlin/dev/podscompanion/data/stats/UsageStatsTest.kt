package dev.podscompanion.data.stats

import com.google.common.truth.Truth.assertThat
import java.time.LocalDate
import org.junit.jupiter.api.Test

class UsageStatsTest {
    private val today = LocalDate.of(2026, 10, 9)

    private fun act(address: String, worn: Boolean = true, music: Boolean = false, call: Boolean = false) =
        PairActivity(address, "AirPods $address", 0x1B20, worn, music, call)

    @Test
    fun `каждая пара считается отдельно`() {
        val history = UsageHistory()
            .record(listOf(act("A", music = true), act("B", worn = false)), today, 10, nowMs = 1)
            .record(listOf(act("A")), today, 10, nowMs = 2)

        assertThat(history.pairs["A"]!!.on(today)).isEqualTo(UsageTotals(wornSec = 20, musicSec = 10))
        // Подключённая, но не надетая пара всё равно получает карточку.
        assertThat(history.pairs["B"]!!.on(today).isEmpty).isTrue()
        assertThat(history.sorted().first().address).isEqualTo("A")
    }

    @Test
    fun `запись и чтение дают то же самое`() {
        val history = UsageHistory()
            .record(listOf(act("AA:BB", call = true)), today.minusDays(1), 30, nowMs = 5)
            .record(listOf(act("AA:BB", music = true)), today, 20, nowMs = 6)

        assertThat(UsageHistory.decode(history.encode())).isEqualTo(history)
    }

    @Test
    fun `повреждённая строка не ломает остальное`() {
        val text = UsageHistory().record(listOf(act("A")), today, 10, 1).encode() + "D\tA\tмусор\n"
        assertThat(UsageHistory.decode(text).pairs["A"]!!.on(today).wornSec).isEqualTo(10)
    }

    @Test
    fun `старые дни отрезаются`() {
        val history = UsageHistory()
            .record(listOf(act("A")), today.minusDays(40), 10, 1)
            .record(listOf(act("A")), today, 10, 2)
            .trimmed(today)
        assertThat(history.pairs["A"]!!.days.keys).containsExactly(today)
    }

    @Test
    fun `сумма за неделю`() {
        val history = (0L..9L).fold(UsageHistory()) { h, back -> h.record(listOf(act("A")), today.minusDays(back), 60, 1) }
        assertThat(history.pairs["A"]!!.lastDays(today, 7).wornSec).isEqualTo(7 * 60)
    }

    @Test
    fun `музыку слушает та пара, что в ушах`() {
        val result = UsageAttribution.attribute(
            listOf(
                UsageAttribution.Connected("MAX", "AirPods Max", null, worn = false),
                UsageAttribution.Connected("PRO", "AirPods Pro", null, worn = true),
            ),
            musicActive = true, callActive = false,
        )
        assertThat(result.single { it.address == "PRO" }.music).isTrue()
        assertThat(result.single { it.address == "MAX" }.music).isFalse()
    }

    @Test
    fun `одна пара не в ушах — музыка всё равно её, звонок вместо музыки`() {
        val one = listOf(UsageAttribution.Connected("A", "AirPods", null, worn = false))
        assertThat(UsageAttribution.attribute(one, musicActive = true, callActive = false).single().music).isTrue()
        val call = UsageAttribution.attribute(one, musicActive = true, callActive = true).single()
        assertThat(call.call).isTrue()
        assertThat(call.music).isFalse()
    }
}
