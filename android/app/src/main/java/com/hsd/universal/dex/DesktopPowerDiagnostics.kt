package com.hsd.universal.dex

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.BatteryManager
import android.provider.Settings
import android.view.Display
import com.hsd.universal.AppLog

/** Session-scoped observations. Never changes power, lock, battery or scheduler policy. */
internal class DesktopPowerDiagnostics(private val context: Context) {
    private val main = Handler(Looper.getMainLooper())
    private val power = context.getSystemService(PowerManager::class.java)
    private val displays = context.getSystemService(DisplayManager::class.java)
    private val phoneContext = displays.getDisplay(Display.DEFAULT_DISPLAY)?.let(context::createDisplayContext) ?: context
    private val keyguard = phoneContext.getSystemService(KeyguardManager::class.java)
    private val prefs = DexPrefs(context)
    private var active = false
    private var registered = false
    private var previous: String? = null
    private val tick = object : Runnable {
        override fun run() {
            if (!active) return
            sample()
            main.postDelayed(this, 15_000)
        }
    }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { if (active) sample() }
    }

    fun start() {
        if (active) return
        active = true
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
            addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
        }
        registered = runCatching {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            true
        }.getOrElse {
            AppLog.i("DESKTOP POWER: broadcast observation unavailable=${it.javaClass.simpleName}", false)
            false
        }
        tick.run()
    }

    fun stop() {
        if (!active) return
        active = false
        main.removeCallbacks(tick)
        if (registered) runCatching { context.unregisterReceiver(receiver) }
        registered = false
    }

    private fun sample() {
        val value = runCatching {
            val ids = (DelegatedInputGateway.ownedDisplayIds() + DexState.displayId).filter { it > 0 }.sorted()
            val owned = ids.associateWith(displays::getDisplay)
            val states = owned.entries.joinToString(",") { (id, display) -> "$id:${display?.state ?: -1}" }
            val flags = owned.entries.joinToString(",") { (id, display) -> "$id:0x${(display?.flags ?: 0).toString(16)}" }
            val groups = owned.entries.joinToString(",") { (id, display) -> "$id:${display?.let(DesktopDisplayIsolation::groupId) ?: "?"}" }
            val resolver = context.contentResolver
            val timeout = Settings.System.getLong(resolver, Settings.System.SCREEN_OFF_TIMEOUT, -1)
            val lockDelay = runCatching { Settings.Secure.getLong(resolver, "lock_screen_lock_after_timeout", -1) }.getOrDefault(-1)
            val stayOn = Settings.Global.getInt(resolver, Settings.Global.STAY_ON_WHILE_PLUGGED_IN, -1)
            val plugged = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                ?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1
            val chargingKeepsOn = if (plugged < 0 || stayOn < 0) "unknown" else ((plugged and stayOn) != 0).toString()
            "phoneState=${displays.getDisplay(Display.DEFAULT_DISPLAY)?.state ?: -1} " +
                "interactive=${power.isInteractive} locked=${keyguard.isKeyguardLocked} " +
                "deviceLocked=${keyguard.isDeviceLocked} deviceSecure=${keyguard.isDeviceSecure} " +
                "powerSave=${power.isPowerSaveMode} idle=${power.isDeviceIdleMode} " +
                "batteryExempt=${power.isIgnoringBatteryOptimizations(context.packageName)} " +
                "panelControl=${prefs.desktopPanelControl} panelTimer=${prefs.phoneIdleMode} " +
                "screenTimeoutMs=$timeout stayOnMask=$stayOn plugged=$plugged " +
                "lockAfterMs=$lockDelay stayOnForPower=$chargingKeepsOn " +
                "thermal=${power.currentThermalStatus} desktopStates=[$states] desktopFlags=[$flags] desktopGroups=[$groups]"
        }.getOrElse { "unavailable=${it.javaClass.simpleName}" }
        if (value != previous) {
            previous = value
            AppLog.i("DESKTOP POWER: $value", false)
        }
    }
}
