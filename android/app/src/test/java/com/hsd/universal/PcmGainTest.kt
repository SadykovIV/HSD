package com.hsd.universal

import org.junit.Assert.*
import org.junit.Test

class PcmGainTest {
    private fun tone(frames: Int) = ByteArray(frames * 4).also { bytes ->
        for (i in bytes.indices step 4) {
            bytes[i] = 0x10; bytes[i + 1] = 0x27 // 10000
            bytes[i + 2] = 0xf0.toByte(); bytes[i + 3] = 0xd8.toByte() // -10000
        }
    }
    private fun sample(pcm: ByteArray, at: Int) = ((pcm[at].toInt() and 255) or (pcm[at + 1].toInt() shl 8)).toShort().toInt()
    @Test fun duckRampPreservesStereoAndRestoresWithoutMutatingCapture() {
        val gain = PcmGain()
        val input = tone(2400)
        assertSame(input, gain.apply(input, 48000, 2))
        gain.target = 0.2
        val lowered = gain.apply(input, 48000, 2)
        assertEquals(10000, sample(input, 0))
        assertEquals(2000, sample(lowered, lowered.size - 4))
        assertEquals(-2000, sample(lowered, lowered.size - 2))
        for (i in 4 until lowered.size step 4) assertTrue(kotlin.math.abs(sample(lowered, i) - sample(lowered, i - 4)) <= 11)
        gain.target = 1.0
        val restored = gain.apply(input, 48000, 2)
        assertEquals(10000, sample(restored, restored.size - 4))
        assertEquals(-10000, sample(restored, restored.size - 2))
    }
    @Test fun handlesSignedExtremesAndEmptyBuffersAtEveryNegotiatedRate() {
        for (rate in listOf(16000, 24000, 44100, 48000)) {
            val gain = PcmGain().apply { target = 0.0 }
            assertEquals(0, gain.apply(byteArrayOf(), rate, 2).size)
            val bytes = ByteArray(rate / 10 * 4) { i -> when (i % 4) { 0 -> 0xff.toByte(); 1 -> 0x7f; 2 -> 0; else -> 0x80.toByte() } }
            val out = gain.apply(bytes, rate, 2)
            assertEquals(0, sample(out, out.size - 4))
            assertEquals(0, sample(out, out.size - 2))
        }
    }
}
