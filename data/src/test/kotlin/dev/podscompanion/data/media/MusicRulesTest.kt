package dev.podscompanion.data.media

import com.google.common.truth.Truth.assertThat
import dev.podscompanion.protocol.aap.ListeningMode
import org.junit.jupiter.api.Test

class MusicRulesTest {

    @Test
    fun `short break does not rewind`() {
        val resume = SmartResume(awayMs = 120_000)
        resume.onPaused(0)
        assertThat(resume.shouldRewind(60_000)).isFalse()
    }

    @Test
    fun `long break rewinds once`() {
        val resume = SmartResume(awayMs = 120_000)
        resume.onPaused(0)
        assertThat(resume.shouldRewind(180_000)).isTrue()
        assertThat(resume.shouldRewind(400_000)).isFalse()
    }

    @Test
    fun `resume without pause does not rewind`() {
        assertThat(SmartResume().shouldRewind(1_000_000)).isFalse()
    }

    @Test
    fun `mode switches only when the playing app changes`() {
        val switcher = AppModeSwitcher()
        val rules = mapOf("podcasts" to ListeningMode.TRANSPARENCY, "music" to ListeningMode.NOISE_CANCELLATION)
        assertThat(switcher.onPlaying("podcasts", rules)).isEqualTo(ListeningMode.TRANSPARENCY)
        assertThat(switcher.onPlaying("podcasts", rules)).isNull()
        assertThat(switcher.onPlaying(null, rules)).isNull()
        assertThat(switcher.onPlaying("podcasts", rules)).isNull()
        assertThat(switcher.onPlaying("music", rules)).isEqualTo(ListeningMode.NOISE_CANCELLATION)
        assertThat(switcher.onPlaying("video", rules)).isNull()
        switcher.reset()
        assertThat(switcher.onPlaying("video", rules)).isNull()
        assertThat(switcher.onPlaying("music", rules)).isEqualTo(ListeningMode.NOISE_CANCELLATION)
    }

    @Test
    fun `rules survive encode and decode`() {
        val rules = mapOf("com.spotify.music" to ListeningMode.NOISE_CANCELLATION, "ru.yandex.music" to ListeningMode.ADAPTIVE)
        assertThat(AppModeRulesCodec.decode(AppModeRulesCodec.encode(rules))).isEqualTo(rules)
        assertThat(AppModeRulesCodec.decode(setOf("broken", "=OFF", "a=WHAT", "b=UNKNOWN"))).isEmpty()
    }
}
