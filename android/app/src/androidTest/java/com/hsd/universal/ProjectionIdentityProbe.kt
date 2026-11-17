package com.hsd.universal

import android.content.Context
import android.content.ContextWrapper
import android.os.Bundle
import net.prototux.aare.aap.fingerprint
import java.io.File
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.time.Instant
import java.util.UUID

/** The signed app must negotiate a trusted identity with the AA package entirely absent. */
internal fun checkStandalonePhoneIdentity(context: Context): Bundle {
    check(runCatching { context.packageManager.getPackageInfo("com.google.android.projection.gearhead", 0) }
        .exceptionOrNull() is android.content.pm.PackageManager.NameNotFoundException)
    val identity = ProjectionIdentity(context).phone("qa-standalone")
    val references = AaCertificateReference.from(context)
    check(references.trustedPhoneChain(listOf(identity.certificate) + identity.chain, Instant.now()) != null)
    AaApkIdentity.verifyKey(identity)
    val bundle = context.assets.open("projection/phone.p12").use { AutomaticAaIdentity.readStore(it, references, Instant.now()) }
    check(fingerprint(identity.certificate) == fingerprint(bundle.certificate))
    return Bundle().apply {
        putString("standalone_identity", "PASS: AA package absent; bundled issuer, TLS role and key possession verified")
        putString("certificate_expires", identity.certificate.notAfter.toInstant().toString())
    }
}

/** Automatic selection, persistent cache and an explicit override against the installed APK. */
internal fun checkInstalledPhoneIdentity(base: Context): Bundle {
    val run = UUID.randomUUID().toString()
    val storage = File(base.cacheDir, run).apply { mkdirs() }
    val isolated = object : ContextWrapper(base) {
        override fun getNoBackupFilesDir(): File = storage
        override fun getSharedPreferences(name: String, mode: Int) = base.getSharedPreferences("$run-$name", mode)
    }
    try {
        val identities = ProjectionIdentity(isolated)
        val first = identities.phone("qa")
        val references = AaCertificateReference.from(isolated)
        check(references.trustedPhoneChain(listOf(first.certificate) + first.chain, Instant.now()) != null)
        val description = ProjectionDescription.create(isolated)
        val cache = File(storage, "aa-installed.identity")
        check(cache.isFile)
        val bytes = cache.readBytes()
        val second = ProjectionIdentity(isolated).phone("another-head-unit")
        check(fingerprint(first.certificate) == fingerprint(second.certificate))
        check(cache.readBytes().contentEquals(bytes)) // Successful reuse must not rewrite PKCS12.
        check(ProjectionDescription.create(isolated).instanceId == description.instanceId)
        cache.writeBytes(byteArrayOf(1, 2, 3))
        check(fingerprint(identities.phone("qa").certificate) == fingerprint(first.certificate))
        check(cache.length() > 3)
        identities.selectDefault("")
        val generated = identities.phone("qa")
        check(fingerprint(generated.certificate) != fingerprint(first.certificate))
        check(ProjectionDescription.create(isolated).instanceId == description.instanceId)
        identities.authenticated("qa")
        identities.selectDefault(null)
        check(fingerprint(identities.phone("qa").certificate) == fingerprint(first.certificate))
        val expired = ProjectionIdentity(isolated, java.time.Clock.fixed(
            first.certificate.notAfter.toInstant().plusSeconds(1), java.time.ZoneOffset.UTC))
        check(runCatching { expired.phone("qa") }.isFailure) // Never silently substitute a self-signed key.
        return Bundle().apply { putString("installed_identity", "PASS: trusted installed APK, persisted cache, corrupt cache recovery, explicit selection, stable instance ID, expired identity refused") }
    } finally {
        for (name in listOf("usb-receivers", "projection-route", "aa-receiver-details")) base.deleteSharedPreferences("$run-$name")
        storage.deleteRecursively()
    }
}

/** Exercises real Android PKCS12, SharedPreferences and upgrade data without touching the user's keys. */
internal fun checkReceiverIdentities(base: Context): Bundle {
    val run = UUID.randomUUID().toString()
    val storage = File(base.cacheDir, run).apply { mkdirs() }
    val isolated = object : ContextWrapper(base) {
        override fun getNoBackupFilesDir(): File = storage
        override fun getSharedPreferences(name: String, mode: Int) = base.getSharedPreferences("$run-$name", mode)
    }
    try {
        val legacy = "manufacturer|model|description|1"
        val old = "1".repeat(64)
        val next = "2".repeat(64)
        val other = "3".repeat(64)
        isolated.getSharedPreferences("usb-receivers", Context.MODE_PRIVATE).edit()
            .putStringSet(legacy, setOf(old)).commit()
        val first = ProjectionIdentity(isolated)
        // This branch exercises the generated identity explicitly, independently of an
        // installed Android Auto package. Automatic selection is checked by the TLS probe.
        first.selectDefault("")
        check(first.receiverIds() == listOf(legacy) && first.get(legacy) == setOf(old))
        val id = "lan|192.0.2.2"
        val bluetooth = "bt|00:11:22:33:44:55"
        val certificate = first.phone(id).certificate
        val identity = fingerprint(certificate)
        val factory = CertificateFactory.getInstance("X.509")
        fun reference(resource: Int) = base.resources.openRawResource(resource).use {
            factory.generateCertificate(it) as X509Certificate
        }
        val root = reference(R.raw.aa_google_automotive_link)
        val service = reference(R.raw.aa_car_service)
        check(fingerprint(root) == "49e52efc13ad2ed09f204c3b10698bd84bb7105f510558aa14b8119a5c4ad17f")
        check(fingerprint(service) == "39b7417be3f2bcd60b30e3acd4a2995d82661d6d66110e45c10a15d2a3c2ee6e")
        val references = AaCertificateReference.from(isolated)
        val during = Instant.parse("2026-11-14T12:00:00Z")
        check(references.receiverError(listOf(service), during) == R.string.aa_receiver_phone_key)
        check(references.receiverError(listOf(root), during) == R.string.aa_receiver_issuer_key)
        check(references.receiverError(listOf(certificate), during) == null)
        check(references.phoneChain(listOf(service, root), during) == listOf(service))
        check(references.phoneChain(listOf(service), during) == listOf(service))
        check(references.phoneChain(listOf(certificate), during) == listOf(certificate))
        check(references.phoneChain(listOf(certificate, root), during) == null)
        check(references.identify(listOf(service), during) == AaCertificateReference.Match.CAR_SERVICE)
        check(references.identify(listOf(certificate, service, root), during) == AaCertificateReference.Match.NO_MATCH)
        check(references.identify(listOf(root), during) == AaCertificateReference.Match.NO_MATCH)
        // Isolate the path-building branch using a different exact-leaf reference. The peer
        // omits the root, which must be supplied by the application's local trust anchor.
        val issuerOnly = AaCertificateReference(root, certificate)
        check(issuerOnly.receiverError(listOf(service), during) == null)
        check(issuerOnly.receiverError(listOf(service), service.notAfter.toInstant().plusSeconds(1)) == R.string.aa_receiver_invalid_chain)
        check(runCatching { first.checkReceiverChain(listOf(service)) }.exceptionOrNull() is java.security.cert.CertificateException)
        check(issuerOnly.identify(listOf(service), during) == AaCertificateReference.Match.GOOGLE_AUTOMOTIVE_LINK)
        check(issuerOnly.identify(listOf(service), service.notAfter.toInstant().plusSeconds(1)) == AaCertificateReference.Match.NO_MATCH)
        first.observeReceiver(id, listOf(service), tlsEstablished = false)
        check(first.get(id).isEmpty() && first.seen(id) == 0L)
        check(first.receiverReference(id) == AaCertificateReference.Match.CAR_SERVICE)
        check(first.observedFingerprint(id) == fingerprint(service))
        val dates = PhoneCertificateDates(certificate)
        fun at(time: java.time.Instant) = ProjectionIdentity(isolated, java.time.Clock.fixed(time, java.time.ZoneOffset.UTC)).phone(id)
        for (time in listOf(dates.from, dates.until)) check(fingerprint(at(time).certificate) == identity)
        for ((time, message) in listOf(dates.from.minusMillis(1) to R.string.aa_phone_cert_not_yet_valid,
            dates.until.plusMillis(1) to R.string.aa_phone_cert_expired)) {
            val failure = runCatching { at(time) }.exceptionOrNull()
            check(failure is IllegalStateException && failure.message.orEmpty().startsWith(isolated.getString(message)))
        }
        first.add(id, old)
        first.authenticated(id, "DiAuto")
        val second = ProjectionIdentity(isolated)
        check(fingerprint(second.phone(bluetooth).certificate) == identity)
        check(second.receiverName(id) == "DiAuto" && second.seen(id) > 0)
        second.rejected(id, next)
        check(second.get(id) == setOf(old) && second.changed(id) == next)
        check(!second.acceptChange(id, other) && second.get(id) == setOf(old))
        check(runCatching { second.add(id, next) }.isFailure)
        check(second.acceptChange(id, next) && second.changed(id) == null)
        check(ProjectionIdentity(isolated).get(id) == setOf(next))
        second.selectDefault("test-selected-by-user")
        second.select(bluetooth, "")
        check(fingerprint(second.phone(bluetooth).certificate) == identity)
        second.select(bluetooth, null)
        check(second.assignedAlias(bluetooth) == null && second.defaultAlias == "test-selected-by-user")
        second.selectDefault("")
        second.forget(id)
        check(id !in second.receiverIds() && second.get(legacy) == setOf(old) &&
            second.receiverReference(id) == null && second.observedFingerprint(id) == null)
        check(fingerprint(second.phone(id).certificate) == identity)
        return Bundle().apply {
            putString("certificates", "PASS: persistent phone identity, old pins, authenticated names, changed key refused, exact replacement, per-receiver choice, isolated forget")
            putString("AA_references", "Bundled DER hashes, exact leaf, verified issuer without supplied root, expired and unrelated paths, observation does not grant trust")
        }
    } finally {
        for (name in listOf("usb-receivers", "projection-route", "aa-receiver-details")) base.deleteSharedPreferences("$run-$name")
        storage.deleteRecursively()
    }
}
