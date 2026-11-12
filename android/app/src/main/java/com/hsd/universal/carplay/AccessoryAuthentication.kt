package com.hsd.universal.carplay

import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.ECPublicKey
import java.security.interfaces.RSAPublicKey
import org.bouncycastle.asn1.ASN1Integer
import org.bouncycastle.asn1.DERSequence

/**
 * iAP authentication ICs take a digest. Only the fresh preimage is kept for signature verification.
 */
internal class AccessoryAuthentication(private val authorities: Collection<X509Certificate>) {
    private var leaf: X509Certificate? = null
    private var nonce: ByteArray? = null

    fun challenge(certificateData: ByteArray): ByteArray {
        leaf = null
        nonce = null
        val certs =
            CertificateFactory.getInstance("X.509")
                .generateCertificates(certificateData.inputStream())
                .map { it as X509Certificate }
        require(certs.size in 1..12)
        val device = certs.single { it.basicConstraints < 0 }
        val subject = javax.security.auth.x500.X500Principal.CANONICAL
        require(
            device.subjectX500Principal.getName(subject).split(",").any {
                it.startsWith("cn=ipa_")
            } && device.notBefore.time >= 1149033600000L
        ) {
            "Сертификат не идентифицирует аксессуар iAP"
        }
        require(device.keyUsage?.getOrNull(0) != false)
        fun chainsToApple(cert: X509Certificate, seen: Set<X509Certificate>): Boolean {
            if (cert in authorities) return true
            return (certs + authorities).any { ca ->
                ca != cert &&
                    ca !in seen &&
                    ca.basicConstraints >= 0 &&
                    ca.subjectX500Principal == cert.issuerX500Principal &&
                    ca.keyUsage?.getOrNull(5) != false &&
                    ca.basicConstraints >= seen.size &&
                    runCatching { cert.verify(ca.publicKey) }.isSuccess &&
                    chainsToApple(ca, seen + cert)
            }
        }
        require(chainsToApple(device, emptySet())) {
            "Сертификат магнитолы не подписан центром Apple Accessories"
        }
        val hash =
            when (val key = device.publicKey) {
                is ECPublicKey -> {
                    require(key.params.order.bitLength() == 256)
                    "SHA-256"
                }
                is RSAPublicKey -> {
                    require(key.modulus.bitLength() >= 1024)
                    "SHA-1"
                }
                else -> error("Неизвестный ключ iAP")
            }
        val secret = ByteArray(32).also { SecureRandom().nextBytes(it) }
        leaf = device
        nonce = secret
        return MessageDigest.getInstance(hash).digest(secret)
    }

    fun verify(response: ByteArray): Boolean {
        val secret = nonce ?: return false
        nonce = null
        val key = leaf?.publicKey ?: return false
        leaf = null
        return runCatching {
                val ec = key is ECPublicKey
                val wire =
                    if (ec && response.size == 64) {
                        DERSequence(
                                arrayOf(
                                    ASN1Integer(BigInteger(1, response.copyOfRange(0, 32))),
                                    ASN1Integer(BigInteger(1, response.copyOfRange(32, 64))),
                                )
                            )
                            .encoded
                    } else response
                Signature.getInstance(if (ec) "SHA256withECDSA" else "SHA1withRSA").run {
                    initVerify(key)
                    update(secret)
                    verify(wire)
                }
            }
            .getOrDefault(false)
    }
}
