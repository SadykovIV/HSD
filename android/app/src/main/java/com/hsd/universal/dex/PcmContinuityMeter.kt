package com.hsd.universal.dex

/** Counts stereo PCM frames and near-silent runs without retaining any samples. */
internal class PcmContinuityMeter(private val rate: Int = 48000) {
    data class Reading(val frames: Long, val longestQuietMs: Long, val maxPacketGapMs: Long)
    private var frames = 0L
    private var quiet = 0L
    private var longestQuiet = 0L
    private var lastNs = 0L
    private var maxGapNs = 0L

    @Synchronized fun accept(pcm: ByteArray, nowNs: Long = System.nanoTime()) {
        if (lastNs != 0L) maxGapNs = maxOf(maxGapNs, nowNs - lastNs)
        lastNs = nowNs
        for (i in 0 until pcm.size - 3 step 4) {
            val left = (pcm[i].toInt() and 255) or (pcm[i + 1].toInt() shl 8)
            val right = (pcm[i + 2].toInt() and 255) or (pcm[i + 3].toInt() shl 8)
            quiet = if (left in -19..19 && right in -19..19) quiet + 1 else 0
            longestQuiet = maxOf(longestQuiet, quiet)
        }
        frames += pcm.size / 4
    }

    @Synchronized fun reading() = Reading(frames, longestQuiet * 1000 / rate, maxGapNs / 1_000_000)
}
