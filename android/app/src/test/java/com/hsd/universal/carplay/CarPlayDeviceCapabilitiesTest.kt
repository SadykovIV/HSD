package com.hsd.universal.carplay

import org.junit.Assert.*
import org.junit.Test
import java.io.EOFException

class CarPlayDeviceCapabilitiesTest {
    @Test fun alternateNavigationNeverReusesTelephonyOrMediaAndPreservesEldFrameSize() {
        fun cap(stream:Int,role:String,mask:Long) = mapOf("type" to stream,"audioType" to role,"audioOutputFormats" to mask)
        val info = mapOf("audioFormats" to listOf(cap(100,"telephony",1L shl 26),
            cap(102,"media",1L shl 22),cap(101,"default",1L shl 24)))
        val voice = CarPlayAudioProfile.candidates(info,true).single()
        assertEquals(101L,voice.stream); assertEquals(44100,voice.rate)
        assertEquals(2,voice.channels); assertEquals(39,voice.aacProfile); assertEquals(480,voice.framesPerPacket)
        assertTrue(CarPlayAudioProfile.candidates(mapOf("audioFormats" to listOf(cap(100,"alert",1L shl 24))),true).isEmpty())
    }
    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private val finger = "050d0922a102050d093875089501810209331500250175019501810275079501810105010930150026ff7f7510950181020931150026ff7f751095018102c0"
    @Test fun twoTouchSlotsMapControllerUnitsToDisplayPixels() {
        val layout = CarPlayTouchLayout.parse(hex("050d0904a101"+finger+finger+"c0"))!!
        val touches = layout.contacts(hex("0001004000400101ff7fff7f"),1920,1080)!!
        assertEquals(listOf(960,1919),touches.map { it.xPixels })
        assertEquals(listOf(540,1079),touches.map { it.yPixels })
        assertTrue(touches.all { it.down })
        assertNull(layout.contacts(ByteArray(11),1920,1080))
        val up = layout.contacts(ByteArray(12),720,1280)!!
        assertTrue(up.none { it.down })
    }
    @Test fun packedSignedAxesReportIdAndNonTouchReport() {
        // 1 tip bit, 3 padding bits, two signed 12-bit axes -1024..1023, in report 7.
        val d = hex("050d0904a10185070942150025017501950181027503950181010501093009311600fc26ff03750c95028102c0")
        val layout = CarPlayTouchLayout.parse(d)!!
        fun report(x: Int,y: Int): ByteArray {
            val n=1 or ((x and 4095) shl 4) or ((y and 4095) shl 16)
            return byteArrayOf(7,n.toByte(),(n ushr 8).toByte(),(n ushr 16).toByte(),(n ushr 24).toByte())
        }
        val c = layout.contacts(report(-1024,1023),720,1280)!!.single()
        assertEquals(0,c.xPixels); assertEquals(1279,c.yPixels); assertTrue(c.down)
        assertNull(layout.contacts(report(0,0).also {it[0]=3},720,1280))
        assertNull(CarPlayTouchLayout.parse(hex("050c0901a10115002506090009b0750895018100c0")))
    }
    @Test fun advertisedMediaGetsPriorityAndNoAudioIsSentOnInputOrVoiceOnlyRoles() {
        fun cap(stream:Int,role:String,out:Long, input:Long=0) = mapOf("type" to stream,"audioType" to role,"audioOutputFormats" to out,"audioInputFormats" to input)
        val caps=listOf(cap(100,"compatibility",0x810),cap(101,"default",0x8000),cap(100,"telephony",0x8000),
            cap(102,"media",1L shl 22),cap(100,"media",0,0x8000))
        val all=CarPlayAudioProfile.candidates(mapOf("audioFormats" to caps))
        assertEquals(listOf(102L,100L),all.map {it.stream})
        assertEquals(listOf(1024,352),all.map {it.framesPerPacket})
        assertEquals(listOf(44100,44100),all.map {it.rate})
        assertTrue(CarPlayAudioProfile.candidates(mapOf("audioFormats" to caps.subList(1,3))).isEmpty())
    }
    @Test fun retryOnlyAfterStreamingAndNeverForProtocolFailures() {
        val retry=CarPlayRecovery()
        assertNull(retry.retry(10L,null,true))
        assertEquals(1000L,retry.retry(10_000L,3000,true))
        assertEquals(2000L,retry.retry(12_000L,null,true))
        assertEquals(4000L,retry.retry(20_000L,1500,true))
        assertNull(retry.retry(25_000L,2500,false))
        assertNull(retry.retry(160_000L,null,true))
        assertEquals(1000L,retry.retry(300_000L,61_000,true))
        assertTrue(EOFException().carPlayLinkInterrupted())
        assertFalse(RtspError(403,"SETUP","/").carPlayLinkInterrupted())
        assertFalse(IllegalStateException("pairing refused").carPlayLinkInterrupted())
    }
    @Test fun noPlaceholdersCountAsAnIdentity() {
        for (v in listOf(null,"", "not granted","02:00:00:00:00:00","FF:FF:FF:FF:FF:FF","00:00:00:00:00:00")) assertNull(BluetoothIdentity.normalize(v))
        assertEquals("12:34:56:78:9A:BC",BluetoothIdentity.normalize("12:34:56:78:9a:bc\n"))
    }
}
