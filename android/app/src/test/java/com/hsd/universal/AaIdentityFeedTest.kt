package com.hsd.universal

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import java.io.File
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.time.Instant
import java.util.Base64

class AaIdentityFeedTest {
    private fun bytes() = File(requireNotNull(System.getenv("HSD_AA_FEED"))).readBytes()
    private fun references(): AaCertificateReference {
        val factory = CertificateFactory.getInstance("X.509")
        fun read(name: String) = File("src/main/res/raw/$name.der").inputStream().use { factory.generateCertificate(it) as X509Certificate }
        return AaCertificateReference(read("aa_google_automotive_link"), read("aa_car_service"))
    }
    private fun decode(bytes: ByteArray = bytes(), now: Instant = Instant.now()) = AaIdentityFeed.decode(bytes, references(), now)
    @Test fun actualPublicPackageHasAValidPhoneIdentity() {
        val result = decode()
        assertTrue(result.version > 0)
        AaApkIdentity.verifyKey(result.identity)
        assertTrue(result.identity.certificate.notAfter.toInstant() > Instant.now())
    }
    @Test fun encryptedEnvelopeCannotOverrideSignedCertificateExpiry() {
        val invalid = JSONObject(bytes().toString(Charsets.UTF_8)).put("expire_date", 20991231)
        assertThrows(Exception::class.java) { decode(invalid.toString().toByteArray()) }
    }
    @Test fun rejectsExpiredAndNotYetValidPackages() {
        val identity = decode().identity
        assertThrows(Exception::class.java) { decode(now = identity.certificate.notAfter.toInstant().plusSeconds(1)) }
        assertThrows(Exception::class.java) { decode(now = identity.certificate.notBefore.toInstant().minusSeconds(1)) }
    }
    @Test fun rejectsTruncatedCorruptOversizeAndNonPackages() {
        val json = JSONObject(bytes().toString(Charsets.UTF_8))
        val encrypted = Base64.getDecoder().decode(json.getString("package_data"))
        encrypted[encrypted.lastIndex] = (encrypted.last().toInt() xor 1).toByte()
        json.put("package_data", Base64.getEncoder().encodeToString(encrypted))
        for (bad in listOf(json.toString().toByteArray(), bytes().copyOf(50), byteArrayOf(), ByteArray(AaIdentityFeed.MAX_BYTES + 1)))
            assertThrows(Exception::class.java) { decode(bad) }
    }
    @Test fun boundedInputCannotAllocateUnboundedData() {
        assertArrayEquals(bytes(), AaIdentityUpdates.readLimited(bytes().inputStream()))
        assertThrows(Exception::class.java) { AaIdentityUpdates.readLimited(ByteArray(AaIdentityFeed.MAX_BYTES + 1).inputStream()) }
    }
}
