package com.hsd.universal.dex

import android.media.AudioRecord
import android.media.AudioTimestamp
import android.os.Process
import com.hsd.universal.AppLog
import java.io.Closeable
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport

/** Each native submix has its own ring and can stop producing independently of the others. */
internal class PlaybackCaptureReader(private val mix: RegisteredPlaybackMix, private val onOverrun: () -> Unit) : Closeable {
    private val mixer = PlaybackVolumeMixer(mix.stems.map { it.guidance })
    private val consumer = Thread.currentThread()
    private val failure = AtomicReference<Exception?>()
    @Volatile private var stopped = false
    private var discardBeforeUs = 0L
    private val threads = mix.records.mapIndexed { index, record ->
        Thread({ drain(index, record) }, "hsd-playback-$index")
    }.also { readers -> readers.forEach(Thread::start) }

    private fun drain(index: Int, record: AudioRecord) {
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            val capacity = record.bufferSizeInFrames
            val readBudget = PlaybackReadBudget(capacity * 1_000_000L / DesktopAudioPolicy.SAMPLE_RATE)
            val clock = DesktopAudioClock(bufferCapacityFrames = capacity)
            val frames = DesktopPcmFrames()
            val input = ByteArray(DesktopAudioPolicy.READ_BYTES)
            val stamp = AudioTimestamp()
            var samples = 0L
            var reads = 0L
            var lastReadUs = 0L
            var lastReportUs = 0L
            var maxGapUs = 0L
            var maxDiscontinuityUs = 0L
            while (!stopped) {
                val count = record.read(input, 0, input.size, AudioRecord.READ_BLOCKING)
                if (stopped) return
                check(count >= 0) { "AudioRecord read failed: $count" }
                if (count == 0) { Thread.sleep(4); continue }
                val pcm = frames.append(input, count)
                if (pcm.isEmpty()) continue
                val timed = try {
                    record.getTimestamp(stamp, AudioTimestamp.TIMEBASE_MONOTONIC) == AudioRecord.SUCCESS
                } catch (_: UnsupportedOperationException) { false }
                val arrived = System.nanoTime() / 1000
                val at = clock.read(pcm.size / 4, arrived, stamp.framePosition.takeIf { timed },
                    (stamp.nanoTime / 1000).takeIf { timed })
                if (!mix.stems[index].guidance) DesktopAudioState.nativeMediaMeter?.accept(pcm, arrived * 1000)
                synchronized(mixer) {
                    if (at >= discardBeforeUs) clock.correctFutureSamples(mixer.offer(index, pcm, at, arrived))
                }
                LockSupport.unpark(consumer)
                val gap = if (lastReadUs == 0L) 0 else arrived - lastReadUs
                lastReadUs = arrived
                val durationUs = (pcm.size / 4) * 1_000_000L / DesktopAudioPolicy.SAMPLE_RATE
                if (readBudget.underPressure(arrived, gap, durationUs, pcm.any { it != 0.toByte() })) onOverrun()
                samples += pcm.size / 4
                reads++
                maxGapUs = maxOf(maxGapUs, gap)
                maxDiscontinuityUs = maxOf(maxDiscontinuityUs, clock.discontinuityUs)
                // Summarize stalls instead of holding up the recorder with a line for every overdue read.
                if (reads <= 3 || reads == 50L || arrived - lastReportUs >= 5_000_000) {
                    AppLog.i("DESKTOP AUDIO CLOCK: route=$index read=$reads frames=${pcm.size / 4} total=$samples " +
                        "buffer=$capacity timed=$timed position=${stamp.framePosition} captureAgeUs=${arrived - at} " +
                        "maxReadGapUs=$maxGapUs maxDiscontinuityUs=$maxDiscontinuityUs", false)
                    maxGapUs = 0
                    maxDiscontinuityUs = 0
                    lastReportUs = arrived
                }
            }
        } catch (e: Exception) {
            if (!stopped) failure.compareAndSet(null, e)
            LockSupport.unpark(consumer)
        }
    }

    fun read(music: Int, navigation: Int): PlaybackVolumeMixer.Output? {
        failure.get()?.let { throw it }
        val output = synchronized(mixer) {
            mixer.poll(System.nanoTime() / 1000, music, navigation, mix.navigationState())
        }
        if (output == null) LockSupport.parkNanos(20_000_000)
        return output
    }

    fun discard() = synchronized(mixer) {
        discardBeforeUs = System.nanoTime() / 1000
        mixer.discard()
    }

    override fun close() {
        stopped = true
        // Stop wakes a blocking native read, including a permanently idle guidance route.
        mix.records.forEach { record -> runCatching { record.stop() } }
        var interrupted = false
        for (thread in threads) while (thread.isAlive) {
            try { thread.join() } catch (_: InterruptedException) { interrupted = true }
        }
        if (interrupted) Thread.currentThread().interrupt()
    }
}

/** Reserve room for the next blocking read while audible playback is in flight. */
internal class PlaybackReadBudget(private val ringUs: Long) {
    private var previousAudible = false
    private var lastNearMissUs: Long? = null

    fun underPressure(nowUs: Long, gapUs: Long, packetUs: Long, audible: Boolean): Boolean {
        val active = audible || previousAudible
        previousAudible = audible
        if (!active) {
            lastNearMissUs = null
            return false
        }
        // A ring as small as one packet normally fills on every successful read.
        val hard = maxOf(ringUs, 2 * packetUs)
        if (gapUs >= hard) {
            lastNearMissUs = null
            return true
        }
        if (gapUs < maxOf(hard - packetUs, 2 * packetUs)) return false
        val previous = lastNearMissUs
        lastNearMissUs = nowUs
        return previous != null && nowUs - previous <= 1_000_000
    }
}
