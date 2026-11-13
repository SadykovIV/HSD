package com.hsd.universal

import android.content.Context
import android.hardware.usb.UsbAccessory
import android.security.KeyChain
import android.util.AtomicFile
import com.hsd.universal.ProjectionRoute
import net.prototux.aare.aap.PhoneIdentity
import net.prototux.aare.aap.PinStore
import net.prototux.aare.aap.cert.SelfSignedCertificate
import java.io.File
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate

internal class ProjectionIdentity(private val context: Context) : PinStore {
    private val file = AtomicFile(File(context.noBackupFilesDir, "phone.p12"))
    private val peers = context.getSharedPreferences("usb-receivers", Context.MODE_PRIVATE)
    private val settings = ProjectionRoute.prefs(context)
    private val devices = context.getSharedPreferences("aa-receiver-details", Context.MODE_PRIVATE)
    private val legacyKeys = mutableMapOf<String, String>()
    private val usbNames = mutableMapOf<String, String>()
    private var usedAlias: String? = null

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
        if (usedAlias != null) edit.putString("lastAlias.$id", usedAlias)
        edit.putLong("seen.$id", System.currentTimeMillis()).apply()
    }

    fun rejected(id: String, changedFingerprint: String?) = synchronized(lock) {
        val edit = devices.edit().putBoolean("known.$id", true)
        legacyKeys[id]?.takeIf { peers.contains(it) && !peers.contains(id) }?.let { edit.putString("legacy.$id", it) }
        usbNames[id]?.takeIf { it.isNotBlank() && !devices.contains("name.$id") }?.let { edit.putString("name.$id", it) }
        if (changedFingerprint != null) edit.putString("changed.$id", changedFingerprint)
        edit.apply()
    }

    val defaultAlias: String? get() = settings.getString("phoneCertificate", null)
    fun selectDefault(alias: String?) = settings.edit().putString("phoneCertificate", alias).apply()
    fun assignedAlias(id: String): String? = devices.getString("alias.$id", null)
    fun select(id: String, alias: String?) = devices.edit().putString("alias.$id", alias)
        .remove("lastAlias.$id").apply()

    fun phone(id: String): PhoneIdentity = synchronized(lock) {
        val selected = assignedAlias(id) ?: defaultAlias
        val alias = selected ?: devices.getString("lastAlias.$id", null)
        usedAlias = null
        if (!alias.isNullOrEmpty()) {
            try {
                val key = KeyChain.getPrivateKey(context, alias) ?: error(context.getString(R.string.aa_key_unavailable))
                val chain = KeyChain.getCertificateChain(context, alias)
                check(!chain.isNullOrEmpty()) { context.getString(R.string.aa_chain_unavailable) }
                usedAlias = alias
                return@synchronized PhoneIdentity(chain.first(), key, chain.drop(1))
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw e
            } catch (e: Exception) {
                if (selected != null) throw IllegalStateException(
                    context.getString(R.string.aa_reselect_key), e)
            }
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
        PhoneIdentity(store.getCertificate("phone") as X509Certificate, store.getKey("phone", password) as PrivateKey)
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
        for (prefix in listOf("name", "seen", "changed", "known", "alias", "lastAlias", "legacy")) edit.remove("$prefix.$id")
        edit.apply()
    }

    override fun get(peerKey: String): Set<String> = synchronized(lock) {
        (peers.getStringSet(peerKey, null) ?: (legacyKeys[peerKey] ?: devices.getString("legacy.$peerKey", null))?.let { peers.getStringSet(it, null) }).orEmpty().toSet()
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
