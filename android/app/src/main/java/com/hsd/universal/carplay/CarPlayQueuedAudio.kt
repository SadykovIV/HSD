package com.hsd.universal.carplay

import com.hsd.universal.AutoPcmQueue

/** Each negotiated output owns a bounded sender; codec flush cannot block capture or another stream. */
internal class CarPlayQueuedAudio(private val sender: CarPlayAudio,
    private val failed: (Exception) -> Unit) : AutoCloseable {
    private val queue = AutoPcmQueue(synchronize = false, maxLatenessUs = sender.sendWindowUs) { pcm, pts, current ->
        if (current()) try { sender.offer(pcm, pts) } catch (error: Exception) { failed(error) }
    }.apply { setPlaying(true) }

    fun offer(pcm: ByteArray, ptsUs: Long) = queue.enqueue(pcm, ptsUs)
    val discardedPackets: Long get() = queue.discardedPackets()

    fun endSegment() = queue.afterPending { current ->
        if (current()) try { sender.endSegment() } catch (error: Exception) { failed(error) }
    }

    override fun close() = queue.close()
}
