package com.hsd.universal

import android.annotation.SuppressLint
import android.content.Context
import android.os.PowerManager

/** Bluetooth negotiation and media heartbeat must progress while the phone's panel is asleep. */
internal object ProjectionCpuLease {
    // The exact projection lifetime owns release, including failed setup, cancellation and retry.
    // A fixed timeout would silently suspend a sufficiently long, otherwise healthy drive.
    @SuppressLint("WakelockTimeout")
    fun hold(context: Context, life: ProjectionResources): PowerManager.WakeLock {
        val lock = context.getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"HSD:projection-cpu")
        lock.setReferenceCounted(false)
        lock.acquire()
        life.own { if (lock.isHeld) lock.release() }
        return lock
    }
}
