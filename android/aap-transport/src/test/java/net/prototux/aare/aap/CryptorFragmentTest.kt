package net.prototux.aare.aap

import net.prototux.aare.aap.cert.SelfSignedCertificate
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.security.cert.CertificateException

class CryptorFragmentTest {
    @Test fun issuerAndRoleChecksAlsoRunForAnAlreadyPinnedReceiver() {
        val certificate = SelfSignedCertificate.generate("test receiver").certificate
        val known = fingerprint(certificate)
        val store = object : PinStore {
            override fun checkReceiverChain(chain: List<java.security.cert.X509Certificate>) {
                assertEquals(listOf(certificate), chain)
                throw CertificateException("receiver role rejected")
            }
            override fun get(peerKey: String) = setOf(known)
            override fun add(peerKey: String, sha256: String) = error("refused chain must not be pinned")
        }
        val manager = PinningTrustManager("HU", store, true)
        assertThrows(CertificateException::class.java) { manager.checkClientTrusted(arrayOf(certificate), "RSA") }
        assertEquals("receiver role rejected", manager.chainRejection?.message)
        assertNull(manager.refusal)
        manager.commit()
    }

    @Test fun twoConcurrentFirstConnectionsCannotBothClaimTheSameHeadUnit() {
        val firstCert = SelfSignedCertificate.generate("receiver one").certificate
        val nextCert = SelfSignedCertificate.generate("receiver two").certificate
        val pins = MemoryPinStore()
        val first = PinningTrustManager("one USB identity", pins, true)
        val next = PinningTrustManager("one USB identity", pins, true)
        first.checkClientTrusted(arrayOf(firstCert), "RSA")
        next.checkClientTrusted(arrayOf(nextCert), "RSA")
        assertTrue(pins.get("one USB identity").isEmpty())
        first.commit()
        assertThrows(CertificateException::class.java) { next.commit() }
        assertEquals(setOf(fingerprint(firstCert)), pins.get("one USB identity"))
        assertEquals(fingerprint(nextCert), next.refusal?.fingerprint)
        assertTrue(next.refused && next.refusal?.changed == true)
        assertEquals(listOf(nextCert), next.presentedChain)
    }

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
