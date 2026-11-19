package com.hsd.universal.carplay

/** RTP counts sample frames; capture/codec PTS are on the same monotonic clock as video.
 * Do not turn a lost capture interval into an ever-growing offset from the receiver's clock.
 * Keep the extended counter until serialization, including across RTP's 32-bit wrap.
 */
internal class CarPlayAudioTimeline(private val rate: Int) {
    private var originUs: Long? = null
    private var nextFrame = 0L
    @Volatile var skippedFrames = 0L
        private set
    @Volatile var backwardsPts = 0L
        private set

    data class Position(val rtp: Long, val ptsUs: Long)

    fun next(ptsUs: Long, frames: Int): Position {
        require(frames > 0 && rate > 0)
        val origin = originUs ?: ptsUs.also { originUs = it }
        val elapsed = ptsUs - origin
        val fromPts = elapsed / 1_000_000L * rate +
            (elapsed % 1_000_000L * rate + 500_000L) / 1_000_000L
        // Microsecond rounding at 44.1 kHz can differ by one sample. Larger forward
        // differences represent missing source samples, not more data in the queue.
        val timestamp = if (fromPts > nextFrame + 1) fromPts else nextFrame
        skippedFrames += timestamp - nextFrame
        if (fromPts < nextFrame - 1) backwardsPts++
        nextFrame = timestamp + frames
        val normalizedPts = origin + timestamp / rate * 1_000_000L + timestamp % rate * 1_000_000L / rate
        return Position(timestamp, normalizedPts)
    }
}
