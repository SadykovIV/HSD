package com.hsd.universal.aa

/** Keep obsolete desktop pictures out of the codec. Only already submitted pictures need credits. */
internal class EncoderFrameWindow(reportedDelay: Int) {
    private data class Picture(val ptsUs: Long, val submittedNs: Long, val redraw: Boolean)
    private val pictures = ArrayDeque<Picture>()
    private val minimum = 1 + reportedDelay.coerceIn(0, 30)
    // A cold encoder has not demonstrated throughput yet. Do not put a second
    // obsolete picture ahead of the first interaction unless the codec requires it.
    private var depth = minimum
    private var lastProgressNs = 0L
    private var lastSubmitNs = 0L
    private var quickFrames = 0
    private var slowOutput = false

    @Synchronized fun oldestAgeMs(nowNs: Long): Double =
        pictures.firstOrNull()?.let { (nowNs - it.submittedNs).coerceAtLeast(0) / 1_000_000.0 } ?: 0.0

    @Synchronized fun canSubmit(nowNs: Long, periodNs: Long, fresh: Boolean = true,
                                afterTouch: Boolean = false): Boolean {
        // Reserve the extra steady-state slot for changed content. A keepalive may
        // still fill the codec's required lookahead or recover a dropped input.
        if (pictures.size < if (fresh) depth else minimum) return true
        if (fresh && pictures.size == depth && (afterTouch || pictures.all { it.redraw })) return true
        // A Surface codec may drop an input or need another picture to release its output.
        // Allow one fresh picture after two missed frame periods. If that also stalls,
        // pace subsequent probes so a genuinely slow encoder cannot build a long queue.
        val retryNs = if (fresh && !slowOutput && pictures.size == depth && periodNs > 0)
            minOf(STALL_RETRY_NS, 2 * periodNs)
        else {
            // A stalled codec may have accepted several recovery pictures already.
            // Leave new touches outside that queue until it makes progress.
            val extra = (pictures.size - depth - 1).coerceIn(0, 3)
            STALL_RETRY_NS * (1L shl extra)
        }
        return nowNs - maxOf(lastProgressNs, lastSubmitNs) >= retryNs
    }

    @Synchronized fun submit(ptsUs: Long, nowNs: Long, redraw: Boolean = false) {
        if (pictures.size == 32) pictures.removeFirst()
        pictures.addLast(Picture(ptsUs, nowNs, redraw))
        lastSubmitNs = nowNs
    }

    @Synchronized fun completed(ptsUs: Long, nowNs: Long, periodNs: Long): Boolean {
        val first = pictures.firstOrNull() ?: return false
        if (ptsUs < first.ptsUs) return false
        val wasFull = pictures.size >= depth
        // The next emitted PTS also retires any older Surface frames dropped by the codec.
        var last = pictures.removeFirst()
        while (pictures.firstOrNull()?.let { it.ptsUs <= ptsUs } == true) last = pictures.removeFirst()
        lastProgressNs = nowNs
        if (periodNs > 0 && last.ptsUs == ptsUs) {
            val age = nowNs - last.submittedNs
            if (age > 2 * periodNs) {
                slowOutput = true
                depth = minimum
                quickFrames = 0
            } else if (age <= periodNs && ++quickFrames >= 4) {
                slowOutput = false
                depth = maxOf(2, minimum)
                quickFrames = 0
            } else if (age > periodNs) quickFrames = 0
        }
        return wasFull && pictures.size < depth
    }

    private companion object { const val STALL_RETRY_NS = 350_000_000L }
}
