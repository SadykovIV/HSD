package com.hsd.universal.dex

import com.hsd.universal.R
import com.hsd.universal.compat.PublishedDesktop
import com.hsd.universal.BuildConfig

import android.app.Activity
import android.app.AlertDialog
import android.widget.CheckBox
import com.hsd.universal.component.DesktopComponentEntry
import com.hsd.universal.component.DesktopComponents

/** Profile names belong to the phone UI. Independent profiles run on the Android Desktop engine. */
internal object DesktopProfilePicker {
    fun name(entry: DesktopComponentEntry) = when {
        entry.modeId != "carbridge" -> entry.label
        entry.packageName == PublishedDesktop.CARBRIDGE_PREVIEW ->
            if (entry.dashboard) "CarBridge · панель ${BuildConfig.BRAND}" else "CarBridge · тест SDK"
        else -> "CarBridge"
    }

    fun show(activity: Activity, currentMode: Int, unlocked: () -> Boolean, apply: (Int, DesktopComponentEntry?, Boolean) -> Unit) {
        val installed = DesktopComponents.list(activity)
        val selected = DesktopComponents.selected(activity).takeIf { currentMode == DesktopModePolicy.ANDROID_DESKTOP }
        val carBridge = installed.filter { it.modeId == "carbridge" }
        val labels = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()
        var checked = -1

        fun row(label: String, active: Boolean, action: () -> Unit) {
            if (active) checked = labels.size
            labels += label
            actions += action
        }
        fun builtin(label: String, mode: Int) =
            row(label, selected == null && currentMode == mode) { if (unlocked()) apply(mode, null, false) }
        fun choose(entry: DesktopComponentEntry) {
            if (!unlocked()) return
            if (entry == selected && !entry.dashboard) return
            val media = if (entry.dashboard) CheckBox(activity).apply {
                text = activity.getString(R.string.desktop_allow_current_track_and_cover_to_be_shown_and)
                isChecked = DesktopComponents.mediaAllowed(activity, entry)
            } else null
            val dialog = AlertDialog.Builder(activity).setTitle(activity.getString(R.string.desktop_use_profile, name(entry)))
                .setMessage(activity.getString(R.string.desktop_app_package_activity_version_certificate_sha_256_this_profile, entry.appLabel, entry.packageName, entry.componentName.className, entry.versionName, entry.versionCode, entry.signerSha256.sorted().joinToString("\n")))
                .setPositiveButton(activity.getString(R.string.desktop_use_profile_7e5d)) { _, _ -> if (unlocked()) apply(DesktopModePolicy.ANDROID_DESKTOP, entry, media?.isChecked == true) }
                .setNegativeButton(activity.getString(R.string.mock_cancel), null)
            if (media != null) dialog.setView(media)
            dialog.show()
        }
        builtin("Android Desktop", DesktopModePolicy.ANDROID_DESKTOP)
        val carBridgeTitle = when (carBridge.size) {
            0 -> activity.getString(R.string.desktop_carbridge_fixed)
            1 -> name(carBridge.single())
            else -> "CarBridge"
        }
        row(carBridgeTitle, selected?.modeId == "carbridge") {
            when (carBridge.size) {
                0 -> AlertDialog.Builder(activity).setTitle(activity.getString(R.string.desktop_profile_of_carbridge))
                    .setMessage(activity.getString(R.string.desktop_this_profile_requires_an_apk_compatible_panel_from_the, BuildConfig.BRAND))
                    .setPositiveButton(activity.getString(R.string.apps_warn_ok), null).show()
                1 -> choose(carBridge.single())
                else -> AlertDialog.Builder(activity).setTitle(activity.getString(R.string.desktop_profile_of_carbridge_select))
                    .setItems(carBridge.map { "${it.appLabel}\n${it.componentName.flattenToShortString()}" }.toTypedArray()) { _, index -> choose(carBridge[index]) }
                    .setNegativeButton(activity.getString(R.string.mock_cancel), null).show()
            }
        }
        builtin("DriveDeck", DesktopModePolicy.DRIVEDECK)
        if (SamsungDexCapability.isAvailable(activity)) builtin("Samsung DeX", DesktopModePolicy.SAMSUNG_DEX)
        for (entry in installed.filterNot { it.modeId == "carbridge" }) {
            row("${name(entry)} · ${entry.packageName}", selected?.componentName == entry.componentName) { choose(entry) }
        }
        AlertDialog.Builder(activity).setTitle(activity.getString(R.string.desktop_desktop_profile))
            .setSingleChoiceItems(labels.toTypedArray(), checked) { dialog, index ->
                dialog.dismiss()
                if (unlocked()) actions[index]()
            }.setNegativeButton(activity.getString(R.string.mock_cancel), null).show()
    }
}
