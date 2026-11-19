package com.hsd.universal

import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.provider.Settings
import net.prototux.aare.aap.AapLog

internal object ProjectionWirelessLog : AapLog {
    override fun i(msg: String) = AppLog.i(msg, false)
    override fun w(msg: String, t: Throwable?) = AppLog.i("Wireless: $msg" + (t?.let { " (${it.javaClass.simpleName})" } ?: ""), false)
    override fun e(msg: String, t: Throwable?) = AppLog.i("Wireless error: $msg" + (t?.let { " (${it.javaClass.simpleName})" } ?: ""), false)
}

/** State of the phone's Wi-Fi radio, independent of association, internet and the default network. */
internal enum class ProjectionWifi {
    OFF, STARTING, READY, UNKNOWN;

    val needsEnable get() = this == OFF || this == STARTING
    val message get() = if (this == STARTING) R.string.projection_wifi_starting else R.string.projection_wifi_enable

    companion object {
        fun of(state: Int?) = when (state) {
            WifiManager.WIFI_STATE_ENABLED -> READY
            WifiManager.WIFI_STATE_ENABLING -> STARTING
            WifiManager.WIFI_STATE_DISABLED, WifiManager.WIFI_STATE_DISABLING -> OFF
            else -> UNKNOWN
        }

        fun read(context: Context) = of(runCatching {
            context.applicationContext.getSystemService(WifiManager::class.java)?.wifiState
        }.getOrNull())

        fun settings(context: Context): Intent {
            val panel = Intent(Settings.Panel.ACTION_WIFI)
            return if (panel.resolveActivity(context.packageManager) != null) panel else Intent(Settings.ACTION_WIFI_SETTINGS)
        }
    }
}
