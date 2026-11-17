package com.hsd.universal.dex

import android.content.Context
import android.content.IntentFilter
import android.provider.Settings
import com.flyfishxu.kadb.Kadb
import com.hsd.universal.AppLog

/** Observes settings only. Enabling debugging always remains an explicit action in Android Settings. */
internal object DesktopUsbDebugging {
    private fun appSetting(context: Context): String? = runCatching {
        Settings.Global.getString(context.contentResolver, Settings.Global.ADB_ENABLED)
    }.getOrNull()

    private fun usbAdb(context: Context): Boolean? = runCatching {
        val usb = context.registerReceiver(null, IntentFilter("android.hardware.usb.action.USB_STATE"))
        // A disconnected/stale broadcast cannot prove that debugging is disabled.
        usb?.getBooleanExtra("adb", false)?.takeIf { usb.getBooleanExtra("connected", false) }
    }.getOrNull()

    fun state(context: Context): DesktopUsbDebuggingPolicy.State =
        DesktopUsbDebuggingPolicy.state(appSetting(context), usbAdb(context))

    /** Call after verifying shell UID and this installation's proof. The proven channel,
     * not USB_ADB_ENABLED or a manufacturer name, decides whether bootstrap can proceed. */
    fun observeAuthenticatedChannel(adb: Kadb) {
        fun read(command: String): String? = runCatching { adb.shell(command).let { result ->
            result.output.trim().takeIf { result.exitCode == 0 && it.length <= 256 }
        } }.getOrNull()
        val setting = read("settings get global adb_enabled")
        val functions = read("getprop sys.usb.config")
        val current = DesktopUsbDebuggingPolicy.verifiedState(setting, functions)
        AppLog.i("Desktop ADB channel authenticated: USB debugging=$current (transport diagnostic)", false)
    }
}
