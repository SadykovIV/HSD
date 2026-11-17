package com.hsd.universal

import net.prototux.aare.aap.PhoneIdentity
import org.json.JSONObject
import java.security.KeyFactory
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Mgears' public data envelope, never executable code. Its AES wrapper is not authentication:
 * trust comes from HTTPS and the pinned automotive CA, TLS purpose and proof of key possession. */
internal object AaIdentityFeed {
    const val URL = "https://api.mgears.io/v1/mgears/api/mgears_aa_key.json"
    const val MAX_BYTES = 32_768
    data class Verified(val version: Long, val identity: PhoneIdentity)

    fun decode(bytes: ByteArray, references: AaCertificateReference, now: Instant): Verified {
        require(bytes.size in 1..MAX_BYTES) { "Invalid identity feed size" }
        val json = JSONObject(bytes.toString(Charsets.UTF_8))
        val version = json.getLong("version_code")
        require(version > 0)
        val expiry = json.getLong("expire_date").toString()
        require(expiry.matches(Regex("[0-9]{8}")))
        val encrypted = Base64.getDecoder().decode(json.getString("package_data"))
        require(encrypted.size in 16..16_384 && encrypted.size % 16 == 0)
        // Public MStick APK signer digest and format IV. No device/account identifiers are sent.
        val key = "0e570b8594f258f80200af8ce74786ec0839c70b14e20958799e1afc35f1db53"
            .chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val plain = Cipher.getInstance("AES/CBC/PKCS5Padding").run {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec("This is IV value".toByteArray(Charsets.US_ASCII)))
            doFinal(encrypted)
        }
        key.fill(0)
        try {
            var at = 0
            fun field(): ByteArray {
                require(at + 8 <= plain.size)
                val length = plain.copyOfRange(at, at + 8).toString(Charsets.US_ASCII)
                require(length.matches(Regex("[0-9]{8}")))
                val count = length.toInt(); at += 8
                require(count in 1..8192 && count <= plain.size - at)
                return plain.copyOfRange(at, at + count).also { at += count }
            }
            require(field().toString(Charsets.US_ASCII) == expiry) { "Conflicting identity expiry" }
            val certificates = CertificateFactory.getInstance("X.509").generateCertificates(field().inputStream())
                .map { it as X509Certificate }
            val pem = field()
            try {
                require(at == plain.size) { "Trailing identity data" }
                val text = pem.toString(Charsets.US_ASCII).trim()
                require(text.startsWith("-----BEGIN PRIVATE KEY-----") && text.endsWith("-----END PRIVATE KEY-----"))
                val encoded = Base64.getMimeDecoder().decode(text.removePrefix("-----BEGIN PRIVATE KEY-----").removeSuffix("-----END PRIVATE KEY-----"))
                try {
                    val chain = references.trustedPhoneChain(certificates, now)
                        ?: error("Untrusted or invalid downloaded Android Auto identity")
                    val leaf = chain.first()
                    require(DateTimeFormatter.BASIC_ISO_DATE.format(leaf.notAfter.toInstant().atZone(ZoneOffset.UTC).toLocalDate()) == expiry)
                    val privateKey = KeyFactory.getInstance(leaf.publicKey.algorithm).generatePrivate(PKCS8EncodedKeySpec(encoded))
                    return Verified(version, PhoneIdentity(leaf, privateKey, chain.drop(1)).also(AaApkIdentity::verifyKey))
                } finally { encoded.fill(0) }
            } finally { pem.fill(0) }
        } finally { plain.fill(0) }
    }
}
