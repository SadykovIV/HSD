package com.hsd.universal.dex

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.text.InputFilter
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import com.hsd.universal.ProjectionRoute
import com.hsd.universal.R
import com.hsd.universal.link.ReceiverAudioCalibration

internal object ProjectionAudioSettings {
    fun timing(activity: Activity, ui: DesktopSetupViews, prefs: DexPrefs, saved: () -> Unit) {
        val receiver = ReceiverAudioCalibration.selected(activity, ProjectionRoute.selected(activity))
        val body = ui.column().apply { setPadding(ui.dp(20), ui.dp(8), ui.dp(20), ui.dp(8)) }
        ui.text(body, receiver.name, 16f, true)
        ui.text(body, activity.getString(R.string.audio_timing_question), 15f)
        val options = RadioGroup(activity).also { body.addView(it) }
        fun option(label: Int) = RadioButton(activity).apply {
            id = View.generateViewId()
            setText(label)
            options.addView(this)
        }
        val automatic = option(R.string.audio_timing_matches)
        val early = option(R.string.audio_timing_early)
        val late = option(R.string.audio_timing_late)
        val amount = EditText(activity).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            filters = arrayOf(InputFilter.LengthFilter(4))
            setSingleLine()
            hint = activity.getString(R.string.audio_timing_amount, ReceiverAudioCalibration.MAX_MS)
            contentDescription = hint
            if (receiver.ms != 0) setText(kotlin.math.abs(receiver.ms).toString())
            body.addView(this)
        }
        val explanation = ui.text(body, "", 14f)
        options.setOnCheckedChangeListener { _, id ->
            amount.visibility = if (id == automatic.id) View.GONE else View.VISIBLE
            amount.error = null
            explanation.setText(when (id) {
                early.id -> R.string.audio_timing_early_help
                late.id -> R.string.audio_timing_late_help
                else -> R.string.audio_timing_auto_help
            })
        }
        options.check(when {
            receiver.ms > 0 -> early.id
            receiver.ms < 0 -> late.id
            else -> automatic.id
        })
        ui.text(body, activity.getString(R.string.audio_timing_applies), 13f)
        val dialog = AlertDialog.Builder(activity).setTitle(R.string.desktop_audio_sync)
            .setView(ScrollView(activity).apply { addView(body) })
            .setNegativeButton(R.string.mock_cancel, null)
            .setPositiveButton(if (prefs.synchronizeAudioVideo) R.string.desktop_save else R.string.audio_timing_enable_save, null)
            .create()
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val direction = options.checkedRadioButtonId
            val offset = if (direction == automatic.id) 0 else {
                val value = amount.text.toString().toIntOrNull()
                if (value == null || value !in 1..ReceiverAudioCalibration.MAX_MS) {
                    amount.error = amount.hint
                    return@setOnClickListener
                }
                if (direction == late.id) -value else value
            }
            ReceiverAudioCalibration.set(activity, receiver, offset)
            prefs.synchronizeAudioVideo = true
            saved()
            dialog.dismiss()
        }
    }

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
        val navigationLabel = activity.getString(R.string.projection_separate_navigation)
        val navigation = ui.toggle(parent, navigationLabel, prefs.separateNavigationAudio) {
            prefs.separateNavigationAudio = it
        }
        ui.help(navigation.row, navigationLabel) {
            activity.getString(R.string.projection_separate_navigation_help) + "\n\n" + activity.getString(R.string.projection_volume_help)
        }
        ui.button(parent, activity.getString(R.string.projection_navigation_apps)) {
            activity.startActivity(Intent(activity, NavigationAppsActivity::class.java))
        }
    }
}
