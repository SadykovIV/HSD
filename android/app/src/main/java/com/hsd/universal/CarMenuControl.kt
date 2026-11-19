package com.hsd.universal

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import com.hsd.universal.embed.EmbeddedSessionRegistry
import com.hsd.universal.shared.ProjectionHost

/** A command for the current projected display. It never starts a service or a connection. */
internal object CarMenuControl {
    fun request(context: Context, displayId: Int) {
        if (displayId <= 0) return
        context.sendBroadcast(Intent(ProjectionHost.CAR_MENU_ACTION)
            .setPackage(context.packageName).putExtra(ProjectionHost.DISPLAY, displayId))
    }

    @android.annotation.SuppressLint("UnspecifiedRegisterReceiverFlag") // Only the API <33 branch uses legacy export semantics.
    fun register(context: Context, request: () -> Unit): AutoCloseable {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action != ProjectionHost.CAR_MENU_ACTION) return
                val display = intent.getIntExtra(ProjectionHost.DISPLAY, -1)
                if (display <= 0 || EmbeddedSessionRegistry.captureParent(display) == null) return
                AppLog.i("Car menu: active display $display", false)
                request()
            }
        }
        val filter = IntentFilter(ProjectionHost.CAR_MENU_ACTION)
        val permission = context.packageName + ".permission.EMBED_DISPLAY"
        if (Build.VERSION.SDK_INT >= 33)
            context.registerReceiver(receiver, filter, permission, null, Context.RECEIVER_EXPORTED)
        else @Suppress("DEPRECATION") context.registerReceiver(receiver, filter, permission, null)
        return AutoCloseable { context.unregisterReceiver(receiver) }
    }
}
