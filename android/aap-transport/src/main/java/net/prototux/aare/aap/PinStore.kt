package net.prototux.aare.aap

import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager

/**
 * The head unit certificates the user trusts. Open-source head units cannot be required to chain
 * to Google's CA, so each head unit is trusted by the SHA-256 of its leaf certificate instead.
 * A key can hold several: head units that report the same identity, or one whose certificate was
 * renewed and trusted again.
 */
interface PinStore {
    /** Additional issuer and TLS-role checks for a presented receiver chain. These also apply to known pins. */
    @Throws(CertificateException::class)
    fun checkReceiverChain(chain: List<X509Certificate>) {}

    /** The trusted SHA-256 fingerprints (lowercase hex) of leaf certificates for [peerKey]; empty if none. */
    fun get(peerKey: String): Set<String>

    /** Trusts [sha256] for [peerKey], next to the ones already trusted. */
    fun add(peerKey: String, sha256: String)
}

/** A [PinStore] that lives only as long as the object, for tests and tools. */
class MemoryPinStore : PinStore {
    private val pins = java.util.concurrent.ConcurrentHashMap<String, Set<String>>()
    override fun get(peerKey: String): Set<String> = pins[peerKey].orEmpty()
    override fun add(peerKey: String, sha256: String) { pins.merge(peerKey, setOf(sha256)) { a, b -> a + b } }
}

fun fingerprint(certificate: X509Certificate): String =
    MessageDigest.getInstance("SHA-256").digest(certificate.encoded).joinToString("") { "%02x".format(it) }

/** Why the phone refused a head unit's certificate: [fingerprint] is not trusted for [peerKey]. */
class PinRefusal(
    val peerKey: String,
    val fingerprint: String,
    /** The key already had trusted certificates, and this is not one of them. */
    val changed: Boolean,
)

/**
 * Accepts the head unit certificate if its fingerprint is trusted for [peerKey]. With
 * [trustOnFirstUse], a key with nothing trusted yet trusts the first certificate it sees; otherwise
 * an unknown certificate is refused and [refusal] says which one, for the user to trust.
 * Only the leaf is compared: head units are not expected to chain to anything we know.
 * A first-use pin is only written by [commit], once the handshake has proven the peer holds the key.
 */
internal class PinningTrustManager(
    private val peerKey: String,
    private val pins: PinStore,
    private val trustOnFirstUse: Boolean,
) : X509TrustManager {
    /** Set when the head unit's certificate was refused. */
    @Volatile var refusal: PinRefusal? = null
    /** Set when this side refused the head unit, whatever the reason. */
    @Volatile var refused = false
    /** Set when the head unit presented no certificate at all: there is nothing to trust. */
    @Volatile var noCertificate = false
    /** Only for reporting a failed handshake. This chain has not proved possession of the key. */
    @Volatile var presentedChain: List<X509Certificate> = emptyList()
        private set
    @Volatile var chainRejection: CertificateException? = null
        private set
    @Volatile private var pending: String? = null

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        try {
            val leaf = chain?.firstOrNull() ?: run {
                noCertificate = true
                throw CertificateException("head unit sent no certificate")
            }
            presentedChain = chain.toList()
            try {
                pins.checkReceiverChain(presentedChain)
            } catch (invalid: CertificateException) {
                chainRejection = invalid
                throw invalid
            }
            val actual = fingerprint(leaf)
            synchronized(pins) {
                val trusted = pins.get(peerKey)
                when {
                    actual in trusted -> Unit
                    trustOnFirstUse && trusted.isEmpty() -> pending = actual
                    else -> {
                        refusal = PinRefusal(peerKey, actual, changed = trusted.isNotEmpty())
                        throw CertificateException("head unit certificate for $peerKey not trusted (SHA-256 $actual)")
                    }
                }
            }
        } catch (e: Exception) {
            refused = true
            throw e
        }
    }

    /** Trusts the certificate seen during the handshake, if the head unit had none trusted yet. */
    fun commit() {
        val candidate = pending ?: return
        synchronized(pins) {
            val current = pins.get(peerKey)
            if (current.isNotEmpty() && candidate !in current) {
                refused = true
                refusal = PinRefusal(peerKey, candidate, changed = true)
                throw CertificateException("head unit certificate changed during TLS (SHA-256 $candidate)")
            }
            if (current.isEmpty()) pins.add(peerKey, candidate)
        }
    }

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}
