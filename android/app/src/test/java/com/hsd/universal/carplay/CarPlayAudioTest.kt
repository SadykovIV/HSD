package com.hsd.universal.carplay

import io.github.shaonialife.cardeck.airplay.AirPlayCrypto
import io.github.shaonialife.cardeck.airplay.TimingServer
import io.github.shaonialife.cardeck.audio.AudioSyncPacket
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketAddress
import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test

class CarPlayAudioTest {
    private class Sink(private val sent: (DatagramPacket) -> Unit) : DatagramSocket(null as SocketAddress?) {
        override fun send(p: DatagramPacket) = sent(p)
    }
    private fun u32(bytes: ByteArray, offset: Int) = ByteBuffer.wrap(bytes).getInt(offset).toLong() and 0xffffffffL
    private fun ntpNanos(bytes: ByteArray, offset: Int) =
        u32(bytes, offset) * 1_000_000_000L + (u32(bytes, offset + 4) * 1_000_000_000L ushr 32)

    @Test fun receiverSchedulesSourceSampleAfterNegotiatedLatencyIncludingRtpWrap() {
        // Receiver calculation independently follows rtp.c's anchor_time / anchor_rtptime:
        // RTP anchor = final timestamp - latency; play time = NTP + signed sample delta / rate.
        for (rate in listOf(44100, 48000)) for (rtp in listOf(0L, 352L, 0xffffffffL - 50, 0x100000000L + 100)) {
            val frames = rate * 250L / 1000
            val packet = AudioSyncPacket.build(rtp, frames, TimingServer.ntpFromNanos(20_000_000_000), false)
            assertEquals(20, packet.size)
            assertEquals(4, ByteBuffer.wrap(packet).getShort(2).toInt())
            val anchor = u32(packet, 16) - frames
            assertEquals(anchor and 0xffffffffL, u32(packet, 4))
            val sampleDelta = (rtp - anchor).toInt()
            val playAt = ntpNanos(packet, 8) + sampleDelta * 1_000_000_000L / rate
            assertEquals(20_250_000_000L, playAt)
        }
    }

    @Test fun twoHoursOfAacClockHaveNoCumulativeRoundingDrift() {
        for (rate in listOf(44100, 48000)) {
            val timeline = CarPlayAudioTimeline(rate)
            val origin = 123_456_789_000L
            repeat(rate * 7200 / 1024) { index ->
                val frame = index * 1024L
                val pts = origin + frame * 1_000_000L / rate
                val position = timeline.next(pts, 1024)
                assertEquals(frame, position.rtp)
                assertEquals(pts, position.ptsUs)
            }
            assertEquals(0L, timeline.skippedFrames)
            assertEquals(0L, timeline.backwardsPts)
        }
    }

    @Test fun captureGapAdvancesSampleClockWithoutReplayingMissingTime() {
        val timeline = CarPlayAudioTimeline(48000)
        assertEquals(0L, timeline.next(1_000_000, 480).rtp)
        assertEquals(480L, timeline.next(1_010_000, 480).rtp)
        assertEquals(48960L, timeline.next(2_020_000, 480).rtp)
        assertEquals(48000L, timeline.skippedFrames)
        assertEquals(49440L, timeline.next(2_030_000, 480).rtp)
        // A stale timestamp must not duplicate RTP time or rewind the receiver's clock.
        assertEquals(49920L, timeline.next(1_000_000, 480).rtp)
        assertEquals(1L, timeline.backwardsPts)
    }

    @Test fun packetCountersWrapWithoutReusingNonceAndSyncKeepsCaptureTime() {
        val host = InetAddress.getLoopbackAddress()
        val key = ByteArray(32) { it.toByte() }
        val pcm = ByteArray(352 * 4) { (it and 127).toByte() }
        var now = 10_040_000_000L
        var packetNumber = 0L
        var sourcePts = 10_000_000L
        var syncs = 0
        Sink { p ->
            val bytes = p.data.copyOfRange(p.offset, p.offset + p.length)
            if (p.port == 5002) {
                assertEquals(4, ByteBuffer.wrap(bytes).getShort(2).toInt())
                assertTrue(kotlin.math.abs(sourcePts * 1000 - ntpNanos(bytes, 8)) <= 1L)
                assertEquals(packetNumber * 352 and 0xffffffffL, u32(bytes, 16))
                assertEquals(12000L, (u32(bytes, 16) - u32(bytes, 4)) and 0xffffffffL)
                syncs++
            } else {
                val header = ByteBuffer.wrap(bytes)
                assertEquals(packetNumber.toInt() and 65535, header.getShort(2).toInt() and 65535)
                assertEquals(packetNumber * 352 and 0xffffffffL, u32(bytes, 4))
                val nonce = ByteArray(4) + bytes.takeLast(8).toByteArray()
                assertArrayEquals(AirPlayCrypto.nonce64(packetNumber), nonce)
                if (packetNumber in 65534L..65538L || packetNumber == 0L) {
                    val plain = AirPlayCrypto.chachaOpen(key, nonce, bytes.copyOfRange(12, bytes.size - 8), bytes.copyOfRange(4, 12))
                    assertTrue(plain.indices.all { plain[it] == pcm[it xor 1] })
                }
            }
        }.use { sink ->
            val sender = CarPlayAudio(sink, host, 5001, 5002, 100, key, 48000,
                nowNanos = { now }, log = {})
            repeat(65540) { n ->
                packetNumber = n.toLong()
                sourcePts = 10_000_000L + n * 352_000_000L / 48000
                // Variable delivery delay must not leak into the advertised media clock.
                now = sourcePts * 1000 + if (n % 10 == 0) 90_000_000L else 40_000_000L
                sender.offer(pcm, sourcePts)
            }
            assertTrue(syncs > 400)
            assertTrue(sender.progressSummary().contains("rtpPackets=65540"))
        }
    }

    @Test fun pcmPacketSplitAcrossOffersKeepsTheFirstSampleTimestamp() {
        var stamp: Long? = null
        Sink { p -> if (p.port == 5002) stamp = ntpNanos(p.data, p.offset + 8) }.use { sink ->
            val sender = CarPlayAudio(sink, InetAddress.getLoopbackAddress(), 5001, 5002, 100,
                ByteArray(32), 48000, nowNanos = { 4_100_000_000L }, log = {})
            sender.offer(ByteArray(176 * 4), 4_000_000)
            assertNull(stamp)
            sender.offer(ByteArray(176 * 4), 4_003_666)
            assertEquals(4_000_000_000L, stamp)
        }
    }
}
