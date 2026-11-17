package com.hsd.universal

import android.content.Context
import android.content.pm.PackageManager
import android.util.AtomicFile
import net.prototux.aare.aap.PhoneIdentity
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.time.Instant

/** An APK update invalidates the cache. Disabled Android Auto remains readable; root is unnecessary. */
internal class InstalledAaIdentity(private val context: Context, private val references: AaCertificateReference) {
    private val cache = AtomicFile(File(context.noBackupFilesDir, "aa-installed.identity"))

    fun load(now: Instant): PhoneIdentity? {
        val info = try {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo("com.google.android.projection.gearhead", PackageManager.MATCH_DISABLED_COMPONENTS)
        } catch (_: PackageManager.NameNotFoundException) { return null }
        val application = checkNotNull(info.applicationInfo)
        val files = (listOf(application.sourceDir) + application.splitSourceDirs.orEmpty()).map(::File)
        val revision = listOf(info.longVersionCode.toString(), info.lastUpdateTime.toString(),
            files.joinToString("|") { "${it.path}:${it.length()}:${it.lastModified()}" }).joinToString(";")
        fun validate(identity: PhoneIdentity): PhoneIdentity {
            val chain = references.trustedPhoneChain(listOf(identity.certificate) + identity.chain, now)
                ?: throw java.security.cert.CertificateException("Invalid installed Android Auto identity")
            AaApkIdentity.verifyKey(identity)
            return PhoneIdentity(chain.first(), identity.privateKey, chain.drop(1))
        }
        val cached = runCatching {
            DataInputStream(cache.openRead()).use { input ->
                check(input.readInt() == 1 && input.readUTF() == revision)
                val store = KeyStore.getInstance("PKCS12").apply { load(input, CharArray(0)) }
                val chain = store.getCertificateChain("phone").map { it as X509Certificate }
                validate(PhoneIdentity(chain.first(), store.getKey("phone", CharArray(0)) as PrivateKey, chain.drop(1)))
            }
        }.getOrNull()
        if (cached != null) return cached
        try {
            val identity = validate(AaApkIdentity.extract(files, references, now))
            val store = KeyStore.getInstance("PKCS12").apply {
                load(null, CharArray(0))
                setKeyEntry("phone", identity.privateKey, CharArray(0), (listOf(identity.certificate) + identity.chain).toTypedArray())
            }
            val output = cache.startWrite()
            try {
                val data = DataOutputStream(output)
                data.writeInt(1); data.writeUTF(revision); store.store(data, CharArray(0)); data.flush()
                cache.finishWrite(output)
            } catch (error: Throwable) { cache.failWrite(output); throw error }
            AppLog.i("AA phone identity: verified installed Android Auto ${info.versionName}; expires=${identity.certificate.notAfter.toInstant()}", false)
            return identity
        } catch (error: Exception) {
            throw IllegalStateException(context.getString(R.string.aa_installed_identity_unavailable), error)
        }
    }
}
