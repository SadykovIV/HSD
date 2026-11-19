package com.hsd.universal.link

import java.util.concurrent.BlockingQueue
import java.util.concurrent.Delayed
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

internal class QueuedTransportPacket(val chan: Int, val inner: ByteArray, val deadlineMs: Long,
    val frame: VideoWrite? = null, val lastFragment: Boolean = true, val enqueuedMs: Long = 0) : Delayed {
    val sequence = nextSequence.getAndIncrement()
    // DelayQueue wakes immediately for incoming audio/control while a picture awaits its
    // presentation time. No sleep on the single USB writer can hold up another channel.
    override fun getDelay(unit: TimeUnit): Long = unit.convert(frame?.readyInUs() ?: 0, TimeUnit.MICROSECONDS)
    override fun compareTo(other: Delayed): Int {
        other as QueuedTransportPacket
        val priority = (if (chan == com.hsd.universal.Ch.VIDEO) 1 else 0)
            .compareTo(if (other.chan == com.hsd.universal.Ch.VIDEO) 1 else 0)
        return if (priority != 0) priority else sequence.compareTo(other.sequence)
    }
    fun congestionAgeMs(nowMs: Long): Long = (nowMs - enqueuedMs).coerceAtLeast(0).let {
        frame?.congestionAgeMs(it) ?: it
    }
    companion object { private val nextSequence = AtomicLong() }
}

/** Bound waiting timed PCM even when its writer is blocked and other TCP6 channels remain healthy.
 * A partially written packet is never interrupted: at most one further in-flight packet per writer
 * can exist outside these limits. Control/video messages are neither removed nor reordered.
 */
internal object AudioQueueAdmission {
    const val MAX_PENDING_PCM_BYTES = 64 * 1024
    const val MAX_PENDING_PCM_PACKETS = 32

    fun offer(queue: BlockingQueue<QueuedTransportPacket>, packet: QueuedTransportPacket,
        nowMs: Long, expired: (Long) -> Unit, overflow: (Long) -> Unit) = synchronized(queue) {
        if (packet.deadlineMs == Long.MAX_VALUE || (packet.inner.size <= MAX_PENDING_PCM_BYTES && queue.isEmpty())) {
            queue.offer(packet)
            return@synchronized
        }
        var old = 0L
        var bytes = 0L
        var count = 0
        // Expire at admission as well as at dequeue. Relying only on the writer permits an
        // unbounded PCM queue when e.g. MEDIA stalls while the independent VIDEO socket drains.
        for (queued in queue) {
            if (queued.deadlineMs == Long.MAX_VALUE) continue
            if (nowMs >= queued.deadlineMs) {
                if (queue.remove(queued)) old++
            } else if (queued.chan == packet.chan) {
                bytes += queued.inner.size
                count++
            }
        }
        if (old > 0) expired(old)
        if (packet.inner.size > MAX_PENDING_PCM_BYTES) {
            overflow(1)
            return@synchronized
        }
        if (bytes + packet.inner.size > MAX_PENDING_PCM_BYTES || count >= MAX_PENDING_PCM_PACKETS) {
            // The writer may have drained packets since the scan. Recount a new snapshot
            // before evicting, and preserve FIFO even for queues whose iterator is unordered.
            val same = queue.filter { it.chan == packet.chan && it.deadlineMs != Long.MAX_VALUE }.sortedBy { it.sequence }
            bytes = same.sumOf { it.inner.size.toLong() }
            count = same.size
            var evicted = 0L
            for (queued in same) {
                if (bytes + packet.inner.size <= MAX_PENDING_PCM_BYTES && count < MAX_PENDING_PCM_PACKETS) break
                if (queue.remove(queued)) evicted++
                bytes -= queued.inner.size
                count--
            }
            if (evicted > 0) overflow(evicted)
        }
        queue.offer(packet)
    }
}
