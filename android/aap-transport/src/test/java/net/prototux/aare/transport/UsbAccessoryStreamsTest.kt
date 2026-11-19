package net.prototux.aare.transport

import java.io.ByteArrayInputStream
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class UsbAccessoryStreamsTest {
    @Test fun missingFrameworkDescriptorFallsBackAfterClosingPartialStreams() {
        var closed = false
        val input = object : ByteArrayInputStream(byteArrayOf()) { override fun close() { closed = true } }
        val pair = ByteArrayInputStream(byteArrayOf(7)) to java.io.ByteArrayOutputStream()
        val opened = UsbAccessoryTransport.withDescriptorFallback({
            UsbAccessoryTransport.openStreams(input) { throw NullPointerException("ParcelFileDescriptor.getFileDescriptor()") }
        }, { assertTrue(closed); pair })
        assertSame(pair, opened)
        val failure = assertThrows(IOException::class.java) {
            UsbAccessoryTransport.withDescriptorFallback({ throw NullPointerException() }, { throw IOException("USB unavailable") })
        }
        assertTrue(failure.suppressed.single() is NullPointerException)
        assertThrows(SecurityException::class.java) {
            UsbAccessoryTransport.withDescriptorFallback({ throw SecurityException("USB permission") }, { error("unexpected retry") })
        }
    }

    @Test fun failedSecondEndpointReleasesInputBeforeReopeningTheAccessory() {
        var closed = 0
        val input = object : ByteArrayInputStream(byteArrayOf()) {
            override fun close() { closed++ }
        }
        val error = IOException("accessory detached")
        val result = assertThrows(IOException::class.java) {
            UsbAccessoryTransport.openStreams(input) { throw error }
        }
        assertSame(error, result)
        assertEquals(1, closed)
    }
}
