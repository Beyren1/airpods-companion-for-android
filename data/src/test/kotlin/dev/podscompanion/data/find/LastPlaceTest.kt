package dev.podscompanion.data.find

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class LastPlaceTest {

    @Test
    fun `место с координатами сохраняется и читается`() {
        val place = LastPlace(
            address = "AA:BB:CC:DD:EE:FF",
            name = "AirPods Pro | Beyren",
            disconnectedAtMs = 1_760_000_000_000,
            location = LastPlace.Fix(55.751244, 37.618423, 12.5f, 1_759_999_990_000),
        )
        assertThat(LastPlace.decode(LastPlace.encode(place))).isEqualTo(place)
    }

    @Test
    fun `место без координат`() {
        val place = LastPlace("AA:BB:CC:DD:EE:FF", "AirPods", 42)
        assertThat(LastPlace.decode(LastPlace.encode(place))).isEqualTo(place)
    }

    @Test
    fun `испорченная строка не читается`() {
        assertThat(LastPlace.decode("мусор")).isNull()
        assertThat(LastPlace.decode("AA|не число|||||AirPods")).isNull()
    }
}
