package com.hsd.universal.aa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.openssl.jcajce.JcaPEMWriter
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.InputStream
import java.io.StringWriter
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.util.Date
import javax.security.auth.x500.X500Principal

/** Ephemeral receiver credentials exercise both supported PEM key encodings. */
object TestAssets {
    private fun pem(value: Any): String = StringWriter().also { text ->
        JcaPEMWriter(text).use { it.writeObject(value) }
    }.toString()

    private val files: Map<String, String> by lazy {
        buildMap {
            for (set in AapCerts.SETS) {
                val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
                val name = X500Principal("CN=HSD test $set")
                val now = System.currentTimeMillis()
                val certificate = JcaX509v3CertificateBuilder(name, BigInteger.ONE, Date(now - 86_400_000),
                    Date(now + 86_400_000), name, pair.public).build(JcaContentSignerBuilder("SHA256withRSA").build(pair.private))
                put("aa/$set/cert.pem", pem(certificate))
                val key = PrivateKeyInfo.getInstance(pair.private.encoded)
                val pkcs1 = set == "jvc"
                put("aa/$set/key.pem", pem(org.bouncycastle.util.io.pem.PemObject(
                    if (pkcs1) "RSA PRIVATE KEY" else "PRIVATE KEY",
                    if (pkcs1) key.parsePrivateKey().toASN1Primitive().encoded else key.encoded)))
            }
        }
    }
    val opener: (String) -> InputStream = { path -> files.getValue(path).byteInputStream() }
}

class AapTlsTest {
    @Test
    fun certificatesLoad() {
        for (set in AapCerts.SETS) {
            val c = AapCerts.load(TestAssets.opener, set)
            assertEquals(set, c.certSet)
            assertEquals("RSA", c.key.algorithm)
            println("[$set] subject=${c.chain[0].subjectX500Principal} notBefore=${c.chain[0].notBefore} notAfter=${c.chain[0].notAfter}")
        }
    }

    @Test
    fun jvcLoadsWithoutFallback() {
        val l = AapCerts.loadWithFallback(TestAssets.opener, "jvc")
        assertEquals("откат на hur: ${l.error}", "jvc", l.creds.certSet)
        assertEquals(null, l.error)
    }

    @Test
    fun pkcs1KeyIsWrappedToPkcs8() {
        val key = AapCerts.parsePrivateKey(String(TestAssets.opener("aa/jvc/key.pem").readBytes()))
        assertEquals("PKCS#8", key.format)
    }

    private fun check(set: String, tls12: Boolean) {
        val r = AapTlsSelfTest.run(TestAssets.opener, set, tls12)
        println("TLS selftest set=$set tls12=$tls12 -> ${r.ok} ${r.detail}")
        assertTrue("set=$set tls12=$tls12: ${r.detail}", r.ok)
        if (tls12) assertTrue(r.detail, r.detail.startsWith("TLSv1.2"))
    }

    @Test fun handshakeJvcAuto() = check("jvc", false)
    @Test fun handshakeHurAuto() = check("hur", false)
    @Test fun handshakeJvcTls12() = check("jvc", true)
    @Test fun handshakeHurTls12() = check("hur", true)

    @Test
    fun clientCertificateReachesServer() {
        // клиент jvc, сервер hur: сервер требует сертификат клиента; handshake проходит только если он принят
        val client = AapTls(AapCerts.load(TestAssets.opener, "jvc"))
        val server = AapTls(AapCerts.load(TestAssets.opener, "hur"), serverMode = true)
        var toServer = client.handshakeStep()
        repeat(6) {
            val toClient = server.handshakeStep(toServer)
            toServer = client.handshakeStep(toClient)
        }
        assertTrue(client.handshakeDone && server.handshakeDone)
        val big = ByteArray(16384) { it.toByte() }
        val rec = client.encrypt(big)
        assertTrue(rec.size > big.size)
        assertEquals(big.toList(), server.decrypt(rec).plaintext.toList())
    }
}
