package com.hsd.universal.carplay

import com.hsd.universal.AnnexB
import java.util.Arrays
import io.github.shaonialife.cardeck.airplay.AirPlayCrypto
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
) {
    private var configChanged = false
    private var counter = 0L
    private var hasIdr = false
    private var sps: ByteArray? = null
    private var pps: ByteArray? = null

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
        hasIdr = true
        return true
    }

    @Synchronized
    fun keepAlive() {
        out.write(header(0, 2, System.nanoTime() / 1000).array())
        out.flush()
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
) {
    private val pending = ByteArray(352 * 4)
    private var used = 0
    private var counter = 0L
    private var time = 0L
    private var syncAt = 0L

    @Synchronized
    fun offer(pcm: ByteArray) {
        var pos = 0
        while (pos < pcm.size) {
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
            val h =
                ByteBuffer.allocate(12)
                    .apply {
                        put(0x80.toByte())
                        put(((if (counter == 0L) 0x80 else 0) or (streamType and 127)).toByte())
                        putShort(counter.toShort())
                        putInt(time.toInt())
                        putInt(0)
                    }
                    .array()
            val nonce = AirPlayCrypto.nonce64(counter)
            val data =
                h +
                    AirPlayCrypto.chachaSeal(key, nonce, pending, h.copyOfRange(4, 12)) +
                    nonce.copyOfRange(4, 12)
            udp.send(DatagramPacket(data, data.size, host, port))
            val now = System.nanoTime()
            if (control in 1..65535 && (syncAt == 0L || now - syncAt >= 1_000_000_000)) {
                val sync =
                    AudioSyncPacket.build(
                        (counter and 65535).toInt(),
                        time,
                        time + rate / 4,
                        TimingServer.ntpFromNanos(now),
                        syncAt == 0L,
                    )
                udp.send(DatagramPacket(sync, sync.size, host, control))
                syncAt = now
            }
            counter++
            time += 352
            used = 0
        }
    }
}
