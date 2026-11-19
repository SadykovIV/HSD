package com.hsd.universal.dex

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream

class NavigationAudioTest {
    private fun pcm(value: Int, frames: Int = 960) = ByteArray(frames * 4).also { out ->
        for (i in out.indices step 2) { out[i] = value.toByte(); out[i + 1] = (value shr 8).toByte() }
    }
    @Test fun separateBusesKeepTheirGainAndNeverDuplicateTheOtherStem() {
        val mixer = PlaybackVolumeMixer(listOf(false, true, true))
        mixer.offer(0, pcm(12000), 1_000_000)
        mixer.offer(1, pcm(4000), 1_000_000)
        mixer.offer(2, pcm(-2000), 1_000_000)
        val out = checkNotNull(mixer.poll(1_020_000, 50, 25))
        assertArrayEquals(pcm(6000), out.music)
        assertArrayEquals(pcm(500), out.navigation)
        assertArrayEquals(pcm(6500), out.pcm)
    }
    @Test fun mixedClippingDoesNotDamageTheIndependentBuses() {
        val mixer = PlaybackVolumeMixer(listOf(false, true))
        mixer.offer(0, pcm(20000), 1_000_000); mixer.offer(1, pcm(20000), 1_000_000)
        val out = checkNotNull(mixer.poll(1_020_000, 100, 100))
        assertArrayEquals(pcm(32767), out.pcm)
        assertArrayEquals(pcm(20000), out.music); assertArrayEquals(pcm(20000), out.navigation)
    }
    @Test fun converterHasExactlyTheSameWaveformAtArbitraryReadBoundaries() {
        val source = pcm(0, 48000)
        for (i in source.indices step 4) {
            val v = (10000 * kotlin.math.sin(2 * Math.PI * 440 * (i / 4) / 48000)).toInt()
            source[i] = v.toByte(); source[i+1] = (v shr 8).toByte()
            source[i+2] = (v / 2).toByte(); source[i+3] = ((v / 2) shr 8).toByte()
        }
        for (rate in listOf(16000,24000,32000,44100,48000)) for (channels in 1..2) {
            val expected = ProjectionPcmConverter(rate, channels).convert(source)
            val converter = ProjectionPcmConverter(rate, channels)
            val actual = ByteArrayOutputStream()
            var offset = 0; var n = 0
            val sizes = listOf(1, 17, 960, 511, 3072)
            while (offset < source.size) {
                val end = minOf(source.size, offset + sizes[n++ % sizes.size] * 4)
                actual.write(converter.convert(source.copyOfRange(offset, end))); offset = end
            }
            assertArrayEquals("$rate/$channels", expected, actual.toByteArray())
            assertEquals(rate * channels * 2, expected.size)
            converter.reset(); assertArrayEquals(expected, converter.convert(source))
        }
    }
    @Test fun silenceNeverOpensSpeechAndPlayerPauseDrainsCapturedTail() {
        val gate = NavigationPlaybackGate()
        assertFalse(gate.update(pcm(0),1_000_000,NavigationPlaybackState(true,900_000)))
        assertTrue(gate.update(pcm(2000),1_020_000,NavigationPlaybackState(true,900_000)))
        assertTrue(gate.update(pcm(0),1_040_000,NavigationPlaybackState(true,900_000)))
        val stopped = NavigationPlaybackState(false,1_100_000)
        assertTrue(gate.update(pcm(0),1_060_000,stopped))
        assertTrue(gate.update(pcm(1000),1_080_000,stopped))
        assertFalse(gate.update(pcm(0),1_100_000,stopped))
    }
    @Test fun missingObservationHasBoundedSilenceAndResetReleasesSpeech() {
        val gate = NavigationPlaybackGate()
        assertTrue(gate.update(pcm(1),1_000_000,null))
        assertTrue(gate.update(pcm(0),1_500_000,null))
        assertFalse(gate.update(pcm(0),1_520_000,null))
        gate.update(pcm(1),2_000_000,null); gate.reset()
        assertFalse(gate.update(pcm(0),2_020_000,null))
    }
    @Test fun anOpenSilentNavigationTrackCannotHoldTheHeadUnitSpeechRouteForever() {
        val gate = NavigationPlaybackGate()
        val open = NavigationPlaybackState(true,900_000)
        assertTrue(gate.update(pcm(1000),1_000_000,open))
        assertTrue(gate.update(pcm(0),1_500_000,open))
        assertFalse(gate.update(pcm(0),1_520_000,open))
    }
    @Test fun hiddenPlayerFieldsAreUnknownRatherThanProofOfStoppedSpeech() {
        val stems = PlaybackStem.rules(setOf(123))
        assertEquals(true, navigationPlaybackActive(listOf(PlaybackActivity(12,null,true)), stems))
        assertNull(navigationPlaybackActive(listOf(PlaybackActivity(1,null,true)), stems))
        assertNull(navigationPlaybackActive(listOf(PlaybackActivity(12,null,null)), stems))
        assertEquals(false, navigationPlaybackActive(listOf(PlaybackActivity(1,456,true)), stems))
        assertEquals(false, navigationPlaybackActive(listOf(PlaybackActivity(12,null,false)), stems))
    }
}
