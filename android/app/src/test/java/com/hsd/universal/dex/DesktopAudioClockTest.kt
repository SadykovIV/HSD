package com.hsd.universal.dex

import org.junit.Assert.*
import org.junit.Test

class DesktopAudioClockTest {
    @Test fun producerTimebaseChangeDoesNotLeaveAContinuousFrameCounterPermanentlyLate() {
        val clock = DesktopAudioClock(bufferCapacityFrames = 3072)
        clock.read(960, 1_000_000, 3072, 990_000)
        // A submix can pause/restart its producer without resetting the frame counter.
        // The fresh producer timestamp locates the new ring; the previous sample origin
        // must not leave this stem behind the other, continuously advancing mixer route.
        assertEquals(1_906_000L, clock.read(960, 2_000_000, 4032, 1_990_000))
        assertEquals(1_926_000L, clock.read(960, 2_020_000, 4992, 2_010_000))
    }

    @Test fun delayedReaderDoesNotInsertGapsWhenProducerHasNotAdvanced() {
        val clock = DesktopAudioClock(bufferCapacityFrames = 3072)
        val first = clock.read(960, 1_000_000, 3072, 990_000)
        // The producer has stopped with three blocks in its ring. Delayed reads do
        // not imply that any of those samples were overwritten. Their age must stay
        // visible to the output queue instead of being reset to the polling time.
        assertEquals(first + 20_000, clock.read(960, 1_100_000, 3072, 990_000))
        assertEquals(0L, clock.discontinuityUs)
        assertEquals(first + 40_000, clock.read(960, 1_200_000, 3072, 990_000))
        assertEquals(0L, clock.discontinuityUs)
        // A later producer advance really can overwrite the remaining ring.
        val recovered = clock.read(960, 2_000_000, 51_072, 1_990_000)
        assertTrue(recovered >= 1_906_000)
        assertTrue(clock.discontinuityUs > 0)
    }

    @Test fun mixerCorrectionAndClockShareOneOriginAcrossSilentPrefillAndPlayback() {
        val clock = DesktopAudioClock(bufferCapacityFrames = 3072)
        val mixer = PlaybackVolumeMixer(listOf(false))
        val pcm = ByteArray(3840) { 1 }
        var now = 1_000_000L
        var playbackBuffers = 0
        // A silent remote submix can initially drain faster than realtime.
        repeat(100) { index ->
            now += if (index < 20) 5_000 else 20_000
            val at = clock.read(960, now)
            clock.correctFutureSamples(mixer.offer(0, pcm, at, now))
            while (true) {
                val out = mixer.poll(now, 100, 100) ?: break
                if (index >= 20) playbackBuffers++
                assertTrue("delivered PCM cannot have future samples", out.ptsUs + 20_000 <= now)
                assertTrue("origin correction must not accumulate stale playback", now - out.ptsUs < 100_000)
            }
        }
        val next = clock.read(960, now + 20_000)
        assertTrue("playback must resume after the initial burst", playbackBuffers >= 75)
        assertTrue(now + 20_000 - next in 0..100_000)
    }
    @Test fun sustainedSlowReadsRecoverInsteadOfLeavingEveryFuturePacketStale() {
        val clock = DesktopAudioClock(bufferCapacityFrames = 3072)
        repeat(100) { index ->
            val wall = 1_000_000 + index * 40_000L
            val at = clock.read(960, wall, 3072L + index * 1920, wall - 5000)
            assertTrue("a bounded native ring cannot contain seconds of queued audio", wall - at <= 160_000)
            assertTrue(at <= wall)
        }
    }
    @Test fun remoteSubmixFrameCounterCannotInventAnOverrunWhileReaderKeepsDraining() {
        val clock = DesktopAudioClock(bufferCapacityFrames = 3072)
        val start = clock.read(960, 1_000_000, 3072, 990_000)
        repeat(100) { index ->
            val wall = 1_020_000 + index * 20_000L
            // A loopback HAL can advance its timestamp counter through synthetic silence.
            // That counter alone is not evidence that unread AudioRecord samples were lost.
            val hardware = 5056L + index * 1024
            assertEquals(start + (index + 1L) * 20_000,
                clock.read(960, wall, hardware, wall - 5000))
        }
    }
    @Test fun driverTimestampJitterDoesNotCutOrDuplicateCapturedSamples() {
        val clock = DesktopAudioClock(bufferCapacityFrames = 1920)
        val start = clock.read(960, 1_000_000, 960, 1_000_000)
        repeat(100) { index ->
            val frame = (index + 2L) * 960
            val wall = 1_020_000 + index * 20_000L
            val jitter = if (index % 2 == 0) -1400 else -400
            assertEquals(start + (index + 1L) * 20_000,
                clock.read(960, wall, frame, wall + jitter))
        }
    }
    @Test fun boundedRingAlsoRecoversWithoutDriverTimestamps() {
        val clock = DesktopAudioClock(bufferCapacityFrames = 1920)
        clock.read(960, 1_000_000)
        val afterStall = clock.read(960, 4_000_000)
        assertEquals(3_940_000L, afterStall)
        assertEquals(3_960_000L, clock.read(960, 4_000_000))
        assertEquals(3_980_000L, clock.read(960, 4_000_000))
        assertEquals(4_000_000L, clock.read(960, 4_020_000))
    }
    @Test fun readerRecoversAfterAudioRecordOverwritesItsRing() {
        val clock = DesktopAudioClock(bufferCapacityFrames = 1920)
        clock.read(960, 1_000_000, 960, 1_000_000)
        val afterStall = clock.read(960, 4_000_000, 144960, 4_000_000)
        assertTrue("overwritten samples must not leave audio permanently stale", afterStall >= 3_940_000)
        val next = clock.read(960, 4_020_000, 145920, 4_020_000)
        assertTrue(next >= afterStall + 20_000)
    }
    @Test fun HardwareTimestampLossUsesLastCalibratedSampleClock() {
        val clock = DesktopAudioClock(bufferCapacityFrames = 1920)
        val a = clock.read(960, 1_000_000, 1440, 1_000_000)
        val b = clock.read(960, 1_040_000)
        assertEquals(a + 20_000, b)
    }
    @Test fun captureClockDoesNotFollowDelayedReader() {
        val clock = DesktopAudioClock()
        assertEquals(980_000L, clock.read(960, 1_000_000))
        assertEquals(1_000_000L, clock.read(960, 1_500_000))
        assertEquals(1_020_000L, clock.read(960, 1_500_000))
    }
    @Test fun hardwareFramePositionLocatesStartOfRead() {
        val clock = DesktopAudioClock()
        assertEquals(900_000L, clock.read(960, 1_000_000, 4800, 1_000_000))
        assertEquals(920_000L, clock.read(960, 1_020_000, 5760, 1_020_000))
    }
    @Test fun invalidOrBackwardHardwareTimestampNeverReversesTime() {
        val clock = DesktopAudioClock()
        val a = clock.read(960, 1_000_000)
        val b = clock.read(960, 1_020_000, 999_999, 1)
        assertTrue(b >= a)
        val reset = DesktopAudioClock().read(960, 2_000_000)
        assertEquals(1_980_000L, reset)
    }
}
