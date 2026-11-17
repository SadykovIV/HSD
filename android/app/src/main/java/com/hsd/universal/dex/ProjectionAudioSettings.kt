package com.hsd.universal.dex

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.widget.LinearLayout
import android.widget.SeekBar
import com.hsd.universal.R

internal object ProjectionAudioSettings {
    fun add(activity: Activity, ui: DesktopSetupViews, parent: LinearLayout, prefs: DexPrefs) {
        fun volume(title: Int, current: Int, save: (Int) -> Unit) {
            val name = activity.getString(title)
            val label = ui.text(parent, activity.getString(R.string.projection_volume_value, name, current), 15f, true)
            parent.addView(SeekBar(activity).apply {
                max = 100; progress = current; contentDescription = name
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onStartTrackingTouch(view: SeekBar) {}
                    override fun onStopTrackingTouch(view: SeekBar) {}
                    override fun onProgressChanged(view: SeekBar, value: Int, user: Boolean) {
                        if (user) save(value)
                        label.text = activity.getString(R.string.projection_volume_value, name, value)
                    }
                })
            }, LinearLayout.LayoutParams(-1, -2))
        }
        volume(R.string.projection_music_volume, prefs.musicVolume) { prefs.musicVolume = it }
        volume(R.string.projection_navigation_volume, prefs.navigationVolume) { prefs.navigationVolume = it }
        ui.text(parent, activity.getString(R.string.projection_volume_help), 13f)
        ui.button(parent, activity.getString(R.string.projection_navigation_apps)) {
            val pm = activity.packageManager
            val apps = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                .mapNotNull { it.activityInfo?.applicationInfo }.filter { it.enabled && it.uid % 100000 >= 10000 }
                .distinctBy { it.packageName }.map { it.packageName to pm.getApplicationLabel(it).toString() }
                .sortedBy { it.second.lowercase() }
            val selected = prefs.navigationAudioApps.toMutableSet()
            AlertDialog.Builder(activity).setTitle(R.string.projection_navigation_apps)
                .setMultiChoiceItems(apps.map { it.second }.toTypedArray(),
                    apps.map { it.first in selected }.toBooleanArray()) { _, index, checked ->
                    if (checked) selected.add(apps[index].first) else selected.remove(apps[index].first)
                }
                .setPositiveButton(android.R.string.ok) { _, _ -> prefs.navigationAudioApps = selected }
                .setNegativeButton(android.R.string.cancel, null).show()
        }
        ui.text(parent, activity.getString(R.string.projection_navigation_apps_help), 13f)
    }
}
