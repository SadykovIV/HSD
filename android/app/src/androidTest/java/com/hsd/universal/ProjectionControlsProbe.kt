package com.hsd.universal

import android.app.Instrumentation
import android.content.Intent
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.hsd.universal.aa.FpsSetting
import com.hsd.universal.dex.DexPrefs
import com.hsd.universal.dex.DexSetupActivity
import com.hsd.universal.dex.UsbAttachActivity

/** Check real Activity entry points and rendered controls on each supported Android release. */
internal fun checkProjectionControls(test: Instrumentation): Bundle {
    val context = test.targetContext
    val prefs = DexPrefs(context)
    prefs.initialSetupAutoPending = false
    prefs.desktopFps = FpsSetting.FPS15
    prefs.synchronizeAudioVideo = false
    check(prefs.useSystemScreenTimeout)
    com.hsd.universal.ui.Actions.stopUsbAutoStartForSession()
    val activity = test.startActivitySync(Intent(context, DexSetupActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    fun texts(view: View): List<String> = buildList {
        if (view is TextView) add(view.text.toString())
        if (view is ViewGroup) for (i in 0 until view.childCount) addAll(texts(view.getChildAt(i)))
    }
    try {
        test.runOnMainSync {
            val labels = texts(activity.findViewById(android.R.id.content))
            check(context.getString(R.string.desktop_fps_limit_value, context.getString(FpsSetting.FPS15.titleRes)) in labels)
            check(context.getString(R.string.desktop_av_sync_enabled) in labels)
            check(context.getString(R.string.desktop_system_sleep) in labels)
            check(context.getString(R.string.projection_volume_value, context.getString(R.string.projection_music_volume), prefs.musicVolume) in labels)
            check(context.getString(R.string.projection_volume_value, context.getString(R.string.projection_navigation_volume), prefs.navigationVolume) in labels)
            check(activity.window.attributes.flags and android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON == 0)
        }
    } finally { test.runOnMainSync(activity::finish) }
    test.waitForIdleSync()
    check(context.getSystemService(UsbManager::class.java).accessoryList.isNullOrEmpty())
    val handlers = context.packageManager.queryIntentActivities(
        Intent(UsbManager.ACTION_USB_ACCESSORY_ATTACHED).setPackage(context.packageName), 0)
    check(handlers.map { it.activityInfo.name } == listOf(UsbAttachActivity::class.java.name)) { handlers.toString() }
    test.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)
    SystemClock.sleep(500)
    for (entry in listOf(UsbAttachActivity::class.java.name, context.packageName + ".UsbActivity", DexSetupActivity::class.java.name)) {
        context.startActivity(Intent(UsbManager.ACTION_USB_ACCESSORY_ATTACHED)
            .setClassName(context, entry).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        SystemClock.sleep(600)
        test.waitForIdleSync()
        check(test.uiAutomation.rootInActiveWindow?.packageName?.toString() != context.packageName) { "USB entry opened phone UI: $entry" }
        check(!BridgeService.alive) { "An unverified USB intent started projection" }
    }
    prefs.desktopFps = FpsSetting.AUTO
    prefs.synchronizeAudioVideo = true
    com.hsd.universal.ui.Actions.resumeUsbAutoStart()
    return Bundle().apply { putString("controls", "FPS and A/V controls rendered; normal screen policy; all USB entries stayed invisible and rejected absent hardware") }
}
