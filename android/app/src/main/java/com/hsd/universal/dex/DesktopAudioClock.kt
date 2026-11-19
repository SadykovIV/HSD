package com.hsd.universal.dex

/** PCM timestamps describe captured samples, not the moment a polling thread reads them. */
internal class DesktopAudioClock(private val sampleRate: Int = DesktopAudioPolicy.SAMPLE_RATE,
                                private val bufferCapacityFrames: Int = sampleRate) {
    private var framesRead = 0L
    private var fallbackOriginUs: Long? = null
    private var lastEndUs: Long? = null
    private var lastReadUs: Long? = null
    private var calibrated = false
    var discontinuityUs = 0L
        private set

    /** The mixer shifts pending samples by the same amount; keep one shared origin. */
    fun correctFutureSamples(backwardsUs: Long) {
        require(backwardsUs >= 0)
        fallbackOriginUs = fallbackOriginUs?.minus(backwardsUs)
        lastEndUs = lastEndUs?.minus(backwardsUs)
    }

    fun read(frames: Int, nowUs: Long, hardwareFrame: Long? = null, hardwareTimeUs: Long? = null): Long {
        require(frames >= 0)
        val durationUs = frames * 1_000_000L / sampleRate
        val ringUs = bufferCapacityFrames.coerceAtLeast(frames) * 1_000_000L / sampleRate
        // A consumer can fall behind gradually without one long sleep. Two native
        // ring depths separate that sustained deficit from startup/HAL timestamp jitter.
        val accumulatedLag = lastEndUs?.let { nowUs - it >= 2 * ringUs } ?: false
        val mayHaveOverrun = accumulatedLag || (lastReadUs?.let { nowUs - it >= ringUs } ?: true)
        val timed = hardwareFrame != null && hardwareFrame >= 0 && hardwareTimeUs != null && hardwareTimeUs in 0..nowUs
        if (fallbackOriginUs == null || (timed && !calibrated)) {
            fallbackOriginUs = if (timed) hardwareTimeUs!! - hardwareFrame!! * 1_000_000L / sampleRate
                else nowUs - durationUs - framesRead * 1_000_000L / sampleRate
            calibrated = timed
        }
        if (timed && mayHaveOverrun) {
            // AudioRecord may drop unread samples on overflow. Its bounded ring cannot contain
            // an arbitrarily old frame, so move the read cursor past frames already overwritten.
            val oldestPossible = hardwareFrame!! - bufferCapacityFrames.coerceAtLeast(frames) - frames
            if (oldestPossible > framesRead) {
                framesRead = oldestPossible
                // The samples before this cursor no longer exist. Recalibrate the
                // remaining ring as well: a remote-submix driver can restart its clock
                // when its first player is attached, without restarting AudioRecord.
                fallbackOriginUs = hardwareTimeUs!! - hardwareFrame * 1_000_000L / sampleRate
            }
        }
        // Samples in AudioRecord are contiguous. Re-anchoring on every independently
        // sampled driver timestamp turns timestamp jitter into gaps/overlaps when stems
        // are mixed. Calibrate once; advance by sample count until an actual overrun.
        val estimate = checkNotNull(fallbackOriginUs) + framesRead * 1_000_000L / sampleRate
        // Bound recovery in the producer's time domain. A fresh AudioTimestamp can
        // also re-anchor a submix that paused/restarted without resetting its counter.
        // A delayed reader with an unchanged producer timestamp must not invent gaps
        // or make old samples fresh. Wall time is only the fallback without a timestamp.
        val producerTimeUs = if (timed) hardwareTimeUs!! else nowUs
        val oldestTimeUs = if (mayHaveOverrun) producerTimeUs - ringUs - durationUs else Long.MIN_VALUE
        val value = maxOf(lastEndUs ?: Long.MIN_VALUE, oldestTimeUs, minOf(nowUs, estimate))
        discontinuityUs = lastEndUs?.let { (value - it).coerceAtLeast(0) } ?: 0
        framesRead += frames
        lastEndUs = value + durationUs
        lastReadUs = nowUs
        if (value != estimate)
            fallbackOriginUs = value - (framesRead - frames) * 1_000_000L / sampleRate
        return value
    }
}
