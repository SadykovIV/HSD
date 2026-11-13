package com.hsd.universal

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import java.io.Closeable

/** Raw AAC-LC or ADTS. AAP needs a ten-byte prefix; CarPlay encrypts raw access units directly. */
internal class AutoAudioEncoder(private val prefix: Int = 10) : Closeable {
    private var encoder: MediaCodec? = null
    private var rate = 0
    private val info = MediaCodec.BufferInfo()

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
        val codec = checkNotNull(encoder)
        var pos = offset
        var deadline = System.nanoTime() + 100_000_000L
        while (pos < pcm.size) {
            val index = codec.dequeueInputBuffer(2000)
            if (index >= 0) {
                val input = checkNotNull(codec.getInputBuffer(index))
                val length = minOf(input.remaining(), pcm.size - pos)
                input.put(pcm, pos, length)
                codec.queueInputBuffer(index, 0, length, pts + (pos - offset) * 1_000_000L / (4 * hz), 0)
                pos += length
                deadline = System.nanoTime() + 100_000_000L
            }
            drain(codec, hz, adts, write)
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
                        MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, hz, 2)
                    format.setInteger(
                        MediaFormat.KEY_AAC_PROFILE,
                        MediaCodecInfo.CodecProfileLevel.AACObjectLC,
                    )
                    format.setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
                    it.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                    it.start()
                    rate = hz
                }
        } catch (e: Exception) { close(); throw e }
    }

    private fun drain(codec: MediaCodec, hz: Int, adts: Boolean, write: (ByteArray, Long) -> Unit) {
        while (true) {
            val index = codec.dequeueOutputBuffer(info, 0)
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) continue
            if (index < 0) break
            try {
                if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                    val start = prefix + if (adts) 7 else 0
                    val packet = ByteArray(start + info.size)
                    if (adts) {
                        val n = info.size + 7
                        val frequency = frequencies.indexOf(hz)
                        require(frequency >= 0 && n <= 8191)
                        packet[prefix] = 0xff.toByte()
                        packet[prefix+1] = 0xf1.toByte()
                        packet[prefix+2] = (0x40 or (frequency shl 2)).toByte()
                        packet[prefix+3] = (0x80 or (n ushr 11)).toByte()
                        packet[prefix+4] = (n ushr 3).toByte()
                        packet[prefix+5] = ((n shl 5) or 31).toByte()
                        packet[prefix+6] = 0xfc.toByte()
                    }
                    checkNotNull(codec.getOutputBuffer(index)).apply {
                        position(info.offset)
                        get(packet, start, info.size)
                    }
                    write(packet, info.presentationTimeUs)
                }
            } finally {
                codec.releaseOutputBuffer(index, false)
            }
        }
    }

    @Synchronized
    override fun close() {
        encoder?.let {
            runCatching { it.stop() }
            runCatching { it.release() }
        }
        encoder = null
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
