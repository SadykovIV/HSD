package com.hsd.universal

import net.prototux.aare.aap.PhoneIdentity
import java.io.InputStream
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.time.Instant

/** Automatic credentials always have an independently verified automotive issuer and TLS role. */
internal object AutomaticAaIdentity {
    data class Candidate(val source: String, val identity: PhoneIdentity)

    fun readStore(input: InputStream, references: AaCertificateReference, now: Instant): PhoneIdentity {
        val bytes = ByteArray(65_537)
        var size = 0
        while (size < bytes.size) {
            val read = input.read(bytes, size, bytes.size - size)
            if (read < 0) break
            check(read > 0) { "Phone identity stream stalled" }
            size += read
        }
        require(size <= 65_536) { "Phone identity exceeds size limit" }
        val store = try { KeyStore.getInstance("PKCS12").apply { load(java.io.ByteArrayInputStream(bytes, 0, size), CharArray(0)) } }
            finally { bytes.fill(0) }
        val aliases = store.aliases().toList().filter(store::isKeyEntry)
        require(aliases.size == 1) { "Expected one phone identity" }
        val alias = aliases.single()
        val chain = store.getCertificateChain(alias).map { it as X509Certificate }
        val trusted = references.trustedPhoneChain(chain, now)
            ?: throw CertificateException("Invalid automatic Android Auto identity")
        return PhoneIdentity(trusted.first(), store.getKey(alias, CharArray(0)) as PrivateKey, trusted.drop(1))
            .also(AaApkIdentity::verifyKey)
    }

    /** A missing/broken optional provider cannot take a working bundled identity out of service.
     * The first source wins ties, so a shared certificate does not cause needless source changes. */
    fun choose(providers: List<Pair<String, () -> PhoneIdentity?>>, rejected: (String, Exception) -> Unit): Candidate? {
        var best: Candidate? = null
        for ((source, load) in providers) {
            val identity = try { load() } catch (error: InterruptedException) {
                Thread.currentThread().interrupt()
                throw error
            } catch (error: Exception) { rejected(source, error); null } ?: continue
            if (best == null || identity.certificate.notAfter.after(best.identity.certificate.notAfter))
                best = Candidate(source, identity)
        }
        return best
    }
}
