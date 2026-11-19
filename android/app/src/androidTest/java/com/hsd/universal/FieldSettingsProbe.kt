package com.hsd.universal

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.widget.Button
import android.widget.EditText
import android.widget.RadioButton
import android.widget.TextView
import com.hsd.universal.carplay.CarPlayReceiverPairing
import com.hsd.universal.dex.DesktopModePolicy
import com.hsd.universal.dex.DesktopOnboardingActivity
import com.hsd.universal.dex.DexPrefs
import com.hsd.universal.dex.DexSetupActivity
import com.hsd.universal.link.ReceiverAudioCalibration
import io.github.shaonialife.cardeck.airplay.CarPlayTestPeer
import io.github.shaonialife.cardeck.airplay.TlvTransport
import java.io.File
import java.io.IOException
import java.net.ProtocolException

/** Operates the production wizard, settings and pairing store on disposable emulator installs. */
internal fun fieldSettings(test: Instrumentation, mode: String): String {
    val context = test.targetContext
    val prefs = DexPrefs(context)
    fun onUi(action: () -> Unit) {
        var failure: Throwable? = null
        test.runOnMainSync { runCatching(action).onFailure { failure = it } }
        failure?.let { throw it }
    }
    fun string(id: Int, vararg args: Any) = context.getString(id, *args)
    fun views(view: View): List<View> = buildList {
        add(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) addAll(views(view.getChildAt(i)))
    }
    fun page(activity: Activity) = views(activity.window.decorView)
    fun dialog() = views(WindowInspector.getGlobalWindowViews().last())
    fun button(items: List<View>, label: String) = items.filterIsInstance<Button>().single { it.isShown && it.text == label }
    fun launch(type: Class<out Activity>) = test.startActivitySync(Intent(context,type).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    val mark = File(context.filesDir, "field-upgrade-uid")
    if (mode == "updated") {
        check(mark.readText() == context.applicationInfo.uid.toString())
        check(prefs.initialSetupInProgress && prefs.initialSetupStep == 1) {
            val stored = context.getSharedPreferences("hsd_dex", Context.MODE_PRIVATE)
            "Wizard after update: in progress=${prefs.initialSetupInProgress}, step=${prefs.initialSetupStep}, " +
                "raw progress=${stored.all["initial_setup_in_progress"]}, raw step=${stored.all["initial_setup_step"]}, " +
                "data=${context.dataDir}, deviceProtected=${context.isDeviceProtectedStorage}"
        }
        check(prefs.desktopMode == DesktopModePolicy.DRIVEDECK) { "Desktop profile after update: ${prefs.desktopMode}" }
    }
    prefs.initialSetupAutoPending = false
    prefs.desktopMode = DesktopModePolicy.DRIVEDECK
    prefs.initialSetupInProgress = true
    prefs.initialSetupStep = 0
    val wizard = launch(DesktopOnboardingActivity::class.java)
    try {
        val keyguard = context.getSystemService(android.app.KeyguardManager::class.java)
        if (keyguard.isKeyguardLocked && !keyguard.isDeviceSecure) {
            // The idle scenario removes its test PIN. On older images the swipe screen
            // can remain on the phone even after "wm dismiss-keyguard" reports success.
            val metrics = context.resources.displayMetrics
            val x = metrics.widthPixels / 2
            val swipe = "input swipe $x ${metrics.heightPixels * 3 / 4} $x ${metrics.heightPixels / 4} 240"
            android.os.ParcelFileDescriptor.AutoCloseInputStream(test.uiAutomation.executeShellCommand(swipe))
                .use { it.readBytes() }
        }
        // A previous screen-lock scenario can leave the window attached before its first traversal.
        val showDeadline = SystemClock.elapsedRealtime() + 5_000
        var visible = false
        do {
            onUi { visible = page(wizard).filterIsInstance<Button>().any { it.isShown } }
            if (!visible) SystemClock.sleep(50)
        } while (!visible && SystemClock.elapsedRealtime() < showDeadline)
        check(visible) {
            val power = context.getSystemService(android.os.PowerManager::class.java)
            var window = ""
            onUi {
                val decor = wizard.window.decorView
                window = "display=${decor.display?.displayId} visibility=${decor.windowVisibility} focus=${decor.hasWindowFocus()}"
            }
            "Setup window did not show a button: $window interactive=${power.isInteractive} locked=${keyguard.isKeyguardLocked} secure=${keyguard.isDeviceSecure} step=${prefs.initialSetupStep}"
        }
        onUi {
            val all = page(wizard)
            val next = if (mode == "seed") {
                // Resource IDs can change across APK versions.
                val id = context.resources.getIdentifier("desktop_next", "string", context.packageName)
                button(all, context.getString(id))
            } else {
                val status = all.filterIsInstance<TextView>().filter { it.isShown }.map { it.text.toString() }
                if (mode == "missing") check(context.getString(R.string.desktop_for_the_selected_desktop_install_drivedeck_it_shows_the, "HSD") in status)
                else {
                    val info = context.packageManager.getPackageInfo(DesktopModePolicy.DRIVEDECK_PACKAGE, 0)
                    check(string(R.string.desktop_installed_build, info.versionName.orEmpty(), info.longVersionCode) in status) {
                        "Installed DriveDeck ${info.versionName} (${info.longVersionCode}), wizard shows $status"
                    }
                    check(context.getString(R.string.desktop_for_the_selected_desktop_install_drivedeck_it_shows_the, "HSD") !in status)
                }
                button(all, string(R.string.desktop_next))
            }
            check(next.isEnabled && next.performClick())
            check(prefs.initialSetupStep == 1)
        }
    } finally { test.runOnMainSync { wizard.finish() }; test.waitForIdleSync() }
    if (mode == "seed") {
        // Finishing instrumentation immediately kills this process. Wait on the
        // instrumentation thread for the wizard's last asynchronous preference write.
        check(context.getSharedPreferences("hsd_dex", Context.MODE_PRIVATE).edit().commit()) {
            "Published wizard state did not reach disk"
        }
        mark.writeText(context.applicationInfo.uid.toString())
        return "Published wizard reached Permissions and retained the selected profile"
    }
    if (mode == "missing") return "Next opened Permissions without the companion"
    prefs.initialSetupInProgress = false
    val originalSync = prefs.synchronizeAudioVideo
    prefs.synchronizeAudioVideo = false
    val route = ProjectionRoute.selected(context)
    val target = ReceiverAudioCalibration.selected(context,route)
    val activity = launch(DexSetupActivity::class.java)
    try {
        onUi {
            val app = page(activity)
            button(app,string(R.string.home_settings)).performClick()
            for ((title, hint) in listOf(string(R.string.projection_separate_navigation) to string(R.string.projection_separate_navigation_help),
                string(R.string.desktop_managing_the_phone_display, "HSD") to string(R.string.desktop_phone_screen_owner_hint))) {
                check(app.filterIsInstance<TextView>().none { it.isShown && it.text.toString().contains(hint) })
                app.single { it.contentDescription == string(R.string.desktop_help, title) }.performClick()
                val contents = dialog()
                check(contents.filterIsInstance<TextView>().any { it.text.toString().contains(hint) })
                button(contents, string(R.string.apps_warn_ok)).performClick()
            }
        }
        for ((option, value) in listOf(R.string.audio_timing_late to -100, R.string.audio_timing_early to 200, R.string.audio_timing_matches to 0)) {
            test.waitForIdleSync()
            onUi {
                button(page(activity), string(R.string.desktop_audio_sync)).performClick()
                val form = dialog()
                form.filterIsInstance<RadioButton>().single { it.text == string(option) }.performClick()
                val field = form.filterIsInstance<EditText>().single()
                if (value != 0) {
                    val save = button(form, string(if (prefs.synchronizeAudioVideo) R.string.desktop_save else R.string.audio_timing_enable_save))
                    field.setText("2001"); save.performClick()
                    check(field.error != null)
                    field.setText(kotlin.math.abs(value).toString())
                    save.performClick()
                } else {
                    check(!field.isShown)
                    button(form,string(R.string.desktop_save)).performClick()
                }
                check(ReceiverAudioCalibration.selected(context,route).ms == value && prefs.synchronizeAudioVideo)
            }
        }
    } finally {
        test.runOnMainSync { activity.finish() }; test.waitForIdleSync()
        prefs.synchronizeAudioVideo = originalSync
        ReceiverAudioCalibration.set(context,target,target.ms)
    }
    val receiver = "fixture-pairing-${SystemClock.uptimeMillis()}"
    val original = CarPlayTestPeer()
    val replacement = CarPlayTestPeer()
    fun connect(peer: CarPlayTestPeer, setupAllowed: Boolean) {
        val keys = CarPlayReceiverPairing.authenticate(context, receiver, null, TlvTransport { path, body ->
            check(setupAllowed || path == "/pair-verify") { "Unexpected Pair Setup on reconnect" }
            peer.handle(path, body)
        })
        check(keys.phoneReadKey.contentEquals(peer.lastWriteKey) && keys.phoneWriteKey.contentEquals(peer.lastReadKey))
    }
    try {
        connect(original, true)
        connect(original, false)
        check(runCatching { connect(replacement, false) }.exceptionOrNull() is ProtocolException)
        CarPlayReceiverPairing.requestRenewal(context, receiver)
        val interrupted = runCatching {
            CarPlayReceiverPairing.authenticate(context, receiver, null, TlvTransport { path, body ->
                if (path == "/pair-verify") throw IOException("Fixture disconnected before verification")
                replacement.handle(path, body)
            })
        }
        check(interrupted.exceptionOrNull()?.message == "Fixture disconnected before verification") {
            "Renewal did not reach Pair Verify: ${interrupted.exceptionOrNull()?.stackTraceToString()}"
        }
        connect(original, false)
        check(runCatching { connect(replacement, false) }.exceptionOrNull() is ProtocolException)
        CarPlayReceiverPairing.requestRenewal(context, receiver)
        connect(replacement, true)
        connect(replacement, false)
        check(runCatching { connect(original, false) }.exceptionOrNull() is ProtocolException)
    } finally { CarPlayReceiverPairing.forget(context, receiver) }
    return "Wizard, info, timing directions, invalid input, saved timing, authenticated reconnect and interrupted/confirmed receiver renewal verified"
}
