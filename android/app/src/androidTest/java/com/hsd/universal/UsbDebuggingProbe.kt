package com.hsd.universal

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.provider.Settings

/** Mutates only a disposable emulator. Production never enables or disables debugging. */
internal fun disableUsbDebuggingForProbe(context: Context): AutoCloseable {
    check(Build.HARDWARE in listOf("ranchu", "goldfish") && Build.VERSION.SDK_INT >= 30)
    val resolver = context.contentResolver
    val usb = Settings.Global.getString(resolver, Settings.Global.ADB_ENABLED)
    val wifi = Settings.Global.getString(resolver, "adb_wifi_enabled")
    val restore = AutoCloseable {
        check(Settings.Global.putString(resolver, Settings.Global.ADB_ENABLED, usb))
        check(Settings.Global.putString(resolver, "adb_wifi_enabled", wifi))
    }
    try {
        check(Settings.Global.putInt(resolver, "adb_wifi_enabled", 0))
        check(Settings.Global.putInt(resolver, Settings.Global.ADB_ENABLED, 0))
        SystemClock.sleep(1500)
        check(Settings.Global.getInt(resolver, Settings.Global.ADB_ENABLED) == 0)
        check(Settings.Global.getInt(resolver, "adb_wifi_enabled") == 0)
        return restore
    } catch (error: Throwable) {
        restore.close()
        throw error
    }
}
