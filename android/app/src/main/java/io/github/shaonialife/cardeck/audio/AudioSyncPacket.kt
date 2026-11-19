package io.github.shaonialife.cardeck.audio

import java.nio.ByteBuffer

/** AirPlay's 20-byte time announcement (PT 84), sent to the negotiated control port.
 * The first RTP value is the sample playing at NTP time; the last is the source sample
 * at that instant. Their difference is the negotiated playout latency, in samples.
 * Bytes 2..3 are the fixed RTCP word length, not the audio packet sequence number.
 */
object AudioSyncPacket {
    const val SIZE = 20

    fun build(sourceRtpTimestamp: Long, latencyFrames: Long, ntp: ByteArray, first: Boolean): ByteArray {
        require(ntp.size == 8)
        require(latencyFrames in 0..Int.MAX_VALUE.toLong())
        return ByteBuffer.allocate(SIZE).apply {
            put(if (first) 0x90.toByte() else 0x80.toByte())
            put(0xd4.toByte())
            putShort((SIZE / 4 - 1).toShort())
            putInt((sourceRtpTimestamp - latencyFrames).toInt())
            put(ntp)
            putInt(sourceRtpTimestamp.toInt())
        }.array()
    }
}
