package com.hsd.universal

import android.content.Context
import android.content.ContextWrapper
import android.os.Bundle
import net.prototux.aare.aap.fingerprint
import org.json.JSONObject
import java.io.File
import java.time.Instant

/** Real Android crypto/AtomicFile; the fixture never changes the installation's identity cache. */
internal fun checkIdentityUpdates(context: Context): Bundle {
    val directory = File(context.cacheDir, "qa-identity-updates").apply { mkdirs() }
    val isolated = object : ContextWrapper(context) {
        override fun getNoBackupFilesDir() = directory
    }
    val file = File(directory, "aa-downloaded.json")
    val feed = File(context.cacheDir, "qa-identity-feed.json").readBytes()
    val references = AaCertificateReference.from(context)
    val now = Instant.now()
    file.delete()
    try {
        check(AaIdentityUpdates.install(isolated, feed, references, now))
        val identity = checkNotNull(AaIdentityUpdates.load(isolated, references, now))
        val original = file.readBytes()
        val forgedVersion = JSONObject(feed.toString(Charsets.UTF_8)).put("version_code", Long.MAX_VALUE).toString().toByteArray()
        check(!AaIdentityUpdates.install(isolated, forgedVersion, references, now))
        for (bad in listOf(byteArrayOf(), feed.copyOf(100),
                JSONObject(feed.toString(Charsets.UTF_8)).put("expire_date", 20991231).toString().toByteArray())) {
            check(runCatching { AaIdentityUpdates.install(isolated, bad, references, now) }.isFailure)
            check(file.readBytes().contentEquals(original))
        }
        // Simulate a process death between startWrite and finishWrite; AtomicFile recovers old data.
        val atomic = android.util.AtomicFile(file)
        atomic.startWrite().use { it.write(byteArrayOf(1, 2, 3)) }
        check(fingerprint(checkNotNull(AaIdentityUpdates.load(isolated, references, now)).certificate) == fingerprint(identity.certificate))
        check(file.readBytes().contentEquals(original))
        return Bundle().apply {
            putString("identity_update", "Verified public feed; offline reload; malformed and metadata-only replacement refused; interrupted atomic write recovered")
            putString("certificate_expiry", identity.certificate.notAfter.toInstant().toString())
        }
    } finally { directory.deleteRecursively() }
}
