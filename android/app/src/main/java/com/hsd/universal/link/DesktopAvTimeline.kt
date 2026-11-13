package com.hsd.universal.link

/** One access unit can span multiple writes. Once it begins, finish every fragment. */
interface VideoWrite {
    fun begin(): Boolean
    fun written()
}

/**
 * One MONOTONIC capture timeline per projection. Learn the actual capture-to-wire time of a
 * whole video frame, not the (second-resolution) CarLife header or the encoder queue size.
 * All callbacks only enqueue into the transport; no I/O or bridge callbacks under this lock.
 */
internal class DesktopAvTimeline(private val nowUs: () -> Long,
                                 private val adjustmentMs: () -> Int = { 0 },
                                 private val emit: (Int, ByteArray, Long) -> Unit) {
    private data class Pcm(val pts: Long, val chan: Int, val bytes: ByteArray)
    private val audio = ArrayDeque<Pcm>()
    private var audioBytes = 0
    private var epoch = 0L
    private var lastPicture: Long? = null
    private var decoding = false
    private var needKey = false
    private var projecting = false
    private var closed = false
    private var period = 33_334L
    private var trim = 0L
    private var lag = 0L
    private var audioLag = 0L
    private var expiredVideo = 0L
    private var skippedAudio = 0L
    // Allow ordinary codec/transport latency plus jitter. A fixed cutoff measured from
    // capture would repeatedly break a healthy reference chain on slower encoders.
    private val videoBudget get() = (maxOf(AUDIO_SLACK, 2 * period) + lag).coerceAtMost(MAX_FRAME_AGE)
    // A reference frame can establish a new baseline after a mode change or a stall.
    // Comparing it only to the *old* latency can reject every recovery frame forever.
    private fun admissionBudget(key: Boolean) = if (key) MAX_FRAME_AGE else videoBudget

    @Synchronized fun reset(active: Boolean) {
        epoch++; lastPicture = null; decoding = false; needKey = false
        projecting = active; lag = 0; audioLag = 0; clearAudio()
    }
    @Synchronized fun close() { closed = true; reset(false) }
    @Synchronized fun clearAudio() { audio.clear(); audioBytes = 0 }
    @Synchronized fun drainDueAudio() { if (audio.isNotEmpty()) flush(nowUs()) }

    @Synchronized fun frame(pts: Long, key: Boolean, fps: Int): VideoWrite? {
        if (closed || !projecting) return null
        period = 1_000_000L / fps.coerceIn(1, 60)
        val age = nowUs() - pts
        if (age > admissionBudget(key) || (needKey && !key)) { needKey = true; expiredVideo++; return null }
        val generation = epoch
        if (key) needKey = false
        return object : VideoWrite {
            private var accepted: Boolean? = null
            override fun begin(): Boolean = synchronized(this@DesktopAvTimeline) {
                accepted?.let { return@synchronized it }
                if (generation != epoch || closed) false else {
                    val valid = nowUs() - pts <= admissionBudget(key) && (key || decoding)
                    if (!valid) { decoding = false; needKey = true; expiredVideo++ }
                    else if (key) { decoding = true; needKey = false }
                    valid
                }.also { accepted = it }
            }
            override fun written() = synchronized(this@DesktopAvTimeline) {
                if (generation == epoch && !closed && accepted == true) {
                    val now = nowUs()
                    // A blocked, already-started write cannot be cancelled. Do not use a multi-
                    // second outlier as a new playback delay; recover at a fresh reference frame.
                    if (now - pts > admissionBudget(key)) { decoding = false; needKey = true; expiredVideo++ }
                    else {
                        val sample = (now - pts).coerceAtLeast(0)
                        lag = if (lastPicture == null || sample > lag) sample else (7 * lag + sample) / 8
                        // A burst of large frames during a pinch must not insert an audible gap.
                        // Follow drift gradually once playback has a baseline.
                        audioLag = if (lastPicture == null) lag else {
                            val step = ((pts - checkNotNull(lastPicture)).coerceIn(0, 100_000L) / 20)
                            audioLag + (lag - audioLag).coerceIn(-step, step)
                        }
                        lastPicture = pts
                    }
                    flush(now)
                }
            }
        }
    }

    @Synchronized fun pcm(channel: Int, inner: ByteArray, pts: Long): Boolean {
        if (closed) return false
        val now = nowUs()
        val correction = adjustmentMs().coerceIn(0, ReceiverAudioCalibration.MAX_MS) * 1000L
        if (correction != trim) { clearAudio(); trim = correction }
        val budgetBytes = MAX_BYTES + trim * 224 / 1000 // 48 kHz stereo 16-bit, plus packet headers
        val budgetCount = MAX_PACKETS + trim / 500
        if (now - pts >= AUDIO_SLACK + audioLag + trim || inner.size > budgetBytes) { skippedAudio++; return false }
        while (audio.isNotEmpty() && (audioBytes + inner.size > budgetBytes || audio.size >= budgetCount)) drop()
        audio.addLast(Pcm(pts, channel, inner)); audioBytes += inner.size
        flush(now)
        return true
    }

    private fun drop() { audioBytes -= audio.removeFirst().bytes.size; skippedAudio++ }
    private fun flush(now: Long) {
        while (audio.isNotEmpty()) {
            val first = audio.first()
            val remaining = first.pts + audioLag + trim + AUDIO_SLACK - now
            if (remaining <= 0) { drop(); continue }
            // Align startup to the first decoded picture. After that, audio follows the
            // capture clock even while a large frame or a replacement keyframe is pending.
            if (projecting && lastPicture == null) break
            if (first.pts + audioLag + trim > now) break
            audio.removeFirst(); audioBytes -= first.bytes.size
            emit(first.chan, first.bytes, (remaining + 999) / 1000)
        }
    }
    @Synchronized fun diagnostics() = "captureToWireMs=${lag / 1000} receiverTrimMs=${trim / 1000} staleVideo=$expiredVideo stalePcm=$skippedAudio pendingPcmBytes=$audioBytes"
    companion object {
        private const val MAX_FRAME_AGE = 500_000L
        private const val AUDIO_SLACK = 150_000L
        private const val MAX_BYTES = 128 * 1024
        private const val MAX_PACKETS = 96
    }
}
