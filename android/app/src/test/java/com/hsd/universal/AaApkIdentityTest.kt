package com.hsd.universal

import net.prototux.aare.aap.PhoneIdentity
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.ExtendedKeyUsage
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.KeyPurposeId
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.time.Instant
import java.util.Base64
import java.util.Date
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.security.auth.x500.X500Principal

class AaApkIdentityTest {
    @Test fun selfMixingMatchesIndependentUpstreamVector() {
        val bytes = AaApkIdentity.derive(ByteArray(256) { it.toByte() }, "first\n".toByteArray(), "second\n".toByteArray())
        assertEquals("ba429ccffc2bdfc485a5957af28d66aa733d8d9d50a5ce9c18921f5fa872cae6b40971e912598753c781fe015998467c",
            bytes.joinToString("") { "%02x".format(it) })
    }
    private val now = Instant.parse("2026-11-16T00:00:00Z")
    private val generator = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }
    private val issuer = generator.generateKeyPair()
    private val phone = generator.generateKeyPair()
    private fun certificate(key: KeyPair, ca: Boolean = false, signer: KeyPair = issuer,
                            usage: KeyPurposeId = KeyPurposeId.id_kp_serverAuth): X509Certificate =
        JcaX509CertificateConverter().getCertificate(JcaX509v3CertificateBuilder(X500Principal("CN=Fixture CA"),
            BigInteger(1, key.public.encoded.takeLast(16).toByteArray()), Date.from(now.minusSeconds(60)),
            Date.from(now.plusSeconds(3600)), X500Principal(if (ca) "CN=Fixture CA" else "CN=Fixture Phone"), key.public)
            .addExtension(Extension.basicConstraints, true, BasicConstraints(ca)).apply {
                if (!ca) addExtension(Extension.extendedKeyUsage, false, ExtendedKeyUsage(usage))
            }.build(JcaContentSignerBuilder("SHA256withRSA").build(signer.private)))
    private fun pem(c: X509Certificate) = ("-----BEGIN CERTIFICATE-----\n" +
        Base64.getMimeEncoder(64, byteArrayOf(10)).encodeToString(c.encoded) + "\n-----END CERTIFICATE-----\n").toByteArray()

    private fun fixture(root: X509Certificate, leaf: X509Certificate, key: KeyPair = phone, corrupt: Boolean = false,
                        intermediates: List<X509Certificate> = emptyList(),
                        action: (File) -> Unit) {
        val first = pem(leaf); val second = pem(root); val salt = ByteArray(256) { it.toByte() }
        val material = AaApkIdentity.derive(salt, first, second)
        val encrypted = Cipher.getInstance("AES/CBC/PKCS5Padding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(material, 0, 32, "AES"), IvParameterSpec(material, 32, 16))
        }.doFinal(ByteArray(28) + Base64.getEncoder().encode(key.private.encoded) + ByteArray(26))
        // Flipping the last CBC block can accidentally leave valid padding while changing
        // only the ignored trailer. A truncated block is deterministically malformed.
        val payloadBytes = if (corrupt) encrypted.copyOf(encrypted.size - 1) else encrypted
        fun payload(b: ByteArray) = ByteBuffer.allocate(8 + b.size).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(0x300).putShort(1).putInt(b.size).put(b).array()
        val dex = "dex\n035\u0000".toByteArray() + payload(salt) + payload(payloadBytes) + first + byteArrayOf(0) + second + byteArrayOf(0) +
            intermediates.fold(ByteArray(0)) { bytes, cert -> bytes + pem(cert) + byteArrayOf(0) }
        val apk = File.createTempFile("hsd-aa-identity", ".apk")
        try {
            ZipOutputStream(apk.outputStream()).use { z -> z.putNextEntry(ZipEntry("classes2.dex")); z.write(dex); z.closeEntry() }
            action(apk)
        } finally { apk.delete() }
    }

    @Test fun extractsVerifiedIdentityFromAnUnnamedDexAndProvesPrivateKeyPossession() {
        val root = certificate(issuer, ca = true); val leaf = certificate(phone)
        fixture(root, leaf) { file ->
            val identity = AaApkIdentity.extract(listOf(file), AaCertificateReference(root, leaf), now)
            assertEquals(leaf, identity.certificate)
            assertTrue(identity.chain.isEmpty()) // The receiver supplies its own trust anchor.
            AaApkIdentity.verifyKey(identity)
        }
    }

    @Test fun buildsIntermediateChainFromTheApkWithoutSendingTheRoot() {
        val root = certificate(issuer, ca = true)
        val middleKey = generator.generateKeyPair()
        val middle = certificate(middleKey, ca = true)
        val leaf = certificate(phone, signer = middleKey)
        fixture(root, leaf, intermediates = listOf(middle)) { file ->
            val identity = AaApkIdentity.extract(listOf(file), AaCertificateReference(root, leaf), now)
            assertEquals(leaf, identity.certificate)
            assertEquals(listOf(middle), identity.chain)
        }
    }

    @Test fun refusesWrongKeyWrongIssuerWrongRoleExpiryAndCorruptedPayload() {
        val root = certificate(issuer, ca = true); val leaf = certificate(phone)
        val references = AaCertificateReference(root, leaf)
        fun rejected(cert: X509Certificate = leaf, key: KeyPair = phone, at: Instant = now, corrupt: Boolean = false) {
            fixture(root, cert, key, corrupt) { file ->
                assertTrue(runCatching { AaApkIdentity.extract(listOf(file), references, at) }.isFailure)
            }
        }
        rejected(key = generator.generateKeyPair())
        rejected(cert = certificate(phone, signer = generator.generateKeyPair()))
        rejected(cert = certificate(phone, usage = KeyPurposeId.id_kp_clientAuth))
        rejected(at = now.plusSeconds(3601)); rejected(at = now.minusSeconds(61))
        rejected(corrupt = true)
        rejected(cert = root, key = issuer)
    }

    @Test fun verifiesRealLocallySuppliedAndroidAutoApkWithoutShippingItsKey() {
        val path = System.getenv("HSD_AA_APK")
        assumeTrue("Set HSD_AA_APK for the local APK integration test", !path.isNullOrBlank())
        val factory = CertificateFactory.getInstance("X.509")
        fun cert(name: String) = File("src/main/res/raw/$name.der").inputStream().use {
            factory.generateCertificate(it) as X509Certificate
        }
        val references = AaCertificateReference(cert("aa_google_automotive_link"), cert("aa_car_service"))
        val result = AaApkIdentity.extract(listOf(File(path!!)), references, now)
        assertEquals(AaCertificateReference.Match.CAR_SERVICE, references.identify(listOf(result.certificate), now))
        AaApkIdentity.verifyKey(result)
    }
}
