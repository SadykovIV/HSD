package com.hsd.universal

import android.app.Instrumentation
import android.app.Activity
import android.content.Intent
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.Switch
import android.view.inspector.WindowInspector
import com.hsd.universal.aa.FpsSetting
import com.hsd.universal.dex.DexPrefs
import com.hsd.universal.dex.DexSetupActivity
import com.hsd.universal.dex.NavigationAppsActivity
import com.hsd.universal.dex.UsbAttachActivity
import com.hsd.universal.ui.Actions

/** Start through the two real phone screens with an absent or delayed cable interface. */
internal fun checkHiCarUsbEntry(test: Instrumentation, lateInterface: Boolean): String {
    val app = test.targetContext
    val routes = ProjectionRoute.prefs(app)
    val original = routes.getString("mode", null)
    val desktop = DexPrefs(app)
    val onboarding = desktop.initialSetupAutoPending
    val last = app.getSharedPreferences("projection", android.content.Context.MODE_PRIVATE)
    val lastKey = "last.HICAR_USB"
    val originalLast = last.getString(lastKey, null)
    val accessoryIntent = Intent(UsbManager.ACTION_USB_ACCESSORY_ATTACHED).setPackage(app.packageName)
    fun offersAoa() = app.packageManager.queryIntentActivities(accessoryIntent, 0)
        .any { it.activityInfo.name == UsbAttachActivity::class.java.name }
    fun launch(type: Class<out Activity>) = test.startActivitySync(Intent(app, type).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    fun views(root: View): List<View> = buildList {
        add(root)
        if (root is ViewGroup) for (i in 0 until root.childCount) addAll(views(root.getChildAt(i)))
    }
    fun click(activity: Activity, label: String) {
        var clicked = false
        test.runOnMainSync {
            clicked = views(activity.window.decorView).firstOrNull {
                it.isShown && it.isEnabled && it.isClickable && (it.contentDescription == label || it is Button && it.text == label)
            }?.performClick() == true
        }
        check(clicked) { "Missing clickable control: $label" }
        test.waitForIdleSync()
    }
    var main: Activity? = null
    var settings: Activity? = null
    try {
        desktop.initialSetupAutoPending = false
        Actions.stopUsbAutoStartForSession()
        ProjectionRoute.save(app, routes.edit().putString("mode", ProjectionRoute.USB.name))
        check(offersAoa())
        settings = launch(ProjectionSettingsActivity::class.java)
        click(settings, app.getString(ProjectionRoute.HICAR_USB.titleRes))
        click(settings, app.getString(R.string.desktop_use_this_connection))
        check(ProjectionRoute.selected(app) == ProjectionRoute.HICAR_USB)
        val accessoryHandler = offersAoa()
        last.edit().remove(lastKey).commit()
        main = launch(DexSetupActivity::class.java)
        if (lateInterface) {
            check(java.net.NetworkInterface.getByName("ncm95")?.isUp != true)
            android.util.Log.i("HSD-HiCar-QA", "Starting delayed USB interface test")
        }
        click(main, app.getString(R.string.desktop_connect))
        val deadline = SystemClock.elapsedRealtime() + 25_000
        var reachedService = false
        while (SystemClock.elapsedRealtime() < deadline) {
            if (BridgeService.alive && DesktopTransport.activeRoute == ProjectionRoute.HICAR_USB) reachedService = true
            if (reachedService && !BridgeService.alive) break
            SystemClock.sleep(100)
        }
        val reason = last.getString(lastKey, "").orEmpty()
        val expectedStage = if (lateInterface) "ГУ не объявило Nearby" else "12"
        check(!accessoryHandler && reachedService && reason.contains(expectedStage) && ProjectionRoute.selected(app) == ProjectionRoute.HICAR_USB) {
            "AOA handler offered=$accessoryHandler; HiCar service reached=$reachedService; result=$reason"
        }
        ProjectionRoute.save(app, routes.edit().putString("mode", ProjectionRoute.CARLIFE_USB.name))
        check(offersAoa())
        val outcome = if (lateInterface) "NCM appeared after Connect and HSD waited for Nearby" else "No NCM appeared during the interface timeout"
        return "Selected wired HiCar in Settings and pressed Connect. $outcome. AOA handler disabled for HiCar and restored for CarLife."
    } finally {
        test.runOnMainSync { main?.finish(); settings?.finish() }
        Actions.stopBridge(app, userInitiated = false)
        ProjectionRoute.save(app, routes.edit().putString("mode", original))
        last.edit().putString(lastKey, originalLast).apply()
        desktop.initialSetupAutoPending = onboarding
        Actions.resumeUsbAutoStart()
    }
}

/** Check real Activity entry points and rendered controls on each supported Android release. */
internal fun checkProjectionControls(test: Instrumentation): Bundle {
    val context = test.targetContext
    val prefs = DexPrefs(context)
    prefs.initialSetupAutoPending = false
    prefs.desktopFps = FpsSetting.FPS15
    prefs.synchronizeAudioVideo = false
    val oldOwner = prefs.desktopPanelControl
    val oldNavigation = prefs.navigationAudioApps
    val oldDpi = prefs.dpiCorrectionPackages
    val fixture = "org.example.saved.navigation"
    prefs.desktopPanelControl = false
    prefs.navigationAudioApps = setOf(fixture)
    com.hsd.universal.ui.Actions.stopUsbAutoStartForSession()
    val activity = test.startActivitySync(Intent(context, DexSetupActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    fun views(view: View): List<View> = buildList {
        add(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) addAll(views(view.getChildAt(i)))
    }
    val monitor = test.addMonitor(NavigationAppsActivity::class.java.name, null, false)
    var nav: android.app.Activity? = null
    try {
        test.runOnMainSync {
            val controls = views(activity.findViewById(android.R.id.content))
            controls.filterIsInstance<Button>().single { it.isShown && it.text == context.getString(R.string.home_settings) }.performClick()
            val labels = controls.filterIsInstance<TextView>().map { it.text.toString() }
            check(context.getString(R.string.desktop_fps_limit_value, context.getString(FpsSetting.FPS15.titleRes)) in labels)
            check(context.getString(R.string.desktop_av_sync_enabled) in labels)
            val owner = controls.filterIsInstance<Switch>().single {
                it.contentDescription == context.getString(R.string.desktop_managing_the_phone_display, DesktopTransport.appName)
            }
            val mode = controls.filterIsInstance<Button>().single {
                it.text == context.getString(R.string.desktop_deactivation_method, context.getString(prefs.phoneScreenMode.labelRes))
            }
            val timer = controls.filterIsInstance<Button>().single {
                it.text == context.getString(R.string.desktop_extinguish_through, context.getString(R.string.desktop_no_timer))
            }
            check(owner.isShown && !owner.isChecked && !mode.isShown && !timer.isShown)
            owner.performClick()
            check(prefs.desktopPanelControl && mode.isShown && timer.isShown && timer.isEnabled)
            owner.performClick()
            check(!prefs.desktopPanelControl && !mode.isShown && !timer.isShown)
            check(context.getString(R.string.projection_volume_value, context.getString(R.string.projection_music_volume), prefs.musicVolume) in labels)
            check(context.getString(R.string.projection_volume_value, context.getString(R.string.projection_navigation_volume), prefs.navigationVolume) in labels)
            check(activity.window.attributes.flags and android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON == 0)
            controls.filterIsInstance<Button>().single { it.text == context.getString(R.string.projection_navigation_apps) }.performClick()
        }
        val page = checkNotNull(monitor.waitForActivityWithTimeout(5000))
        nav = page
        val clock = listOf("com.google.android.deskclock", "com.android.deskclock").first {
            context.packageManager.getLaunchIntentForPackage(it) != null
        }
        fun await(condition: () -> Boolean) {
            val until = SystemClock.elapsedRealtime() + 8000
            while (!condition()) { check(SystemClock.elapsedRealtime() < until) { "Navigation app picker timed out" }; SystemClock.sleep(100) }
        }
        await {
            var ready = false
            test.runOnMainSync {
                val rows = views(page.findViewById(android.R.id.content))
                val add = rows.filterIsInstance<Button>().single { it.text == context.getString(R.string.desktop_add_app) }
                if (add.isEnabled) {
                    check(rows.filterIsInstance<TextView>().any { it.text == fixture })
                    rows.single { it.contentDescription == context.getString(R.string.desktop_remove_from_the_list, fixture) }.performClick()
                    check(prefs.navigationAudioApps.isEmpty())
                    add.performClick()
                    ready = true
                }
            }
            ready
        }
        await {
            var selected = false
            test.runOnMainSync {
                val widgets = WindowInspector.getGlobalWindowViews().flatMap(::views)
                val search = widgets.filterIsInstance<EditText>().firstOrNull {
                    it.isShown && it.hint == context.getString(R.string.desktop_search_by_app_or_package_name)
                } ?: return@runOnMainSync
                if (search.text.toString() != clock) search.setText(clock)
                val list = widgets.filterIsInstance<ListView>().single { it.isShown && it.rootView == search.rootView }
                check(list.count == 1 && list.getItemAtPosition(0).toString().endsWith(clock)) { "Clock absent from picker: $clock" }
                val row = list.getChildAt(0) ?: return@runOnMainSync
                selected = list.performItemClick(row, 0, list.getItemIdAtPosition(0))
            }
            selected
        }
        await { DexPrefs(context).navigationAudioApps == setOf(clock) }
        check(prefs.dpiCorrectionPackages == oldDpi)
    } finally {
        test.removeMonitor(monitor)
        test.runOnMainSync { nav?.finish(); activity.finish() }
        prefs.navigationAudioApps = oldNavigation
        prefs.desktopPanelControl = oldOwner
    }
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
    return Bundle().apply { putString("controls", "Screen owner toggled on settings index, method and timer followed it. Navigation app added by search and removed independently of DPI. FPS, A/V, volume and background USB entries checked.") }
}
