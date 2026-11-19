package com.hsd.universal.hicar

import java.io.Closeable
import java.nio.ByteBuffer
import java.security.SecureRandom

/** HiSight uses a 90 kHz clock. The same capture timestamp drives picture and AAC. */
internal class HiCarMedia(key: ByteArray) : Closeable {
    private val videoCipher = SessionCipher(key, 2 * 1024 * 1024)
    private val audioCipher = SessionCipher(key, 65536)
    private val inputCipher = SessionCipher(key, 1500)
    private var videoSequence = SecureRandom().nextInt(65536)
    private var audioSequence = SecureRandom().nextInt(65536)
    private val videoSsrc = SecureRandom().nextInt()
    private val audioSsrc = SecureRandom().nextInt()

    fun video(frame: ByteArray, ptsUs: Long, send: (ByteArray) -> Unit) {
        require(frame.isNotEmpty() && frame.size < 2 * 1024 * 1024 - 64)
        val encrypted = videoCipher.encrypt(frame)
        val time = ticks(ptsUs)
        val size = encrypted.size + 14
        val pes = ByteBuffer.allocate(size).put(0).put(0).put(1).put(0xe0.toByte())
            .putShort(if (size - 6 <= 65535) (size - 6).toShort() else 0)
            .put(0x84.toByte()).put(0x80.toByte()).put(5)
        // MPEG PES PTS: marker bits at the standard 33-bit clock positions.
        pes.put((0x21 or (((time ushr 30) and 7).toInt() shl 1)).toByte())
            .put((time ushr 22).toByte()).put((((time ushr 15).toInt() and 127) shl 1 or 1).toByte())
            .put((time ushr 7).toByte()).put(((time.toInt() and 127) shl 1 or 1).toByte()).put(encrypted)
        val bytes = pes.array()
        var at = 0
        while (at < bytes.size) {
            val count = minOf(1200, bytes.size-at)
            val marker = if (at+count == bytes.size) 128 else 0
            val packet = ByteBuffer.allocate(12+count).put(0x80.toByte()).put((96 or marker).toByte())
                .putShort((videoSequence++).toShort()).putInt(time.toInt()).putInt(videoSsrc).put(bytes, at, count).array()
            send(packet)
            at += count
        }
    }

    fun audio(adts: ByteArray, ptsUs: Long): ByteArray {
        require(adts.size in 8..8191)
        val rtp = ByteBuffer.allocate(12+adts.size).put(0x80.toByte()).put(96)
            .putShort((audioSequence++).toShort()).putInt(ticks(ptsUs).toInt()).putInt(audioSsrc).put(adts).array()
        val encrypted = audioCipher.encrypt(rtp)
        return ByteBuffer.allocate(3+encrypted.size).put(171.toByte()).putShort(encrypted.size.toShort()).put(encrypted).array()
    }

    data class Input(val action: Int, val x: Int = 0, val y: Int = 0, val key: Int = 0)
    fun input(encrypted: ByteArray, width: Int, height: Int): Input {
        val plain = inputCipher.decrypt(encrypted)
        try {
            check(plain.size >= 9) { "HiCar: input header" }
            val body = ByteBuffer.wrap(plain, 6, plain.size-6)
            val action = body.get().toInt() and 255
            val length = body.short.toInt() and 65535
            check(length == plain.size - 9) { "HiCar: input length" }
            return when (action) {
                0, 1, 2 -> {
                    check(length == 11 && body.get() == 1.toByte() && body.get() == 0.toByte()) { "HiCar: input contacts" }
                    val x = body.short.toInt() and 65535; val y = body.short.toInt() and 65535
                    check(x in 0 until width && y in 0 until height) { "HiCar: input coordinates" }
                    Input(action, x, y)
                }
                3, 4 -> {
                    check(length == 9 && body.get() == 0.toByte()) { "HiCar: input key format" }
                    val key = body.short.toInt() and 65535
                    check(key in setOf(3, 4)) { "HiCar: unsupported input key" }
                    Input(action, key = key)
                }
                else -> error("HiCar: unsupported input action")
            }
        } finally { plain.fill(0) }
    }
    override fun close() { videoCipher.close(); audioCipher.close(); inputCipher.close() }
    companion object {
        fun ticks(ptsUs: Long): Long { require(ptsUs >= 0); return ((ptsUs / 100) * 9 + (ptsUs % 100) * 9 / 100) and 0xffffffffL }
        fun keyFrame(bytes: ByteArray): Boolean = (2 until bytes.size - 1).any { at ->
            bytes[at-2] == 0.toByte() && bytes[at-1] == 0.toByte() && bytes[at] == 1.toByte() && (bytes[at+1].toInt() and 31) == 5
        }
    }
}
