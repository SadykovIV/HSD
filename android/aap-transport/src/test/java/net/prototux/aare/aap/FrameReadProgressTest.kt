package net.prototux.aare.aap

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import org.junit.Assert.*
import org.junit.Test

class FrameReadProgressTest {
    @Test fun distinguishesSilencePartialHeaderPartialPayloadAndInvalidEncryptedFrame() {
        fun stopped(bytes: ByteArray): FrameReader {
            val reader = FrameReader(ByteArrayInputStream(bytes)) { null }
            reader.trackProgress = true
            assertThrows(Exception::class.java) { reader.read() }
            return reader
        }
        val silent = stopped(byteArrayOf()).progress
        assertEquals(0L, silent.bytes)
        assertEquals("header", silent.stage)
        val header = stopped(byteArrayOf(0, 11)).progress
        assertEquals(2L, header.bytes)
        assertEquals(2, header.received)
        val body = stopped(byteArrayOf(0, 11, 0, 40, 23, 3, 3)).progress
        assertEquals(7L, body.bytes)
        assertEquals("payload", body.stage)
        assertEquals(3, body.received)
        assertEquals(40, body.expected)
        assertEquals(0, body.frames)
        val malformed = stopped(byteArrayOf(0, 11, 0, 2, 0, -1)).progress
        assertEquals(1, malformed.frames)
        assertEquals("decode", malformed.stage)
        assertEquals(0, malformed.tlsType)
        assertEquals(2, malformed.payloadSize)
    }

    @Test fun packetReadsPreserveCoalescedFramesAndFragmentedMessages() {
        val bytes = ByteArrayOutputStream()
        val body = ByteArray(2 * Frame.MAX_PAYLOAD + 17) { (it * 31).toByte() }
        val plain = AapMessage.plaintext(ControlMsg.SERVICE_DISCOVERY_RESPONSE, body)
        val writer = FrameWriter(bytes) { null }
        writer.write(0, false, false, plain)
        writer.write(0, false, false, AapMessage.plaintext(ControlMsg.PING_REQUEST, byteArrayOf(8, 7)))
        // Like AOA: a short read consumes the whole USB transfer. All raw reads must be large.
        val wire = bytes.toByteArray()
        var position = 0
        val usb = object : InputStream() {
            override fun read(): Int = error("USB must be read in transfers")
            override fun read(target: ByteArray, offset: Int, length: Int): Int {
                if (position == wire.size) return -1
                val packet = minOf(16 * 1024, wire.size - position)
                check(length >= packet) { "Partial AOA transfer would be dropped" }
                wire.copyInto(target, offset, position, position + packet)
                position += packet
                return packet
            }
        }
        val reader = FrameReader(usb) { null }.apply { trackProgress = true }
        assertArrayEquals(body, reader.read().bodyBytes())
        assertEquals(3, reader.progress.frames)
        assertEquals(ControlMsg.PING_REQUEST, reader.read().id)
        assertEquals(4, reader.progress.frames)
        assertEquals(wire.size.toLong(), reader.progress.bytes)
        val frozen = reader.progress
        reader.trackProgress = false
        assertThrows(EOFException::class.java) { reader.read() }
        assertSame(frozen, reader.progress)
    }
}
