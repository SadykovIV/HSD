package com.hsd.universal.ui

import com.hsd.universal.DesktopTransport
import com.hsd.universal.ProjectionRoute
import com.hsd.universal.link.car.WirelessCarLife
import com.hsd.universal.link.car.CarProfile

import android.Manifest
import android.app.PendingIntent
import android.content.ComponentName
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbManager
import android.hardware.usb.UsbAccessory
import android.os.SystemClock
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.hsd.universal.MainActivity
import com.hsd.universal.AppLocale
import com.hsd.universal.AppLog
import com.hsd.universal.BridgeService
import com.hsd.universal.CarLifeState
import com.hsd.universal.R
import com.hsd.universal.aa.AaCreds
import com.hsd.universal.aa.AaServerCheck
import com.hsd.universal.aa.ManualServerCheck
import com.hsd.universal.aa.ProbeResult
import com.hsd.universal.aa.TranscodeSelfCheck
import com.hsd.universal.dex.DexPrefs
import com.hsd.universal.dex.DesktopAccess
import com.hsd.universal.dex.DexAutoConnectPolicy
import com.hsd.universal.link.car.CarLifeConnectionMode
import com.hsd.universal.link.car.WirelessPrefs

/**
 * Действия, которые раньше жили прямо в MainActivity (кнопки UI и команды intent):
 * сканирование USB, старт/стоп сервиса моста, запуск сервера ГУ, диагностика.
 */
object Actions {
    private const val AA_PACKAGE = "com.google.android.projection.gearhead"
    private const val AA_SETTINGS_ACTIVITY = "com.google.android.projection.gearhead.companion.settings.DefaultSettingsActivity"

    enum class UsbScanState { DETACHED, WAITING_FOR_ACCESSORY, PERMISSION_REQUESTED, STARTING, ALREADY_RUNNING, FAILED }
    data class UsbScanResult(val state: UsbScanState, val detail: String? = null)
    @Volatile var lastUsbResult = UsbScanResult(UsbScanState.WAITING_FOR_ACCESSORY)
        private set
    private val usbGate = UsbScanGate { SystemClock.elapsedRealtime() }
    private val autoConnect = DexAutoConnectPolicy()
    private val detachedIdle = UsbDetachedIdle()
    private val wiredSelection = WiredAccessorySelection()
    private var observingUsb = false
    private var usbBus = UsbBusState.read(null)
    private const val USB_PERMISSION_TOKEN = "hsd.usb.permission.token"

    fun isCarLifeAccessory(accessory: UsbAccessory) = UsbAccessoryPolicy.accepts(accessory.manufacturer, accessory.model)
    private fun matchesRoute(route: ProjectionRoute, accessory: UsbAccessory) =
        route == UsbAccessoryPolicy.route(accessory.manufacturer, accessory.model)

    @Synchronized internal fun routeChanged(ctx: Context) {
        usbGate.reset()
        CarProfile.enableTrigger(ctx, ProjectionRoute.selected(ctx) == ProjectionRoute.CARLIFE_WIFI)
        if (BridgeService.alive && !autoConnect.stopped)
            ctx.startForegroundService(DesktopTransport.service(ctx).setAction(BridgeService.ACTION_RECONFIGURE))
    }

    internal fun startSelected(ctx: Context) {
        if (!DesktopAccess.isReadyForProjection(ctx) || isUsbAutoStartStopped()) return
        val route = ProjectionRoute.selected(ctx)
        try {
            when {
                route.wired -> scan(ctx, "смена способа подключения", retryPermission = true)
                route.carLife -> WirelessCarLife.startReady(ctx)
                else -> ctx.startForegroundService(DesktopTransport.service(ctx))
            }
        } catch (failure: RuntimeException) {
            CarLifeState.setStatus(ctx.getString(R.string.projection_launch_error, route.label(ctx), failure.javaClass.simpleName))
            AppLog.e(CarLifeState.status(ctx), failure)
        }
    }
    private fun accessoryKey(accessory: UsbAccessory): String = UsbAccessoryPolicy.key(
        accessory.manufacturer, accessory.model, accessory.description, accessory.version, accessory.uri)

    @Synchronized fun isUsbAutoStartStopped(): Boolean = autoConnect.stopped

    @Synchronized fun isUsbDetachedIdle(): Boolean = detachedIdle.idle

    @Synchronized internal fun connectDecision(explicit: Boolean, serviceAlive: Boolean, sessionReady: Boolean): DexAutoConnectPolicy.Decision {
        if (explicit) detachedIdle.explicitConnect()
        return autoConnect.decide(explicit, serviceAlive, sessionReady)
    }

    @Synchronized fun stopUsbAutoStartForSession() { autoConnect.stop() }

    @Synchronized fun resumeUsbAutoStart() { detachedIdle.explicitConnect(); autoConnect.resume() }

    /** Observe the system signal. Opening an accessory still goes through the permission gate. */
    private fun registerUsbObserver(ctx: Context) {
        if (!observingUsb) {
            val app = ctx.applicationContext
            val initial = app.registerReceiver(object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    if (intent.action == UsbDetachPolicy.USB_STATE) synchronized(this@Actions) {
                        usbBus = UsbBusState.read(intent)
                    }
                    // Use current system state: a queued old detach must not rearm
                    // a stopped connection that is still physically present.
                    observeUsbConnection(context)
                }
            }, IntentFilter().apply {
                addAction(UsbManager.ACTION_USB_ACCESSORY_ATTACHED)
                addAction(UsbManager.ACTION_USB_ACCESSORY_DETACHED)
                addAction(UsbDetachPolicy.USB_STATE)
            }, Context.RECEIVER_NOT_EXPORTED)
            usbBus = UsbBusState.read(initial)
            observingUsb = true
        }
    }

    @Synchronized fun observeUsbConnection(ctx: Context) {
        registerUsbObserver(ctx)
        // A failed query is not evidence that the physical accessory was detached.
        runCatching { ctx.getSystemService(UsbManager::class.java).accessoryList.orEmpty() }
            .onSuccess { observeAccessories(ctx, it) }
    }

    private fun observeAccessories(ctx: Context, list: Array<out UsbAccessory>) {
        val selected = ProjectionRoute.selected(ctx)
        val available = list.mapNotNull { UsbAccessoryPolicy.route(it.manufacturer, it.model) }.toSet()
        val route = if (DesktopTransport.universal) wiredSelection.observe(selected, available) else selected
        if (route != selected) ProjectionRoute.save(ctx, ProjectionRoute.prefs(ctx).edit().putString("mode", route.name))
        val key = list.firstOrNull { matchesRoute(route, it) }?.let(::accessoryKey)
        val reconnected = detachedIdle.idle && key != null
        if (key == null) usbGate.reset()
        autoConnect.observeAccessory(key)
        detachedIdle.observeCompatibleAccessory(key != null)
        if (reconnected) publish(UsbScanState.WAITING_FOR_ACCESSORY,
            ctx.getString(R.string.usb_receiver_found, route.protocol), "новый аксессуар после detach")
    }

    /** Repeated refresh/attach events reuse a pending request. Explicit Connect may retry a denial. */
    @Synchronized fun scan(ctx: Context, reason: String, retryPermission: Boolean = false): UsbScanResult {
        try {
            registerUsbObserver(ctx)
            val usb = ctx.getSystemService(UsbManager::class.java)
            val list = usb.accessoryList.orEmpty()
            observeAccessories(ctx, list)
            if (ProjectionRoute.wireless(ctx) || WirelessPrefs(ctx).mode == CarLifeConnectionMode.WIFI) {
                usbGate.reset()
                return publish(UsbScanState.WAITING_FOR_ACCESSORY, ctx.getString(R.string.usb_wifi_selected, DesktopTransport.name), reason)
            }
            if (detachedIdle.idle) {
                return publish(UsbScanState.DETACHED, ctx.getString(R.string.usb_wait_reconnect), reason)
            }
            if (autoConnect.stopped) {
                return publish(UsbScanState.WAITING_FOR_ACCESSORY, ctx.getString(R.string.desktop_connection_stopped), reason)
            }
            if (com.hsd.universal.dex.PhonePairingService.blocksProjectionForCommittedActivation(ctx)) {
                usbGate.reset()
                return publish(UsbScanState.WAITING_FOR_ACCESSORY, ctx.getString(R.string.projection_activation_check), reason)
            }
            if (DexPrefs(ctx).useDesktopService && !DesktopAccess.isReady(ctx)) {
                usbGate.reset() // A late USB grant must not bypass the selected backend's permission.
                return publish(UsbScanState.FAILED, if (DexPrefs(ctx).useDelegated) ctx.getString(R.string.usb_open_activate, DesktopTransport.shortName) else ctx.getString(R.string.usb_shizuku_access, DesktopTransport.appName), reason)
            }
            val acc = list.firstOrNull { matchesRoute(ProjectionRoute.selected(ctx), it) }
            val active = BridgeService.alive && CarLifeState.running && !CarLifeState.wirelessSession
            if (BridgeService.alive && DesktopTransport.activeRoute != ProjectionRoute.selected(ctx))
                return publish(UsbScanState.STARTING, ctx.getString(R.string.desktop_switching_connection_type), reason)
            if (acc != null && DesktopTransport.activeRoute == ProjectionRoute.USB && !CarLifeState.wirelessSession && !active && usbGate.failureDetail == null)
                return publish(UsbScanState.STARTING, ctx.getString(R.string.usb_negotiating), reason)
            val step = usbGate.decide(acc?.let(::accessoryKey), acc?.let(usb::hasPermission) == true, active, retryPermission)
            if (acc != null) CarLifeState.accessory = MainActivity.describe(ctx, acc)
            return when (step) {
                UsbScanGate.Step.WAITING -> {
                    CarLifeState.accessory = ctx.getString(R.string.desktop_not_found)
                    val prompt = if (list.isEmpty()) usbBus.waitingMessage() else R.string.usb_unsupported_accessory
                    val detail = ctx.getString(prompt, ProjectionRoute.selected(ctx).protocol)
                    CarLifeState.setStatus(detail)
                    publish(UsbScanState.WAITING_FOR_ACCESSORY, detail,
                        "$reason · connected=${usbBus.connected} configured=${usbBus.configured} accessory=${usbBus.accessory} visible=${list.size}")
                }
                UsbScanGate.Step.ACTIVE -> publish(UsbScanState.ALREADY_RUNNING, ctx.getString(R.string.usb_connected, DesktopTransport.name), reason)
                UsbScanGate.Step.STARTING -> publish(UsbScanState.STARTING, ctx.getString(R.string.usb_starting, DesktopTransport.name), reason)
                UsbScanGate.Step.PERMISSION_PENDING -> publish(UsbScanState.PERMISSION_REQUESTED, ctx.getString(R.string.usb_permission_pending), reason)
                UsbScanGate.Step.DENIED -> publish(UsbScanState.FAILED, ctx.getString(R.string.usb_permission_denied), reason)
                UsbScanGate.Step.PERMISSION_TIMEOUT -> publish(UsbScanState.FAILED, ctx.getString(R.string.usb_permission_timeout), reason)
                UsbScanGate.Step.START_FAILED -> publish(UsbScanState.FAILED, usbGate.failureDetail ?: ctx.getString(R.string.usb_not_started, DesktopTransport.name), reason)
                UsbScanGate.Step.REQUEST_PERMISSION -> {
                    val token = usbGate.pendingToken!!
                    val pi = PendingIntent.getBroadcast(ctx, token.toInt(),
                        Intent(MainActivity.ACTION_PERMISSION).setPackage(ctx.packageName).putExtra(USB_PERMISSION_TOKEN, token),
                        PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                    usb.requestPermission(acc!!, pi)
                    publish(UsbScanState.PERMISSION_REQUESTED, ctx.getString(R.string.usb_permission_pending), reason)
                }
                UsbScanGate.Step.START -> {
                    ctx.startForegroundService(DesktopTransport.service(ctx))
                    publish(UsbScanState.STARTING, ctx.getString(R.string.usb_starting, DesktopTransport.name), reason)
                }
            }
        } catch (e: Exception) {
            usbGate.failed()
            AppLog.i("USB scan failed: ${e.javaClass.simpleName}")
            return publish(UsbScanState.FAILED, ctx.getString(R.string.usb_open_error, DesktopTransport.name, e.javaClass.simpleName), reason)
        }
    }

    /** Verify the actual USB grant, ignore stale callbacks, then continue without another tap. */
    @Synchronized fun handleUsbPermissionResult(ctx: Context, intent: Intent): UsbScanResult? {
        if (intent.action != MainActivity.ACTION_PERMISSION) return null
        try {
            val token = intent.getLongExtra(USB_PERMISSION_TOKEN, -1)
            if (usbGate.pendingToken != token) return null
            val acc = if (android.os.Build.VERSION.SDK_INT >= 33)
                intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY, UsbAccessory::class.java)
            else @Suppress("DEPRECATION") intent.getParcelableExtra<UsbAccessory>(UsbManager.EXTRA_ACCESSORY)
            if (acc == null) return null
            if (!matchesRoute(ProjectionRoute.selected(ctx), acc)) return null
            val usb = ctx.getSystemService(UsbManager::class.java)
            // UsbAccessory.equals() reads protected serials and throws after permission denial;
            // toString() contains a Binder proxy identity that changes between parcels.
            val key = accessoryKey(acc)
            val present = usb.accessoryList.orEmpty().any { accessoryKey(it) == key }
            val granted = present && intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false) && usb.hasPermission(acc)
            if (!usbGate.permissionResult(key, token, granted)) return null
            return if (!present || granted) scan(ctx, "результат разрешения USB")
                else publish(UsbScanState.FAILED, ctx.getString(R.string.usb_permission_denied), "разрешение USB отклонено")
        } catch (e: Exception) {
            usbGate.failed()
            AppLog.i("USB permission result failed: ${e.javaClass.simpleName}")
            return publish(UsbScanState.FAILED, ctx.getString(R.string.usb_grant_check_error), "проверка разрешения USB")
        }
    }

    /** Service lifecycle hooks keep the activity informed after scan() has returned. */
    @Synchronized fun onUsbSessionStarted(ctx: Context) {
        if (autoConnect.stopped) return
        detachedIdle.observeCompatibleAccessory(true)
        usbGate.started()
        publish(UsbScanState.ALREADY_RUNNING, ctx.getString(R.string.usb_connected, DesktopTransport.name), "сессия запущена")
    }

    @Synchronized fun onUsbHandshakeFailed(accessory: UsbAccessory, detail: String) {
        if (detachedIdle.idle || autoConnect.stopped || !usbGate.handshakeFailed(accessoryKey(accessory), detail)) return
        publish(UsbScanState.FAILED, detail, "сеанс USB не согласован")
    }

    @Synchronized fun onUsbStartFailed(detail: String) {
        if (!usbGate.isStarting) return
        usbGate.failed()
        publish(UsbScanState.FAILED, detail, "сервис не запущен")
    }

    @Synchronized fun onUsbSessionClosed(ctx: Context) {
        usbGate.reset()
        if (detachedIdle.idle) publish(UsbScanState.DETACHED, ctx.getString(R.string.usb_wait_reconnect), "сессия закрыта после USB detach")
        else if (autoConnect.stopped) publish(UsbScanState.WAITING_FOR_ACCESSORY, ctx.getString(R.string.desktop_connection_stopped), "сессия остановлена")
        else publish(UsbScanState.WAITING_FOR_ACCESSORY, ctx.getString(R.string.usb_wait_accessory, DesktopTransport.name), "сессия закрыта")
    }

    /** Only BridgeService calls this after confirming physical accessory absence. No privilege teardown. */
    @Synchronized fun onUsbDetached(ctx: Context) {
        detachedIdle.detached()
        autoConnect.observeAccessory(null)
        usbGate.reset() // Late USB grants cannot restart an absent accessory.
        publish(UsbScanState.DETACHED, ctx.getString(R.string.usb_wait_reconnect), "физическое отключение USB")
    }

    private fun publish(state: UsbScanState, detail: String, reason: String): UsbScanResult {
        val result = UsbScanResult(state, detail)
        if (result != lastUsbResult) AppLog.i("USB state=$state ($reason): $detail")
        lastUsbResult = result
        return result
    }

    fun startBridge(ctx: Context) { scan(ctx, "запуск моста") }

    @Synchronized fun stopBridge(ctx: Context, userInitiated: Boolean = true) {
        if (userInitiated) {
            com.hsd.universal.dex.DesktopMusicPlayback.cancel("explicit Stop")
            observeUsbConnection(ctx)
            autoConnect.stop()
            detachedIdle.explicitConnect() // Keep explicit Stop distinct from passive detached idle.
        }
        AppLog.i(if (userInitiated) "кнопка Стоп" else "остановка для восстановления подключения")
        val serviceRequested = BridgeService.alive || usbGate.isStarting
        usbGate.reset() // Any outstanding system USB permission callback is now stale.
        publish(UsbScanState.WAITING_FOR_ACCESSORY, ctx.getString(R.string.desktop_connection_stopped), "стоп")
        if (serviceRequested) ctx.startForegroundService(DesktopTransport.service(ctx).setAction(BridgeService.ACTION_STOP))
    }

    /** Самопроверка сертификатов AA и TLS без сети: результат в лог и AaState.selftest. */
    fun aaSelftest(ctx: Context) {
        val app = ctx.applicationContext
        Thread({ AaCreds.selfTest(app) }, "aa-selftest").start()
    }

    fun transcodeTest(ctx: Context, opts: String) = TranscodeSelfCheck.run(ctx, opts)

    /** Настройки Android Auto (сервер ГУ): HyperOS прячет их из системных настроек. */
    fun openAaSettings(ctx: Context) {
        try {
            ctx.startActivity(
                Intent().setComponent(ComponentName(AA_PACKAGE, AA_SETTINGS_ACTIVITY))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            AppLog.i("открыты настройки Android Auto")
        } catch (e: Exception) {
            AppLog.e("не открыть настройки Android Auto", e)
            Toast.makeText(ctx, AppLocale.wrap(ctx).getString(R.string.act_open_aa_failed, "${e.javaClass.simpleName}: ${e.message}"), Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Кнопка «Открыть Android Auto» на главном экране и в онбординге: сервер ГУ включает пользователь сам
     * (⋮ → «Вкл. сервер головного устр-ва»), поэтому только снимаем «Машина отключена» и открываем настройки AA.
     */
    fun startServerFromUi(ctx: Context) {
        HSDStatus.markServerRequested()
        openAaSettings(ctx)
    }

    /**
     * Ручная проба сервера ГУ («Для разработчиков»): одна попытка полным рукопожатием, итог тостом и строками SRVCHECK в лог.
     * Только без моста и USB: проба сбивает Android Auto. На состояние главного экрана не влияет.
     */
    fun checkServer(ctx: Context) {
        val app = ctx.applicationContext
        val main = Handler(Looper.getMainLooper())
        fun toast(text: String) = main.post { Toast.makeText(app, text, Toast.LENGTH_LONG).show() }
        val str = AppLocale.wrap(app)
        val refusal = AaServerCheck.start(app) { result, detail ->
            toast(
                when (result) {
                    ProbeResult.ALIVE -> str.getString(R.string.dev_check_server_alive)
                    ProbeResult.REFUSED -> str.getString(R.string.dev_check_server_refused)
                    ProbeResult.HUNG -> str.getString(R.string.dev_check_server_hung)
                    ProbeResult.ERROR -> str.getString(R.string.dev_check_server_error, detail ?: "?")
                },
            )
        }
        if (refusal != null) {
            toast(
                str.getString(
                    when (refusal) {
                        ManualServerCheck.Refusal.BRIDGE -> R.string.dev_check_server_bridge
                        ManualServerCheck.Refusal.USB -> R.string.dev_check_server_usb
                        ManualServerCheck.Refusal.RUNNING -> R.string.dev_check_server_running
                    },
                ),
            )
        }
    }

    /** Legacy AA diagnostics entry; this fork never records microphone audio. */
    fun micTest(ctx: Context) {
        AppLog.i("${DesktopTransport.shortName} DeX: запись микрофона не поддерживается")
    }
}
