package com.hsd.universal.link

import org.junit.Assert.*
import org.junit.Test

class DesktopAvTimelineTest {
    @Test fun disabledAlignmentDoesNotWaitForVideoOrReceiverTrimButKeepsReferenceProtection() {
        var now = 1_000_000L
        val heard = mutableListOf<Int>()
        val t = DesktopAvTimeline({ now }, { 1500 }, synchronize = false) { _, bytes, ttl ->
            assertTrue(ttl in 1..150)
            heard += bytes[0].toInt()
        }
        t.reset(true)
        repeat(100) { tick ->
            assertTrue(t.pcm(3, byteArrayOf(tick.toByte()), now))
            now += 10_000
        }
        assertEquals((0 until 100).toList(), heard)
        val key = t.frame(now - 80_000, true, 30)!!
        assertTrue(key.begin()); key.written()
        val frame = t.frame(now, false, 30)!!
        assertEquals(0, frame.readyInUs())
        now += 240_000
        assertFalse(frame.begin())
        assertNull(t.frame(now, false, 30))
        assertTrue(t.frame(now, true, 30)!!.begin())
        assertTrue(t.diagnostics().contains("audioTargetMs=0 receiverTrimMs=0"))
    }
    @Test fun disablingAudioAlignmentDoesNotRejectHealthyFramesFromASlowerCodec() {
        var now = 1_000_000L
        val t = DesktopAvTimeline({ now }, synchronize = false) { _, _, _ -> }
        t.reset(true)
        t.frame(now - 320_000, true, 15)!!.apply { assertTrue(begin()); written() }
        repeat(120) {
            now += 66_667
            val frame = t.frame(now - 340_000, false, 15)!!
            assertEquals(0L, frame.readyInUs())
            assertTrue(frame.begin()); frame.written()
        }
        assertNull(t.frame(now - 501_000, false, 15))
    }
    @Test fun aFasterVideoEncoderStaysOnTheContinuousAudioClock() {
        var now = 1_000_000L
        val sent = mutableListOf<Long>()
        val t = DesktopAvTimeline({ now }) { _, _, _ -> sent += now }
        t.reset(true)
        t.frame(now - 210_000, true, 30)!!.apply { assertTrue(begin()); written() }
        val capture = now
        assertTrue(t.pcm(3, byteArrayOf(1), capture))
        now += 10_000
        val early = t.frame(capture, false, 30)!!
        assertEquals(200_000L, early.readyInUs())
        now += 199_000
        t.drainDueAudio()
        assertTrue(sent.isEmpty())
        assertEquals(0L, early.congestionAgeMs(199))
        now += 1_000
        assertEquals(0L, early.readyInUs())
        assertTrue(early.begin())
        early.written(); t.drainDueAudio()
        assertEquals(listOf(capture + 210_000), sent)
        assertEquals(0L, early.readyInUs()) // A second fragment must never be paced again.
    }
    @Test fun slowerEncoderCanStartButRecoveryCannotAccumulateMorePlayoutDelay() {
        var now = 1_000_000L
        val t = DesktopAvTimeline({ now }) { _, _, _ -> }
        t.reset(true)
        fun written(age: Long, key: Boolean) {
            t.frame(now - age, key, 30)!!.apply { assertTrue(begin()); written() }
        }
        written(210_000, true)
        now += 50_000; written(225_000, false)
        now += 400_000
        assertNull(t.frame(now - 390_000, false, 30))
        assertNull(t.frame(now - 501_000, true, 30))
        assertNull(t.frame(now - 400_000, true, 30))
        written(240_000, true)
        now += 40_000; written(245_000, false)
        // A genuinely new projection may establish a different codec baseline.
        t.reset(true)
        written(400_000, true)
        now += 40_000; written(405_000, false)
    }
    @Test fun tenMinutesOfVideoJitterAndCameraTransitionsDoNotInsertAudioGapsOrDrift() {
        var now = 1_000_000L
        val times = mutableListOf<Long>()
        val samples = mutableListOf<Int>()
        val t = DesktopAvTimeline({ now }) { _, bytes, _ ->
            times += now
            samples += java.nio.ByteBuffer.wrap(bytes).int
        }
        t.reset(true)
        t.frame(now - 20_000, true, 30)!!.apply { assertTrue(begin()); written() }
        repeat(60_000) { tick ->
            now += 10_000
            val camera = tick % 1000 in 700..799
            if (tick % 1000 == 700) t.videoActive(false)
            if (tick % 1000 == 800) t.videoActive(true)
            if (!camera && tick % 4 == 0) {
                val delay = if (tick % 30 < 10) 100_000L else 20_000L
                t.frame(now - delay, true, 25)!!.apply { assertTrue(begin()); written() }
            }
            assertTrue(t.pcm(3, java.nio.ByteBuffer.allocate(4).putInt(tick).array(), now))
            t.drainDueAudio()
        }
        repeat(2) { now += 10_000; t.drainDueAudio() }
        assertEquals((0 until 60_000).toList(), samples)
        assertTrue(times.zipWithNext().all { (a, b) -> b - a == 10_000L })
        assertTrue(t.diagnostics().contains("audioTargetMs=20"))
        assertTrue(t.diagnostics().contains("stalePcm=0"))
    }
    @Test fun aStalledVideoWriterCannotMuteOrReplayLiveAudio() {
        var now = 1_000_000L
        val heard = mutableListOf<Int>()
        val t = DesktopAvTimeline({ now }) { _, bytes, ttl -> assertTrue(ttl > 0); heard += bytes[0].toInt() }
        t.reset(true)
        t.frame(now, true, 30)!!.apply { assertTrue(begin()); written() }
        val blocked = t.frame(now, false, 30)!!
        assertTrue(blocked.begin())
        val waiting = t.frame(now, false, 30)!!
        repeat(60) { now += 20_000; t.pcm(3, byteArrayOf(it.toByte()), now) }
        assertEquals((0 until 60).toList(), heard)
        blocked.written()
        assertFalse(waiting.begin())
        assertNull(t.frame(now, false, 30))
        val idr = t.frame(now, true, 30)!!
        assertTrue(idr.begin())
        now += 10_000
        assertTrue(idr.begin()) // even a fragmented access unit makes one admission decision
        idr.written()
        assertEquals((0 until 60).toList(), heard)
        assertTrue(t.frame(now, false, 30)!!.begin())
    }
    @Test fun steadyStreamUsesMeasuredVideoLatencyAndStaticPictureDoesNotMuteMusic() {
        var now = 1_000_000L
        val sent = mutableListOf<Long>()
        val t = DesktopAvTimeline({ now }) { _, _, _ -> sent += now }
        t.reset(true); val f = t.frame(now, true, 30)!!
        assertTrue(f.begin()); t.pcm(3, byteArrayOf(1), now)
        now += 90_000; assertTrue(sent.isEmpty()); f.written()
        assertEquals(listOf(now), sent)
        repeat(40) { now += 20_000; t.pcm(3, byteArrayOf(1), now) }
        assertTrue(sent.size in 34..38)
    }
    @Test fun startupDoesNotPlayASecondOfUnmatchedSoundBeforeFirstPicture() {
        var now = 2_000_000L; val heard = mutableListOf<Int>()
        val t = DesktopAvTimeline({ now }) { _, b, _ -> heard += b[0].toInt() }
        t.reset(true)
        repeat(70) { now += 20_000; t.pcm(3, byteArrayOf(it.toByte()), now) }
        assertTrue(heard.isEmpty())
        t.frame(now, true, 30)!!.apply { assertTrue(begin()); written() }
        assertTrue(heard.isNotEmpty() && heard.all { it >= 62 })
    }
    @Test fun receiverTrimIsOptionalBoundedAndDoesNotReplayOldAudioOnChange() {
        var now = 2_000_000L; var trim = 1500; val heard = mutableListOf<Int>()
        val t = DesktopAvTimeline({ now }, { trim }) { _, b, _ -> heard += b[0].toInt() }
        t.reset(true); t.frame(now, true, 30)!!.apply { assertTrue(begin()); written() }
        repeat(75) { t.pcm(3, byteArrayOf(it.toByte()), now); now += 20_000 }
        assertTrue(heard.isEmpty()); t.pcm(3, byteArrayOf(75), now); assertEquals(listOf(0), heard)
        trim = 0; now += 20_000
        t.pcm(3, byteArrayOf(76), now); assertEquals(listOf(0, 76), heard)
    }
    @Test fun audioDeadlineFiresWithNoFurtherPictureOrCaptureBlock() {
        var now = 1_000_000L
        val sent = mutableListOf<Int>()
        val timeline = DesktopAvTimeline({ now }, { 100 }) { _, bytes, _ -> sent += bytes[0].toInt() }
        timeline.reset(true)
        timeline.frame(now, true, 30)!!.apply { assertTrue(begin()); written() }
        timeline.pcm(3, byteArrayOf(7), now)
        now += 99_000
        timeline.drainDueAudio()
        assertTrue(sent.isEmpty())
        now += 1_000
        timeline.drainDueAudio()
        timeline.drainDueAudio()
        assertEquals(listOf(7), sent)
    }
    @Test fun pauseAndReplacementCancelOldFragmentsAndPcm() {
        var now = 1_000_000L; val sent = mutableListOf<Int>()
        val t = DesktopAvTimeline({ now }) { _, b, _ -> sent += b[0].toInt() }
        t.reset(true)
        val old = t.frame(now, true, 30)!!
        t.pcm(3, byteArrayOf(1), now)
        t.reset(false)
        assertFalse(old.begin()); old.written()
        t.pcm(3, byteArrayOf(2), now); assertEquals(listOf(2), sent)
        t.close(); now += 10_000; assertFalse(t.pcm(3, byteArrayOf(3), now))
    }
}
