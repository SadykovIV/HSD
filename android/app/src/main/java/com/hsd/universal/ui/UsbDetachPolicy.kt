package com.hsd.universal.ui

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import com.hsd.universal.R

internal data class UsbBusState(val connected: Boolean?, val configured: Boolean?, val accessory: Boolean?) {
    fun waitingMessage() = when {
        connected == false -> R.string.usb_wait_data
        accessory == true -> R.string.usb_wait_enumeration
        connected == true && configured == false -> R.string.usb_wait_configuration
        connected == true -> R.string.usb_wait_accessory_mode
        else -> R.string.usb_wait_accessory
    }

    companion object {
        fun read(intent: Intent?): UsbBusState {
            fun flag(name: String) = intent?.takeIf { it.action == UsbDetachPolicy.USB_STATE && it.hasExtra(name) }
                ?.getBooleanExtra(name, false)
            return UsbBusState(flag("connected"), flag("configured"), flag("accessory"))
        }
    }
}

/** Absence must be observed successfully; an IO error or old broadcast alone is not a detach. */
internal object UsbDetachPolicy {
    const val USB_STATE = "android.hardware.usb.action.USB_STATE"
    fun cableConnected(context: Context): Boolean? = runCatching {
        UsbBusState.read(context.registerReceiver(null, IntentFilter(USB_STATE))).connected
    }.getOrNull()

    fun physicalDisconnect(cableConnected: Boolean?, accessoryPresent: Boolean?) =
        cableConnected != true && accessoryPresent != true && (cableConnected == false || accessoryPresent == false)

    fun confirmedDetached(ownedUsbSession: Boolean, compatibleAccessoryPresent: Boolean?, cableConnected: Boolean?): Boolean =
        ownedUsbSession && physicalDisconnect(cableConnected, compatibleAccessoryPresent)
}

/** Physical detach stops projection polling, but does not revoke access or disable the next CarLife attach. */
internal class UsbDetachedIdle {
    var idle = false
        private set

    fun detached() { idle = true }
    fun observeCompatibleAccessory(present: Boolean) { if (present) idle = false }
    fun explicitConnect() { idle = false }
}
