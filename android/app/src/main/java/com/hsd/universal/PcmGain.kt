package com.hsd.universal

/** A short frame-wise ramp avoids clicks when the receiver asks media to duck/unduck. */
internal class PcmGain(initial: Double = 1.0) {
    init { require(initial in 0.0..1.0) }
    var target = initial
        set(value) { require(value in 0.0..1.0); field = value }
    private var current = initial

    fun advanceSilence(frames: Int, sampleRate: Int) {
        val step = frames / (sampleRate * 0.02)
        current = if (current < target) minOf(target, current + step) else maxOf(target, current - step)
    }

    fun apply(pcm: ByteArray, sampleRate: Int, channels: Int): ByteArray {
        validate(pcm, sampleRate, channels)
        if (current == 1.0 && target == 1.0) return pcm
        return ByteArray(pcm.size).also { scale(pcm, it, sampleRate, channels) }
    }

    /** Only for scratch storage owned by the mixer. Capture buffers can be shared by several sinks. */
    fun applyInPlace(pcm: ByteArray, sampleRate: Int, channels: Int) {
        validate(pcm, sampleRate, channels)
        if (current != 1.0 || target != 1.0) scale(pcm, pcm, sampleRate, channels)
    }

    private fun validate(pcm: ByteArray, sampleRate: Int, channels: Int) {
        require(sampleRate > 0 && channels in 1..Int.MAX_VALUE / 2 && pcm.size % (channels * 2) == 0)
    }

    private fun scale(pcm: ByteArray, output: ByteArray, sampleRate: Int, channels: Int) {
        val step = 1.0 / (sampleRate * 0.02) // 20 ms for the full gain range
        var i = 0
        while (i < pcm.size && current != target) {
            current = if (current < target) minOf(target, current + step) else maxOf(target, current - step)
            val end = i + channels * 2
            while (i < end) {
                writeSample(pcm, output, i, current)
                i += 2
            }
        }
        when (current) {
            0.0 -> if (output === pcm) output.fill(0, i)
            1.0 -> if (output !== pcm) pcm.copyInto(output, i, i)
            else -> while (i < pcm.size) {
                writeSample(pcm, output, i, current)
                i += 2
            }
        }
    }

    private fun writeSample(pcm: ByteArray, out: ByteArray, i: Int, gain: Double) {
        val sample = (pcm[i].toInt() and 255) or (pcm[i + 1].toInt() shl 8)
        val value = (sample * gain).toInt()
        out[i] = value.toByte()
        out[i + 1] = (value shr 8).toByte()
    }
}
