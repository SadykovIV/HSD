package com.hsd.universal.dex

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import android.os.Bundle
import com.hsd.universal.AppLog
import com.hsd.universal.BuildConfig
import com.hsd.universal.CarLifeState
import com.hsd.universal.ProjectionRoute
import com.hsd.universal.R
import com.hsd.universal.ui.Actions

/** USB accessory attachment is an Activity intent. Consume it without opening a phone window. */
class UsbAttachActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try { connect(this, intent) } finally { finish() }
    }

    companion object {
        private const val CHANNEL = "usb-setup"
        private const val NOTICE = 1082

        internal fun syncHandler(context: Context, route: ProjectionRoute) {
            val component = ComponentName(context, UsbAttachActivity::class.java)
            val state = if (route == ProjectionRoute.HICAR_USB) PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                else PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
            val manager = context.packageManager
            if (manager.getComponentEnabledSetting(component) != state)
                manager.setComponentEnabledSetting(component, state, PackageManager.DONT_KILL_APP)
        }

        internal fun connect(context: Context, intent: Intent) {
            if (intent.action != UsbManager.ACTION_USB_ACCESSORY_ATTACHED) return
            AppLog.init(context)
            Actions.usbAttachmentReported(context)
        }

        internal fun startAttached(context: Context, usb: UsbManager, accessory: UsbAccessory): Boolean {
            val manager = context.getSystemService(NotificationManager::class.java)
            val ready = DesktopAccess.isReadyForProjection(context)
            var failureDetail: String? = null
            if (ready && runCatching { usb.hasPermission(accessory) }.getOrDefault(false)) {
                val result = Actions.scan(context, "USB attachment · background")
                if (result.state == Actions.UsbScanState.WAITING_FOR_ACCESSORY || result.state == Actions.UsbScanState.DETACHED) return false
                if (result.state != Actions.UsbScanState.FAILED) {
                    manager.cancel(NOTICE)
                    return true
                }
                failureDetail = result.detail
            }
            val detail = failureDetail ?: context.getString(if (ready) R.string.usb_background_permission else R.string.usb_background_setup)
            CarLifeState.setStatus(detail)
            AppLog.i("USB background start: $detail")
            manager.createNotificationChannel(NotificationChannel(CHANNEL, BuildConfig.APP_TITLE, NotificationManager.IMPORTANCE_DEFAULT))
            val open = PendingIntent.getActivity(context, NOTICE,
                Intent(context, DexSetupActivity::class.java).putExtra(DexSetupActivity.EXTRA_SHOW_ACTIVATION, !ready)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            runCatching {
                manager.notify(NOTICE, Notification.Builder(context, CHANNEL)
                    .setSmallIcon(R.drawable.ic_stat_hsd).setContentTitle(BuildConfig.APP_TITLE)
                    .setContentText(detail).setStyle(Notification.BigTextStyle().bigText(detail))
                    .setAutoCancel(true).setContentIntent(open).build())
            }.onFailure { AppLog.i("USB setup notification unavailable: ${it.javaClass.simpleName}", false) }
            return true
        }
    }
}
