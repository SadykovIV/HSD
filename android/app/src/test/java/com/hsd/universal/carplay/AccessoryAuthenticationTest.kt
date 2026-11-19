package com.hsd.universal.carplay

import java.math.BigInteger
import java.security.*
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Date
import javax.security.auth.x500.X500Principal
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.cert.jcajce.*
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.junit.Assert.*
import org.junit.Test
import io.github.shaonialife.cardeck.iap2.Iap2Csm

class AccessoryAuthenticationTest {
    private val caKeys =
        KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
    private val device =
        KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()

    private fun cert(subject: String, keys: KeyPair, ca: Boolean): X509Certificate {
        val root = X500Principal("CN=Emulator Accessory CA")
        val builder =
            JcaX509v3CertificateBuilder(
                root,
                BigInteger(100, SecureRandom()),
                Date(System.currentTimeMillis() - 86400000),
                Date(System.currentTimeMillis() + 86400000),
                X500Principal(subject),
                keys.public,
            )
        builder.addExtension(Extension.basicConstraints, true, BasicConstraints(ca))
        return JcaX509CertificateConverter()
            .getCertificate(
                builder.build(JcaContentSignerBuilder("SHA256withECDSA").build(caKeys.private))
            )
    }

    @Test
    fun onlyTrustedChipCanAnswerOnce() {
        val root = cert("CN=Emulator Accessory CA", caKeys, true)
        val leaf = cert("CN=IPA_EmulatorAccessory", device, false)
        val chain =
            CertificateFactory.getInstance("X.509")
                .generateCertPath(listOf(leaf, root))
                .getEncoded("PKCS7")
        val auth = AccessoryAuthentication(listOf(root))
        val challenge = auth.challenge(chain)
        val signature =
            Signature.getInstance("NONEwithECDSA").run {
                initSign(device.private)
                update(challenge)
                sign()
            }
        assertTrue(auth.verify(signature))
        assertFalse(auth.verify(signature))
        assertFalse(auth.challenge(chain).contentEquals(challenge))
        assertFalse(auth.verify(signature))
        assertThrows(IllegalArgumentException::class.java) {
            AccessoryAuthentication(emptyList()).challenge(chain)
        }
    }

    @Test fun serialIsOptionalAndNeverAuthenticatesTheAccessory() {
        val root = cert("CN=Emulator Accessory CA", caKeys, true)
        val leaf = cert("CN=IPA_Test", device, false)
        val serial = listOf(Iap2Csm.Param(0, byteArrayOf(1, 2, 3)))
        for (validSignature in listOf(true, false)) {
            val sent = mutableListOf<Iap2Csm.Frame>()
            val exchange = AccessoryAuthExchange(AccessoryAuthentication(listOf(root)),
                { id, body -> sent.add(Iap2Csm.Frame(id, body)) }, "invalid signature")
            exchange.start(2)
            exchange.identified()
            assertEquals(listOf(0x1d00, 0x1d02, 0xaa00), sent.map { it.messageId })
            assertTrue(sent.last().body.isEmpty())
            exchange.receive(0xaa06, serial)
            exchange.receive(0xaa06, serial)
            assertEquals(listOf(0x1d00, 0x1d02, 0xaa00, 0xaa00), sent.map { it.messageId })
            assertTrue(sent.last().body.isEmpty())
            assertThrows(IllegalStateException::class.java) { exchange.receive(0xaa03, serial) }
            exchange.receive(0xaa01, listOf(Iap2Csm.Param(0, leaf.encoded)))
            val challenge = Iap2Csm.parseParams(sent.last().body).single().value
            exchange.receive(0xaa06, serial)
            assertEquals(0xaa02, sent.last().messageId)
            val signature = Signature.getInstance("NONEwithECDSA").run { initSign(device.private); update(challenge); sign() }
            if (validSignature) {
                exchange.receive(0xaa03, listOf(Iap2Csm.Param(0, signature)))
                assertEquals(AccessoryAuthExchange.Stage.VERIFIED, exchange.stage)
                assertEquals(0xaa05, sent.last().messageId)
            } else {
                assertThrows(SignatureException::class.java) { exchange.receive(0xaa03, serial) }
                assertNotEquals(AccessoryAuthExchange.Stage.VERIFIED, exchange.stage)
                assertEquals(0xaa04, sent.last().messageId)
            }
        }
    }

    @Test fun fullCertificateAuthenticatesWithoutTheSerialCacheOptimization() {
        val root = cert("CN=Emulator Accessory CA", caKeys, true)
        val leaf = cert("CN=IPA_FullCertificate", device, false)
        val sent = mutableListOf<Iap2Csm.Frame>()
        val exchange = AccessoryAuthExchange(AccessoryAuthentication(listOf(root)),
            { id, body -> sent.add(Iap2Csm.Frame(id, body)) }, "invalid signature")
        exchange.start(2)
        exchange.identified()
        // Independent wire bytes reconstructed from Carlinkit's send_request_auth_cert at 0x3f60.
        assertArrayEquals(byteArrayOf(0x40, 0x40, 0, 6, 0xaa.toByte(), 0),
            Iap2Csm.encode(sent.last().messageId, sent.last().body))
        exchange.receive(0xaa01, listOf(Iap2Csm.Param(0, leaf.encoded)))
        val challenge = Iap2Csm.parseParams(sent.last().body).single().value
        assertNotEquals(AccessoryAuthExchange.Stage.VERIFIED, exchange.stage)
        val signature = Signature.getInstance("NONEwithECDSA").run {
            initSign(device.private); update(challenge); sign()
        }
        exchange.receive(0xaa03, listOf(Iap2Csm.Param(0, signature)))
        assertEquals(AccessoryAuthExchange.Stage.VERIFIED, exchange.stage)
        assertEquals(listOf(0x1d00, 0x1d02, 0xaa00, 0xaa02, 0xaa05), sent.map { it.messageId })
    }

    @Test fun unansweredSerialFallsBackOnceAndASilentSignerCannotAdvanceTheSession() {
        val root = cert("CN=Emulator Accessory CA", caKeys, true)
        val leaf = cert("CN=IPA_Silent", device, false)
        var now = 100L
        val sent = mutableListOf<Iap2Csm.Frame>()
        val exchange = AccessoryAuthExchange(AccessoryAuthentication(listOf(root)),
            { id, body -> sent += Iap2Csm.Frame(id, body) }, "invalid signature") { now }
        exchange.start(2)
        exchange.identified(true)
        now += 4_999
        assertFalse(exchange.responseTimedOut())
        assertEquals(AccessoryAuthExchange.Stage.SERIAL, exchange.stage)
        now++
        assertFalse(exchange.responseTimedOut())
        assertEquals(AccessoryAuthExchange.Stage.CERTIFICATE, exchange.stage)
        assertArrayEquals(byteArrayOf(), sent.last().body)
        exchange.receive(0xaa06, listOf(Iap2Csm.Param(0, byteArrayOf(1))))
        exchange.receive(0xaa01, listOf(Iap2Csm.Param(0, leaf.encoded)))
        assertEquals(AccessoryAuthExchange.Stage.SIGNATURE, exchange.stage)
        now += 29_999
        assertFalse(exchange.responseTimedOut())
        now++
        assertTrue(exchange.responseTimedOut())
        assertEquals(listOf(0x1d00, 0x1d02, 0xaa00, 0xaa00, 0xaa02), sent.map { it.messageId })
    }

    @Test fun controlV1AuthenticatesBeforeIdentificationAndNeverOpensAnUnidentifiedSession() {
        val root = cert("CN=Emulator Accessory CA", caKeys, true)
        val leaf = cert("CN=IPA_ControlV1", device, false)
        for (validSignature in listOf(true, false)) {
            val sent = mutableListOf<Iap2Csm.Frame>()
            val exchange = AccessoryAuthExchange(AccessoryAuthentication(listOf(root)),
                { id, body -> sent.add(Iap2Csm.Frame(id, body)) }, "invalid signature")
            exchange.start(1)
            assertEquals(listOf(0xaa00), sent.map { it.messageId })
            assertTrue(sent.single().body.isEmpty())
            assertThrows(IllegalStateException::class.java) { exchange.identified() }
            exchange.receive(0xaa01, listOf(Iap2Csm.Param(0, leaf.encoded)))
            val challenge = Iap2Csm.parseParams(sent.last().body).single().value
            val signature = Signature.getInstance("NONEwithECDSA").run {
                initSign(if (validSignature) device.private else caKeys.private)
                update(challenge)
                sign()
            }
            if (validSignature) {
                exchange.receive(0xaa03, listOf(Iap2Csm.Param(0, signature)))
                assertEquals(listOf(0xaa00, 0xaa02, 0xaa05, 0x1d00), sent.map { it.messageId })
                assertEquals(AccessoryAuthExchange.Stage.IDENTIFICATION, exchange.stage)
                exchange.identified()
                assertEquals(0x1d02, sent.last().messageId)
                assertEquals(5, sent.size) // no second certificate request after identification
                assertEquals(AccessoryAuthExchange.Stage.VERIFIED, exchange.stage)
            } else {
                assertThrows(SignatureException::class.java) {
                    exchange.receive(0xaa03, listOf(Iap2Csm.Param(0, signature)))
                }
                assertEquals(listOf(0xaa00, 0xaa02, 0xaa04), sent.map { it.messageId })
                assertNotEquals(AccessoryAuthExchange.Stage.VERIFIED, exchange.stage)
            }
        }
    }

    @Test fun advertisedSerialExchangeStillRequiresAFullChainAndFreshSignature() {
        val root = cert("CN=Emulator Accessory CA", caKeys, true)
        val leaf = cert("CN=IPA_SerialExchange", device, false)
        for (directCertificate in listOf(false, true)) {
            val sent = mutableListOf<Iap2Csm.Frame>()
            val exchange = AccessoryAuthExchange(AccessoryAuthentication(listOf(root)),
                { id, body -> sent.add(Iap2Csm.Frame(id, body)) }, "invalid signature")
            exchange.start(2)
            exchange.identified(true)
            // Carlinkit libSdBTBridge: advertised 0xaa06 -> param 0 with zero value bytes.
            assertArrayEquals(byteArrayOf(0x40, 0x40, 0, 10, 0xaa.toByte(), 0, 0, 4, 0, 0),
                Iap2Csm.encode(sent.last().messageId, sent.last().body))
            assertEquals(AccessoryAuthExchange.Stage.SERIAL, exchange.stage)
            if (!directCertificate) {
                val serial = listOf(Iap2Csm.Param(0, byteArrayOf(1, 2, 3)))
                exchange.receive(0xaa06, serial)
                assertEquals(AccessoryAuthExchange.Stage.CERTIFICATE, exchange.stage)
                assertArrayEquals(byteArrayOf(0x40, 0x40, 0, 6, 0xaa.toByte(), 0),
                    Iap2Csm.encode(sent.last().messageId, sent.last().body))
                val count = sent.size
                exchange.receive(0xaa06, serial)
                assertEquals(count, sent.size)
            }
            assertThrows(IllegalStateException::class.java) {
                exchange.receive(0xaa03, listOf(Iap2Csm.Param(0, byteArrayOf(1))))
            }
            exchange.receive(0xaa01, listOf(Iap2Csm.Param(0, leaf.encoded)))
            val challenge = Iap2Csm.parseParams(sent.last().body).single().value
            val signature = Signature.getInstance("NONEwithECDSA").run {
                initSign(device.private); update(challenge); sign()
            }
            exchange.receive(0xaa03, listOf(Iap2Csm.Param(0, signature)))
            assertEquals(AccessoryAuthExchange.Stage.VERIFIED, exchange.stage)
        }
    }
}
