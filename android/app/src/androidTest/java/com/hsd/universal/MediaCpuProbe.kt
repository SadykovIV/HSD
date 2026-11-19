package com.hsd.universal

import android.os.Bundle
import android.os.Debug
import com.hsd.universal.dex.DesktopTouchState
import com.hsd.universal.link.AudioQueueAdmission
import com.hsd.universal.link.QueuedTransportPacket
import java.util.concurrent.DelayQueue

/** Uses only methods present in rc8 so the same test APK measures both installed versions. */
internal fun measureMediaCpu(): Bundle {
    val result = Bundle()
    fun measure(name: String, iterations: Int, run: () -> Int) {
        var checksum = 0L
        fun sample(): Long {
            val start = Debug.threadCpuTimeNanos()
            repeat(iterations) { checksum += run() }
            return (Debug.threadCpuTimeNanos() - start) / iterations
        }
        repeat(3) { sample() }
        val times = List(9) { sample() }.sorted()
        result.putLong(name + "_median_ns", times[4])
        result.putLong(name + "_checksum", checksum)
    }
    val pcm = ByteArray(3840) { (it * 31).toByte() }
    for ((name, gain) in listOf("mute" to 0.0, "gain50" to 0.5)) {
        val level = PcmGain(gain)
        measure(name, 600) { level.apply(pcm, 48000, 2)[112].toInt() }
    }
    val state = DesktopTouchState()
    val ids = intArrayOf(0)
    val coords = floatArrayOf(120f)
    state.events(0, 0, ids, coords, coords, 1280, 720)
    measure("move", 12000) { state.events(2, 0, ids, coords, coords, 1280, 720).single().points.size }
    val queue = DelayQueue<QueuedTransportPacket>()
    val block = ByteArray(1920)
    repeat(4) { queue.offer(QueuedTransportPacket(Ch.MEDIA, block, 200)) }
    measure("admit_four_pcm", 2000) {
        val incoming = QueuedTransportPacket(Ch.MEDIA, block, 200)
        AudioQueueAdmission.offer(queue, incoming, 100, {}, {})
        val count = queue.size
        check(queue.remove(incoming))
        count
    }
    return result
}
