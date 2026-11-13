package com.hsd.universal.dex

import com.hsd.universal.R
import com.hsd.universal.DesktopTransport

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.net.Uri
import android.provider.Settings
import android.widget.LinearLayout
import com.hsd.universal.cluster.ClusterNavigationListener
import com.hsd.universal.update.*

/** The wizard and Settings show the same grantable capabilities. Never request another package's grants. */
internal class DesktopPermissionList(private val activity: Activity, private val ui: DesktopSetupViews) {
    private val pm get() = activity.packageManager
    private fun pkgUri(pkg: String) = Uri.fromParts("package", pkg, null)
    private fun details(pkg: String) = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkgUri(pkg))
    private fun settings(vararg intents: Intent) {
        for (intent in intents) {
            try { activity.startActivity(intent); return } catch (_: android.content.ActivityNotFoundException) { }
            catch (_: SecurityException) { }
        }
        AlertDialog.Builder(activity).setMessage(activity.getString(R.string.desktop_open_this_setting_manually_on_this_phone))
            .setPositiveButton(activity.getString(R.string.apps_warn_ok), null).show()
    }
    private fun row(parent: LinearLayout, name: String, granted: Boolean?, reason: String, action: () -> Unit) {
        ui.permission(parent, name, action).update(granted, reason)
    }
    private fun runtime(parent: LinearLayout, pkg: String) {
        @Suppress("DEPRECATION")
        val info = pm.getPackageInfo(pkg, PackageManager.GET_PERMISSIONS)
        val mine = pkg == activity.packageName
        info.requestedPermissions.orEmpty().distinct().forEach { permission ->
            @Suppress("DEPRECATION")
            val declared = try { pm.getPermissionInfo(permission, 0) } catch (_: PackageManager.NameNotFoundException) { return@forEach }
            if (declared.protection != PermissionInfo.PROTECTION_DANGEROUS ||
                (mine && permission == Manifest.permission.POST_NOTIFICATIONS)) return@forEach
            val granted = pm.checkPermission(permission, pkg) == PackageManager.PERMISSION_GRANTED
            val label = labels[permission]?.let(activity::getString) ?: declared.loadLabel(pm).toString()
            row(parent, label, granted, when {
                mine && permission == "android.permission.ACCESS_LOCAL_NETWORK" -> activity.getString(R.string.desktop_find_the_phone_and_connect_to_local_network_devices)
                mine -> activity.getString(R.string.desktop_for_wireless_connection_to_a_compatible_head_unit)
                else -> if (granted) activity.getString(R.string.desktop_allowed_for_drivedeck) else activity.getString(R.string.desktop_you_can_configure_it_in_drivedeck_permissions)
            }) {
                if (!mine) DriveDeckPermissions.openAppSettings(activity)
                else if (granted) settings(details(pkg))
                else activity.requestPermissions(arrayOf(permission), REQUEST)
            }
        }
    }
    fun hsd(parent: LinearLayout) {
        val pkg = activity.packageName
        val debug = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
        val usb = DesktopUsbDebugging.state(activity)
        row(parent, activity.getString(R.string.desktop_usb_debugging_fae9), when (usb) {
            DesktopUsbDebuggingPolicy.State.ENABLED -> true
            DesktopUsbDebuggingPolicy.State.DISABLED -> false
            else -> null
        }, activity.getString(R.string.desktop_to_activate_from_your_computer_and_restore_an_independent)) { settings(debug, Intent(Settings.ACTION_SETTINGS)) }
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            val wifi = runCatching { Settings.Global.getString(activity.contentResolver, "adb_wifi_enabled") }.getOrNull()
            row(parent, activity.getString(R.string.desktop_wireless_debugging_wi_fi), when (wifi) { "1" -> true; "0" -> false; else -> null },
                activity.getString(R.string.desktop_you_don_t_need_wi_fi_to_activate_from)) { settings(debug, Intent(Settings.ACTION_SETTINGS)) }
        }
        val blocked = PhonePairingService.notificationBlock(activity, needsCode = true)
        row(parent, activity.getString(R.string.desktop_notifications, DesktopTransport.shortName), blocked == null, when (blocked) {
            PairingNotificationBlock.CODE_CHANNEL -> activity.getString(R.string.desktop_enable_the_input_pairing_code_channel)
            PairingNotificationBlock.PROGRESS_CHANNEL -> activity.getString(R.string.desktop_enable_the_activation_channel, DesktopTransport.shortName)
            else -> activity.getString(R.string.desktop_activation_status_and_code_entry_directly_in_the_notification)
        }) {
            if (activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                activity.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST)
            else settings(PhonePairingService.notificationSettings(activity, blocked ?: PairingNotificationBlock.APP), details(pkg))
        }
        val component = ComponentName(activity, ClusterNavigationListener::class.java)
        row(parent, activity.getString(R.string.desktop_notification_access_for, DesktopTransport.shortName),
            activity.getSystemService(NotificationManager::class.java).isNotificationListenerAccessGranted(component),
            activity.getString(R.string.desktop_for_navigation_tips_on_the_dashboard_and_hud)) {
            settings(Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, component.flattenToString()), Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        row(parent, activity.getString(R.string.desktop_display_over_other_apps), Settings.canDrawOverlays(activity),
            activity.getString(R.string.desktop_for_the_curtain_on_the_gu_screen_the_curtain)) {
            settings(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, pkgUri(pkg)), details(pkg))
        }
        val battery = DesktopOnboardingActivity.batteryExempt(activity)
        row(parent, activity.getString(R.string.desktop_unrestricted_battery_use), battery, activity.getString(R.string.desktop_helps_to_keep_the_connection_with_the_head_unit)) {
            if (battery) settings(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS), details(pkg))
            else DesktopOnboardingActivity.requestBatteryExemption(activity)
        }
        row(parent, activity.getString(R.string.desktop_component_installation), pm.canRequestPackageInstalls(), activity.getString(R.string.desktop_you_only_need_to_install_the_apk_from_the)) {
            settings(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, pkgUri(pkg)), details(pkg))
        }
        runtime(parent, pkg)
    }
    fun drivedeck(parent: LinearLayout) {
        val state = DriveDeckPermissions.snapshot(activity)
        if (state.availability != DriveDeckPermissions.Availability.READY) {
            ui.text(parent, state.description)
            ui.button(parent, activity.getString(R.string.desktop_install_components)) { activity.startActivity(Intent(activity, ComponentUpdatesActivity::class.java)) }
            return
        }
        row(parent, activity.getString(R.string.desktop_drivedeck_access_to_music_data), state.musicAccess,
            activity.getString(R.string.desktop_for_the_title_of_the_track_and_the_cover)) { DriveDeckPermissions.openMusicAccess(activity) }
        try { runtime(parent, DriveDeckPermissionPolicy.PACKAGE) }
        catch (_: PackageManager.NameNotFoundException) { ui.text(parent, activity.getString(R.string.desktop_component_removed)) }
    }
    companion object {
        const val REQUEST = 1307
        private val labels = mapOf(
            Manifest.permission.ACCESS_COARSE_LOCATION to R.string.desktop_approximate_location,
            Manifest.permission.ACCESS_FINE_LOCATION to R.string.desktop_precise_location,
            Manifest.permission.READ_CONTACTS to R.string.desktop_contacts,
            Manifest.permission.READ_PHONE_STATE to R.string.desktop_phone_status,
            Manifest.permission.BLUETOOTH_CONNECT to R.string.desktop_connecting_bluetooth_devices,
            Manifest.permission.BLUETOOTH_SCAN to R.string.desktop_search_for_bluetooth_devices,
            Manifest.permission.NEARBY_WIFI_DEVICES to R.string.desktop_nearby_wi_fi_devices,
            Manifest.permission.POST_NOTIFICATIONS to R.string.desktop_showing_drivedeck_notifications,
            "android.permission.ACCESS_LOCAL_NETWORK" to R.string.desktop_local_network_access,
        )
    }
}
