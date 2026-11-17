package com.hsd.universal.dex

import org.junit.Assert.*
import org.junit.Test

class PlaybackVolumeMixerTest {
    private fun pcm(value: Int, frames: Int = 960) = ByteArray(frames * 4).also { out ->
        for (i in out.indices step 2) { out[i] = value.toByte(); out[i + 1] = (value shr 8).toByte() }
    }
    private fun sample(bytes: ByteArray, frame: Int = 0) =
        ((bytes[frame * 4].toInt() and 255) or (bytes[frame * 4 + 1].toInt() shl 8)).toShort().toInt()

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
