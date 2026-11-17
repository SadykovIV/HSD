package com.hsd.universal

import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import com.hsd.universal.link.ReceiverAudioCalibration

/** Present captured PCM on the same monotonic clock as completed video frames. */
internal class AutoPcmQueue(private val calibration: ReceiverAudioCalibration,
                            private val synchronize: Boolean = true,
                            private val emit: (ByteArray, Long) -> Unit) {
    private data class Packet(val buffer: ByteArray, val ptsUs: Long, val dueUs: Long)
    private val thread = HandlerThread("hsd-universal-pcm").apply { start() }
    private val worker = Handler(thread.looper)
    private val queue = ArrayDeque<Packet>()
    private val wake = Runnable { drain() }
    private var bytes = 0
    private var videoLagUs: Long? = null
    private var trim = 0
    private var closed = false
    private var playing = false

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
        val selected = if (synchronize) calibration.milliseconds() else 0
        if (trim != selected) { clear(); trim = selected }
        val due = ptsUs + (if (synchronize) videoLagUs ?: 0L else 0L) + selected * 1000L
        val now = System.nanoTime() / 1000
        if (due < now - 150_000 || due > now + 3_000_000 || pcm.size > MAX_BYTES) return false
        while (queue.isNotEmpty() && (bytes + pcm.size > MAX_BYTES || queue.size >= 512)) {
            bytes -= queue.removeFirst().buffer.size
        }
        queue.addLast(Packet(pcm, ptsUs, due))
        bytes += pcm.size
        if (playing && queue.size == 1) schedule(due, now)
        return true
    }

    private fun schedule(due: Long, now: Long) {
        worker.postAtTime(wake, SystemClock.uptimeMillis() + (due - now + 999).coerceAtLeast(0) / 1000)
    }

    @Synchronized private fun drain() {
        while (playing && !closed && queue.isNotEmpty()) {
            val packet = queue.first()
            val now = System.nanoTime() / 1000
            if (packet.dueUs > now) { schedule(packet.dueUs, now); return }
            queue.removeFirst()
            bytes -= packet.buffer.size
            if (now - packet.dueUs <= 150_000) emit(packet.buffer, packet.dueUs)
        }
    }

    @Synchronized fun clear() { worker.removeCallbacks(wake); queue.clear(); bytes = 0 }
    @Synchronized fun close() { closed = true; clear(); thread.quitSafely() }

    private companion object { const val MAX_BYTES = 512 * 1024 }
}
