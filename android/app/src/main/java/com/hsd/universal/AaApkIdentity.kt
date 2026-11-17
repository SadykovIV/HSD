package com.hsd.universal

import net.prototux.aare.aap.PhoneIdentity
import java.io.File
import java.security.KeyFactory
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Instant
import java.util.Base64
import java.util.zip.ZipFile
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Reads public APK code; never loads or executes code from another application.
 * The key-blob format/KDF is documented by prototux/AARE's extract_key.py (GPL-3.0).
 * Names and offsets are deliberately not tied to a particular Android Auto release.
 * A candidate is usable only after issuer, TLS role, dates and proof of key possession pass.
 */
internal object AaApkIdentity {
    private data class Certificate(val pem: ByteArray, val certificate: X509Certificate)
    private data class Blob(val salt: ByteArray, val encrypted: ByteArray)
    private val begin = "-----BEGIN CERTIFICATE-----".toByteArray(Charsets.US_ASCII)
    private val end = "-----END CERTIFICATE-----".toByteArray(Charsets.US_ASCII)
    private const val MAX_DEX_BYTES = 64 * 1024 * 1024
    private const val MAX_TOTAL_BYTES = 256 * 1024 * 1024

    fun extract(apks: List<File>, references: AaCertificateReference, now: Instant): PhoneIdentity {
        val certificates = linkedMapOf<String, Certificate>()
        val blobs = mutableListOf<Blob>()
        var total = 0L
        for (apk in apks) ZipFile(apk).use { zip ->
            for (entry in zip.entries()) {
                if (!entry.name.matches(Regex("classes[0-9]*\\.dex"))) continue
                require(entry.size in 8..MAX_DEX_BYTES.toLong()) { "Unsupported DEX size" }
                total += entry.size
                require(total <= MAX_TOTAL_BYTES) { "APK code exceeds identity scan limit" }
                val dex = zip.getInputStream(entry).use { input ->
                    val bytes = ByteArray(entry.size.toInt())
                    var used = 0
                    while (used < bytes.size) {
                        val count = input.read(bytes, used, bytes.size - used)
                        require(count > 0) { "Truncated DEX container" }
                        used += count
                    }
                    require(input.read() == -1) { "DEX exceeds its declared size" }
                    bytes
                }
                require(dex.size.toLong() == entry.size && dex.copyOfRange(0, 4).contentEquals(byteArrayOf(100, 101, 120, 10))) {
                    "Invalid DEX container"
                }
                scan(dex, certificates, blobs)
            }
        }
        val roots = certificates.values.filter { references.isAutomotiveRoot(it.certificate) }
        val availableChain = certificates.values.map { it.certificate }.distinct()
        val leaves = certificates.values.mapNotNull { cert ->
            references.trustedPhoneChain(listOf(cert.certificate) + availableChain.filter { it != cert.certificate }, now)
                ?.let { cert to it }
        }.sortedByDescending { it.first.certificate.notAfter }
        for ((leaf, chain) in leaves) for (root in roots) for (blob in blobs) {
            for ((first, second) in listOf(leaf.pem to root.pem, root.pem to leaf.pem)) {
                val material = derive(blob.salt, first, second)
                val identity = try {
                    val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
                    cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(material, 0, 32, "AES"), IvParameterSpec(material, 32, 16))
                    val plain = cipher.doFinal(blob.encrypted)
                    try {
                        if (plain.size <= 54) null else {
                            val der = Base64.getMimeDecoder().decode(plain.copyOfRange(28, plain.size - 26))
                            try {
                                val key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(der))
                                PhoneIdentity(leaf.certificate, key, chain.drop(1)).also(::verifyKey)
                            } finally { der.fill(0) }
                        }
                    } finally { plain.fill(0) }
                } catch (_: java.security.GeneralSecurityException) { null }
                  catch (_: IllegalArgumentException) { null }
                finally { material.fill(0) }
                if (identity != null) return identity
            }
        }
        throw java.security.cert.CertificateException("No valid Android Auto phone identity in installed APK")
    }

    internal fun verifyKey(identity: PhoneIdentity) {
        val algorithm = when (identity.privateKey.algorithm) {
            "RSA" -> "SHA256withRSA"
            "EC" -> "SHA256withECDSA"
            else -> throw java.security.InvalidKeyException("Unsupported phone key algorithm")
        }
        val challenge = "HSD Android Auto identity validation".toByteArray(Charsets.US_ASCII)
        val proof = Signature.getInstance(algorithm).apply { initSign(identity.privateKey); update(challenge) }.sign()
        require(Signature.getInstance(algorithm).apply { initVerify(identity.certificate.publicKey); update(challenge) }.verify(proof)) {
            "Phone certificate and private key do not match"
        }
    }

    private fun scan(dex: ByteArray, certificates: MutableMap<String, Certificate>, blobs: MutableList<Blob>) {
        var offset = 0
        while (offset <= dex.size - begin.size) {
            if (!dex.matches(offset, begin)) { offset++; continue }
            val start = offset
            val limit = minOf(dex.size, start + 8192)
            while (offset < limit && dex[offset] != 0.toByte()) offset++
            if (offset == limit) continue
            val pem = dex.copyOfRange(start, offset)
            if (pem.toString(Charsets.US_ASCII).contains(end.toString(Charsets.US_ASCII))) {
                val cert = runCatching {
                    CertificateFactory.getInstance("X.509").generateCertificate(pem.inputStream()) as X509Certificate
                }.getOrNull()
                if (cert != null) {
                    certificates.putIfAbsent(pem.toString(Charsets.US_ASCII), Certificate(pem, cert))
                    require(certificates.size <= 128) { "Too many APK certificate candidates" }
                }
            }
        }
        // Dalvik array-payloads: ident 0x0300, element width 1, u32 count, contents.
        // Adjacent payloads may have one code unit of alignment padding between them.
        for (start in 0..(dex.size - 8) step 2) {
            val saltSize = arraySize(dex, start)
            if (saltSize !in 64..1024) continue
            for (padding in intArrayOf(0, 2)) {
                val next = start + 8 + saltSize + padding
                val encryptedSize = arraySize(dex, next)
                if (encryptedSize !in 512..8192 || encryptedSize % 16 != 0) continue
                blobs += Blob(dex.copyOfRange(start + 8, start + 8 + saltSize),
                    dex.copyOfRange(next + 8, next + 8 + encryptedSize))
                require(blobs.size <= 128) { "Too many APK key candidates" }
            }
        }
    }

    private fun arraySize(bytes: ByteArray, at: Int): Int {
        if (at < 0 || at > bytes.size - 8 || bytes[at] != 0.toByte() || bytes[at + 1] != 3.toByte() ||
            bytes[at + 2] != 1.toByte() || bytes[at + 3] != 0.toByte()) return -1
        var size = 0L
        for (i in 0..3) size = size or ((bytes[at + 4 + i].toLong() and 255) shl (8 * i))
        return if (size <= bytes.size - at - 8) size.toInt() else -1
    }

    private fun ByteArray.matches(at: Int, pattern: ByteArray): Boolean =
        pattern.indices.all { this[at + it] == pattern[it] }

    internal fun derive(salt: ByteArray, first: ByteArray, second: ByteArray): ByteArray {
        require(salt.isNotEmpty())
        val buffer = ByteArray(48)
        fun mix(source: ByteArray) {
            for (i in source.indices) for (j in buffer.indices) {
                val value = buffer[j].toInt() and 255
                // Re-read source[i] on EVERY inner iteration: source and buffer alias in self-mixing rounds.
                buffer[j] = ((((value ushr 7) or (value shl 1)) + 33) xor
                    (salt[j % salt.size].toInt() and 255) xor (source[i].toInt() and 255)).toByte()
            }
        }
        mix(first); mix(second); repeat(7) { mix(buffer) }
        return buffer
    }
}
