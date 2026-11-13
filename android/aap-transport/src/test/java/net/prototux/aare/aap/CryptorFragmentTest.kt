package net.prototux.aare.aap

import net.prototux.aare.aap.cert.SelfSignedCertificate
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream

class CryptorFragmentTest {
    @Test fun fragmentedHandshakeRecordsCompleteWithTheSamePinnedCertificates() {
        val handset = SelfSignedCertificate.generate("HSD test phone")
        val receiver = SelfSignedCertificate.generate("HSD test head unit")
        val pins = MemoryPinStore().apply { add("receiver", fingerprint(receiver.certificate)) }
        val phone = Cryptor(handset.certificate, handset.keyPair.private, pins = pins, peerKey = "receiver")
        val hu = Cryptor(receiver.certificate, receiver.keyPair.private, client = true)
        fun transfer(peer: Cryptor, bytes: ByteArray): ByteArray {
            val output = ByteArrayOutputStream()
            var offset = 0
            while (offset < bytes.size) {
                val end = minOf(bytes.size, offset + if (offset < 5) 1 else 61)
                output.write(peer.handshake(bytes.copyOfRange(offset, end)))
                offset = end
            }
            return output.toByteArray()
        }
        phone.beginHandshake()
        hu.beginHandshake()
        var flight = hu.handshake(byteArrayOf())
        var rounds = 0
        while (!(phone.handshakeDone && hu.handshakeDone) && rounds++ < 12) {
            assertTrue("handshake stalled on a partial TLS record", flight.isNotEmpty())
            flight = transfer(hu, transfer(phone, flight))
        }
        assertTrue(phone.handshakeDone && hu.handshakeDone)
        assertEquals(fingerprint(receiver.certificate), fingerprint(phone.peerCertificates.first()))
    }
}
