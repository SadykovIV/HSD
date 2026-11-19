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
