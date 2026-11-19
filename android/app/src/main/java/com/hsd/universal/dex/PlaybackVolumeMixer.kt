package com.hsd.universal.dex

import com.hsd.universal.PcmGain
import java.util.ArrayDeque

/** Timestamped, disjoint loopback stems. Never waits indefinitely for an inactive route. */
internal class PlaybackVolumeMixer(private val guidance: List<Boolean>, private val rate: Int = 48_000) {
    data class Output(val pcm: ByteArray, val ptsUs: Long, val music: ByteArray, val navigation: ByteArray)
    private data class Block(var start: Long, val pcm: ByteArray, val arrivedUs: Long, val carriesOutput: Boolean) {
        val end get() = start + pcm.size / 4
    }
    private val queues = guidance.map { ArrayDeque<Block>() }
    private val ends = LongArray(guidance.size) { Long.MIN_VALUE }
    private val arrivals = LongArray(guidance.size) { Long.MIN_VALUE }
    private val gains = arrayOfNulls<PcmGain>(guidance.size)
    private val musicRoute = guidance.indices.singleOrNull { !guidance[it] }
    private var cursor: Long? = null
    private fun frame(us: Long) = (us * rate + 500_000) / 1_000_000
    private val waitFrames = rate / 50 // fixed 20 ms output quantum
    // A late reader can deliver a burst after another route has waited 100 ms.
    // Keep two windows of samples. Polling still emits each ready quantum immediately.
    private val maxFrames = rate / 5
    private val stem = ByteArray(waitFrames * 4)
    private val silence = ByteArray(waitFrames * 4)
    private val mediaSum = IntArray(waitFrames * 2)
    private val navigationSum = IntArray(waitFrames * 2)
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
        var correction = (measured + pcm.size / 4 - frame(arrivedUs)).coerceAtLeast(0)
        val delivered = cursor
        if (delivered != null && correction <= waitFrames) {
            // The receiver already played everything before this frame. Moving a
            // continuous block across it clips real samples on an early native read.
            // More than a full quantum ahead indicates a prefetched burst, for
            // which the native clock and pending queue still need re-anchoring.
            val firstPending = q.peekFirst()?.start ?: measured
            correction = minOf(correction, (firstPending - delivered).coerceAtLeast(0))
        }
        if (correction > 0) {
            q.forEach { it.start -= correction }
            if (ends[route] != Long.MIN_VALUE) ends[route] -= correction
            measured -= correction
        }
        // Quantized microsecond timestamps must not insert a sample every few buffers.
        val start = if (ends[route] != Long.MIN_VALUE && kotlin.math.abs(measured - ends[route]) <= 1)
            ends[route] else measured
        val block = Block(start, pcm, arrivedUs, !guidance[route] || pcm.any { it != 0.toByte() })
        arrivals[route] = arrivedUs
        ends[route] = maxOf(ends[route], block.end)
        if (block.end > (cursor ?: Long.MIN_VALUE)) q.add(block)
        while (q.isNotEmpty() && q.first.end <= ends[route] - maxFrames) q.removeFirst()
        return correction * 1_000_000 / rate
    }

    fun poll(nowUs: Long, musicPercent: Int, navigationPercent: Int,
             navigationState: NavigationPlaybackState? = null): Output? {
        require(musicPercent in 0..100 && navigationPercent in 0..100)
        var first = Long.MAX_VALUE
        var oldestArrival = Long.MAX_VALUE
        for (queue in queues) {
            val head = queue.peekFirst() ?: continue
            first = minOf(first, head.start)
            oldestArrival = minOf(oldestArrival, head.arrivedUs)
        }
        if (first == Long.MAX_VALUE) return null
        var at = maxOf(cursor ?: first, first)
        val newest = ends.maxOrNull() ?: return null
        // Once the playback service has confirmed the end of speech, the idle submix
        // can stop supplying silence. Drain the last spoken quantum across that boundary.
        // Already captured samples from every route are still mixed into the output.
        val silentGuidance = navigationState != null && !navigationState.active &&
            at >= frame(navigationState.changedAtUs) + waitFrames
        if (silentGuidance) {
            // Timed zeros on the speech recorder must not fill a media clock jump or
            // place silence before the next real music packet. Preserve speech already in flight.
            var nextOutput = Long.MAX_VALUE
            for (queue in queues) {
                val block = queue.firstOrNull { it.end > at && it.carriesOutput } ?: continue
                nextOutput = minOf(nextOutput, block.start)
            }
            if (nextOutput == Long.MAX_VALUE) return null
            at = maxOf(at, nextOutput)
        }
        var common = newest
        for (route in ends.indices) {
            if (silentGuidance && guidance[route]) continue
            val arrival = arrivals[route]
            if (arrival == Long.MIN_VALUE) {
                if (nowUs - oldestArrival < inactiveUs) return null
            } else if (nowUs - arrival < inactiveUs) common = minOf(common, ends[route])
        }
        // Follow the common sample watermark of every live recorder. Native submix
        // buffers can differ by several quanta: expiring each faster block after 20 ms
        // would continuously erase the slower stem even though it is healthy.
        // Only an entire recorder that stopped producing (including silence) may time out.
        val ready = minOf(newest, common, frame(nowUs))
        // A genuinely stalled output can leave both routes full. Catch up to the
        // recent 100 ms instead of playing an entire stale queue to the receiver.
        if (ready - at >= maxFrames) at = ready - maxFrames / 2
        if (ready < at + waitFrames) return null
        // Never emit one-sample packets as wall time advances between AudioRecord reads.
        val count = waitFrames
        val end = at + count
        // When Android reports that guidance is idle, preserve the native music samples
        // and clock. Still mix any already captured speech overlapping this interval.
        val single = musicRoute
        if (silentGuidance && single != null && queues.indices.none { route -> guidance[route] &&
                queues[route].any { it.carriesOutput && it.start < end && it.end > at } }) {
            val queue = queues[single]
            // A hardware clock correction may fall inside the quantum. End this packet
            // at the last actual sample and use the next read's timestamp for the next packet.
            var musicEnd = at
            for (block in queue) {
                if (block.start > musicEnd || musicEnd == end) break
                musicEnd = maxOf(musicEnd, minOf(end, block.end))
            }
            val musicFrames = (musicEnd - at).toInt()
            if (musicFrames == 0) return null
            val head = queue.first
            val raw = if (head.start == at && head.end == musicEnd) head.pcm
                else ByteArray(musicFrames * 4).also { copySamples(queue, at, musicEnd, it) }
            val music = gain(single, musicPercent).apply(raw, rate, 2)
            for (route in queues.indices) {
                if (guidance[route]) gain(route, navigationPercent).advanceSilence(musicFrames, rate)
                discardThrough(queues[route], musicEnd)
            }
            cursor = musicEnd
            return Output(music, at * 1_000_000 / rate, music, if (musicFrames == count) silence else ByteArray(music.size))
        }
        mediaSum.fill(0)
        navigationSum.fill(0)
        for ((route, q) in queues.withIndex()) {
            stem.fill(0)
            copySamples(q, at, end, stem)
            gain(route, if (guidance[route]) navigationPercent else musicPercent).applyInPlace(stem, rate, 2)
            val sum = if (guidance[route]) navigationSum else mediaSum
            for (i in sum.indices) sum[i] += (stem[i * 2].toInt() and 255) or (stem[i * 2 + 1].toInt() shl 8)
            discardThrough(q, end)
        }
        val music = ByteArray(count * 4)
        val navigation = ByteArray(count * 4)
        val combined = ByteArray(count * 4)
        for (i in mediaSum.indices) {
            putSample(music, i * 2, mediaSum[i])
            putSample(navigation, i * 2, navigationSum[i])
            putSample(combined, i * 2, mediaSum[i] + navigationSum[i])
        }
        cursor = at + count
        return Output(combined, at * 1_000_000 / rate, music, navigation)
    }

    private fun gain(route: Int, percent: Int): PcmGain {
        val level = percent / 100.0
        val gain = gains[route] ?: PcmGain(level).also { gains[route] = it }
        gain.target = level
        return gain
    }

    private fun copySamples(q: ArrayDeque<Block>, at: Long, end: Long, output: ByteArray) {
        for (block in q) {
            val from = maxOf(at, block.start)
            val until = minOf(end, block.end)
            if (until > from) block.pcm.copyInto(output, ((from - at) * 4).toInt(),
                ((from - block.start) * 4).toInt(), ((until - block.start) * 4).toInt())
        }
    }

    private fun discardThrough(q: ArrayDeque<Block>, end: Long) {
        while (q.isNotEmpty() && q.first.end <= end) q.removeFirst()
    }

    private fun putSample(output: ByteArray, offset: Int, value: Int) {
        val pcm = value.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
        output[offset] = pcm.toByte()
        output[offset + 1] = (pcm shr 8).toByte()
    }

    fun discard() {
        queues.forEach { it.clear() }
        ends.fill(Long.MIN_VALUE)
        arrivals.fill(Long.MIN_VALUE)
        cursor = null
    }
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
