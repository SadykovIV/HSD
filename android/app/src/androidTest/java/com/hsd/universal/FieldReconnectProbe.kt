package com.hsd.universal

import android.app.Instrumentation
import android.app.NotificationManager
import android.content.Intent
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.hsd.universal.dex.UsbAttachActivity
import com.hsd.universal.update.ComponentPackages
import com.hsd.universal.update.ComponentUpdatesActivity
import com.hsd.universal.update.DesktopComponent

/** Starts the same exported USB entry point that Android can deliver before an accessory is visible. */
internal fun checkFieldReconnect(test: Instrumentation): Bundle {
    val app = test.targetContext
    check(ProjectionRoute.selected(app).usesUsbAccessory && !BridgeService.alive)
    check(app.getSystemService(UsbManager::class.java).accessoryList.isNullOrEmpty())
    repeat(2) {
        app.startActivity(Intent(app, UsbAttachActivity::class.java)
            .setAction(UsbManager.ACTION_USB_ACCESSORY_ATTACHED).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        test.waitForIdleSync()
    }
    SystemClock.sleep(5200)
    check(!BridgeService.alive)
    check(app.getSystemService(NotificationManager::class.java).activeNotifications.none { it.notification.channelId == "usb-setup" })

    val code = requireNotNull(ComponentPackages.installed(app, DesktopComponent.DRIVEDECK)).longVersionCode
    check(code == 357L || code == 362L)
    val page = test.startActivitySync(Intent(app, ComponentUpdatesActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    try {
        test.runOnMainSync {
            fun contains(view: View, label: String): Boolean {
                if (view is TextView && label in view.text.toString()) return true
                return view is ViewGroup && (0 until view.childCount).any { contains(view.getChildAt(it), label) }
            }
            val root = page.findViewById<View>(android.R.id.content)
            check(contains(root, page.getString(R.string.desktop_installed_build, "0.7.0", code)))
            check(contains(root, page.getString(R.string.drivedeck_playback_update)) == (code == 357L))
        }
    } finally {
        test.runOnMainSync { page.finish() }
    }
    return Bundle().apply {
        putString("result", "PASS")
        putString("field", "two USB attach events without an accessory stayed idle; DriveDeck $code playback advisory verified")
    }
}
