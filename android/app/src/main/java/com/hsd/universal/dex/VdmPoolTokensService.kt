package com.hsd.universal.dex

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import android.os.Process

/** Zygote-owned process: stopping adbd cannot kill it. Main-process death retires all native tokens. */
class VdmPoolTokensService : Service() {
    private var owner: IBinder? = null
    private var ownerPid = -1
    private var stoppedTransaction = -1
    private val death = IBinder.DeathRecipient { terminate() }
    private val callbacks = LinkedHashMap<String, IBinder>()
    private val lifetime = Binder()
    private val activity = listener("android.companion.virtual.IVirtualDeviceActivityListener")
    private val sound = listener("android.companion.virtual.IVirtualDeviceSoundEffectListener")
    private val broker = object : IVdmPoolTokens.Stub() {
        private fun requireApp() = check(Binder.getCallingUid() == Process.myUid()) { "Foreign token owner" }
        @Synchronized override fun claim(client: IBinder, stoppedCode: Int): Bundle {
            requireApp()
            check(owner == null && client.isBinderAlive && stoppedCode in 1..32)
            owner = client; ownerPid = Binder.getCallingPid(); stoppedTransaction = stoppedCode
            client.linkToDeath(death, 0)
            VdmPoolProtocol.names.forEach { callbacks[it] = listener("android.hardware.display.IVirtualDisplayCallback", true) }
            return Bundle().apply {
                putBinder("deviceToken", lifetime); putBinder("activityListener", activity); putBinder("soundListener", sound)
                putStringArrayList("names", ArrayList(callbacks.keys))
                callbacks.values.forEachIndexed { index, binder -> putBinder("displayToken$index", binder) }
            }
        }
        override fun ownerPid(): Int { requireApp(); return Process.myPid() }
        override fun retire() {
            requireApp(); check(ownerPid == Binder.getCallingPid()); terminate()
        }
    }
    private fun listener(descriptor: String, display: Boolean = false) = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == INTERFACE_TRANSACTION) { reply?.writeString(descriptor); return true }
            data.enforceInterface(descriptor)
            // A native display unexpectedly disappearing invalidates the entire family.
            // Kernel Binder death, including unknown partially-created displays, is the retirement proof.
            if (display && code == stoppedTransaction) terminate()
            return true
        }
    }
    override fun onBind(intent: Intent): IBinder = broker
    override fun onUnbind(intent: Intent): Boolean = terminate()
    private fun terminate(): Nothing { Process.killProcess(Process.myPid()); throw IllegalStateException("VDM token owner retired") }
}

internal object VdmPoolProtocol {
    const val TOKENS = "poolTokens"
    const val CREATED = "poolCreated"
    val names: List<String> get() = listOf(
        DesktopModePolicy.displayName(DesktopModePolicy.DRIVEDECK),
        DesktopModePolicy.displayName(DesktopModePolicy.ANDROID_DESKTOP),
        "${com.hsd.universal.BuildConfig.BRAND} navigator widget A",
        "${com.hsd.universal.BuildConfig.BRAND} navigator widget B",
        "${com.hsd.universal.BuildConfig.BRAND} fullscreen app",
    )
    val permissions = arrayOf("android.permission.REMOVE_TASKS", "android.permission.MANAGE_COMPANION_DEVICES", "android.permission.DEVICE_POWER")
}
