package com.hsd.universal.carplay

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothServerSocket
import android.os.Handler
import android.os.Looper
import java.util.UUID

/** Some head units inspect the phone's SDP services during bonding, before HSD has a selected peer. */
@SuppressLint("MissingPermission")
internal object CarPlayBluetoothService {
    private val phoneUuid = UUID.fromString("00000000-deca-fade-deca-deafdecacafe")
    private val handler = Handler(Looper.getMainLooper())
    private var prepared: BluetoothServerSocket? = null
    private var active: BluetoothServerSocket? = null
    private var expiry: Runnable? = null
    const val PAIRING_SECONDS = 180

    @Synchronized fun prepare(adapter: BluetoothAdapter) {
        if (active != null) return
        val listener = prepared ?: adapter.listenUsingRfcommWithServiceRecord("iAP2", phoneUuid)
        prepared = listener
        expiry?.let(handler::removeCallbacks)
        expiry = Runnable { synchronized(this) {
            if (prepared === listener) cancelPreparation()
        } }.also { handler.postDelayed(it, PAIRING_SECONDS * 1000L) }
    }

    @Synchronized fun cancelPreparation() {
        expiry?.let(handler::removeCallbacks)
        expiry = null
        prepared?.let { runCatching(it::close) }
        prepared = null
    }

    @Synchronized fun claim(adapter: BluetoothAdapter): BluetoothServerSocket {
        check(active == null) { "CarPlay Bluetooth listener is already in use" }
        val listener = prepared ?: adapter.listenUsingRfcommWithServiceRecord("iAP2", phoneUuid)
        prepared = null
        expiry?.let(handler::removeCallbacks)
        expiry = null
        active = listener
        return listener
    }

    @Synchronized fun release(listener: BluetoothServerSocket) {
        if (active === listener) active = null
        runCatching(listener::close)
    }
}
