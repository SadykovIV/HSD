package com.hsd.universal

import android.Manifest
import android.annotation.SuppressLint
import android.app.Instrumentation
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.SystemClock

/** Test-only pairing for two disposable emulators; never enabled for an ordinary radio probe. */
@SuppressLint("MissingPermission")
internal fun emulatorBluetoothPairing(instrumentation: Instrumentation, peer: String,
    initiate: Boolean, life: ProjectionResources) {
    check(Build.HARDWARE.startsWith("ranchu") || Build.HARDWARE.startsWith("goldfish")) {
        "Automatic pairing is only permitted on an Android emulator"
    }
    val context = instrumentation.targetContext
    val ui = instrumentation.uiAutomation
    ui.adoptShellPermissionIdentity(Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_PRIVILEGED)
    life.own(ui::dropShellPermissionIdentity)
    val adapter = context.getSystemService(BluetoothManager::class.java).adapter
    check(adapter.isEnabled)
    val device = adapter.getRemoteDevice(peer)
    val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            @Suppress("DEPRECATION") val remote = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
            if (remote != device) return
            val variant = intent.getIntExtra(BluetoothDevice.EXTRA_PAIRING_VARIANT, -1)
            check(variant == BluetoothDevice.PAIRING_VARIANT_PASSKEY_CONFIRMATION ||
                variant == 3 /* AOSP PAIRING_VARIANT_CONSENT, hidden from the public SDK */) {
                "Unexpected pairing method: $variant"
            }
            check(remote.setPairingConfirmation(true))
        }
    }
    val filter = IntentFilter(BluetoothDevice.ACTION_PAIRING_REQUEST).apply { priority = 1000 }
    if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
    else @Suppress("DEPRECATION") context.registerReceiver(receiver, filter)
    life.own { context.unregisterReceiver(receiver) }
    if (initiate && device.bondState != BluetoothDevice.BOND_BONDED) {
        check(device.createBond())
        val until = SystemClock.elapsedRealtime() + 30_000
        while (device.bondState != BluetoothDevice.BOND_BONDED && SystemClock.elapsedRealtime() < until)
            SystemClock.sleep(100)
        check(device.bondState == BluetoothDevice.BOND_BONDED) { "Emulator pairing timed out" }
    }
}
