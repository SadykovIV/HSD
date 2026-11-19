package com.hsd.universal.aa

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EncoderFrameWindowTest {
    private val period = 33_333_333L

    @Test fun onlyOneNewPictureAfterTouchCanEnterABusyCodecBeforeItsNextOutput() {
        val window = EncoderFrameWindow(0)
        window.submit(100, 0)
        assertFalse(window.canSubmit(period, period, fresh = false, afterTouch = true))
        assertFalse(window.canSubmit(period, period, fresh = true))
        assertTrue(window.canSubmit(period, period, fresh = true, afterTouch = true))
        window.submit(101, period)
        assertFalse(window.canSubmit(period + 1, period, fresh = true, afterTouch = true))
    }

    @Test fun stalledCodecDoesNotQueueAnotherOldPictureAtEveryRecoveryTick() {
        val window = EncoderFrameWindow(0)
        val tick = 350_000_000L
        window.submit(100, 0)
        for (index in 1..2) {
            assertTrue(window.canSubmit(tick * index, period, fresh = false))
            window.submit(100L + index, tick * index, redraw = true)
        }
        assertFalse(window.canSubmit(3 * tick, period))
        assertTrue(window.canSubmit(4 * tick, period))
        assertTrue(window.completed(102, 4 * tick, period))
        assertTrue(window.canSubmit(4 * tick, period))
    }

    @Test fun aNewPictureDoesNotWaitBehindAKeepaliveAndOnlyOneCanPass() {
        val window = EncoderFrameWindow(0)
        window.submit(100, 0, redraw = true)
        assertFalse(window.canSubmit(2 * period, period, fresh = false))
        assertTrue(window.canSubmit(period, period, fresh = true))
        window.submit(200, period)
        assertFalse(window.canSubmit(2 * period, period))
        assertTrue(window.canSubmit(400_000_000, period, fresh = false))
    }

    @Test fun startupDoesNotQueueASecondPictureUntilThroughputIsKnown() {
        val window = EncoderFrameWindow(0)
        repeat(4) { index ->
            val at = index * period
            window.submit(index.toLong(), at)
            assertFalse(window.canSubmit(at + 1, period))
            window.completed(index.toLong(), at + period / 2, period)
        }
        window.submit(4, 4 * period)
        assertTrue(window.canSubmit(4 * period + 1, period))
    }

    @Test fun saturatedCodecKeepsLastTextureOutsideTheEncoder() {
        val window = EncoderFrameWindow(0)
        window.submit(100, 0)
        window.submit(103, period)
        assertFalse(window.canSubmit(2 * period, period))
        assertEquals(100.0, window.oldestAgeMs(100_000_000), 0.0)
        // This slow output reduces the window to one; the next picture is already pending.
        assertFalse(window.completed(100, 4 * period, period))
        assertFalse(window.canSubmit(4 * period, period))
        assertTrue(window.completed(103, 6 * period, period))
        assertEquals(0.0, window.oldestAgeMs(6 * period), 0.0)
        assertTrue(window.canSubmit(6 * period, period))
    }

    @Test fun aWithheldOutputGetsOneFreshPictureAtTheNegotiatedCadence() {
        val window = EncoderFrameWindow(0)
        window.submit(100, 0)
        assertFalse(window.canSubmit(2 * period - 1, period))
        assertTrue(window.canSubmit(2 * period, period))
        window.submit(200, 2 * period)
        assertFalse(window.canSubmit(5 * period, period))
        assertTrue(window.completed(200, 5 * period, period))
        assertEquals(0.0, window.oldestAgeMs(5 * period), 0.0)
    }

    @Test fun droppedSurfaceInputCanRecoverWithoutAnOutputCallback() {
        val window = EncoderFrameWindow(0)
        window.submit(100, 0)
        window.submit(200, period)
        assertFalse(window.canSubmit(3 * period, period))
        assertTrue(window.canSubmit(500_000_000, period))
        window.submit(500, 500_000_000)
        assertFalse(window.completed(90, 501_000_000, period))
        assertTrue(window.completed(500, 510_000_000, period))
        assertTrue(window.canSubmit(510_000_000, period))
    }

    @Test fun aSlowEncoderKeepsItsNextInteractionOutsideTheBacklog() {
        val window = EncoderFrameWindow(0)
        window.submit(100, 0)
        window.completed(100, 4 * period, period)
        window.submit(200, 4 * period)
        assertFalse(window.canSubmit(6 * period, period))
        assertTrue(window.canSubmit(4 * period + 350_000_000, period))
        assertTrue(window.completed(200, 7 * period, period))
        assertTrue(window.canSubmit(7 * period, period))
    }

    @Test fun codecReportingLookaheadCanReceiveEnoughPicturesToProduceOutput() {
        val window = EncoderFrameWindow(3)
        repeat(4) { assertTrue(window.canSubmit(it * period, period)); window.submit(it.toLong(), it * period) }
        assertFalse(window.canSubmit(4 * period, period))
        assertTrue(window.completed(0, 5 * period, period))
    }
}
