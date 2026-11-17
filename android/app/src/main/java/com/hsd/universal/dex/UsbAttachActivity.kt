package com.hsd.universal.dex

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbManager
import android.os.Bundle
import com.hsd.universal.AppLog
import com.hsd.universal.BuildConfig
import com.hsd.universal.CarLifeState
import com.hsd.universal.ProjectionRoute
import com.hsd.universal.R
import com.hsd.universal.ui.Actions
import com.hsd.universal.ui.UsbAccessoryPolicy

/** USB accessory attachment is an Activity intent. Consume it without opening a phone window. */
class UsbAttachActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try { connect(this, intent) } finally { finish() }
    }

    companion object {
        private const val CHANNEL = "usb-setup"
        private const val NOTICE = 1082

        internal fun connect(context: Context, intent: Intent) {
            if (intent.action != UsbManager.ACTION_USB_ACCESSORY_ATTACHED) return
            AppLog.init(context)
            val usb = context.getSystemService(UsbManager::class.java)
            // Intent extras are not proof of either presence or a USB grant.
            val accessories = runCatching { usb.accessoryList.orEmpty() }.getOrDefault(emptyArray())
            if (accessories.none { UsbAccessoryPolicy.route(it.manufacturer, it.model) != null }) return
            Actions.observeUsbConnection(context)
            if (Actions.isUsbAutoStartStopped()) return
            val route = ProjectionRoute.selected(context)
            val accessory = accessories.firstOrNull { UsbAccessoryPolicy.route(it.manufacturer, it.model) == route } ?: return
            val manager = context.getSystemService(NotificationManager::class.java)
            val ready = DesktopAccess.isReadyForProjection(context)
            var failureDetail: String? = null
            if (ready && usb.hasPermission(accessory)) {
                val result = Actions.scan(context, "USB attachment · background")
                if (result.state != Actions.UsbScanState.FAILED) {
                    manager.cancel(NOTICE)
                    return
                }
                failureDetail = result.detail
            }
            val detail = failureDetail ?: context.getString(if (ready) R.string.usb_background_permission else R.string.usb_background_setup)
            CarLifeState.setStatus(detail)
            AppLog.i("USB background start: $detail")
            manager.createNotificationChannel(NotificationChannel(CHANNEL, BuildConfig.APP_TITLE, NotificationManager.IMPORTANCE_DEFAULT))
            val open = PendingIntent.getActivity(context, NOTICE,
                Intent(context, DexSetupActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            runCatching {
                manager.notify(NOTICE, Notification.Builder(context, CHANNEL)
                    .setSmallIcon(R.drawable.ic_stat_hsd).setContentTitle(BuildConfig.APP_TITLE)
                    .setContentText(detail).setStyle(Notification.BigTextStyle().bigText(detail))
                    .setAutoCancel(true).setContentIntent(open).build())
            }.onFailure { AppLog.i("USB setup notification unavailable: ${it.javaClass.simpleName}", false) }
        }
    }
}
