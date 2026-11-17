package com.hsd.universal

import android.content.Context
import android.hardware.usb.UsbAccessory
import android.security.KeyChain
import android.util.AtomicFile
import net.prototux.aare.aap.PhoneIdentity
import net.prototux.aare.aap.fingerprint
import net.prototux.aare.aap.PinStore
import net.prototux.aare.aap.cert.SelfSignedCertificate
import java.io.File
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.time.Instant
import java.time.Clock

internal data class PhoneCertificateDates(val from: Instant, val until: Instant) {
    constructor(cert: X509Certificate) : this(cert.notBefore.toInstant(), cert.notAfter.toInstant())

    enum class Status { NOT_YET_VALID, VALID, EXPIRED }

    fun at(time: Instant) = when {
        time < from -> Status.NOT_YET_VALID
        time > until -> Status.EXPIRED
        else -> Status.VALID
    }
}

internal class ProjectionIdentity(private val context: Context, private val clock: Clock = Clock.systemUTC()) : PinStore {
    private val file = AtomicFile(File(context.noBackupFilesDir, "phone.p12"))
    private val peers = context.getSharedPreferences("usb-receivers", Context.MODE_PRIVATE)
    private val settings = ProjectionRoute.prefs(context)
    private val devices = context.getSharedPreferences("aa-receiver-details", Context.MODE_PRIVATE)
    private val references by lazy { AaCertificateReference.from(context) }
    private val legacyKeys = mutableMapOf<String, String>()
    private val usbNames = mutableMapOf<String, String>()
    private var usedAlias: String? = null
    private var automaticSource: String? = null

    fun usbPeer(a: UsbAccessory): String {
        val descriptors = listOf(a.manufacturer, a.model, a.description, a.version)
        val serial = runCatching { a.serial }.getOrNull()
        val stable = if (serial.isNullOrBlank()) descriptors + a.uri else listOf(a.manufacturer, a.model, serial)
        val id = "usb|" + stable.joinToString("") { v -> v?.let { "${it.length}:$it" } ?: "-:" }
        legacyKeys[id] = descriptors.joinToString("|") { it.orEmpty() }
        usbNames[id] = listOf(a.manufacturer, a.model).filterNotNull().distinct().joinToString(" ")
        return id
    }

    fun authenticated(id: String, name: String = "") = synchronized(lock) {
        legacyKeys[id]?.takeIf { peers.contains(it) }?.let { old ->
            check(peers.edit().putStringSet(id, get(id)).remove(old).commit())
        }
        val edit = devices.edit().remove("changed.$id").remove("legacy.$id")
        val title = name.ifBlank { usbNames[id].orEmpty() }.trim().take(96)
        if (title.isNotBlank()) edit.putString("name.$id", title)
        edit.putString("lastAlias.$id", usedAlias)
        edit.putLong("seen.$id", System.currentTimeMillis()).apply()
    }

    fun rejected(id: String, changedFingerprint: String?) = synchronized(lock) {
        val edit = devices.edit().putBoolean("known.$id", true)
        legacyKeys[id]?.takeIf { peers.contains(it) && !peers.contains(id) }?.let { edit.putString("legacy.$id", it) }
        usbNames[id]?.takeIf { it.isNotBlank() && !devices.contains("name.$id") }?.let { edit.putString("name.$id", it) }
        if (changedFingerprint != null) edit.putString("changed.$id", changedFingerprint)
        edit.apply()
    }

    /** Record what a peer actually presented, including on attempts rejected before service discovery. */
    fun observeReceiver(id: String, chain: List<X509Certificate>, tlsEstablished: Boolean) {
        val leaf = chain.firstOrNull() ?: return
        val now = clock.instant()
        val match = references.identify(chain, now)
        val sha256 = fingerprint(leaf)
        devices.edit().putString("peerReference.$id", match.name).putString("observedFingerprint.$id", sha256).apply()
        AppLog.i("AA head unit TLS · established=$tlsEstablished · reference=$match · SHA-256=$sha256", false)
        chain.forEachIndexed { index, certificate ->
            val dates = PhoneCertificateDates(certificate)
            AppLog.i("AA head unit TLS · chain[$index] · from=${dates.from} · until=${dates.until} · phoneUTC=$now · validityAtPhoneTime=${dates.at(now)}", false)
        }
    }

    fun receiverReference(id: String) = devices.getString("peerReference.$id", null)?.let { stored ->
        AaCertificateReference.Match.entries.find { it.name == stored }
    }
    fun observedFingerprint(id: String): String? = devices.getString("observedFingerprint.$id", null)

    val defaultAlias: String? get() = settings.getString("phoneCertificate", null)
    fun selectDefault(alias: String?) = settings.edit().putString("phoneCertificate", alias).apply()
    fun assignedAlias(id: String): String? = devices.getString("alias.$id", null)
    fun select(id: String, alias: String?) = devices.edit().putString("alias.$id", alias)
        .remove("lastAlias.$id").apply()

    fun phone(id: String): PhoneIdentity = synchronized(lock) {
        loadPhone(id).let { identity ->
            val now = clock.instant()
            val source = automaticSource ?: if (usedAlias == null) "HSD" else "Android KeyChain"
            val chain = listOf(identity.certificate) + identity.chain
            AppLog.i("AA phone TLS · source=$source · reference=${references.identify(chain, now)} · SHA-256=${fingerprint(identity.certificate)}", false)
            chain.forEachIndexed { index, certificate ->
                val dates = PhoneCertificateDates(certificate)
                val validity = dates.at(now)
                AppLog.i("AA phone TLS · source=$source · chain[$index] · from=${dates.from} · until=${dates.until} · phoneUTC=$now · validity=$validity", false)
                // Report the device clock and distinguish an invalid leaf from a chain error.
                if (index == 0 && validity != PhoneCertificateDates.Status.VALID) {
                    val problem = if (validity == PhoneCertificateDates.Status.EXPIRED) R.string.aa_phone_cert_expired
                        else R.string.aa_phone_cert_not_yet_valid
                    throw IllegalStateException(context.getString(problem) + " " +
                        context.getString(R.string.aa_check_certificate_dates, dates.from, dates.until, now))
                }
            }
            if (usedAlias == null) identity else {
                val verified = references.phoneChain(chain, now)
                    ?: throw CertificateException(context.getString(R.string.aa_phone_invalid_chain))
                PhoneIdentity(identity.certificate, identity.privateKey, verified.drop(1))
            }
        }
    }

    private fun loadPhone(id: String): PhoneIdentity {
        val alias = assignedAlias(id) ?: defaultAlias
        usedAlias = null
        automaticSource = null
        if (!alias.isNullOrEmpty()) {
            try {
                val key = KeyChain.getPrivateKey(context, alias) ?: error(context.getString(R.string.aa_key_unavailable))
                val chain = KeyChain.getCertificateChain(context, alias)
                check(!chain.isNullOrEmpty()) { context.getString(R.string.aa_chain_unavailable) }
                usedAlias = alias
                return PhoneIdentity(chain.first(), key, chain.drop(1))
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw e
            } catch (e: Exception) {
                throw IllegalStateException(context.getString(R.string.aa_reselect_key), e)
            }
        }
        if (alias == null) {
            val now = clock.instant()
            val providers = listOf(
                "HSD bundled Android Auto" to {
                    context.assets.open("projection/phone.p12").use { AutomaticAaIdentity.readStore(it, references, now) }
                },
                "Downloaded Android Auto" to { AaIdentityUpdates.load(context, references, now) },
                "Installed Android Auto" to { InstalledAaIdentity(context, references).load(now) },
            )
            fun choose() = AutomaticAaIdentity.choose(providers) { source, error ->
                AppLog.i("AA phone identity unavailable · source=$source · ${error.javaClass.simpleName}", false)
            }
            var selected = choose()
            // This is already a connection worker. A valid offline identity never waits for HTTP.
            if (selected == null && AaIdentityUpdates.enabled(context)) {
                AaIdentityUpdates.refresh(context, force = AaIdentityUpdates.lastSucceeded(context), waitForRunning = true)
                selected = choose()
            }
            val available = selected
                ?: throw IllegalStateException(context.getString(R.string.aa_auto_identity_unavailable))
            AaIdentityUpdates.maybeCheck(context)
            automaticSource = available.source
            return available.identity
        }
        val store = KeyStore.getInstance("PKCS12")
        val password = CharArray(0)
        try {
            file.openRead().use { store.load(it, password) }
        } catch (missing: java.io.FileNotFoundException) {
            if (file.baseFile.exists()) throw missing
            val generated = SelfSignedCertificate.generate("HSD")
            store.load(null, password)
            store.setKeyEntry("phone", generated.keyPair.private, password, arrayOf(generated.certificate))
            val output = file.startWrite()
            try {
                store.store(output, password)
                file.finishWrite(output)
            } catch (error: Throwable) {
                file.failWrite(output)
                throw error
            }
        }
        return PhoneIdentity(store.getCertificate("phone") as X509Certificate, store.getKey("phone", password) as PrivateKey)
    }

    fun receiverIds(): List<String> {
        val preferences = devices.all
        val migrated = preferences.filterKeys { it.startsWith("legacy.") }.values.filterIsInstance<String>().toSet()
        return ((peers.all.keys - migrated) + preferences.keys.map { it.substringAfter('.') }).distinct()
            .sortedBy { receiverName(it).lowercase() }
    }
    fun receiverName(id: String): String = devices.getString("name.$id", null)?.takeIf { it.isNotBlank() }
        ?: when {
            id.startsWith("usb|") -> context.getString(R.string.aa_usb_receiver)
            id.startsWith("bt|") -> context.getString(R.string.aa_bt_receiver)
            id.startsWith("lan|") -> context.getString(R.string.aa_wifi_receiver)
            else -> id.split('|').filter(String::isNotBlank).take(2).joinToString(" ").ifBlank { context.getString(R.string.aa_usb_receiver) }
        }
    fun connectionLabel(id: String) = when {
        id.startsWith("bt|") -> "Bluetooth · " + id.substringAfter('|')
        id.startsWith("lan|") -> "Wi-Fi · " + id.substringAfter('|')
        else -> "USB"
    }
    fun changed(id: String): String? = devices.getString("changed.$id", null)
    fun seen(id: String) = devices.getLong("seen.$id", 0)
    fun acceptChange(id: String, expected: String): Boolean = synchronized(lock) {
        if (expected != changed(id) || get(id).isEmpty()) return@synchronized false
        val edit = peers.edit().putStringSet(id, setOf(expected))
        devices.getString("legacy.$id", null)?.let(edit::remove)
        check(edit.commit())
        devices.edit().remove("changed.$id").remove("legacy.$id").apply()
        true
    }

    fun forget(id: String) = synchronized(lock) {
        val pinEdit = peers.edit().remove(id)
        devices.getString("legacy.$id", null)?.let(pinEdit::remove)
        check(pinEdit.commit())
        val edit = devices.edit()
        for (prefix in listOf("name", "seen", "changed", "known", "alias", "lastAlias", "legacy",
            "peerReference", "observedFingerprint")) edit.remove("$prefix.$id")
        edit.apply()
    }

    override fun get(peerKey: String): Set<String> = synchronized(lock) {
        (peers.getStringSet(peerKey, null) ?: (legacyKeys[peerKey] ?: devices.getString("legacy.$peerKey", null))?.let { peers.getStringSet(it, null) }).orEmpty().toSet()
    }
    override fun checkReceiverChain(chain: List<X509Certificate>) {
        references.receiverError(chain, clock.instant())?.let { throw CertificateException(context.getString(it)) }
    }
    override fun add(peerKey: String, sha256: String) = synchronized(lock) {
        val trusted = get(peerKey)
        check(trusted.isEmpty() || sha256 in trusted) { context.getString(R.string.aa_identity_changed) }
        check(peers.edit().putStringSet(peerKey, trusted + sha256).commit()) { context.getString(R.string.aa_identity_save_error) }
    }

    companion object {
        private val lock = Any()
    }
}
