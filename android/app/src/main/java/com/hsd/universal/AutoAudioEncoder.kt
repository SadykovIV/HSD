package com.hsd.universal

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import java.io.Closeable

/** Raw AAC-LC or ADTS. AAP needs a ten-byte prefix; CarPlay encrypts raw access units directly. */
internal class AutoAudioEncoder(private val prefix: Int = 10, private val channels: Int = 2,
    private val profile: Int = MediaCodecInfo.CodecProfileLevel.AACObjectLC) : Closeable {
    private var encoder: MediaCodec? = null
    private var rate = 0
    private val info = MediaCodec.BufferInfo()
    private var configuration: ByteArray? = null
    private var eos = false

    /** ELD format bits imply 480 samples in the receiver. Reject an incompatible platform codec. */
    fun verifyFrameLength(hz: Int, expected: Int) {
        if (profile != MediaCodecInfo.CodecProfileLevel.AACObjectELD) return
        prepare(hz)
        repeat(6) {
            offer(ByteArray(expected * channels * 2), 0, it * expected * 1_000_000L / hz, hz, false) { _, _ -> }
        }
        finish(hz, false) { _, _ -> }
        val actual = AacFrameFormat.samples(checkNotNull(configuration) { "AAC encoder supplied no configuration" })
        check(actual == expected) { "AAC-ELD frame length $actual, receiver requires $expected" }
        close()
        prepare(hz)
    }

    /** Flush codec lookahead at a real stream boundary, rather than truncate the last syllable. */
    @Synchronized fun finish(hz: Int, adts: Boolean, write: (ByteArray, Long) -> Unit) {
        val codec = encoder ?: return
        val deadline = System.nanoTime() + 500_000_000L
        var queued = false
        while (!eos && System.nanoTime() < deadline) {
            drain(codec, hz, adts, write)
            if (!queued) {
                val index = codec.dequeueInputBuffer(2000)
                if (index >= 0) {
                    codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    queued = true
                }
            }
            if (queued) drain(codec, hz, adts, write, 2000)
        }
        check(eos) { "AAC encoder did not finish" }
    }

    @Synchronized
    fun offer(
        pcm: ByteArray,
        offset: Int,
        pts: Long,
        hz: Int,
        adts: Boolean,
        write: (ByteArray, Long) -> Unit,
    ) {
        prepare(hz)
        require(channels in 1..2 && (pcm.size - offset) % (channels * 2) == 0)
        require(!adts || profile == MediaCodecInfo.CodecProfileLevel.AACObjectLC)
        val codec = checkNotNull(encoder)
        var pos = offset
        var deadline = System.nanoTime() + 100_000_000L
        while (pos < pcm.size) {
            // Return output buffers before waiting for input: some codecs share a bounded pool.
            // Progress at either end resets the stall deadline; encoding/network work is not a stall.
            if (drain(codec, hz, adts, write)) deadline = System.nanoTime() + 100_000_000L
            val index = codec.dequeueInputBuffer(2000)
            if (index >= 0) {
                val input = checkNotNull(codec.getInputBuffer(index))
                val capacity = input.remaining() / (channels * 2) * (channels * 2)
                val length = minOf(capacity, pcm.size - pos)
                input.put(pcm, pos, length)
                codec.queueInputBuffer(index, 0, length, pts + (pos - offset) * 1_000_000L / (2 * channels * hz), 0)
                pos += length
                deadline = System.nanoTime() + 100_000_000L
            }
            if (drain(codec, hz, adts, write)) deadline = System.nanoTime() + 100_000_000L
            check(pos == pcm.size || System.nanoTime() < deadline) { "AAC encoder did not accept PCM" }
        }
    }

    @Synchronized
    fun prepare(hz: Int) {
        if (hz != rate) close()
        if (encoder != null) return
        try {
            MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).also {
                    encoder = it
                    val format =
                        MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, hz, channels)
                    format.setInteger(
                        MediaFormat.KEY_AAC_PROFILE,
                        profile,
                    )
                    format.setInteger(MediaFormat.KEY_BIT_RATE, if (channels == 2) 128_000 else 64_000)
                    it.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                    it.start()
                    rate = hz
                }
        } catch (e: Exception) { close(); throw e }
    }

    private fun drain(codec: MediaCodec, hz: Int, adts: Boolean, write: (ByteArray, Long) -> Unit, waitUs: Long = 0): Boolean {
        var progressed = false
        while (true) {
            val index = codec.dequeueOutputBuffer(info, waitUs)
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                codec.outputFormat.getByteBuffer("csd-0")?.let { b ->
                    configuration = ByteArray(b.remaining()).also { b.duplicate().get(it) }
                }
                continue
            }
            if (index < 0) break
            progressed = true
            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) eos = true
            var encoded: ByteArray? = null
            val pts = info.presentationTimeUs
            try {
                if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                    configuration = ByteArray(info.size).also { bytes ->
                        checkNotNull(codec.getOutputBuffer(index)).duplicate().apply { position(info.offset); get(bytes) }
                    }
                } else if (info.size > 0) {
                    val start = prefix + if (adts) 7 else 0
                    val packet = ByteArray(start + info.size)
                    if (adts) {
                        val n = info.size + 7
                        val frequency = frequencies.indexOf(hz)
                        require(frequency >= 0 && n <= 8191)
                        packet[prefix] = 0xff.toByte()
                        packet[prefix+1] = 0xf1.toByte()
                        packet[prefix+2] = (0x40 or (frequency shl 2) or (channels ushr 2)).toByte()
                        packet[prefix+3] = (((channels and 3) shl 6) or (n ushr 11)).toByte()
                        packet[prefix+4] = (n ushr 3).toByte()
                        packet[prefix+5] = ((n shl 5) or 31).toByte()
                        packet[prefix+6] = 0xfc.toByte()
                    }
                    checkNotNull(codec.getOutputBuffer(index)).apply {
                        position(info.offset)
                        get(packet, start, info.size)
                    }
                    encoded = packet
                }
            } finally {
                codec.releaseOutputBuffer(index, false)
            }
            // Only our copy crosses into the transport; never retain a codec buffer during I/O.
            encoded?.let { write(it, pts) }
        }
        return progressed
    }

    @Synchronized
    override fun close() {
        encoder?.let {
            runCatching { it.stop() }
            runCatching { it.release() }
        }
        encoder = null
        configuration = null
        eos = false
        rate = 0
    }

    private companion object {
        val frequencies =
            listOf(
                96000,
                88200,
                64000,
                48000,
                44100,
                32000,
                24000,
                22050,
                16000,
                12000,
                11025,
                8000,
                7350,
            )
    }
}
