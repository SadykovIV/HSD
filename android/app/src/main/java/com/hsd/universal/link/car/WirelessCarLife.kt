package com.hsd.universal.link.car

import com.hsd.universal.R

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.hardware.usb.UsbManager
import com.hsd.universal.AppLog
import com.hsd.universal.BridgeService
import com.hsd.universal.BuildConfig
import com.hsd.universal.CarLifeState
import com.hsd.universal.DesktopTransport
import com.hsd.universal.ProjectionRoute
import com.hsd.universal.dex.DesktopAccess
import com.hsd.universal.ui.Actions

enum class CarLifeConnectionMode { USB, WIFI, AUTO }

/** Wireless projection is independent of the short-lived Wi-Fi ADB activation. */
class WirelessPrefs(context: Context) {
    private val app = context.applicationContext
    private val preferences = app.getSharedPreferences("desktop_wireless", Context.MODE_PRIVATE)

    var mode: CarLifeConnectionMode
        get() = if (DesktopTransport.universal) {
            if (ProjectionRoute.selected(app) == ProjectionRoute.CARLIFE_WIFI) CarLifeConnectionMode.WIFI else CarLifeConnectionMode.USB
        } else WirelessConnectionPolicy.savedMode(preferences.getString("mode", null), preferences.getBoolean("enabled", false))
        set(value) {
            if (DesktopTransport.universal) {
                val route = if (value == CarLifeConnectionMode.USB) ProjectionRoute.CARLIFE_USB else ProjectionRoute.CARLIFE_WIFI
                ProjectionRoute.save(app, ProjectionRoute.prefs(app).edit().putString("mode", route.name))
                return
            }
            preferences.edit().putString("mode", value.name).putBoolean("enabled", value != CarLifeConnectionMode.USB).apply()
            CarProfile.enableTrigger(app, value != CarLifeConnectionMode.USB)
        }

    var enabled: Boolean
        get() = mode != CarLifeConnectionMode.USB
        set(value) {
            mode = if (!value) CarLifeConnectionMode.USB
                else mode.takeUnless { it == CarLifeConnectionMode.USB } ?: CarLifeConnectionMode.WIFI
        }

    fun observeEnabled(changed: (Boolean) -> Unit): () -> Unit {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "enabled" || key == "mode" || key == null) changed(enabled)
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        return { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
}

object WirelessCarLife {
    fun hasCompatibleUsb(context: Context): Boolean = runCatching {
        context.getSystemService(UsbManager::class.java).accessoryList.orEmpty().any(Actions::isCarLifeAccessory)
    }.getOrDefault(false)

    fun selectedTransport(context: Context): CarLifeConnectionMode =
        WirelessConnectionPolicy.selectedTransport(WirelessPrefs(context).mode, hasCompatibleUsb(context), hasConfiguredCar(context))

    fun preferUsb(context: Context): Boolean = selectedTransport(context) == CarLifeConnectionMode.USB

    fun hasConfiguredCar(context: Context): Boolean = CarProfile(context).isSet || CarWifi(context).isSet

    fun missingPermissions(context: Context): List<String> = buildList {
        if (Build.VERSION.SDK_INT >= 31) add(Manifest.permission.BLUETOOTH_CONNECT)
        add(if (Build.VERSION.SDK_INT >= 33) Manifest.permission.NEARBY_WIFI_DEVICES else Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 37) add("android.permission.ACCESS_LOCAL_NETWORK")
    }.filter { context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }

    /** No sockets, Bluetooth listeners or activation are started before this check. */
    fun unavailableReason(context: Context): String? = when {
        !BuildConfig.WIRELESS_BETA || !WirelessPrefs(context).enabled -> context.getString(R.string.projection_select_wifi)
        WirelessPrefs(context).mode == CarLifeConnectionMode.AUTO && !hasConfiguredCar(context) ->
            context.getString(R.string.projection_configure_wireless)
        missingPermissions(context).isNotEmpty() -> context.getString(R.string.projection_network_permissions)
        com.hsd.universal.dex.PhonePairingService.blocksProjectionForCommittedActivation(context) -> context.getString(R.string.projection_activation_check)
        !DesktopAccess.isReadyForProjection(context) -> context.getString(R.string.projection_prepare_access, BuildConfig.BRAND)
        else -> null
    }

    fun configure(activity: Activity) {
        activity.startActivity(Intent(activity, WirelessSetupActivity::class.java))
    }

    fun start(activity: Activity) {
        if (unavailableReason(activity) != null) {
            activity.startActivity(Intent(activity, WirelessSetupActivity::class.java).putExtra(EXTRA_CONNECT, true))
        } else startReady(activity)
    }

    /** Also used by the explicit notification action; never grants permissions itself. */
    fun startReady(context: Context): Boolean {
        val reason = unavailableReason(context)
        if (reason != null) {
            CarLifeState.setStatus(reason)
            return false
        }
        return try {
            // This method is an explicit UI continuation. A prior Stop must not leave
            // the main screen saying "stopped" while the settings button starts Wi-Fi.
            Actions.resumeUsbAutoStart()
            context.startForegroundService(com.hsd.universal.DesktopTransport.service(context).setAction(BridgeService.ACTION_WIFI_B))
            true
        } catch (error: RuntimeException) {
            CarLifeState.setStatus(context.getString(R.string.projection_wifi_error, BuildConfig.BRAND))
            AppLog.i("WIRELESS start failed: ${error.javaClass.simpleName}", false)
            false
        }
    }

    const val EXTRA_CONNECT = "com.hsd.universal.wireless.CONNECT"
}
