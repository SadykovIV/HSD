package com.hsd.universal

import android.app.Notification
import android.bluetooth.BluetoothManager
import android.net.InetAddresses
import net.prototux.aare.aap.AapLog
import net.prototux.aare.aap.Transport
import net.prototux.aare.transport.wireless.WirelessConnector
import com.hsd.universal.carplay.CarPlayRecovery
import com.hsd.universal.dex.DexSetupActivity
import com.hsd.universal.ui.Actions
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import android.os.Handler
import android.os.Looper
import net.prototux.aare.transport.UsbAccessoryTransport
import java.util.concurrent.Executors

private fun UsbManager.autoAccessory(): UsbAccessory? = accessoryList.orEmpty().firstOrNull { com.hsd.universal.ui.UsbAccessoryPolicy.acceptsAndroidAuto(it.manufacturer, it.model) }

class AutoService : BridgeService() {
    override fun endpoint(route: ProjectionRoute): ProjectionEndpoint? =
        if (route.carLife) null else AutoEndpoint(this, route)
}

/** Android Auto and CarPlay share one service owner with CarLife, including route changes. */
private class AutoEndpoint(private val service: Service, private val route: ProjectionRoute) :
    ContextWrapper(service), ProjectionEndpoint {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val usb by lazy { checkNotNull(getSystemService(UsbManager::class.java)) }
    private var projection: ProjectionSession? = null
    private var resources: ProjectionResources? = null
    private var generation = 0
    private var device: UsbAccessory? = null
    private var stopping = false
    private var reconnectUntil = 0L
    private val carPlayRecovery = CarPlayRecovery()
    private var usbOpenRetries = 0
    private var retryDelayMs = 1000L
    private var retryPending = false
    private val reconnect = Runnable {
        retryPending = false
        if (!stopping && resources == null) begin()
    }
    private val detached = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (device != null && usb.autoAccessory() == null) {
                com.hsd.universal.dex.DesktopMusicPlayback.physicalDetach(this@AutoEndpoint)
                Actions.onUsbDetached(service)
                finish(service.getString(R.string.wl_usb_to_air_title))
            }
        }
    }

    init {
        getSystemService(NotificationManager::class.java)!!.createNotificationChannel(
            NotificationChannel(CHANNEL, service.getString(R.string.desktop_head_unit_projection), NotificationManager.IMPORTANCE_LOW))
        registerReceiver(detached, IntentFilter(UsbManager.ACTION_USB_ACCESSORY_DETACHED), RECEIVER_EXPORTED)
    }

    override fun start(intent: Intent?, startId: Int): Int {
        if (intent?.action == BridgeService.ACTION_DESKTOP_HOME || intent?.action == BridgeService.ACTION_PHONE_SCREEN_OFF ||
            intent?.action == BridgeService.ACTION_PHONE_SCREEN_ON) {
            if (!stopping) {
                if (intent.action == BridgeService.ACTION_DESKTOP_HOME) projection?.home()
                else projection?.panel(intent.action == BridgeService.ACTION_PHONE_SCREEN_OFF)
            }
            if (resources == null && !retryPending) service.stopSelfResult(startId)
            return Service.START_NOT_STICKY
        }
        if (intent?.action == BridgeService.ACTION_STOP) {
            service.startForeground(ID, notification(service.getString(R.string.desktop_stopping_projection)))
            Actions.stopUsbAutoStartForSession()
            finish(service.getString(R.string.desktop_stopped_on_phone))
            return Service.START_NOT_STICKY
        }
        if (intent?.action == BridgeService.ACTION_WIFI_B_AUTO || intent?.action == BridgeService.ACTION_WIFI_B_AUTO_OFF ||
            intent?.action == BridgeService.ACTION_WIFI_B) {
            if (resources == null && !retryPending) {
                service.startForeground(ID, notification(service.getString(R.string.desktop_ending_carlife_standby)))
                service.stopSelfResult(startId)
            }
            return Service.START_NOT_STICKY
        }
        if (resources != null || stopping || retryPending) return Service.START_NOT_STICKY
        begin()
        return Service.START_NOT_STICKY
    }

    private fun begin() {
        val ticket = ++generation
        val mode = route
        val lifetime = ProjectionResources()
        resources = lifetime
        CarLifeState.resetSession()
        CarLifeState.wirelessSession = mode != ProjectionRoute.USB
        try {
            service.startForeground(ID, notification(mode.label(service)))
            if (mode == ProjectionRoute.USB) {
                device = usb.autoAccessory()?.takeIf(usb::hasPermission)
                    ?: error(service.getString(R.string.desktop_connect_the_cable_and_allow_hsd_to_open_the))
            }
        } catch (error: RuntimeException) {
            complete(error.message ?: service.getString(R.string.desktop_could_not_start_connection))
            return
        }
        update(if (mode == ProjectionRoute.LAN_SERVER) service.getString(R.string.desktop_waiting_for_the_head_unit_on_tcp_port_5277) else service.getString(R.string.desktop_connecting, mode.label(service)))
        val attached = device
        worker.execute {
            try {
                val publish: (String) -> Unit = { message -> main.post { if (ticket == generation) update(message) } }
                val ended: (String) -> Unit = { message -> main.post { if (ticket == generation) complete(message) } }
                val session = if (mode == ProjectionRoute.CARPLAY) {
                    carPlay(lifetime, publish, ended)
                } else {
                    val identity = ProjectionIdentity(this)
                    val (transport, peer) = openAuto(mode, attached, lifetime,identity)
                    lifetime.own(transport::close)
                    AutoProjection(this, transport, identity.phone(peer), identity, peer, publish, ended) {
                        main.post {
                            if (mode == ProjectionRoute.USB && ticket == generation && !stopping && !CarLifeState.running) {
                                CarLifeState.running = true
                                Actions.onUsbSessionStarted(service)
                            }
                        }
                    }
                }
                main.post {
                    if (ticket == generation && !stopping) {
                        projection = session
                        CarLifeState.running = mode != ProjectionRoute.USB
                        runCatching { session.start() }.onFailure { finish(it.message ?: service.getString(R.string.desktop_failed_to_start_projection)) }
                    } else lifetime.close()
                }
            } catch (error: Exception) {
                val transient = error is java.io.IOException && error !is javax.net.ssl.SSLException ||
                    error is net.prototux.aare.aap.wireless.WirelessBootstrapException && error.whileNegotiating
                main.post { if (ticket == generation) complete(service.getString(R.string.desktop_could_not_connect, error.message), transient) }
            }
        }
    }

    private fun openAuto(mode: ProjectionRoute, attached: UsbAccessory?, lifetime: ProjectionResources, identity: ProjectionIdentity): Pair<Transport,String> {
        val settings = ProjectionRoute.prefs(this)
        return when (mode) {
            ProjectionRoute.USB -> {
                val accessory = checkNotNull(attached)
                val id = identity.usbPeer(accessory)
                UsbAccessoryTransport(usb, accessory) to id
            }
            ProjectionRoute.LAN_SERVER -> {
                val ip = InetAddresses.parseNumericAddress(settings.getString("lanPeer", null)
                    ?: error(service.getString(R.string.desktop_enter_the_head_unit_s_ip_address_in_connection)))
                val listener = HeadUnitListener(ip)
                lifetime.own(listener::close)
                listener.use { it.accept() } to "lan|${ip.hostAddress}"
            }
            ProjectionRoute.LAN_CLIENT -> {
                val address = InetAddresses.parseNumericAddress(settings.getString("lanPeer", null)
                    ?: error(service.getString(R.string.desktop_enter_the_head_unit_s_ip_address_in_connection)))
                val port = settings.getInt("lanPort", 5288)
                val socket = localSocketFor(getSystemService(android.net.ConnectivityManager::class.java)!!, address)
                lifetime.own(socket::close)
                socket.connect(java.net.InetSocketAddress(address, port), 8000)
                net.prototux.aare.aap.SocketTransport(socket, "Android Auto LAN · ${address.hostAddress}:$port") to "lan|${address.hostAddress}"
            }
            ProjectionRoute.WIRELESS_AA -> {
                val address = settings.getString("bluetoothPeer", null) ?: error(service.getString(R.string.desktop_select_a_paired_head_unit))
                val bluetooth = getSystemService(BluetoothManager::class.java)?.adapter ?: error(service.getString(R.string.desktop_bluetooth_unavailable))
                val attempt = WirelessConnector(this, protocolLog).Attempt(bluetooth.getRemoteDevice(address))
                lifetime.own(attempt::abort)
                attempt.run() to "bt|$address"
            }
            else -> error(service.getString(R.string.desktop_this_connection_does_not_use_android_auto, mode.label(service)))
        }
    }

    private fun carPlay(lifetime: ProjectionResources, publish: (String) -> Unit, ended: (String) -> Unit): ProjectionSession =
        CarPlayProjection(this, lifetime, publish, ended)

    private fun notification(message: String): Notification {
        val open = PendingIntent.getActivity(this, 1, Intent(this, DexSetupActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 2, Intent(this, AutoService::class.java).setAction(BridgeService.ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL).setSmallIcon(com.hsd.universal.R.mipmap.ic_launcher).setContentTitle("HSD")
            .setContentText(message).setContentIntent(open).setOnlyAlertOnce(true).setOngoing(true)
            .addAction(Notification.Action.Builder(null, service.getString(R.string.desktop_stop), stop).build()).build()
    }

    private fun update(message: String) {
        if (stopping) return
        CarLifeState.setStatus(message)
        getSystemService(NotificationManager::class.java)!!.notify(ID, notification(message))
    }
    private fun finish(message: String) {
        if (stopping) return
        stopping = true
        main.removeCallbacks(reconnect)
        retryPending = false
        reconnectUntil = 0
        if (projection == null) complete(message) else projection?.stop(message)
    }
    private fun complete(message: String, connectError: Boolean = false) {
        generation++
        val carPlay = projection as? CarPlayProjection
        val lost = (projection as? AutoProjection)?.networkInterrupted == true
        val now = android.os.SystemClock.elapsedRealtime()
        if (lost) {
            reconnectUntil = now + 90_000L
            retryDelayMs = 1000L
        }
        val listenAgain = !stopping && route == ProjectionRoute.LAN_SERVER && projection != null
        val currentResources = resources
        resources = null
        currentResources?.close()
        val attached = device
        val usbFailed = route == ProjectionRoute.USB && !stopping && attached != null &&
            (!CarLifeState.running || (projection as? AutoProjection)?.rejectedVideo == true)
        if (usbFailed && (connectError || (projection as? AutoProjection)?.retryUsbOpen == true) &&
            usbOpenRetries < 2 && runCatching { usb.autoAccessory() == attached }.getOrDefault(false)) {
            projection = null
            retryPending = true
            usbOpenRetries++
            update(service.getString(R.string.desktop_usb_is_not_ready_retrying_android_auto_negotiation))
            main.postDelayed(reconnect, usbOpenRetries * 750L)
            return
        }
        if (route == ProjectionRoute.USB) {
            if (usbFailed) Actions.onUsbHandshakeFailed(checkNotNull(attached), message)
            else if (CarLifeState.running || stopping) Actions.onUsbSessionClosed(service)
            else Actions.onUsbStartFailed(message)
        }
        CarLifeState.running = false
        CarLifeState.setStatus(message)
        com.hsd.universal.AppLog.i("${route.protocol}: $message")
        getSharedPreferences("projection", MODE_PRIVATE).edit()
            .putString("last", message).putString("last." + route.name, message).apply()
        projection = null
        if (!stopping && route == ProjectionRoute.CARPLAY && carPlay != null) {
            val delay = carPlayRecovery.retry(now, carPlay.firstFrameAt?.let { now - it }, carPlay.linkInterrupted)
            if (delay != null) {
                retryPending = true
                AppLog.i("CarPlay: связь с выбранной магнитолой потеряна · повтор через ${delay} мс", false)
                update(service.getString(R.string.desktop_head_unit_disconnected_reconnecting_carplay))
                main.postDelayed(reconnect, delay)
                return
            }
        }
        if (listenAgain) { begin(); return }
        if (!stopping && (route == ProjectionRoute.LAN_CLIENT || route == ProjectionRoute.WIRELESS_AA) &&
            (lost || connectError) && now + retryDelayMs < reconnectUntil) {
            retryPending = true
            update(service.getString(R.string.desktop_head_unit_disconnected_reconnecting, route.protocol))
            main.postDelayed(reconnect, retryDelayMs)
            retryDelayMs = minOf(8000L, retryDelayMs * 2)
            return
        }
        stopping = true
        service.stopForeground(Service.STOP_FOREGROUND_REMOVE)
        service.stopSelf()
    }
    override fun stopForRouteChange() {
        service.startForeground(ID, notification(service.getString(R.string.desktop_switching_connection_type)))
        finish(service.getString(R.string.desktop_switching_connection_type))
    }

    override fun close() {
        generation++
        stopping = true
        main.removeCallbacks(reconnect)
        retryPending = false
        CarLifeState.running = false
        unregisterReceiver(detached)
        projection?.stop(getString(R.string.desktop_connection_stopped))
        resources?.close()
        worker.shutdownNow()
    }

    companion object {
        private val protocolLog = object : AapLog {
            override fun i(msg: String) { android.util.Log.i("HsdWireless", msg) }
            override fun w(msg: String, t: Throwable?) { android.util.Log.w("HsdWireless", msg, t) }
            override fun e(msg: String, t: Throwable?) { android.util.Log.e("HsdWireless", msg, t) }
        }
        private const val CHANNEL = "wired"
        private const val ID = 210
    }
}
