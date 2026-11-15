package com.hsd.universal

import android.app.Notification
import android.bluetooth.BluetoothManager
import android.net.InetAddresses
import android.net.wifi.WifiManager
import net.prototux.aare.aap.AapLog
import net.prototux.aare.aap.Transport
import net.prototux.aare.aap.PhoneIdentity
import net.prototux.aare.aap.wireless.WirelessBootstrap
import net.prototux.aare.aap.wireless.WirelessBootstrapException
import net.prototux.aare.transport.wireless.WirelessConnector
import net.prototux.aare.transport.wireless.ProjectionNetworkDenied
import com.hsd.universal.carplay.CarPlayRecovery
import com.hsd.universal.dex.DexSetupActivity
import com.hsd.universal.ui.Actions
import com.hsd.universal.ui.UsbDetachPolicy
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
    private val wifiChanged = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (!stopping && CarLifeState.waitingForProjectionWifi && ProjectionWifi.read(service) == ProjectionWifi.READY) {
                begin()
            }
        }
    }
    private val reconnect = Runnable {
        retryPending = false
        if (!stopping && resources == null) begin()
    }
    private val detached = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (isInitialStickyBroadcast || device == null ||
                intent.action == UsbDetachPolicy.USB_STATE && intent.getBooleanExtra("connected", true)) return
            val present = runCatching { usb.autoAccessory() != null }.getOrNull()
            if (UsbDetachPolicy.confirmedDetached(true, present, UsbDetachPolicy.cableConnected(context))) {
                com.hsd.universal.dex.DesktopMusicPlayback.physicalDetach(this@AutoEndpoint)
                Actions.onUsbDetached(service)
                finish(service.getString(R.string.wl_usb_to_air_title))
            }
        }
    }

    init {
        getSystemService(NotificationManager::class.java)!!.createNotificationChannel(
            NotificationChannel(CHANNEL, service.getString(R.string.desktop_head_unit_projection), NotificationManager.IMPORTANCE_LOW))
        registerReceiver(detached, IntentFilter(UsbManager.ACTION_USB_ACCESSORY_DETACHED).apply {
            addAction(UsbDetachPolicy.USB_STATE)
        }, RECEIVER_NOT_EXPORTED)
        if (route.usesHeadUnitHotspot) {
            val filter = IntentFilter(WifiManager.WIFI_STATE_CHANGED_ACTION)
            if (android.os.Build.VERSION.SDK_INT >= 33) registerReceiver(wifiChanged, filter, RECEIVER_NOT_EXPORTED)
            else @Suppress("DEPRECATION") registerReceiver(wifiChanged, filter)
        }
    }

    override fun start(intent: Intent?, startId: Int): Int {
        if (intent?.action == BridgeService.ACTION_DESKTOP_HOME || intent?.action == BridgeService.ACTION_PHONE_SCREEN_OFF ||
            intent?.action == BridgeService.ACTION_PHONE_SCREEN_ON) {
            if (!stopping) {
                if (intent.action == BridgeService.ACTION_DESKTOP_HOME) projection?.home()
                else projection?.panel(intent.action == BridgeService.ACTION_PHONE_SCREEN_OFF)
            }
            if (resources == null && !retryPending && !CarLifeState.waitingForProjectionWifi) service.stopSelfResult(startId)
            return Service.START_NOT_STICKY
        }
        if (intent?.action == BridgeService.ACTION_STOP) {
            service.startForeground(ID, notification(service.getString(R.string.desktop_stopping_projection)))
            val userStop = intent.getBooleanExtra(BridgeService.EXTRA_USER_STOP, true)
            if (userStop) Actions.stopUsbAutoStartForSession()
            finish(service.getString(if (userStop) R.string.desktop_stopped_on_phone else R.string.desktop_stopping_projection))
            return Service.START_NOT_STICKY
        }
        if (intent?.action == BridgeService.ACTION_WIFI_B_AUTO || intent?.action == BridgeService.ACTION_WIFI_B_AUTO_OFF ||
            intent?.action == BridgeService.ACTION_WIFI_B) {
            if (resources == null && !retryPending && !CarLifeState.waitingForProjectionWifi) {
                service.startForeground(ID, notification(service.getString(R.string.desktop_ending_carlife_standby)))
                service.stopSelfResult(startId)
            }
            return Service.START_NOT_STICKY
        }
        if (resources != null || stopping || retryPending || CarLifeState.waitingForProjectionWifi) return Service.START_NOT_STICKY
        begin()
        return Service.START_NOT_STICKY
    }

    private fun begin() {
        val ticket = ++generation
        val mode = route
        CarLifeState.waitingForProjectionWifi = false
        CarLifeState.resetSession()
        CarLifeState.wirelessSession = mode != ProjectionRoute.USB
        try {
            service.startForeground(ID, notification(mode.label(service)))
            if (mode.usesHeadUnitHotspot) {
                val radio = ProjectionWifi.read(service)
                if (radio.needsEnable) {
                    waitForWifi(radio)
                    return
                }
            }
            if (mode == ProjectionRoute.USB) {
                val current = usb.autoAccessory()?.takeIf(usb::hasPermission)
                    ?: error(service.getString(R.string.desktop_connect_the_cable_and_allow_hsd_to_open_the))
                check(usbOpenRetries == 0 || current == device) {
                    service.getString(R.string.desktop_connect_the_cable_and_allow_hsd_to_open_the)
                }
                device = current
            }
        } catch (error: RuntimeException) {
            complete(error.message ?: service.getString(R.string.desktop_could_not_start_connection))
            return
        }
        update(if (mode == ProjectionRoute.LAN_SERVER) service.getString(R.string.desktop_waiting_for_the_head_unit_on_tcp_port_5277) else service.getString(R.string.desktop_connecting, mode.label(service)))
        val lifetime = ProjectionResources()
        resources = lifetime
        val attached = device
        worker.execute {
            try {
                val publish: (String) -> Unit = { message -> main.post { if (ticket == generation) update(message) } }
                val ended: (String) -> Unit = { message -> main.post { if (ticket == generation) complete(message) } }
                val ready: () -> Unit = {
                    main.post {
                        if (ticket == generation && !stopping && !CarLifeState.running) {
                            CarLifeState.running = true
                            if (mode == ProjectionRoute.USB) Actions.onUsbSessionStarted(service)
                        }
                    }
                }
                val session = if (mode == ProjectionRoute.CARPLAY) {
                    CarPlayProjection(this, lifetime, publish, ended, ready)
                } else {
                    val identity = ProjectionIdentity(this)
                    val (transport, peer, phone) = openAuto(mode, attached, lifetime, identity)
                    lifetime.own(transport::close)
                    AutoProjection(this, transport, phone, identity, peer, publish, ended, ready, ::canReopenUsb)
                }
                main.post {
                    if (ticket == generation && !stopping) {
                        projection = session
                        runCatching { session.start() }.onFailure { finish(it.message ?: service.getString(R.string.desktop_failed_to_start_projection)) }
                    } else lifetime.close()
                }
            } catch (error: Exception) {
                val interruptedExchange = error is WirelessBootstrapException && error.interruptedExchange
                val transient = interruptedExchange || error is java.io.IOException &&
                    error !is javax.net.ssl.SSLException && error !is ProjectionNetworkDenied
                main.post {
                    if (ticket != generation) return@post
                    if (mode == ProjectionRoute.WIRELESS_AA && interruptedExchange && reconnectUntil == 0L)
                        reconnectUntil = android.os.SystemClock.elapsedRealtime() + 30_000L
                    complete(service.getString(R.string.desktop_could_not_connect, error.message), transient)
                }
            }
        }
    }

    private data class AutoConnection(val transport: Transport, val peer: String, val phone: PhoneIdentity)

    private fun openAuto(mode: ProjectionRoute, attached: UsbAccessory?, lifetime: ProjectionResources, identity: ProjectionIdentity): AutoConnection {
        val settings = ProjectionRoute.prefs(this)
        return when (mode) {
            ProjectionRoute.USB -> {
                val accessory = checkNotNull(attached)
                val peer = identity.usbPeer(accessory)
                val phone = identity.phone(peer)
                AutoConnection(UsbAccessoryTransport(usb, accessory), peer, phone)
            }
            ProjectionRoute.LAN_SERVER -> {
                val ip = InetAddresses.parseNumericAddress(settings.getString("lanPeer", null)
                    ?: error(service.getString(R.string.desktop_enter_the_head_unit_s_ip_address_in_connection)))
                val peer = "lan|${ip.hostAddress}"
                val phone = identity.phone(peer)
                val listener = HeadUnitListener(ip)
                lifetime.own(listener::close)
                AutoConnection(listener.use { it.accept() }, peer, phone)
            }
            ProjectionRoute.LAN_CLIENT -> {
                val address = InetAddresses.parseNumericAddress(settings.getString("lanPeer", null)
                    ?: error(service.getString(R.string.desktop_enter_the_head_unit_s_ip_address_in_connection)))
                val port = settings.getInt("lanPort", 5288)
                val peer = "lan|${address.hostAddress}"
                val phone = identity.phone(peer)
                val socket = localSocketFor(getSystemService(android.net.ConnectivityManager::class.java)!!, address)
                lifetime.own(socket::close)
                socket.connect(java.net.InetSocketAddress(address, port), 8000)
                AutoConnection(net.prototux.aare.aap.SocketTransport(socket, "Android Auto LAN · ${address.hostAddress}:$port"), peer, phone)
            }
            ProjectionRoute.WIRELESS_AA -> {
                val address = settings.getString("bluetoothPeer", null) ?: error(service.getString(R.string.desktop_select_a_paired_head_unit))
                val bluetooth = getSystemService(BluetoothManager::class.java)?.adapter ?: error(service.getString(R.string.desktop_bluetooth_unavailable))
                val peer = "bt|$address"
                val phone = identity.phone(peer)
                val description = phone.projectionDescription()
                val options = WirelessConnector.Options(bootstrap = WirelessBootstrap.Options(
                    instanceId = description.instanceId, deviceName = description.deviceName))
                val attempt = WirelessConnector(this, protocolLog, options).Attempt(bluetooth.getRemoteDevice(address))
                lifetime.own(attempt::abort)
                AutoConnection(attempt.run(), peer, phone)
            }
            else -> error(service.getString(R.string.desktop_this_connection_does_not_use_android_auto, mode.label(service)))
        }
    }

    private fun notification(message: String): Notification {
        val open = PendingIntent.getActivity(this, 1, Intent(this, DexSetupActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 2, Intent(this, AutoService::class.java).setAction(BridgeService.ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL).setSmallIcon(com.hsd.universal.R.mipmap.ic_launcher).setContentTitle("HSD")
            .setContentText(message).setContentIntent(open).setOnlyAlertOnce(true).setOngoing(true)
            .apply {
                if (CarLifeState.waitingForProjectionWifi) {
                    val panel = PendingIntent.getActivity(service, 3, ProjectionWifi.settings(service), PendingIntent.FLAG_IMMUTABLE)
                    addAction(Notification.Action.Builder(null, getString(R.string.desktop_open_wi_fi_settings), panel).build())
                }
            }
            .addAction(Notification.Action.Builder(null, service.getString(R.string.desktop_stop), stop).build()).build()
    }

    private fun waitForWifi(radio: ProjectionWifi) {
        CarLifeState.waitingForProjectionWifi = true
        CarLifeState.running = false
        AppLog.i("${route.protocol}: waiting for phone Wi-Fi · radio=$radio", false)
        update(getString(radio.message))
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
    private fun canReopenUsb(): Boolean {
        val attached = device ?: return false
        return route == ProjectionRoute.USB && !stopping && usbOpenRetries < 2 &&
            runCatching { usb.hasPermission(attached) && usb.autoAccessory() == attached }.getOrDefault(false)
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
        val auto = projection as? AutoProjection
        val usbFailed = route == ProjectionRoute.USB && !stopping && attached != null &&
            (!CarLifeState.running || auto?.rejectedVideo == true || auto?.retryUsbOpen == true)
        if ((connectError || auto?.retryUsbOpen == true) && canReopenUsb()) {
            projection = null
            CarLifeState.running = false
            retryPending = true
            usbOpenRetries++
            AppLog.i("Android Auto USB · reopening attached accessory · attempt=$usbOpenRetries", false)
            update(service.getString(if (lost) R.string.aa_usb_recovering else R.string.desktop_usb_is_not_ready_retrying_android_auto_negotiation))
            main.postDelayed(reconnect, usbOpenRetries * 750L)
            return
        }
        if (route == ProjectionRoute.USB) {
            if (usbFailed) Actions.onUsbHandshakeFailed(checkNotNull(attached), message)
            else if (CarLifeState.running || stopping) Actions.onUsbSessionClosed(service)
            else Actions.onUsbStartFailed(message)
        }
        CarLifeState.running = false
        projection = null
        com.hsd.universal.AppLog.i("${route.protocol}: $message")
        if (!stopping && route.usesHeadUnitHotspot) {
            val radio = ProjectionWifi.read(service)
            if (radio.needsEnable) {
                waitForWifi(radio)
                return
            }
        }
        CarLifeState.waitingForProjectionWifi = false
        CarLifeState.setStatus(message)
        getSharedPreferences("projection", MODE_PRIVATE).edit()
            .putString("last", message).putString("last." + route.name, message).apply()
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
        CarLifeState.waitingForProjectionWifi = false
        if (route.usesHeadUnitHotspot) unregisterReceiver(wifiChanged)
        unregisterReceiver(detached)
        com.hsd.universal.dex.DesktopMusicPlayback.serviceDestroyed(this, false)
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
