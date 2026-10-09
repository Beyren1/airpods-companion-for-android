package dev.podscompanion.data.find

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class FindToneTest {

    private fun channel(pcm: ShortArray, index: Int) = pcm.filterIndexed { i, _ -> i % 2 == index }

    @Test
    fun `в оба наушника — оба канала одинаковые и не пустые`() {
        val pcm = FindTone.pattern(44_100, SoundSide.BOTH, 1f)
        assertThat(channel(pcm, 0)).isEqualTo(channel(pcm, 1))
        assertThat(channel(pcm, 0).any { it != 0.toShort() }).isTrue()
    }

    @Test
    fun `в левый — правый канал молчит`() {
        val pcm = FindTone.pattern(44_100, SoundSide.LEFT, 1f)
        assertThat(channel(pcm, 0).any { it != 0.toShort() }).isTrue()
        assertThat(channel(pcm, 1).all { it == 0.toShort() }).isTrue()
    }

    @Test
    fun `в правый — левый канал молчит`() {
        val pcm = FindTone.pattern(44_100, SoundSide.RIGHT, 1f)
        assertThat(channel(pcm, 0).all { it == 0.toShort() }).isTrue()
        assertThat(channel(pcm, 1).any { it != 0.toShort() }).isTrue()
    }

    @Test
    fun `тише — меньше размах`() {
        val loud = FindTone.pattern(44_100, SoundSide.BOTH, 1f).maxOf { it.toInt() }
        val quiet = FindTone.pattern(44_100, SoundSide.BOTH, 0.4f).maxOf { it.toInt() }
        assertThat(quiet).isLessThan(loud)
        assertThat(loud).isAtMost(Short.MAX_VALUE.toInt())
    }
}
