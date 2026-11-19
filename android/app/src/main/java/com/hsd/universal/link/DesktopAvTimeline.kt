package com.hsd.universal.link

/** One access unit can span multiple writes. Once it begins, finish every fragment. */
interface VideoWrite {
    /** A not-yet-started frame can wait for its capture time on the shared A/V clock. */
    fun readyInUs(): Long = 0
    /** Exclude deliberate A/V pacing from transport congestion measurements. */
    fun congestionAgeMs(queuedMs: Long): Long = queuedMs
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
                                 private val synchronize: Boolean = true,
                                 private val emit: (Int, ByteArray, Long) -> Unit) {
    private data class Pcm(val pts: Long, val chan: Int, val bytes: ByteArray)
    private val audio = ArrayDeque<Pcm>()
    private var audioBytes = 0
    @Volatile private var epoch = 0L
    @Volatile private var lastPicture: Long? = null
    private var decoding = false
    private var needKey = false
    private var keySequence = 0L
    private var projecting = false
    @Volatile private var closed = false
    private var period = 33_334L
    private var trimMs = 0
    private val audioDelayUs get() = ReceiverAudioCalibration.audioDelayUs(audioLag, trimMs)
    private var lag = 0L
    private var initialVideoLag = 0L
    @Volatile private var audioLag = 0L
    @Volatile private var calibrationUntil = 0L
    private var firstWriteUs = 0L
    private var ordinaryFrames = 0
    private var fastestOrdinary = Long.MAX_VALUE
    private var sampleWindowStart = 0L
    private var sampleWindowPeak = 0L
    private var sampleWindowFrames = 0
    private var steadyTarget = Long.MAX_VALUE
    private var lastSlew = 0L
    private var expiredVideo = 0L
    private var lateWrites = 0L
    private val pendingDownUs = java.util.concurrent.atomic.AtomicLong(-1)
    private var touchSamples = 0
    private var longestTouchUs = 0L
    private var touchToSurfaceUs = 0L
    private var touchFrameEncodeUs = 0L
    private var touchFrameQueueUs = 0L
    private var touchFrameWriteUs = 0L
    fun touchDown() { pendingDownUs.compareAndSet(-1, nowUs()) }
    private var skippedAudio = 0L
    // Allow ordinary codec/transport latency plus jitter. A fixed cutoff measured from
    // capture would repeatedly break a healthy reference chain on slower encoders.
    private val videoBudget get() = (maxOf(AUDIO_SLACK, 2 * period) + initialVideoLag).coerceAtMost(MAX_FRAME_AGE)
    // Establish a capture/playout offset once. After startup, late pictures must recover
    // towards the running audio clock instead of moving that clock further behind live input.
    private fun admissionBudget(key: Boolean) = if (key && lastPicture == null) MAX_FRAME_AGE else videoBudget

    @Synchronized fun reset(active: Boolean) {
        epoch++; lastPicture = null; decoding = false; needKey = false
        projecting = active; lag = 0; initialVideoLag = 0; audioLag = 0
        calibrationUntil = 0; firstWriteUs = 0; ordinaryFrames = 0; fastestOrdinary = Long.MAX_VALUE; clearAudio()
        resetSteadySampling()
        pendingDownUs.set(-1); touchSamples = 0; longestTouchUs = 0
    }
    /** Camera/native-screen transitions retire video references, but music keeps its sample timeline. */
    @Synchronized fun videoActive(active: Boolean) {
        if (!active && calibrationUntil != 0L) finishCalibration()
        resetSteadySampling()
        pendingDownUs.set(-1)
        epoch++; decoding = false; needKey = active
        projecting = active
    }
    @Synchronized fun close() { closed = true; reset(false) }
    @Synchronized fun clearAudio() { audio.clear(); audioBytes = 0 }
    @Synchronized fun drainDueAudio() { if (audio.isNotEmpty()) flush(nowUs()) }

    @Synchronized fun frame(pts: Long, key: Boolean, fps: Int, idleWireAtOutput: Boolean = false,
                            transferUs: Long = 0): VideoWrite? {
        if (closed || !projecting) return null
        period = 1_000_000L / fps.coerceIn(1, 60)
        val encodedAt = nowUs()
        val age = encodedAt - pts
        // With an idle transmitter, a slow live codec has just delivered its newest
        // reference. Dropping it would discard the whole GOP and stall the receiver.
        // Give it one period to begin writing, still bounding queue residence.
        val budget = if (idleWireAtOutput && pts > (lastPicture ?: Long.MIN_VALUE))
            maxOf(admissionBudget(key), age + period)
        else admissionBudget(key)
        if (age > budget || (needKey && !key)) { needKey = true; expiredVideo++; return null }
        val generation = epoch
        if (key) { needKey = false; keySequence++ }
        val reference = keySequence
        val framePeriod = period
        return object : VideoWrite {
            @Volatile private var accepted: Boolean? = null
            private var startedUs = 0L
            // DelayQueue asks under its own lock: never acquire the timeline lock here.
            // PCM emission takes locks in the opposite direction (timeline -> queue).
            private fun scheduledStart() = pts + (audioLag - transferUs.coerceAtLeast(0)).coerceAtLeast(0)
            override fun readyInUs(): Long =
                if (!synchronize || calibrationUntil != 0L || accepted != null || generation != epoch || closed || lastPicture == null) 0L
                else (scheduledStart() - nowUs()).coerceAtLeast(0)
            override fun congestionAgeMs(queuedMs: Long): Long = synchronized(this@DesktopAvTimeline) {
                if (!synchronize || calibrationUntil != 0L || lastPicture == null) queuedMs
                else minOf(queuedMs, ((nowUs() - scheduledStart()) / 1000).coerceAtLeast(0))
            }
            override fun begin(): Boolean = synchronized(this@DesktopAvTimeline) {
                accepted?.let { return@synchronized it }
                if (generation != epoch || closed) false else {
                    startedUs = nowUs()
                    val valid = startedUs - pts <= budget && (key || decoding)
                    if (!valid) {
                        decoding = false
                        // A newer IDR may already be queued after this frame.
                        if (reference == keySequence) needKey = true
                        expiredVideo++
                    } else if (key) decoding = true
                    valid
                }.also { accepted = it }
            }
            override fun written() = synchronized(this@DesktopAvTimeline) {
                if (generation == epoch && !closed && accepted == true) {
                    val now = nowUs()
                    val sample = (now - pts).coerceAtLeast(0)
                    if (sample > admissionBudget(key)) lateWrites++
                    lag = if (lastPicture == null || sample > lag) sample else (7 * lag + sample) / 8
                    if (lastPicture == null) {
                        // A large startup IDR may occupy the writer for several frames.
                        // Pace to capture/encode time plus at most one frame of transport.
                        initialVideoLag = minOf(sample, age.coerceAtLeast(0) + framePeriod)
                        if (synchronize) {
                            if (initialVideoLag <= framePeriod) audioLag = initialVideoLag
                            else {
                                firstWriteUs = now
                                calibrationUntil = now + minOf(AUDIO_SLACK, 3 * framePeriod)
                            }
                        }
                    } else if (calibrationUntil != 0L && !key && now <= calibrationUntil) {
                        if (pts >= firstWriteUs) {
                            // A fresh capture traversed the entire pipeline after the first write.
                            fastestOrdinary = minOf(fastestOrdinary, sample)
                            ordinaryFrames++
                            if (ordinaryFrames >= 3 || ordinaryFrames >= 2 && fastestOrdinary <= framePeriod) finishCalibration()
                        } else if (ordinaryFrames == 0) {
                            // In-flight startup pictures can all be hundreds of ms old. Give a
                            // continuously slow codec time to deliver an independent sample.
                            calibrationUntil = firstWriteUs + minOf(MAX_FRAME_AGE, initialVideoLag + 3 * framePeriod)
                        }
                    } else if (synchronize && idleWireAtOutput && !key && calibrationUntil == 0L && audioLag > 0L) {
                        // Exclude our own deliberate wait. Otherwise a slow first IDR permanently
                        // raises the minimum touch latency long after the encoder has warmed up.
                        followSteadyDelivery(now, age.coerceAtLeast(0) + (now - startedUs).coerceAtLeast(0))
                    }
                    // Every byte of this reference reached the receiver. Only an omitted
                    // access unit breaks its prediction chain, even if this one arrived late.
                    lastPicture = pts
                    val down = pendingDownUs.get()
                    if (down >= 0 && pts >= down && pendingDownUs.compareAndSet(down, -1)) {
                        touchSamples++
                        if (now - down >= longestTouchUs) {
                            longestTouchUs = now - down
                            touchToSurfaceUs = pts - down
                            touchFrameEncodeUs = age
                            touchFrameQueueUs = startedUs - encodedAt
                            touchFrameWriteUs = now - startedUs
                        }
                    }
                    flush(now)
                }
            }
        }
    }

    @Synchronized fun pcm(channel: Int, inner: ByteArray, pts: Long): Boolean {
        if (closed) return false
        val now = nowUs()
        val correction = if (synchronize) adjustmentMs().coerceIn(-ReceiverAudioCalibration.MAX_MS, ReceiverAudioCalibration.MAX_MS) else 0
        if (correction != trimMs) { clearAudio(); trimMs = correction }
        val budgetBytes = MAX_BYTES + trimMs.coerceAtLeast(0) * 224 // 48 kHz stereo 16-bit, plus packet headers
        val budgetCount = MAX_PACKETS + trimMs.coerceAtLeast(0) * 2
        if (now - pts >= AUDIO_SLACK + audioDelayUs || inner.size > budgetBytes) { skippedAudio++; return false }
        while (audio.isNotEmpty() && (audioBytes + inner.size > budgetBytes || audio.size >= budgetCount)) drop()
        audio.addLast(Pcm(pts, channel, inner)); audioBytes += inner.size
        flush(now)
        return true
    }

    private fun drop() { audioBytes -= audio.removeFirst().bytes.size; skippedAudio++ }
    private fun resetSteadySampling() {
        sampleWindowFrames = 0; sampleWindowPeak = 0; sampleWindowStart = 0
        steadyTarget = Long.MAX_VALUE; lastSlew = 0
    }
    private fun followSteadyDelivery(now: Long, intrinsic: Long) {
        if (sampleWindowStart == 0L) sampleWindowStart = now
        sampleWindowPeak = maxOf(sampleWindowPeak, intrinsic)
        sampleWindowFrames++
        if (now - sampleWindowStart >= 1_000_000L) {
            steadyTarget = if (sampleWindowFrames >= 8) sampleWindowPeak + 3_000 else Long.MAX_VALUE
            sampleWindowStart = now; sampleWindowPeak = 0; sampleWindowFrames = 0
        }
        // Shorten the shared playout offset by at most 1% of wall time. Every PCM
        // packet retains its original order and samples; an idle period earns no credit.
        if (lastSlew != 0L && steadyTarget < audioLag && intrinsic <= audioLag) {
            val step = (now - lastSlew).coerceIn(0, 100_000L) / 100
            audioLag = maxOf(steadyTarget, audioLag - step)
        }
        lastSlew = now
    }
    private fun finishCalibration() {
        // A static screen may produce no inter frames during calibration. Its startup
        // IDR alone provides no evidence for a long-term playout delay.
        audioLag = minOf(initialVideoLag, if (ordinaryFrames > 0) fastestOrdinary else period)
        calibrationUntil = 0
    }
    private fun flush(now: Long) {
        if (calibrationUntil != 0L) {
            if (now < calibrationUntil) return
            finishCalibration()
        }
        while (audio.isNotEmpty()) {
            val first = audio.first()
            val due = first.pts + audioDelayUs
            val remaining = due + AUDIO_SLACK - now
            if (remaining <= 0) { drop(); continue }
            // Align startup to the first decoded picture. After that, audio follows the
            // capture clock even while a large frame or a replacement keyframe is pending.
            if (synchronize && projecting && lastPicture == null) break
            if (due > now) break
            audio.removeFirst(); audioBytes -= first.bytes.size
            emit(first.chan, first.bytes, (remaining + 999) / 1000)
        }
    }
    @Synchronized fun diagnostics(): String {
        val pending = pendingDownUs.get()
        val waitMs = if (pending < 0) 0 else (nowUs() - pending) / 1000
        val stages = if (touchSamples == 0) "" else " downToSurfaceMs=${touchToSurfaceUs / 1000} " +
            "surfaceToEncoderMs=${touchFrameEncodeUs / 1000} queueMs=${touchFrameQueueUs / 1000} writeMs=${touchFrameWriteUs / 1000}"
        val touch = "downToNextVideoPtsWireMsMax=${longestTouchUs / 1000} samples=$touchSamples pendingDownMs=$waitMs$stages"
        touchSamples = 0; longestTouchUs = 0
        return "avSync=$synchronize captureToWireMs=${lag / 1000} audioTargetMs=${audioLag / 1000} receiverTrimMs=$trimMs staleVideo=$expiredVideo lateVideoWrites=$lateWrites stalePcm=$skippedAudio pendingPcmBytes=$audioBytes $touch"
    }
    companion object {
        private const val MAX_FRAME_AGE = 500_000L
        private const val AUDIO_SLACK = 150_000L
        private const val MAX_BYTES = 128 * 1024
        private const val MAX_PACKETS = 96
    }
}
