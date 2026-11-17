package net.prototux.aare.aap.channels

import org.junit.Assert.*
import org.junit.Test

class PendingVideoFramesTest {
    private var requests = 0
    private val recycled = mutableListOf<Int>()
    private val queue = PendingVideoFrames { requests++ }
    private fun frame(id: Int, timeMs: Long, key: Boolean = false) = PendingVideoFrames.Frame(
        byteArrayOf(id.toByte()), 0, 1, timeMs * 1000, key, timeMs * 1_000_000,
    ) { recycled += it[0].toInt() }

    @Test fun preservesReferenceOrderAndTransfersBufferOwnershipExactlyOnce() {
        queue.offer(frame(1, 0, true)); queue.offer(frame(2, 10)); queue.offer(frame(3, 20))
        val written = List(3) { queue.poll(30_000_000)!! }
        assertEquals(listOf(1, 2, 3), written.map { it.buf[0].toInt() })
        assertTrue(recycled.isEmpty())
        written.forEach { it.recycle(it.buf) }
        queue.clear()
        assertEquals(listOf(1, 2, 3), recycled)
        assertEquals(0, queue.size)
    }

    @Test fun slowFpsStillExpiresAQueueWithOnlyOneFrame() {
        queue.offer(frame(1, 0, true))
        assertEquals(99L, queue.oldestAgeMs(99_000_000))
        queue.offer(frame(2, 100))
        assertEquals(0, queue.size)
        assertEquals(1, requests)
        assertEquals(1L, queue.expired)
        assertEquals(listOf(1, 2), recycled)
    }

    @Test fun stalledWriterCannotFlushOldFramesAfterProducerStops() {
        queue.offer(frame(1, 0, true)); queue.offer(frame(2, 16)); queue.offer(frame(3, 32))
        assertNull(queue.poll(1_000_000_000))
        assertEquals(listOf(1, 2, 3), recycled)
        assertEquals(1, requests)
        queue.offer(frame(4, 1010))
        assertNull(queue.poll(1_010_000_000))
        assertEquals(1, requests)
        queue.offer(frame(5, 1020, true)); queue.offer(frame(6, 1030))
        assertTrue(queue.poll(1_040_000_000)!!.key)
        assertEquals(6, queue.poll(1_040_000_000)!!.buf[0].toInt())
    }

    @Test fun keyframeSupersedesWaitingChainWithoutDemandingAnotherKeyframe() {
        queue.offer(frame(1, 0, true)); queue.offer(frame(2, 20))
        queue.offer(frame(3, 200, true)); queue.offer(frame(4, 210))
        assertEquals(listOf(1, 2), recycled)
        assertEquals(0, requests)
        assertEquals(3, queue.poll(220_000_000)!!.buf[0].toInt())
        assertEquals(4, queue.poll(220_000_000)!!.buf[0].toInt())
    }

    @Test fun memoryIsBoundedEvenAtVeryHighFrameRate() {
        repeat(9) { queue.offer(frame(it, it.toLong(), it == 0)) }
        assertEquals(0, queue.size)
        assertEquals(1L, queue.congestions)
        assertEquals(9, recycled.size)
        assertEquals(1, requests)
        assertEquals(0L, queue.expired)
    }

    @Test fun clearStartsANewIndependentStreamAndDoesNotDoubleRecycle() {
        queue.offer(frame(1, 0, true)); queue.offer(frame(2, 10))
        queue.clear(); queue.clear()
        queue.offer(frame(3, 20)); queue.offer(frame(4, 30, true))
        assertEquals(listOf(1, 2, 3), recycled)
        assertEquals(4, queue.poll(40_000_000)!!.buf[0].toInt())
    }
}
