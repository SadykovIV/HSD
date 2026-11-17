package net.prototux.aare.aap.channels

import java.util.ArrayDeque

/** Encoded reference frames form a chain. A stale chain can only be replaced by a key frame. */
internal class PendingVideoFrames(private val requestKeyFrame: () -> Unit) {
    class Frame(val buf: ByteArray, val offset: Int, val length: Int, val ptsUs: Long,
                val key: Boolean, val queuedAtNanos: Long, val recycle: (ByteArray) -> Unit)

    private val frames = ArrayDeque<Frame>()
    @Volatile var size = 0; private set
    var needsKeyFrame = true; private set
    @Volatile var dropped = 0L; private set
    @Volatile var congestions = 0L; private set
    @Volatile var expired = 0L; private set

    fun oldestAgeMs(nowNanos: Long): Long = frames.peekFirst()?.let {
        ((nowNanos - it.queuedAtNanos) / 1_000_000).coerceAtLeast(0)
    } ?: 0

    fun offer(frame: Frame) {
        if (frame.key) {
            dropQueued()
            needsKeyFrame = false
        } else {
            expire(frame.queuedAtNanos)
            if (!needsKeyFrame && size >= MAX_FRAMES) congested()
            if (needsKeyFrame) { drop(frame); return }
        }
        frames.addLast(frame)
        size = frames.size
    }

    fun poll(nowNanos: Long): Frame? {
        // Also check on write completion: the producer may have gone idle during a stall.
        expire(nowNanos)
        return frames.pollFirst().also { size = frames.size }
    }

    fun clear() {
        while (frames.isNotEmpty()) frames.removeFirst().let { it.recycle(it.buf) }
        size = 0
        needsKeyFrame = true
    }

    private fun expire(nowNanos: Long) {
        if (frames.isNotEmpty() && oldestAgeMs(nowNanos) >= MAX_AGE_MS) {
            expired += frames.size
            congested()
        }
    }

    private fun congested() {
        dropQueued()
        needsKeyFrame = true
        congestions++
        requestKeyFrame()
    }

    private fun dropQueued() {
        while (frames.isNotEmpty()) drop(frames.removeFirst())
        size = 0
    }

    private fun drop(frame: Frame) { dropped++; frame.recycle(frame.buf) }

    companion object {
        const val MAX_FRAMES = 8
        const val MAX_AGE_MS = 100L
    }
}
