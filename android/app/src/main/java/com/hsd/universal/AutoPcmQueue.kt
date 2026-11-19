package com.hsd.universal

import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import com.hsd.universal.link.ReceiverAudioCalibration

/** Present captured PCM on the same monotonic clock as completed video frames. */
internal class AutoPcmQueue(private val calibration: ReceiverAudioCalibration? = null,
                            private val synchronize: Boolean = true,
                            private val maxLatenessUs: Long = DEFAULT_MAX_LATENESS_US,
                            private val emit: (ByteArray, Long, () -> Boolean) -> Unit) {
    private data class Packet(val buffer: ByteArray?, val dueUs: Long, val finish: ((() -> Boolean) -> Unit)? = null)
    private val thread = HandlerThread("hsd-universal-pcm", android.os.Process.THREAD_PRIORITY_AUDIO).apply { start() }
    private val worker = Handler(thread.looper)
    private val queue = ArrayDeque<Packet>()
    private val wake = Runnable { drain() }
    private var bytes = 0
    private var videoLagUs: Long? = null
    private var trim = 0
    private var closed = false
    private var playing = false
    private var generation = 0L
    private var discarded = 0L
    @Volatile private var delivered = 0L
    @Volatile private var maxEmitUs = 0L

    @Synchronized fun setPlaying(enabled: Boolean) {
        if (!enabled) clear()
        if (playing == enabled || closed) return
        playing = enabled
        if (enabled && queue.isNotEmpty()) schedule(queue.first().dueUs, System.nanoTime() / 1000)
    }

    @Synchronized fun videoWritten(ptsUs: Long) {
        val age = System.nanoTime() / 1000 - ptsUs
        // Once playing, moving the audio clock to follow video jitter inserts gaps or bursts.
        if (synchronize && videoLagUs == null && age in 0..500_000) videoLagUs = age
    }

    @Synchronized fun enqueue(pcm: ByteArray, ptsUs: Long): Boolean {
        if (closed) return false
        val selected = if (synchronize) calibration?.milliseconds() ?: 0 else 0
        if (trim != selected) { clear(); trim = selected }
        val due = ptsUs + ReceiverAudioCalibration.audioDelayUs(videoLagUs ?: 0L, selected)
        val now = System.nanoTime() / 1000
        if (due < now - maxLatenessUs || due > now + 3_000_000 || pcm.size > MAX_BYTES) { discarded++; return false }
        while (queue.isNotEmpty() && (bytes + pcm.size > MAX_BYTES || queue.size >= 512)) {
            // A stream boundary must survive overload, or the receiver can remain in speech mode.
            val oldest = queue.iterator()
            var removed = false
            while (oldest.hasNext()) {
                val buffer = oldest.next().buffer ?: continue
                bytes -= buffer.size
                oldest.remove()
                removed = true
                break
            }
            discarded++
            if (!removed) return false
        }
        queue.addLast(Packet(pcm, due))
        bytes += pcm.size
        if (playing && queue.size == 1) schedule(due, now)
        return true
    }

    /** Ordered stream boundary. Codec tail and STOP follow already accepted speech samples. */
    @Synchronized fun afterPending(action: (() -> Boolean) -> Unit) {
        if (closed) return
        val due = queue.lastOrNull()?.dueUs ?: (System.nanoTime() / 1000)
        queue.addLast(Packet(null, due, action))
        if (playing && queue.size == 1) schedule(due, System.nanoTime() / 1000)
    }

    private fun schedule(due: Long, now: Long) {
        worker.postAtTime(wake, SystemClock.uptimeMillis() + (due - now + 999).coerceAtLeast(0) / 1000)
    }

    private fun drain() {
        val drainGeneration = synchronized(this) { generation }
        while (true) {
            val item = synchronized(this) {
                // Yield after invalidation so the worker's codec reset runs before new-epoch PCM.
                if (generation != drainGeneration || !playing || closed || queue.isEmpty()) return
                val packet = queue.first()
                val now = System.nanoTime() / 1000
                if (packet.dueUs > now) { schedule(packet.dueUs, now); return }
                queue.removeFirst()
                bytes -= packet.buffer?.size ?: 0
                Triple(packet, generation, now)
            }
            val (packet, epoch, now) = item
            val current = { synchronized(this) { !closed && playing && generation == epoch } }
            // Encoding and transport I/O must never hold the capture queue's monitor.
            // A focus loss invalidates in-flight encoded output as well as queued PCM.
            if (current()) {
                if (packet.buffer != null && now - packet.dueUs <= maxLatenessUs) {
                    emit(packet.buffer, packet.dueUs, current)
                    delivered++
                    maxEmitUs = maxOf(maxEmitUs,System.nanoTime()/1000-now)
                } else if (packet.finish != null) packet.finish.invoke(current)
                else synchronized(this) { discarded++ }
            }
        }
    }

    @Synchronized fun summary(): String = "queued=${queue.size} bytes=$bytes emitted=$delivered dropped=$discarded maxEmitUs=$maxEmitUs"
    @Synchronized fun discardedPackets(): Long = discarded

    @Synchronized fun clear() { generation++; worker.removeCallbacks(wake); queue.clear(); bytes = 0 }
    @Synchronized fun resetCodec(action: () -> Unit) { clear(); if (!closed) worker.post(action) }
    @Synchronized fun close(cleanup: () -> Unit = {}) {
        if (closed) return
        closed = true; clear(); worker.post(cleanup); thread.quitSafely()
    }

    companion object {
        const val DEFAULT_MAX_LATENESS_US = 150_000L
        private const val MAX_BYTES = 512 * 1024
    }
}
