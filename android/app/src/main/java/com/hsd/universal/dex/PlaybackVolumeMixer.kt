package com.hsd.universal.dex

import com.hsd.universal.PcmGain
import java.util.ArrayDeque

/** Timestamped, disjoint loopback stems. Never waits indefinitely for an inactive route. */
internal class PlaybackVolumeMixer(private val guidance: List<Boolean>, private val rate: Int = 48_000) {
    data class Output(val pcm: ByteArray, val ptsUs: Long)
    private data class Block(var start: Long, val pcm: ByteArray, val arrivedUs: Long) {
        val end get() = start + pcm.size / 4
    }
    private val queues = guidance.map { ArrayDeque<Block>() }
    private val ends = LongArray(guidance.size) { Long.MIN_VALUE }
    private val arrivals = LongArray(guidance.size) { Long.MIN_VALUE }
    private val gains = arrayOfNulls<PcmGain>(guidance.size)
    private var cursor: Long? = null
    private fun frame(us: Long) = (us * rate + 500_000) / 1_000_000
    private val maxFrames = rate / 10 // 100 ms of bounded input per route
    private val waitFrames = rate / 50 // fixed 20 ms output quantum
    private val inactiveUs = 100_000L // an idle AudioRecord normally still supplies timed silence

    fun offer(route: Int, pcm: ByteArray, ptsUs: Long,
              arrivedUs: Long = ptsUs + pcm.size / 4 * 1_000_000L / rate): Long {
        require(route in queues.indices && pcm.size % 4 == 0 && ptsUs >= 0)
        if (pcm.isEmpty()) return 0
        var measured = frame(ptsUs)
        val q = queues[route]
        // A recorder can deliver prefetched silence in a burst when its submix starts.
        // A sample-count clock then runs ahead even with valid monotonic HAL timestamps.
        // All returned samples already exist: their END cannot be after arrival. Correct
        // the route's origin and its pending samples together, before queue eviction;
        // otherwise a fast stem can be discarded forever while the other stem catches up.
        // Arrival jitter never moves an established origin forward or adds buffering.
        val correction = (measured + pcm.size / 4 - frame(arrivedUs)).coerceAtLeast(0)
        if (correction > 0) {
            q.forEach { it.start -= correction }
            if (ends[route] != Long.MIN_VALUE) ends[route] -= correction
            measured -= correction
        }
        // Quantized microsecond timestamps must not insert a sample every few buffers.
        val start = if (ends[route] != Long.MIN_VALUE && kotlin.math.abs(measured - ends[route]) <= 1)
            ends[route] else measured
        val block = Block(start, pcm, arrivedUs)
        arrivals[route] = arrivedUs
        ends[route] = maxOf(ends[route], block.end)
        if (block.end > (cursor ?: Long.MIN_VALUE)) q.add(block)
        while (q.isNotEmpty() && q.first.end <= ends[route] - maxFrames) q.removeFirst()
        return correction * 1_000_000 / rate
    }

    fun poll(nowUs: Long, musicPercent: Int, navigationPercent: Int): Output? {
        require(musicPercent in 0..100 && navigationPercent in 0..100)
        val first = queues.mapNotNull { it.peekFirst()?.start }.minOrNull() ?: return null
        val at = maxOf(cursor ?: first, first)
        val newest = ends.maxOrNull() ?: return null
        val oldestArrival = queues.mapNotNull { it.peekFirst()?.arrivedUs }.minOrNull() ?: return null
        if (arrivals.any { it == Long.MIN_VALUE } && nowUs - oldestArrival < inactiveUs) return null
        // Follow the common sample watermark of every live recorder. Native submix
        // buffers can differ by several quanta: expiring each faster block after 20 ms
        // would continuously erase the slower stem even though it is healthy.
        // Only an entire recorder that stopped producing (including silence) may time out.
        val live = ends.indices.filter { arrivals[it] != Long.MIN_VALUE && nowUs - arrivals[it] < inactiveUs }
        val common = live.minOfOrNull { ends[it] } ?: newest
        val ready = minOf(newest, common, frame(nowUs))
        if (ready < at + waitFrames) return null
        // Never emit one-sample packets as wall time advances between AudioRecord reads.
        val count = waitFrames
        val sum = IntArray(count * 2)
        for ((route, q) in queues.withIndex()) {
            val stem = ByteArray(count * 4)
            for (block in q) {
                val from = maxOf(at, block.start)
                val until = minOf(at + count, block.end)
                if (until > from) block.pcm.copyInto(stem, ((from - at) * 4).toInt(),
                    ((from - block.start) * 4).toInt(), ((until - block.start) * 4).toInt())
            }
            val level = (if (guidance[route]) navigationPercent else musicPercent) / 100.0
            val gain = gains[route] ?: PcmGain(level).also { gains[route] = it }
            gain.target = level
            val adjusted = gain.apply(stem, rate, 2)
            for (i in sum.indices) sum[i] += ((adjusted[i * 2].toInt() and 255) or
                (adjusted[i * 2 + 1].toInt() shl 8)).toShort().toInt()
            while (q.isNotEmpty() && q.first.end <= at + count) q.removeFirst()
        }
        val out = ByteArray(count * 4)
        for (i in sum.indices) {
            val value = sum[i].coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            out[i * 2] = value.toByte(); out[i * 2 + 1] = (value shr 8).toByte()
        }
        cursor = at + count
        return Output(out, at * 1_000_000 / rate)
    }

    fun discard() { queues.forEach { it.clear() }; cursor = null }
}

/** Attribute and UID criteria are ANDed by Android; these rules never overlap. */
internal data class PlaybackStem(val guidance: Boolean, val usages: List<Int>,
    val includeUids: Set<Int> = emptySet(), val excludeUids: Set<Int> = emptySet()) {
    fun matches(usage: Int, uid: Int) = usage in usages &&
        (includeUids.isEmpty() || uid in includeUids) && uid !in excludeUids
    companion object {
        fun rules(navigationUids: Set<Int>): List<PlaybackStem> = buildList {
            add(PlaybackStem(false, listOf(1), excludeUids = navigationUids)) // MEDIA
            add(PlaybackStem(true, listOf(12, 16))) // NAVIGATION_GUIDANCE, ASSISTANT
            if (navigationUids.isNotEmpty()) add(PlaybackStem(true, listOf(1), includeUids = navigationUids))
        }
    }
}
