package net.prototux.aare.aap

import java.io.ByteArrayInputStream
import org.junit.Assert.*
import org.junit.Test

class UsbVersionTest {
    private val old = byteArrayOf(3,11,0,2,0,7)
    private val version = byteArrayOf(0,3,0,6,0,1,0,6,0,1)
    private fun reader(bytes: ByteArray) = FrameReader(ByteArrayInputStream(bytes)) { null }

    @Test fun freshVersionCanFollowBufferedTransfersOnTheSameAccessory() {
        val usb = reader(old + old + version)
        val msg = usb.read(awaitUsbVersion = true)
        assertEquals(2,usb.oldUsbFrames)
        assertEquals(0,msg.channel)
        assertEquals(ControlMsg.VERSION_REQUEST,msg.id)
        assertFalse(msg.encrypted)
    }

    @Test fun persistentOldTrafficIsBoundedAndNonUsbOrHandshakeRecordsRemainStrict() {
        val usb = reader(ByteArray(40*old.size) { old[it % old.size] })
        assertThrows(AapException::class.java) { usb.read(awaitUsbVersion = true) }
        assertEquals(32,usb.oldUsbFrames)
        val current = reader(version + old)
        assertEquals(ControlMsg.VERSION_REQUEST,current.read(awaitUsbVersion = true).id)
        assertThrows(AapException::class.java) { current.read() }
        assertThrows(AapException::class.java) { reader(old + version).read() }
    }
}
