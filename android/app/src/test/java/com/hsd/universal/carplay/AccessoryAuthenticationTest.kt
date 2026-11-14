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
        for (advertised in listOf(true, false)) {
            val sent = mutableListOf<Iap2Csm.Frame>()
            val exchange = AccessoryAuthExchange(AccessoryAuthentication(listOf(root)),
                { id, body -> sent.add(Iap2Csm.Frame(id, body)) }, "invalid signature")
            exchange.identified(if (advertised) listOf(0xaa06) else emptyList())
            assertEquals(listOf(0x1d02, 0xaa00), sent.map { it.messageId })
            assertArrayEquals(if (advertised) Iap2Csm.param(0, byteArrayOf()) else byteArrayOf(), sent.last().body)
            exchange.receive(0xaa06, serial)
            exchange.receive(0xaa06, serial)
            assertEquals(listOf(0x1d02, 0xaa00, 0xaa00), sent.map { it.messageId })
            assertTrue(sent.last().body.isEmpty())
            assertThrows(IllegalStateException::class.java) { exchange.receive(0xaa03, serial) }
            exchange.receive(0xaa01, listOf(Iap2Csm.Param(0, leaf.encoded)))
            val challenge = Iap2Csm.parseParams(sent.last().body).single().value
            exchange.receive(0xaa06, serial)
            assertEquals(0xaa02, sent.last().messageId)
            val signature = Signature.getInstance("NONEwithECDSA").run { initSign(device.private); update(challenge); sign() }
            if (advertised) {
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
}
