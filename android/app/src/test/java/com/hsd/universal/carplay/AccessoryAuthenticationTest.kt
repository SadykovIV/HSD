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
}
