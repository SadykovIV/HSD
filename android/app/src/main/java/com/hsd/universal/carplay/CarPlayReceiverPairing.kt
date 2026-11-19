package com.hsd.universal.carplay

import android.content.Context
import android.util.Base64
import com.hsd.universal.R
import io.github.shaonialife.cardeck.airplay.AirPlayCrypto
import io.github.shaonialife.cardeck.airplay.ControllerIdentity
import io.github.shaonialife.cardeck.airplay.PairingIdentityMismatch
import io.github.shaonialife.cardeck.airplay.PairSetupClient
import io.github.shaonialife.cardeck.airplay.PairVerifyClient
import io.github.shaonialife.cardeck.airplay.TlvTransport
import java.net.ProtocolException
import java.util.UUID

/** A new pairing is explicitly authorized for one receiver and one attempt. Keep its current key
 * until both sides have completed Pair Verify with the new pairing. */
internal object CarPlayReceiverPairing {
    private data class Attempt(val peer: String, val original: String?, val renew: Boolean) {
        val key: ByteArray? get() = original?.let { Base64.decode(it, Base64.DEFAULT) }
    }

    private fun store(context: Context) = context.getSharedPreferences("carplay-identities", Context.MODE_PRIVATE)
    fun known(context: Context, peer: String) = store(context).contains("peer.$peer")

    @Synchronized private fun controller(context: Context): ControllerIdentity {
        val prefs = store(context)
        val savedId = prefs.getString("controller", null)
        val savedKey = prefs.getString("key", null)
        val id = savedId ?: UUID.randomUUID().toString()
        val key = savedKey?.let { Base64.decode(it, Base64.DEFAULT) } ?: AirPlayCrypto.ed25519Generate().privateKey
        if (savedId == null || savedKey == null) {
            check(prefs.edit().putString("controller", id).putString("key", Base64.encodeToString(key, Base64.NO_WRAP)).commit())
        }
        return ControllerIdentity(id, key, AirPlayCrypto.ed25519PublicKey(key))
    }

    fun authenticate(context: Context, peer: String, announcedKey: ByteArray?, transport: TlvTransport): PairVerifyClient.ControlKeys {
        val identity = controller(context)
        val attempt = begin(context, peer)
        val pinned = attempt.key?.takeUnless { attempt.renew }
        fun changed() = ProtocolException(context.getString(R.string.carplay_receiver_changed))
        if (pinned != null && announcedKey != null && !pinned.contentEquals(announcedKey)) throw changed()
        val expected = pinned ?: run {
            val signed = PairSetupClient(identity, transport) {}.run()?.publicKey
            val key = signed ?: announcedKey ?: throw ProtocolException("Магнитола не предоставила проверяемый ключ AirPlay")
            if (announcedKey != null && !announcedKey.contentEquals(key)) throw ProtocolException("Ключ AirPlay отличается от ключа iAP2")
            key
        }
        val session = try {
            PairVerifyClient(identity, transport) {}.run(expected)
        } catch (error: PairingIdentityMismatch) {
            if (pinned == null) throw error
            throw changed().apply { initCause(error) }
        }
        verified(context, attempt, expected)
        return session
    }

    @Synchronized fun requestRenewal(context: Context, peer: String) {
        val prefs = store(context)
        val pin = prefs.getString("peer.$peer", null) ?: return
        prefs.edit().putString("renew.$peer", pin).apply()
    }

    @Synchronized fun forget(context: Context, peer: String) {
        store(context).edit().remove("peer.$peer").remove("renew.$peer").apply()
    }

    @Synchronized private fun begin(context: Context, peer: String): Attempt {
        val prefs = store(context)
        val pin = prefs.getString("peer.$peer", null)
        val consent = prefs.getString("renew.$peer", null)
        if (consent != null) check(prefs.edit().remove("renew.$peer").commit())
        return Attempt(peer, pin, consent != null && consent == pin)
    }

    @Synchronized private fun verified(context: Context, attempt: Attempt, key: ByteArray) {
        val prefs = store(context)
        val entry = "peer.${attempt.peer}"
        check(prefs.getString(entry, null) == attempt.original) { "CarPlay pairing changed during connection" }
        if (attempt.original == null || attempt.renew) {
            check(prefs.edit().putString(entry, Base64.encodeToString(key, Base64.NO_WRAP)).commit())
        } else check(attempt.key!!.contentEquals(key))
    }
}
