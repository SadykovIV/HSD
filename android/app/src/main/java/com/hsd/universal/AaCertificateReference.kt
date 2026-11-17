package com.hsd.universal

import android.content.Context
import androidx.annotation.StringRes
import java.security.GeneralSecurityException
import java.security.cert.CertPathBuilder
import java.security.cert.CertStore
import java.security.cert.CertificateFactory
import java.security.cert.CollectionCertStoreParameters
import java.security.cert.PKIXBuilderParameters
import java.security.cert.PKIXCertPathBuilderResult
import java.security.cert.TrustAnchor
import java.security.cert.X509CertSelector
import java.security.cert.X509Certificate
import java.time.Instant
import java.util.Date

/** Public Android Auto 17.8.663874 credentials used to distinguish the phone, issuer and receiver roles. */
internal class AaCertificateReference(private val root: X509Certificate, private val carService: X509Certificate) {
    fun isAutomotiveRoot(certificate: X509Certificate) = certificate == root

    /** Unlike an explicitly selected independent identity, an automatic APK identity MUST chain to our anchor. */
    fun trustedPhoneChain(chain: List<X509Certificate>, now: Instant): List<X509Certificate>? =
        if (chain.isNotEmpty() && permitsTlsRole(chain.first(), "1.3.6.1.5.5.7.3.1")) automotivePath(chain, now) else null

    enum class Match(@StringRes val label: Int) {
        CAR_SERVICE(R.string.aa_reference_car_service),
        GOOGLE_AUTOMOTIVE_LINK(R.string.aa_reference_google_chain),
        NO_MATCH(R.string.aa_reference_no_match),
    }

    /** A receiver claiming the automotive issuer must supply a valid TLS client chain.
     * Independent receivers still use the existing per-device pin checks. */
    fun receiverError(chain: List<X509Certificate>, now: Instant): Int? {
        val leaf = chain.first()
        val key = leaf.publicKey.encoded
        if (key.contentEquals(carService.publicKey.encoded)) return R.string.aa_receiver_phone_key
        if (key.contentEquals(root.publicKey.encoded)) return R.string.aa_receiver_issuer_key
        if (!claimsAutomotiveIssuer(chain)) return null

        return if (permitsTlsRole(leaf, "1.3.6.1.5.5.7.3.2") && automotivePath(chain, now) != null) null
            else R.string.aa_receiver_invalid_chain
    }

    /** For a KeyChain identity issued under the bundled CA, send its verified leaf and intermediates
     * in TLS order. The peer owns its trust anchor. Other issuers retain the user's chain. */
    fun phoneChain(chain: List<X509Certificate>, now: Instant): List<X509Certificate>? {
        if (!claimsAutomotiveIssuer(chain)) return chain
        if (!permitsTlsRole(chain.first(), "1.3.6.1.5.5.7.3.1")) return null
        return automotivePath(chain, now)
    }

    private fun claimsAutomotiveIssuer(chain: List<X509Certificate>) =
        chain.any { it.issuerX500Principal == root.subjectX500Principal }

    private fun permitsTlsRole(leaf: X509Certificate, role: String): Boolean {
        val usages = leaf.extendedKeyUsage
        return leaf.keyUsage?.firstOrNull() != false && (usages == null || role in usages || "2.5.29.37.0" in usages)
    }

    fun identify(chain: List<X509Certificate>, now: Instant): Match {
        val leaf = chain.firstOrNull() ?: return Match.NO_MATCH
        if (leaf == carService) return Match.CAR_SERVICE // Exact identity, even after expiry. Dates are reported separately.
        return if (automotivePath(chain, now) != null) Match.GOOGLE_AUTOMOTIVE_LINK else Match.NO_MATCH
    }

    private fun automotivePath(chain: List<X509Certificate>, now: Instant): List<X509Certificate>? {
        val leaf = chain.first()
        if (leaf.basicConstraints >= 0) return null
        val target = X509CertSelector().apply { certificate = leaf }
        return try {
            root.checkValidity(Date.from(now))
            val parameters = PKIXBuilderParameters(setOf(TrustAnchor(root, null)), target).apply {
                date = Date.from(now)
                // The projection network need not have internet connectivity.
                isRevocationEnabled = false
                addCertStore(CertStore.getInstance("Collection", CollectionCertStoreParameters(chain)))
            }
            val result = CertPathBuilder.getInstance("PKIX").build(parameters) as PKIXCertPathBuilderResult
            result.certPath.certificates.map { it as X509Certificate }
        } catch (_: GeneralSecurityException) {
            null
        }
    }

    companion object {
        fun from(context: Context): AaCertificateReference {
            val factory = CertificateFactory.getInstance("X.509")
            fun read(resource: Int) = context.resources.openRawResource(resource).use {
                factory.generateCertificate(it) as X509Certificate
            }
            return AaCertificateReference(read(R.raw.aa_google_automotive_link), read(R.raw.aa_car_service))
        }
    }
}
