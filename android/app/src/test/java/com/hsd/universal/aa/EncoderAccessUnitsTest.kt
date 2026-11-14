package com.hsd.universal.aa

import com.hsd.universal.AnnexB
import org.junit.Assert.*
import org.junit.Test

class EncoderAccessUnitsTest {
    private fun nal(vararg bytes: Int) = bytes.map { it.toByte() }.toByteArray()
    private fun annex(vararg bytes: Int) = nal(0, 0, 0, 1) + nal(*bytes)
    private fun sized(vararg bytes: Int) = nal(0, 0, 0, bytes.size) + nal(*bytes)

    @Test fun avcFormatAndCodecBufferDeliverOneConfigAndPreserveThePicture() {
        val configs = mutableListOf<ByteArray>()
        val codec = EncoderAccessUnits(false, configs::add)
        val sps = annex(103, 66)
        val pps = annex(104, 12)
        codec.format(listOf(sps, pps))
        val picture = codec.frame(sized(103, 66) + sized(104, 12) + sized(101, 55), true)
        assertArrayEquals(sps + pps, configs.single())
        assertArrayEquals(sps + pps + annex(101, 55), picture)
        assertArrayEquals(annex(101, 55), codec.frame(nal(0, 0, 1, 101, 55), false))
    }

    @Test fun splitHevcHeadersWaitForCompleteSetAndNextGenerationStartsEmpty() {
        val configs = mutableListOf<ByteArray>()
        repeat(2) {
            val codec = EncoderAccessUnits(true, configs::add)
            assertNull(codec.frame(sized(64, 1) + sized(66, 1), true))
            assertEquals(it, configs.size)
            assertNull(codec.frame(sized(68, 1), true))
            assertEquals(listOf(32, 33, 34), AnnexB.nalTypes(configs.last(), true))
            assertArrayEquals(annex(38, 1, 18), codec.frame(sized(38, 1, 18), false))
        }
    }

    @Test fun inBandAvcHeadersRefreshCachedConfigAndAreNotDuplicatedAtRecovery() {
        val configs = mutableListOf<ByteArray>()
        val codec = EncoderAccessUnits(false, configs::add)
        val sps = annex(103, 66)
        val pps = annex(104, 12)
        val newPps = annex(104, 13)
        val aud = annex(9, 16)
        val sei = annex(6, 5)
        val idr = annex(101, 55)
        codec.format(listOf(sps, pps))
        val partial = codec.frame(aud + newPps + sei + idr, false)!!
        assertArrayEquals(sps + newPps, configs.last())
        fun recover(frame: ByteArray) = AnnexB.withAvcParameterSets(frame, AnnexB.units(frame), configs.last())
        val full = aud + sps + newPps + sei + idr
        assertArrayEquals(full, recover(partial))
        assertArrayEquals(full, recover(aud + sps + sei + idr))
        assertArrayEquals(full, recover(aud + sei + idr))
        assertSame(full, recover(full))
        assertSame(full, AnnexB.withAvcParameterSets(full, AnnexB.units(full), null))
        assertNull(AnnexB.withAvcParameterSets(idr, AnnexB.units(idr), null))
    }

    @Test fun rejectTruncatedLengthsAndReadAValidLengthResemblingAShortStartCode() {
        val codec = EncoderAccessUnits(false) {}
        val truncated = nal(0, 0, 0, 9, 101, 1)
        assertTrue(AnnexB.nalTypes(codec.frame(truncated, false)!!).isEmpty())
        val payload = ByteArray(272) { 67 }.apply { this[0] = 101 }
        assertArrayEquals(nal(0, 0, 0, 1) + payload, codec.frame(nal(0, 0, 1, 16) + payload, false))
    }
}
