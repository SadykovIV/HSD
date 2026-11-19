package com.hsd.universal

import org.junit.Assert.*
import org.junit.Test

class AacFrameFormatTest {
    private fun config(eld: Boolean, short: Boolean): ByteArray {
        val bits = (if (eld) "11111000111" else "00010") + "0100" + "0010" + (if (short) "1" else "0")
        return bits.padEnd((bits.length+7)/8*8,'0').chunked(8).map { it.toInt(2).toByte() }.toByteArray()
    }
    @Test fun distinguishesCarPlay480FromPlatform512SampleEld() {
        assertEquals(480, AacFrameFormat.samples(config(true,true)))
        assertEquals(512, AacFrameFormat.samples(config(true,false)))
        assertEquals(1024,AacFrameFormat.samples(config(false,false)))
        assertEquals(960,AacFrameFormat.samples(config(false,true)))
    }
    @Test(expected = IllegalArgumentException::class) fun truncatedConfigurationCannotSelectAFormat() {
        AacFrameFormat.samples(byteArrayOf(0xf8.toByte()))
    }
}
