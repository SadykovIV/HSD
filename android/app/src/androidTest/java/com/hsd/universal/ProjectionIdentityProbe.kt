package com.hsd.universal

import android.content.Context
import android.content.ContextWrapper
import android.os.Bundle
import net.prototux.aare.aap.fingerprint
import java.io.File
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
        second.selectDefault(null)
        second.forget(id)
        check(id !in second.receiverIds() && second.get(legacy) == setOf(old))
        check(fingerprint(second.phone(id).certificate) == identity)
        return Bundle().apply { putString("certificates", "PASS: persistent phone identity, old pins, authenticated names, changed key refused, exact replacement, per-receiver choice, isolated forget") }
    } finally {
        for (name in listOf("usb-receivers", "projection-route", "aa-receiver-details")) base.deleteSharedPreferences("$run-$name")
        storage.deleteRecursively()
    }
}
