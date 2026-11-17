package com.hsd.universal

/** A short frame-wise ramp avoids clicks when the receiver asks media to duck/unduck. */
internal class PcmGain(initial: Double = 1.0) {
    init { require(initial in 0.0..1.0) }
    var target = initial
        set(value) { require(value in 0.0..1.0); field = value }
    private var current = initial

    fun apply(pcm: ByteArray, sampleRate: Int, channels: Int): ByteArray {
        require(sampleRate > 0 && channels > 0 && pcm.size % (channels * 2) == 0)
        if (current == 1.0 && target == 1.0) return pcm
        val output = pcm.copyOf()
        val step = 1.0 / (sampleRate * 0.02) // 20 ms for the full gain range
        for (frame in output.indices step channels * 2) {
            current = if (current < target) minOf(target, current + step) else maxOf(target, current - step)
            for (channel in 0 until channels) {
                val i = frame + channel * 2
                val sample = ((pcm[i].toInt() and 255) or (pcm[i + 1].toInt() shl 8)).toShort().toInt()
                val value = (sample * current).toInt()
                output[i] = value.toByte(); output[i + 1] = (value shr 8).toByte()
            }
        }
        return output
    }
}
