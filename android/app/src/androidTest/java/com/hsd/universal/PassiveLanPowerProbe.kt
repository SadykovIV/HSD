package com.hsd.universal

import android.app.Instrumentation
import android.content.Intent
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import com.hsd.universal.dex.DexSetupActivity
import java.net.Socket

internal fun checkPassiveLanPower(test: Instrumentation): String {
    val app = test.targetContext
    val prefs = ProjectionRoute.prefs(app)
    val oldMode = prefs.getString("mode", null)
    val oldPeer = prefs.getString("lanPeer", null)
    val service = DesktopTransport.service(app)
    fun read(command: String) =
        ParcelFileDescriptor.AutoCloseInputStream(test.uiAutomation.executeShellCommand(command))
            .bufferedReader().use { it.readText() }
    fun holdsCpu() = read("dumpsys power").lineSequence().any {
        "PARTIAL_WAKE_LOCK" in it && "'HSD:projection-cpu'" in it
    }
    fun listening() = try {
        Socket().use {
            // The real listener admits only 127.0.0.1. This source detects the port
            // without starting negotiation or competing with the listener's bind.
            it.bind(java.net.InetSocketAddress("127.0.0.2", 0))
            it.connect(java.net.InetSocketAddress("127.0.0.1", 5277), 500)
        }
        true
    } catch (_: java.net.ConnectException) { false }
    fun waitFor(label: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 15_000
        while (!condition()) {
            check(SystemClock.elapsedRealtime() < deadline) { label }
            SystemClock.sleep(100)
        }
    }
    val activity = test.startActivitySync(Intent(app, DexSetupActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra(app.packageName + ".SHOW_PERMISSIONS", true))
    try {
        check(!listening()) { "Test port already owned" }
        prefs.edit().putString("mode", ProjectionRoute.LAN_SERVER.name).putString("lanPeer", "127.0.0.1").commit()
        app.startForegroundService(service)
        waitFor("Server did not begin listening") { listening() }
        check(!holdsCpu()) { "Waiting for a head unit holds the CPU" }
        Socket("127.0.0.1", 5277).use {
            waitFor("Connected transport did not acquire CPU lock") { holdsCpu() }
            SystemClock.sleep(400)
        }
        waitFor("Server did not return to passive listening") { listening() && !holdsCpu() }
        return "Listening without a lock, acquired on accept, returned to listening without a lock after EOF"
    } finally {
        app.stopService(service)
        prefs.edit().putString("mode", oldMode).putString("lanPeer", oldPeer).commit()
        test.runOnMainSync(activity::finish)
    }
}
