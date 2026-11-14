package com.hsd.universal

import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.ExtendedKeyUsage
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.KeyPurposeId
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.cert.X509Certificate
import java.time.Instant
import java.util.Date
import javax.security.auth.x500.X500Principal

class AaReceiverCertificateTest {
    private val now = Instant.parse("2026-01-01T12:00:00Z")
    private val generator = KeyPairGenerator.getInstance("EC").apply { initialize(256) }
    private val authority = generator.generateKeyPair()
    private val issuer = X500Principal("CN=HSD test CA")

    private fun issue(name: String, key: KeyPair, signer: KeyPair = authority,
                      signedBy: X500Principal = issuer, ca: Boolean = false,
                      usage: KeyPurposeId? = null): X509Certificate {
        val builder = JcaX509v3CertificateBuilder(signedBy, BigInteger(1, key.public.encoded.takeLast(16).toByteArray()),
            Date.from(now.minusSeconds(3600)), Date.from(now.plusSeconds(3600)), X500Principal(name), key.public)
            .addExtension(Extension.basicConstraints, true, BasicConstraints(ca))
        if (usage != null) builder.addExtension(Extension.extendedKeyUsage, false, ExtendedKeyUsage(usage))
        return JcaX509CertificateConverter().getCertificate(builder.build(
            JcaContentSignerBuilder("SHA256withECDSA").build(signer.private)))
    }

    @Test fun verifiesReceiverRoleAndIssuerIncludingAnIntermediateOmittedRootAndExpiredLeaf() {
        val root = issue(issuer.name, authority, ca = true)
        val phone = issue("CN=Test phone", generator.generateKeyPair())
        val policy = AaCertificateReference(root, phone)
        val intermediateKey = generator.generateKeyPair()
        val intermediate = issue("CN=HSD test intermediate", intermediateKey, ca = true)
        val receiverKey = generator.generateKeyPair()
        val receiver = issue("CN=Test radio", receiverKey, intermediateKey, intermediate.subjectX500Principal,
            usage = KeyPurposeId.id_kp_clientAuth)
        val chain = listOf(receiver, intermediate)
        assertNull(policy.receiverError(chain, now))
        assertNull(policy.phoneChain(chain, now))
        val sender = issue("CN=Test sender", receiverKey, intermediateKey, intermediate.subjectX500Principal,
            usage = KeyPurposeId.id_kp_serverAuth)
        assertEquals(listOf(sender, intermediate), policy.phoneChain(listOf(sender, root, intermediate), now))
        assertNull(policy.phoneChain(listOf(sender, root), now))
        assertEquals(R.string.aa_receiver_invalid_chain, policy.receiverError(chain, now.plusSeconds(3601)))
        assertEquals(R.string.aa_receiver_invalid_chain, policy.receiverError(
            listOf(issue("CN=Test TLS server", receiverKey, usage = KeyPurposeId.id_kp_serverAuth)), now))
        assertEquals(R.string.aa_receiver_invalid_chain, policy.receiverError(
            listOf(issue("CN=Other issuer with same name", receiverKey, generator.generateKeyPair())), now))
        assertEquals(R.string.aa_receiver_issuer_key, policy.receiverError(listOf(root), now))
        assertEquals(R.string.aa_receiver_phone_key, policy.receiverError(listOf(phone), now))
    }
}
