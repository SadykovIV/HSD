package com.hsd.universal.carplay

import com.hsd.universal.AnnexB
import com.hsd.universal.AppLog
import com.hsd.universal.AutoAudioEncoder
import java.util.Arrays
import io.github.shaonialife.cardeck.airplay.AirPlayCrypto
import io.github.shaonialife.cardeck.airplay.BinaryPlist
import io.github.shaonialife.cardeck.airplay.TimingServer
import io.github.shaonialife.cardeck.audio.AudioSyncPacket
import java.io.*
import java.net.*
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal class CarPlayVideo(
    private val out: OutputStream,
    private val key: ByteArray,
    private val width: Int,
    private val height: Int,
    private val heartbeatStats: Boolean = false,
) {
    private var configChanged = false
    private var counter = 0L
    private var hasIdr = false
    private var sps: ByteArray? = null
    private var pps: ByteArray? = null
    private var lastReport = System.nanoTime()
    private var writtenBytes = 0L
    private var writtenFrames = 0L

    private fun header(n: Int, type: Int, pts: Long): ByteBuffer =
        ByteBuffer.allocate(128).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(0, n)
            put(4, type.toByte())
            val ntp = ((pts / 1_000_000L) shl 32) or (((pts % 1_000_000L) shl 32) / 1_000_000L)
            putLong(8, ntp)
        }

    @Synchronized
    fun send(annexB: ByteArray, pts: Long): Boolean {
        val units = AnnexB.units(annexB)
        fun parameter(type: Int, previous: ByteArray?): ByteArray? {
            val unit = units.firstOrNull { it.type(annexB) == type } ?: return previous
            if (previous != null && Arrays.equals(previous, 0, previous.size, annexB, unit.start, unit.end)) return previous
            configChanged = true
            return annexB.copyOfRange(unit.start, unit.end)
        }
        sps = parameter(7, sps)
        pps = parameter(8, pps)
        val s = sps ?: return false
        val p = pps ?: return false
        if (configChanged) {
            require(s.size in 4..65535 && p.size in 1..65535)
            val csd = ByteBuffer.allocate(11 + s.size + p.size).apply {
                put(1)
                put(s, 1, 3)
                put(0xff.toByte())
                put(0xe1.toByte())
                putShort(s.size.toShort())
                put(s)
                put(1)
                putShort(p.size.toShort())
                put(p)
            }.array()
            hasIdr = false
            val h =
                header(csd.size, 1, 0).apply {
                    put(6, 4)
                    putFloat(16, width.toFloat())
                    putFloat(20, height.toFloat())
                }
            out.write(h.array())
            out.write(csd)
            writtenBytes += 128 + csd.size
            configChanged = false
        }
        val nal = units.filter { it.size > 0 && it.type(annexB) !in 7..9 }
        if (nal.isEmpty() || !hasIdr && nal.none { it.type(annexB) == 5 }) return false
        val frame =
            ByteBuffer.allocate(nal.sumOf { it.size + 4 })
                .apply {
                    nal.forEach {
                        putInt(it.size)
                        put(annexB, it.start, it.size)
                    }
                }
                .array()
        val h = header(frame.size + 16, 0, pts).array()
        val encrypted = AirPlayCrypto.chachaSeal(key, AirPlayCrypto.nonce64(counter++), frame, h)
        out.write(h)
        out.write(encrypted)
        out.flush()
        writtenBytes += h.size + encrypted.size
        writtenFrames++
        hasIdr = true
        return true
    }

    @Synchronized
    fun keepAlive() {
        val now = System.nanoTime()
        if (heartbeatStats) {
            val seconds = (now - lastReport).coerceAtLeast(1L) / 1_000_000_000.0
            val report = BinaryPlist.encode(mapOf(
                "txUsageAvg" to writtenBytes / seconds,
                "encoderCurrentFPS" to kotlin.math.round(writtenFrames / seconds).toLong(),
                "sentFramesAvg" to writtenFrames, "queuedFramesAvg" to 0L, "lossAvg" to 0.0,
            ))
            out.write(header(report.size, 5, 0).putFloat(124, report.size.toFloat()).array())
            out.write(report)
        } else out.write(header(0, 2, 0).array())
        out.flush()
        lastReport = now
        writtenBytes = 0
        writtenFrames = 0
    }
}

internal class CarPlayAudio(
    private val udp: DatagramSocket,
    private val host: InetAddress,
    private val port: Int,
    private val control: Int,
    private val streamType: Int,
    private val key: ByteArray,
    val rate: Int,
    private val encoder: AutoAudioEncoder? = null,
    private val latencyMs: Long = 250L,
    private val nowNanos: () -> Long = System::nanoTime,
    private val log: (String) -> Unit = { AppLog.i(it, false) },
    val channels: Int = 2,
    private val encodedFrames: Int = 1024,
) {
    private val bytesPerFrame = channels * 2
    // A packet delayed locally may still arrive before its negotiated playout time.
    // Reserve one wire packet for encoding and transit. This is an expiry, never a send delay.
    val sendWindowUs: Long = (latencyMs * 1000L - (if (encoder == null) 352 else encodedFrames) * 1_000_000L / rate)
        .coerceAtLeast(com.hsd.universal.AutoPcmQueue.DEFAULT_MAX_LATENESS_US)
    private val pending = ByteArray(352 * bytesPerFrame)
    private var used = 0
    private var pendingPtsUs = 0L
    @Volatile private var counter = 0L
    private val timeline = CarPlayAudioTimeline(rate)
    private var syncAt = 0L
    @Volatile private var syncPackets = 0L
    @Volatile private var pcmBytes = 0L
    @Volatile private var sentBytes = 0L
    @Volatile private var lastPacketAt = 0L
    @Volatile private var captureAgeUs = 0L
    private var endPtsUs = 0L

    // An approximate snapshot must remain available while send/codec is blocked:
    // teardown logs it before closing the socket that releases the sender.
    fun progressSummary(): String = "pcmBytes=$pcmBytes rtpPackets=$counter rtpBytes=$sentBytes sync=$syncPackets " +
        "captureAgeMs=${captureAgeUs / 1000} lastSendAgeMs=${if (counter == 0L) -1 else (nowNanos() - lastPacketAt) / 1_000_000} " +
        "skippedFrames=${timeline.skippedFrames} backwardsPts=${timeline.backwardsPts}"

    @Synchronized
    fun offer(pcm: ByteArray, ptsUs: Long = System.nanoTime() / 1000) {
        require(channels in 1..2 && pcm.size % bytesPerFrame == 0) { "Incomplete PCM frame" }
        pcmBytes += pcm.size
        endPtsUs = ptsUs + pcm.size / bytesPerFrame * 1_000_000L / rate
        if (encoder != null) {
            encoder.offer(pcm, 0, ptsUs, rate, false) { frame, encodedPts -> send(frame, encodedFrames, encodedPts) }
            return
        }
        var pos = 0
        while (pos < pcm.size) {
            if (used == 0) pendingPtsUs = ptsUs + pos * 1_000_000L / (rate * bytesPerFrame.toLong())
            val n = minOf(pcm.size - pos, pending.size - used)
            pcm.copyInto(pending, used, pos, pos + n)
            used += n
            pos += n
            if (used != pending.size) continue
            for (i in pending.indices step 2) {
                val b = pending[i]
                pending[i] = pending[i + 1]
                pending[i + 1] = b
            }
            send(pending, 352, pendingPtsUs)
            used = 0
        }
    }

    /** Finish a speech burst without carrying its partial PCM or codec delay into the next one. */
    @Synchronized fun endSegment() {
        if (encoder != null) {
            encoder.finish(rate, false) { data, pts -> send(data, encodedFrames, pts) }
            encoder.close()
        } else if (used > 0) {
            offer(ByteArray(pending.size - used), endPtsUs)
        }
        used = 0
    }

    private fun send(payload: ByteArray, samples: Int, ptsUs: Long) {
        val position = timeline.next(ptsUs, samples)
        val h =
            ByteBuffer.allocate(12)
                .apply {
                    put(0x80.toByte())
                    put(((if (counter == 0L) 0x80 else 0) or (streamType and 127)).toByte())
                    putShort(counter.toShort())
                    putInt(position.rtp.toInt())
                    putInt(0)
                }
                .array()
        val nonce = AirPlayCrypto.nonce64(counter)
        val data =
            h +
                AirPlayCrypto.chachaSeal(key, nonce, payload, h.copyOfRange(4, 12)) +
                nonce.copyOfRange(4, 12)
        udp.send(DatagramPacket(data, data.size, host, port))
        val firstPacket = counter == 0L
        counter++ // A successfully sent RTP nonce must never be reused if the sync send fails.
        val now = nowNanos()
        lastPacketAt = now
        captureAgeUs = now / 1000 - position.ptsUs
        sentBytes += data.size
        if (control in 1..65535 && (syncAt == 0L || now - syncAt >= 1_000_000_000)) {
            val sync =
                AudioSyncPacket.build(
                    position.rtp,
                    rate * latencyMs / 1000,
                    TimingServer.ntpFromNanos(position.ptsUs * 1000),
                    syncAt == 0L,
                )
            udp.send(DatagramPacket(sync, sync.size, host, control))
            syncAt = now
            syncPackets++
        }
        if (firstPacket) log("CarPlay audio: first RTP packet sent (${payload.size} B, ${samples} samples)")
    }
}
