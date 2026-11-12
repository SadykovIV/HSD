package com.hsd.universal.carplay

import com.hsd.universal.AnnexB
import io.github.shaonialife.cardeck.airplay.AirPlayCrypto
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class CarPlayVideoTest {
    @Test fun mixedPrefixesKeepUnitBoundariesAndRotateConfigurationOnlyWhenChanged() {
        val sink = ByteArrayOutputStream()
        val key = ByteArray(32) { it.toByte() }
        val video = CarPlayVideo(sink, key, 1280, 720)
        val sps = byteArrayOf(0, 0, 1, 0x67, 66, 0, 31)
        val pps = byteArrayOf(0, 0, 0, 1, 0x68, 5)
        val idr = byteArrayOf(0, 0, 1, 0x65, 4)
        val delta = byteArrayOf(0, 0, 0, 1, 0x41, 7)
        val aud = byteArrayOf(0, 0, 1, 9, 1)
        assertFalse(video.send(sps, 0))
        assertFalse(video.send(pps + delta, 1))
        assertTrue(video.send(aud + idr + delta, 2))
        assertTrue(video.send(sps + pps + delta, 3))
        assertFalse(video.send(sps.copyOf().apply { this[lastIndex] = 32 } + delta, 4))
        assertTrue(video.send(idr, 5))

        val packets = ByteBuffer.wrap(sink.toByteArray()).order(ByteOrder.LITTLE_ENDIAN)
        val types = ArrayList<Int>()
        val payloads = ArrayList<ByteArray>()
        while (packets.hasRemaining()) {
            val header = ByteArray(128).also { packets.get(it) }
            val length = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).int
            val type = header[4].toInt()
            types.add(type)
            val bytes = ByteArray(length).also { packets.get(it) }
            if (type == 0) payloads.add(AirPlayCrypto.chachaOpen(key, AirPlayCrypto.nonce64(payloads.size.toLong()), bytes, header))
        }
        assertEquals(listOf(1, 0, 0, 1, 0), types)
        assertArrayEquals(byteArrayOf(0, 0, 0, 2, 0x65, 4, 0, 0, 0, 2, 0x41, 7), payloads[0])
        assertArrayEquals(byteArrayOf(0, 0, 0, 2, 0x41, 7), payloads[1])
        assertArrayEquals(byteArrayOf(0, 0, 0, 2, 0x65, 4), payloads[2])
        val mixed = byteArrayOf(35, 0, 0, 1, 0, 0, 0, 1, 0x65, 4)
        assertEquals(listOf(0, 2), AnnexB.units(mixed).map { it.size })
        assertArrayEquals(byteArrayOf(35, 0, 0, 0, 1, 0, 0, 0, 1, 0x65, 4), AnnexB.normalize(mixed))
    }
}
