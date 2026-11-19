package com.hsd.universal.dex

import org.junit.Assert.*
import org.junit.Test

class PlaybackVolumeMixerTest {
    @Test fun musicClockJumpInsideAQuantumDoesNotAppend55SilentFrames() {
        val mixer = PlaybackVolumeMixer(listOf(false, true))
        val quiet = NavigationPlaybackState(false, 900_000)
        val before = pcm(1395, 905)
        val after = pcm(1540)
        mixer.offer(0, before, 1_000_000)
        mixer.offer(1, pcm(0), 1_000_000)
        mixer.offer(0, after, 1_027_438)
        val first = checkNotNull(mixer.poll(1_060_000, 100, 100, quiet))
        val second = checkNotNull(mixer.poll(1_060_000, 100, 100, quiet))
        assertArrayEquals(before, first.music)
        assertEquals(first.music.size, first.navigation.size)
        assertArrayEquals(after, second.music)
        assertEquals(1_000_000L, first.ptsUs)
        assertEquals(1_027_437L, second.ptsUs)
    }

    @Test fun earlyNativeReadKeepsEveryFrameAfterTheReceiverStartedPlaying() {
        val mixer = PlaybackVolumeMixer(listOf(false, true))
        val quiet = NavigationPlaybackState(false, 900_000)
        mixer.offer(0, pcm(1000), 1_000_000, 1_020_000)
        checkNotNull(mixer.poll(1_020_000, 100, 100, quiet))
        // The next HAL read returns one millisecond early. Its first 48 stereo
        // frames belong to this packet right after the previous one was delivered.
        val next = pcm(2000).also { pcm(3100, 48).copyInto(it) }
        assertEquals(0L, mixer.offer(0, next, 1_020_000, 1_039_000))
        assertNull(mixer.poll(1_039_000, 100, 100, quiet))
        val received = checkNotNull(mixer.poll(1_040_000, 100, 100, quiet))
        assertEquals(1_020_000L, received.ptsUs)
        assertArrayEquals(next, received.music)
    }

    @Test fun delayedSpeechReaderDoesNotEvictMusicAlreadyCapturedDuringA107msStall() {
        val mixer = PlaybackVolumeMixer(listOf(false, true))
        val startUs = 1_000_000L
        val active = NavigationPlaybackState(true, startUs)
        for (i in 0..5) {
            mixer.offer(0, pcm(2000 + i), startUs + i * 20_000)
        }
        // Speech resumes 107 ms after its first read was due and drains its native queue.
        val resumeUs = startUs + 20_000 + 107_000
        for (i in 0..5) mixer.offer(1, pcm(4000 + i), startUs + i * 20_000, resumeUs)
        for (i in 0..5) {
            val out = checkNotNull(mixer.poll(resumeUs, 100, 100, active))
            assertEquals(startUs + i * 20_000, out.ptsUs)
            assertEquals(2000 + i, sample(out.music))
            assertEquals(4000 + i, sample(out.navigation))
        }
    }
    @Test fun idleGuidancePreservesTheNativeMusicBufferAndAnInFlightPrompt() {
        val mixer = PlaybackVolumeMixer(listOf(false, true))
        val quiet = NavigationPlaybackState(false, 900_000)
        val block = pcm(2371)
        mixer.offer(0, block, 1_000_000)
        val onlyMusic = mixer.poll(1_020_000, 100, 25, quiet)!!
        assertSame(block, onlyMusic.music)
        assertSame(block, onlyMusic.pcm)
        mixer.offer(0, block, 1_020_000)
        mixer.offer(1, pcm(4000), 1_020_000)
        val tail = mixer.poll(1_040_000, 100, 25, quiet)!!
        assertEquals(1000, sample(tail.navigation))
        assertEquals(3371, sample(tail.pcm))
    }
    @Test fun continuityMeasuresSilenceAcrossPacketsAndCountsSignalInEitherChannel() {
        val meter = PcmContinuityMeter()
        meter.accept(pcm(0), 1_000_000)
        meter.accept(pcm(0), 21_000_000)
        val rightOnly = pcm(0).also { for (i in 2 until it.size step 4) it[i] = 20 }
        meter.accept(rightOnly, 81_000_000)
        assertEquals(PcmContinuityMeter.Reading(2880, 40, 60), meter.reading())
    }
    @Test fun idleSpeechSilenceCannotFillAMusicTimestampJumpButLateSpeechStillPlays() {
        val mixer = PlaybackVolumeMixer(listOf(false, true))
        val quiet = NavigationPlaybackState(false, 900_000L)
        mixer.offer(0, pcm(2000), 1_000_000L)
        checkNotNull(mixer.poll(1_020_000L, 100, 100, quiet))
        for (at in 1_020_000L..1_080_000L step 20_000L) mixer.offer(1, pcm(0), at)
        mixer.offer(0, pcm(2000), 1_080_000L)
        val music = checkNotNull(mixer.poll(1_100_000L, 100, 100, quiet))
        assertEquals(1_080_000L, music.ptsUs)
        assertEquals(2000, sample(music.pcm))
        mixer.offer(1, pcm(4000), 1_100_000L)
        // An idle media recorder can stop completely. A delayed status callback
        // must not discard real speech that arrives on its own.
        val voice = checkNotNull(mixer.poll(1_230_000L, 100, 100, quiet))
        assertEquals(4000, sample(voice.navigation))
    }
    private fun pcm(value: Int, frames: Int = 960) = ByteArray(frames * 4).also { out ->
        for (i in out.indices step 2) { out[i] = value.toByte(); out[i + 1] = (value shr 8).toByte() }
    }
    private fun sample(bytes: ByteArray, frame: Int = 0) =
        ((bytes[frame * 4].toInt() and 255) or (bytes[frame * 4 + 1].toInt() shl 8)).toShort().toInt()
    @Test fun confirmedIdleGuidanceDoesNotHoldMusicAndTheNextPromptRejoinsAtItsTimestamp() {
        val mixer = PlaybackVolumeMixer(listOf(false, true))
        val quiet = NavigationPlaybackState(false, 900_000L)
        repeat(5) { tick ->
            val at = 1_000_000L + tick * 20_000L
            mixer.offer(0, pcm(2000), at)
            val packet = checkNotNull(mixer.poll(at + 20_000, 100, 100, quiet))
            assertEquals(at, packet.ptsUs)
            assertEquals(2000, sample(packet.pcm, 959))
        }
        val active = NavigationPlaybackState(true, 1_100_000L)
        mixer.offer(0, pcm(2000), 1_100_000L)
        assertNull(mixer.poll(1_120_000L, 100, 100, active))
        mixer.offer(1, pcm(4000), 1_100_000L)
        assertEquals(6000, sample(checkNotNull(mixer.poll(1_120_000L, 100, 100, active)).pcm))
    }

    @Test fun prefilledRecorderCannotPermanentlyEraseItsStemWithFutureSampleTimes() {
        val mixer = PlaybackVolumeMixer(listOf(false, true))
        repeat(100) { tick ->
            val now = 1_000_000 + tick * 20_000L
            mixer.offer(0, pcm(2000), now + 100_000, now)
            mixer.offer(1, pcm(4000), now - 20_000, now)
            val out = checkNotNull(mixer.poll(now, 100, 100))
            assertTrue(out.ptsUs + 20_000 <= now)
            assertTrue((0..959).all { sample(out.pcm, it) == 6000 })
        }
    }

    @Test fun independentLevelsAndInitialMuteDoNotLeakOtherStem() {
        for ((music, nav, expected) in listOf(Triple(100, 0, 2000), Triple(0, 100, 4000),
            Triple(50, 25, 2000), Triple(0, 0, 0), Triple(100, 100, 6000))) {
            val mixer = PlaybackVolumeMixer(listOf(false, true))
            mixer.offer(0, pcm(2000), 1_000_000); mixer.offer(1, pcm(4000), 1_000_000)
            val out = mixer.poll(1_030_000, music, nav)!!
            assertEquals(1_000_000L, out.ptsUs)
            assertEquals(expected, sample(out.pcm)); assertEquals(expected, sample(out.pcm, 959))
        }
    }
    @Test fun alignsByCaptureTimeInsteadOfReadOrder() {
        val mixer = PlaybackVolumeMixer(listOf(false, true))
        mixer.offer(1, pcm(4000), 1_010_000)
        mixer.offer(0, pcm(2000), 1_000_000)
        val out = mixer.poll(1_030_000, 100, 100)!!
        assertEquals(2000, sample(out.pcm, 479)); assertEquals(6000, sample(out.pcm, 480))
    }
    @Test fun anInactiveGuidanceRouteCannotStopMusic() {
        val mixer = PlaybackVolumeMixer(listOf(false, true))
        mixer.offer(0, pcm(2000), 1_000_000)
        assertNull(mixer.poll(1_010_000, 100, 100))
        assertNull(mixer.poll(1_050_000, 100, 100))
        val out = mixer.poll(1_120_000, 100, 100)!!
        assertEquals(960 * 4, out.pcm.size); assertEquals(2000, sample(out.pcm, 959))
        mixer.offer(1, pcm(6000), 1_000_000) // Too late for samples already emitted.
        assertNull(mixer.poll(1_050_000, 100, 100))
    }
    @Test fun nativeCaptureLatencyDoesNotLetASilentStemEraseMusic() {
        val mixer = PlaybackVolumeMixer(listOf(false, true))
        mixer.offer(0, pcm(2000), 1_000_000, 1_100_000)
        mixer.offer(1, pcm(0), 1_040_000, 1_100_000)
        assertEquals(2000, sample(mixer.poll(1_100_000, 100, 100)!!.pcm))
        assertNull(mixer.poll(1_100_100, 100, 100))
        mixer.offer(0, pcm(2000), 1_020_000, 1_110_000)
        assertEquals(2000, sample(mixer.poll(1_110_000, 100, 100)!!.pcm))
        mixer.offer(0, pcm(2000), 1_040_000, 1_120_000)
        assertEquals(2000, sample(mixer.poll(1_120_000, 100, 100)!!.pcm))
    }
    @Test fun partialReadsWaitForAWholeQuantumWithoutBusyLooping() {
        val mixer = PlaybackVolumeMixer(listOf(false, true))
        mixer.offer(0, pcm(2000, 480), 1_000_000, 1_100_000)
        assertNull(mixer.poll(1_121_000, 100, 100))
        mixer.offer(0, pcm(2000, 480), 1_010_000, 1_130_000)
        assertNull(mixer.poll(1_131_000, 100, 100))
        assertEquals(3840, mixer.poll(1_200_000, 100, 100)!!.pcm.size)
        assertNull(mixer.poll(1_152_000, 100, 100))
    }
    @Test fun liveVolumeChangeRampsAndPreservesMusic() {
        val mixer = PlaybackVolumeMixer(listOf(false, true))
        for (tick in 0..1) {
            mixer.offer(0, pcm(2000), 1_000_000 + tick * 20_000L)
            mixer.offer(1, pcm(4000), 1_000_000 + tick * 20_000L)
            val out = mixer.poll(1_040_000 + tick * 20_000L, 100, if (tick == 0) 100 else 0)!!
            if (tick == 1) {
                assertTrue(sample(out.pcm) in 5900..6000)
                assertEquals(2000, sample(out.pcm, 959))
                assertTrue((0..959).all { sample(out.pcm, it) >= 2000 })
            }
        }
    }
    @Test fun clipsWithoutWrappingAndBoundsOldInput() {
        val mixer = PlaybackVolumeMixer(listOf(false, true))
        repeat(20) { tick ->
            mixer.offer(0, pcm(30000), 1_000_000 + tick * 20_000L)
            mixer.offer(1, pcm(30000), 1_000_000 + tick * 20_000L)
        }
        val out = mixer.poll(1_450_000, 100, 100)!!
        assertTrue(out.ptsUs >= 1_300_000); assertEquals(32767, sample(out.pcm))
    }
    @Test fun callBoundaryDiscardsQueuedPreCallSamples() {
        val mixer = PlaybackVolumeMixer(listOf(false, true))
        mixer.offer(0, pcm(1111), 1_000_000); mixer.discard()
        mixer.offer(0, pcm(2222), 2_000_000)
        assertEquals(2222, sample(mixer.poll(2_120_000, 100, 100)!!.pcm))
    }
    @Test fun differentNativeBufferDepthsKeepBothLiveStemsIntact() {
        val mixer = PlaybackVolumeMixer(listOf(false, true))
        var outputs = 0
        repeat(100) { tick ->
            val now = 1_000_000 + tick * 20_000L
            mixer.offer(0, pcm(2000), now - 20_000, now)
            if (tick >= 3) mixer.offer(1, pcm(4000), now - 80_000, now)
            while (true) {
                val out = mixer.poll(now, 100, 100) ?: break
                assertEquals(6000, sample(out.pcm)); assertEquals(6000, sample(out.pcm, 959))
                assertTrue(out.ptsUs + 20_000 <= now)
                outputs++
            }
        }
        assertEquals(97, outputs)
    }
    @Test fun usageAndSelectedUidPartitionHasNoDuplicatesOrCallCapture() {
        for (selected in listOf(emptySet(), setOf(10123, 10456))) {
            val rules = PlaybackStem.rules(selected)
            for (uid in listOf(10123, 10456, 10999)) for (usage in 0..18) {
                val matches = rules.filter { it.matches(usage, uid) }
                assertEquals(if (usage in listOf(1, 12, 16)) 1 else 0, matches.size)
                if (matches.isNotEmpty()) assertEquals(usage != 1 || uid in selected, matches.single().guidance)
            }
        }
    }
}
