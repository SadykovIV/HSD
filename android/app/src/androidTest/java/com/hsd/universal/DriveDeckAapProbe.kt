package com.hsd.universal

import android.app.Activity
import android.app.ActivityManager
import android.app.ActivityOptions
import android.app.Instrumentation
import android.app.UiAutomation
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import com.hsd.universal.AppLog
import com.hsd.universal.desktop.DriveDeckFullscreenActivity
import com.hsd.universal.dex.*
import com.hsd.universal.qa.VdmProbeHost
import net.prototux.aare.aap.MemoryPinStore
import net.prototux.aare.aap.SocketTransport
import net.prototux.aare.aap.Transport
import net.prototux.aare.transport.UsbAccessoryTransport
import android.os.ParcelFileDescriptor
import net.prototux.aare.aap.fingerprint
import net.prototux.aare.aap.sim.HeadUnitSimulator
import aap_protobuf.service.media.shared.message.MediaCodecTypeOuterClass.MediaCodecType
import java.io.File
import android.view.View
import android.view.ViewGroup
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Runs production DriveDeck + its real encoder across AAP. Only USB bulk endpoints are substituted. */
class DriveDeckAapProbe : Instrumentation() {
    private var checkCertificates = false
    private var wirelessReceiver = false
    private var wirelessSsid = "HSD-QA"
    private var wirelessPassword = "hsd-avd-wireless"
    private var usbMusic = false
    private var carPlayRadio: Bundle? = null
    private var carLifeUsbPort: Int? = null
    private var carLifeDesktop = false
    private var carLifeHudPort: Int? = null
    private var carLifeGesturePort: Int? = null
    private var carPlay = false
    private var bonjour = false
    private var shizuku = false
    private var modern = false
    private var screen: ProjectionScreenCase? = null
    private var displayOnly = false
    private var layoutProbe: DriveDeckLayoutProbe? = null
    private var checkLayoutControls = false
    private var authRefusal: Int? = null
    private var silentAuth = false
    private var encryptAuthorization = false
    private var testLink: String? = null
    private var disconnectDuringTls = false
    private var v13 = false
    private var v15 = false
    private var v4 = false
    private var checkUsbWait = false
    private var networkPolicy: String? = null
    private var oldUsbFrames = 0
    private var lateUsbVersion = false
    private var usbActivation = false
    private var wifiOffRoute: ProjectionRoute? = null
    private var initialFocus = HeadUnitSimulator.InitialFocus.IMMEDIATE
    private var hevc = false
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        checkCertificates = arguments?.getString("protocol") == "certificates"
        wirelessReceiver = arguments?.getString("protocol") == "wireless-receiver"
        wirelessSsid = arguments?.getString("ssid") ?: wirelessSsid
        wirelessPassword = arguments?.getString("pass") ?: wirelessPassword
        usbMusic = arguments?.getString("protocol") == "usb-music-pause"
        carPlayRadio = arguments?.takeIf { it.getString("protocol") in setOf("carplay-radio-receiver", "carplay-radio-sender") }
        carPlay = arguments?.getString("protocol") == "carplay"
        bonjour = arguments?.getString("protocol") == "carplay-discovery"
        carLifeDesktop = arguments?.getString("protocol") == "carlife-desktop"
        if (carLifeDesktop || arguments?.getString("protocol") == "carlife-usb")
            carLifeUsbPort = arguments?.getString("fixturePort")?.toInt() ?: 7240
        if (arguments?.getString("protocol") == "carlife-hud")
            carLifeHudPort = requireNotNull(arguments.getString("fixturePort")).toInt()
        if (arguments?.getString("protocol") == "carlife-gesture-audio")
            carLifeGesturePort = requireNotNull(arguments.getString("fixturePort")).toInt()
        shizuku = arguments?.getString("backend") == "shizuku"
        modern = arguments?.getString("protocol") == "6.1"
        v4 = arguments?.getString("protocol") == "4.0"
        v13 = arguments?.getString("protocol") == "1.3"
        v15 = arguments?.getString("protocol") == "1.5"
        checkUsbWait = arguments?.getString("protocol") == "usb-wait"
        networkPolicy = arguments?.getString("networkPolicy")
        hevc = arguments?.getString("codec") == "h265"
        val name = arguments?.getString("screen")
        layoutProbe = arguments?.getString("layoutDisplay")?.let {
            DriveDeckLayoutProbe(it, arguments.getString("expectedGap")?.toInt())
        }
        checkLayoutControls = arguments?.getString("layoutControls") == "true"
        displayOnly = name?.endsWith("-display") == true
        screen = name?.removeSuffix("-display")?.let(ProjectionScreenCase::named)
        val auth = arguments?.getString("authRefusal")
        silentAuth = auth == "silent"
        encryptAuthorization = arguments?.getString("encryptedAuth") == "true"
        testLink = arguments?.getString("link")?.also { require(it == "usb" || it == "tcp") }
        disconnectDuringTls = auth == "tls-close"
        if (!silentAuth && !disconnectDuringTls) authRefusal = auth?.toInt()?.also { require(it < 0) }
        oldUsbFrames = arguments?.getString("oldUsbFrames")?.toInt() ?: 0
        lateUsbVersion = arguments?.getString("protocol") == "late-usb-version"
        usbActivation = arguments?.getString("protocol") == "usb-activation"
        wifiOffRoute = arguments?.getString("wifiOffRoute")?.let(ProjectionRoute::valueOf)
        arguments?.getString("initialFocus")?.let { initialFocus = HeadUnitSimulator.InitialFocus.valueOf(it) }
        start()
    }
    override fun onStart() {
        val result = Bundle()
        val statuses = java.util.concurrent.CopyOnWriteArrayList<String>()
        try {
            check(Build.HARDWARE in listOf("ranchu", "goldfish")) { "Disposable emulator required" }
            val context = targetContext
            wifiOffRoute?.let { route ->
                check(route.usesHeadUnitHotspot)
                checkProjectionWifi(route)
                result.putString("wifi_preflight", "${route.name}: radio off, stop, restored radio, automatic resume")
                finish(Activity.RESULT_OK, result); return
            }
            if (usbActivation) {
                val activity = startActivitySync(Intent(context, UsbActivationActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                try {
                    val own = File(context.applicationInfo.sourceDir)
                    UsbPhoneActivation.verifyTarget(context, own)
                    check(runCatching { UsbPhoneActivation.verifyTarget(context, File(this.context.applicationInfo.sourceDir)) }.isFailure)
                    val manager = context.getSystemService(android.hardware.usb.UsbManager::class.java)
                    check(com.flyfishxu.kadb.usb.KadbUsb.devices(manager).isEmpty())
                    await("USB host waiting for device") {
                        uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText(context.getString(R.string.usb_helper_wait))?.isNotEmpty() == true
                    }
                    result.putString("usb_activation", "Host screen waiting for cable; verified HSD signer and rejected the test APK")
                } finally { runOnMainSync(activity::finish) }
                finish(Activity.RESULT_OK, result); return
            }
            networkPolicy?.let { state ->
                // A WifiNetworkSpecifier request requires a foreground owner on Android 10+.
                startActivitySync(Intent(context, ProjectionSettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                val access = net.prototux.aare.transport.wireless.ProjectionNetworkAccess(context)
                val manager = context.getSystemService(android.net.ConnectivityManager::class.java)
                @Suppress("DEPRECATION")
                val wifi = manager.allNetworks.first { network ->
                    val caps = manager.getNetworkCapabilities(network)
                    caps?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) == true &&
                        !caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)
                }
                val deny = state == "vpn"
                check(access.vpnActive() == deny) { "Unexpected VPN state" }
                if (deny) {
                    val error = runCatching { access.socket(wifi).use { } }.exceptionOrNull()
                    check(error is net.prototux.aare.transport.wireless.ProjectionNetworkDenied &&
                        error.message == context.getString(R.string.projection_network_vpn)) { "Expected Wi-Fi binding denial, got $error" }
                    result.putString("WiFi_policy", "VPN active: ${error.cause?.message}; HSD reported VPN policy")
                } else {
                    access.socket(wifi).use { }
                    result.putString("WiFi_policy", "Wi-Fi bind permitted")
                }
                val network = net.prototux.aare.transport.wireless.HeadUnitNetwork(context, net.prototux.aare.aap.AapLog.Stdout, 20000)
                try {
                    val credentials = net.prototux.aare.aap.wireless.WifiCredentials(wirelessSsid, wirelessPassword,
                        null, net.prototux.aare.aap.wireless.WifiSecurity.WPA2_PERSONAL, 2, null)
                    val joined = network.join(credentials)
                    if (deny) check(joined is net.prototux.aare.aap.wireless.JoinResult.Failed &&
                        joined.message == context.getString(R.string.projection_network_vpn)) { "Unexpected join result: $joined" }
                    else {
                        check(joined is net.prototux.aare.aap.wireless.JoinResult.Joined) {
                            (joined as? net.prototux.aare.aap.wireless.JoinResult.Failed)?.message.orEmpty()
                        }
                        access.socket(checkNotNull(network.network)).use { }
                    }
                    result.putString("WiFi_join", if (deny) "Refused before reporting success to head unit" else "Accessible head unit network selected")
                } finally {
                    network.release()
                }
                finish(Activity.RESULT_OK, result); return
            }
            if (bonjour) {
                checkCarPlayDiscovery(context)
                result.putString("CarPlay_Bonjour", "Selected the CarPlay receiver among three AirPlay services")
                finish(Activity.RESULT_OK, result); return
            }
            if (usbMusic) {
                UsbMusicPauseProbe(this).run()
                result.putString("usb_music", "camera kept playing; unplug paused once")
                finish(Activity.RESULT_OK, result); return
            }
            carLifeGesturePort?.let { port ->
                result.putString("gesture", checkCarLifeGestureAudio(port))
                result.putString("result", "PASS")
                finish(Activity.RESULT_OK, result)
                return
            }
            carLifeHudPort?.let { port ->
                checkCarLifeHud(context, port)
                result.putString("result", "PASS")
                finish(Activity.RESULT_OK, result)
                return
            }
            if (checkCertificates) {
                finish(Activity.RESULT_OK, checkReceiverIdentities(context)); return
            }
            if (wirelessReceiver) {
                result.putAll(WirelessHeadUnitProbe(context, wirelessSsid, wirelessPassword).run()); finish(Activity.RESULT_OK,result);return
            }
            carPlayRadio?.let { args ->
                val probe = CarPlayRadioProbe(context,args) {
                    sendStatus(1,Bundle().apply { putString("CarPlay_fixture","listening") })
                }
                // Keep the OS pairing dialog in front when the bootstrap starts during bonding.
                val activity = if (args.getString("protocol") == "carplay-radio-sender" && args.getString("pairingInProgress") != "true")
                    startActivitySync(Intent(context,ProjectionSettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                else null
                try { result.putAll(probe.run()) }
                finally { activity?.let { runOnMainSync(it::finish) } }
                finish(Activity.RESULT_OK,result)
                return
            }
            if (carPlay) {
                result.putAll(CarPlayNetworkProbe(context).run())
                result.putAll(CarPlayNetworkProbe(context, aac = true).run())
                finish(Activity.RESULT_OK,result);return
            }
            AppLog.init(context)
            val ui = getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
            if (shizuku) {
                check(com.hsd.universal.dex.DexPrivilegeAccess.isReady(context)) { "Grant the test installation access through the Shizuku manager" }
                // Only inspect the resulting tasks. The actual Shizuku user service owns display creation and input.
                ui.adoptShellPermissionIdentity(DelegatedDesktopAccess.REAL_GET_TASKS)
            } else ui.adoptShellPermissionIdentity(DelegatedDesktopAccess.ADD_TRUSTED_DISPLAY, DelegatedDesktopAccess.INJECT_EVENTS,
                DelegatedDesktopAccess.REAL_GET_TASKS, DelegatedDesktopAccess.MEDIA_CONTENT_CONTROL, ShellDisplayIme.PERMISSION,
                DelegatedDesktopAccess.ADD_ALWAYS_UNLOCKED_DISPLAY, DesktopAudioPolicy.PERMISSION, DesktopAudioPolicy.ROUTING_PERMISSION,
                "android.permission.DUMP")
            val lease = if (shizuku) null else DelegatedDesktopAccess.begin {}
            try {
                (if (shizuku) null else VdmProbeHost.start(this, ui)).use {
                    DexPrefs(context).apply {
                        desktopMode = if (carLifeDesktop) DesktopModePolicy.ANDROID_DESKTOP else DesktopModePolicy.DRIVEDECK
                        if (shizuku) useShizuku = true else useDelegated = true
                        usbPlaybackAudio = !shizuku
                    }
                    if (checkUsbWait) {
                        val actions = com.hsd.universal.ui.Actions
                        check(context.getSystemService(android.hardware.usb.UsbManager::class.java).accessoryList.isNullOrEmpty())
                        val bus = com.hsd.universal.ui.UsbBusState.read(context.registerReceiver(null,
                            android.content.IntentFilter(com.hsd.universal.ui.UsbDetachPolicy.USB_STATE)))
                        val route = ProjectionRoute.selected(context)
                        check(route.wired)
                        val waiting = actions.scan(context, "emulator USB enumeration")
                        check(waiting.state == com.hsd.universal.ui.Actions.UsbScanState.WAITING_FOR_ACCESSORY &&
                            waiting.detail == context.getString(bus.waitingMessage(), route.protocol)) { waiting.toString() }
                        actions.stopBridge(context)
                        actions.onUsbSessionClosed(context)
                        check(actions.lastUsbResult.detail == context.getString(R.string.desktop_connection_stopped))
                        checkServiceRestart()
                        result.putString("USB_wait", waiting.detail)
                        result.putString("service_restart", "Recovery restarted the endpoint. Notification Stop inhibited automatic restart.")
                        finish(Activity.RESULT_OK, result); return
                    }
                    check(installedDriveDeck(context)) { "Install the matching DriveDeck companion" }
                    val clock = listOf("com.google.android.deskclock", "com.android.deskclock").first {
                        context.packageManager.getLaunchIntentForPackage(it) != null
                    }
                    val fixtureApps = setOf("com.android.settings", clock)
                    fun splitReady(display: Int) = tasks(context).let { activities ->
                        val home = if (shizuku) DesktopModePolicy.DRIVEDECK_HOME_CLASS else DesktopModePolicy.driveDeckHome(context)
                        activities.any { it.first == display && it.second == home } &&
                            activities.filter { it.first > 0 && it.first != display }.map { it.third }.containsAll(fixtureApps)
                    }
                    val settings = checkNotNull(context.packageManager.getLaunchIntentForPackage("com.android.settings")?.component)
                    fun openFullscreen(display: Int, component: android.content.ComponentName): Activity {
                        val monitor = addMonitor(DriveDeckFullscreenActivity::class.java.name, null, false)
                        try {
                            context.startActivity(Intent(context, DriveDeckFullscreenActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("component", component.flattenToString())
                                .putExtra("back", true).putExtra("home", true),
                                ActivityOptions.makeBasic().setLaunchDisplayId(display).toBundle())
                            return checkNotNull(monitor.waitForActivityWithTimeout(8_000))
                        } finally { removeMonitor(monitor) }
                    }
                    fun awaitFullscreenSettings(display: Int) = await("fullscreen Settings ready") {
                        tasks(context).any { it.first > 0 && it.first != display && it.third == settings.packageName &&
                            context.getSystemService(android.hardware.display.DisplayManager::class.java)
                                .getDisplay(it.first)?.name?.contains("fullscreen", ignoreCase = true) == true }
                    }
                    carLifeUsbPort?.let { port ->
                        result.putAll(checkCarLifeBulk(this, port, ::fixtureAudio) { display ->
                            if (carLifeDesktop) tasks(context).any { it.first == display && it.second == DesktopModePolicy.ANDROID_HOME_CLASS }
                            else splitReady(display)
                        })
                        finish(Activity.RESULT_OK, result)
                        return
                    }
                    layoutProbe?.let { layout ->
                        val home = DesktopModePolicy.driveDeckHome(context)
                        result.putAll(checkDriveDeckDisplay(context, layout.width, layout.height, layout.dpi,
                            { display -> tasks(context).any { it.first == display && it.second == home } &&
                                layout.hasPanes(ui, home) }) { display, remote ->
                            result.putAll(layout.measure(ui, home))
                            if (checkLayoutControls) result.putString("DriveDeck_controls", layout.checkControls(this, ui, display, home, remote))
                        })
                        return@use
                    }
                    if (displayOnly) {
                        val spec = checkNotNull(screen)
                        result.putAll(checkDriveDeckDisplay(context, spec.desktopW, spec.desktopH,
                            spec.modes[spec.index].density, ::splitReady) { display, _ ->
                            val overlay = openFullscreen(display, settings)
                            awaitFullscreenSettings(display)
                            runOnMainSync { checkNotNull(button(overlay.window.decorView, R.string.desktop_drivedeck_home)).performClick() }
                            await("return from fullscreen to both tiles") { overlay.isDestroyed && splitReady(display) }
                        })
                        result.putString("DriveDeck_windows", "Split apps, fullscreen app and return to split")
                        return@use
                    }
                    val isUsb = testLink == "usb" || oldUsbFrames > 0 || lateUsbVersion
                    if (isUsb) {
                        checkUsbReopen()
                        result.putString("usb_reopen", "stale input, output and session close left the new descriptor usable both ways")
                    }
                    val (phone, car) = if (testLink == "tcp") {
                        java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress()).use { listener ->
                            val receiver = SocketTransport(java.net.Socket(listener.inetAddress, listener.localPort), "TCP test receiver")
                            SocketTransport(listener.accept(), "TCP test phone") to receiver
                        }
                    } else {
                        val pair = ParcelFileDescriptor.createSocketPair()
                        val sender = if (isUsb) UsbAccessoryTransport(pair[0], "USB test accessory") else localPipe(pair[0])
                        sender to localPipe(pair[1])
                    }
                    val identity = ProjectionIdentity(context)
                    val pins = MemoryPinStore()
                    val codec = if (hevc) MediaCodecType.MEDIA_CODEC_VIDEO_H265 else MediaCodecType.MEDIA_CODEC_VIDEO_H264_BP
                    val file = File(context.cacheDir, if (hevc) "qa-drivedeck-aa.h265" else "qa-drivedeck-aa.h264")
                    if (oldUsbFrames > 0) {
                        val failure = CountDownLatch(1)
                        val first = AutoProjection(context, object : Transport {
                            override val name = "USB open failure fixture"
                            override val usbAccessory = true
                            override val output = java.io.ByteArrayOutputStream()
                            override val input = object : java.io.InputStream() {
                                override fun read(): Int = throw java.io.IOException("read failed: EIO (I/O error)")
                            }
                            override fun close() {}
                        }, identity.phone("qa-initial-usb"), pins, "qa-initial-usb", {}, { failure.countDown() })
                        runOnMainSync { first.start() }
                        check(failure.await(5, TimeUnit.SECONDS) && first.retryUsbOpen)
                        result.putString("initial_EIO", "transient during VERSION")
                    }
                    val closed = CountDownLatch(1)
                    val test = screen
                    file.outputStream().buffered().use videoTest@{ video ->
                        val rejected = authRefusal
                        val authCase = rejected != null || silentAuth || disconnectDuringTls
                        val sender = identity.phone("qa-wired")
                        val receiverClock = when (rejected) {
                            -23 -> java.util.Date(sender.certificate.notBefore.time - 1000)
                            -24 -> java.util.Date(sender.certificate.notAfter.time + 1000)
                            else -> null
                        }
                        val version = when {
                            v13 -> 1 to 3
                            v15 -> 1 to 5
                            v4 || rejected != null || silentAuth -> 4 to 0
                            modern -> 6 to 1
                            else -> 1 to 6
                        }
                        val hu = HeadUnitSimulator(car, authorizationStatus = if (silentAuth) null else if (receiverClock != null) 0 else rejected ?: 0,
                            disconnectDuringTls = disconnectDuringTls, encryptAuthorization = encryptAuthorization,
                            receiverClock = receiverClock, initialFocus = initialFocus,
                            protocolVersion = version, width = test?.panelW ?: 1280, height = test?.panelH ?: 720,
                            previousUsbFrames = oldUsbFrames, offeredModes = test?.modes, offeredIndices = test?.indices ?: listOf(0), maxUnacked = if (modern) 1 else 4, modernNoAcks = modern, videoOut = video, videoCodec = codec)
                        val peer = if (authCase) "usb|qa-refusal-${java.util.UUID.randomUUID()}" else "qa-wired"
                        val projection = AutoProjection(context, phone, sender, if (authCase) identity else pins, peer,
                            { statuses.add(it) }, { statuses.add(it); closed.countDown() })
                        try {
                            runOnMainSync { projection.start() }
                            if (lateUsbVersion) {
                                await("phone waiting on the original USB connection") {
                                    statuses.contains(context.getString(R.string.aa_usb_wait_version))
                                }
                                // The old first-frame deadline must not expire before VERSION either.
                                check(!closed.await(42, TimeUnit.SECONDS))
                                result.putString("usb_wait", "same connection through both startup deadlines")
                            }
                            hu.start()
                            if (initialFocus == HeadUnitSimulator.InitialFocus.NATIVE) {
                                await("video channel setup while native") { hu.openedChannels.contains(hu.video) }
                                // Longer than both startup watchdogs. The receiver owns the native screen.
                                check(!closed.await(57, TimeUnit.SECONDS) && hu.videoFrames.get() == 0 && hu.codecConfigs.get() == 0)
                                hu.requestFocusProjected()
                            }
                            if (authCase) {
                                check(closed.await(18, TimeUnit.SECONDS))
                                check(hu.discoveryRequest.count == 1L && hu.videoFrames.get() == 0)
                                val detail = statuses.last()
                                if (disconnectDuringTls) check("HANDSHAKE" in detail && "STATUS_" !in detail) { detail }
                                else if (silentAuth) check("no AUTH_COMPLETE" in detail && "STATUS_" !in detail) { detail }
                                else check("$rejected (STATUS_" in detail) { detail }
                                check(peer in identity.receiverIds() && identity.seen(peer) == 0L && identity.changed(peer) == null)
                                if (disconnectDuringTls) check(identity.get(peer).isEmpty() && identity.receiverReference(peer) == null)
                                else check(identity.observedFingerprint(peer) == net.prototux.aare.aap.fingerprint(hu.huCert.certificate) &&
                                    identity.receiverReference(peer) == AaCertificateReference.Match.NO_MATCH)
                                result.putString("receiver_identity", "Available for per-head-unit selection, trust and actually presented certificate checked")
                                if (rejected == -3) {
                                    check(detail == context.getString(R.string.aa_auth_unspecified, "-3 (STATUS_AUTHENTICATION_FAILURE)")) { detail }
                                }
                                if (receiverClock != null) check(sender.certificate.notBefore.toInstant().toString() in detail &&
                                    sender.certificate.notAfter.toInstant().toString() in detail) { detail }
                                result.putString("AA_${version.first}_${version.second}_authorization", detail)
                                return@videoTest
                            }
                            val firstFrameDeadline = SystemClock.elapsedRealtime() + 60_000
                            while (hu.firstVideoFrame.count > 0 && closed.count > 0 &&
                                SystemClock.elapsedRealtime() < firstFrameDeadline) SystemClock.sleep(100)
                            if (oldUsbFrames >= 32) {
                                check(closed.count == 0L && hu.videoStart == null && hu.videoFrames.get() == 0)
                                check(statuses.any { it.contains("VERSION_REQUEST") })
                                result.putString("usb_refusal", "Previous-session records never reached an AAP channel")
                                return@videoTest
                            }
                            if (test?.index == -1) {
                                check(closed.count == 0L && hu.videoStart == null && hu.videoFrames.get() == 0)
                                check(statuses.any { it.contains("нет общего") })
                                result.putString("refusal", "No START or video; receiver and sender have no common allowed mode")
                                return@videoTest
                            }
                            check(hu.firstVideoFrame.count == 0L) {
                                "No DriveDeck video · ${hu.error} · ${statuses.joinToString()} · ${DexState.error}"
                            }
                            check(pins.get("qa-wired").contains(fingerprint(hu.huCert.certificate)))
                            check(hu.openedChannels.containsAll(listOf(hu.video, hu.input)))
                            await { DexState.running && DexState.displayId > 0 && hu.codecConfigs.get() > 0 && hu.videoFrames.get() >= 20 }
                            result.putInt("received_video_frames", hu.videoFrames.get())
                            val display = DexState.displayId
                            fun size(id: Int) = context.getSystemService(android.hardware.display.DisplayManager::class.java)
                                ?.getDisplay(id)?.mode?.let { it.physicalWidth to it.physicalHeight }
                            if (test != null) {
                                check(hu.videoStart?.configurationIndex == test.index)
                                check(size(display) == test.desktopW to test.desktopH) { "Display ${size(display)}, wanted ${test.desktopW}x${test.desktopH}" }
                                result.putString("native_desktop", "${test.desktopW}x${test.desktopH}")
                                result.putInt("start_index", test.index)
                            }
                            await("embedded applications") { splitReady(display) }
                            result.putString("DriveDeck_windows", fixtureApps.sorted().joinToString())
                            result.putInt("DriveDeck_display", display)
                            result.putString("AAP_channels", hu.openedChannels.sorted().joinToString())
                            run {
                                fun position(activity: Activity, label: Int): Pair<Int, Int> {
                                    val point = IntArray(2)
                                    await("visible fullscreen button ${context.getString(label)}") {
                                        var ready = false
                                        runOnMainSync {
                                            button(activity.window.decorView, label)?.takeIf { it.isShown && it.width > 0 }?.let {
                                                it.getLocationOnScreen(point)
                                                point[0] += it.width / 2
                                                point[1] += it.height / 2
                                                ready = true
                                            }
                                        }
                                        ready
                                    }
                                    // An actual HU displays encoded frames. Let the newly drawn fullscreen
                                    // window reach it before clicking buttons on the previous frame.
                                    val previous = hu.videoFrames.get()
                                    await("fullscreen frame delivery") { hu.videoFrames.get() >= previous + 5 }
                                    return test?.point(point[0], point[1]) ?: (point[0] to point[1])
                                }
                                val homeOverlay = openFullscreen(display, settings)
                                // Match an interactive session: wait for the child launch and the
                                // independent fullscreen surface before sending the first HU touch.
                                awaitFullscreenSettings(display)
                                waitForIdleSync()
                                val tap = position(homeOverlay, R.string.desktop_drivedeck_home)
                                hu.tap(tap.first, tap.second)
                                await("AAP tap closing fullscreen and returning to split view") {
                                    homeOverlay.isDestroyed && splitReady(display)
                                }
                                result.putString("AAP_touch", "Fullscreen Home restored DriveDeck and both apps")
                                if (test == null && !shizuku) {
                                    val fixture = com.hsd.universal.qa.BackStackRoot::class.java
                                    val component = android.content.ComponentName(this@DriveDeckAapProbe.context.packageName, fixture.name)
                                    val depth = java.util.concurrent.atomic.AtomicInteger(-1)
                                    val gestures = java.util.concurrent.CopyOnWriteArrayList<Triple<Int, Int, Int>>()
                                    val receiver = object : android.content.BroadcastReceiver() {
                                        override fun onReceive(ctx: Context, event: Intent) {
                                            val id = event.getIntExtra("display", -1)
                                            if (id <= 0 || id == display) return
                                            if (event.hasExtra("depth")) depth.set(event.getIntExtra("depth", -1))
                                            if (event.hasExtra("touch")) gestures.add(Triple(event.getIntExtra("touch", -1),
                                                event.getIntExtra("count", -1), event.getIntExtra("changedId", -1)))
                                        }
                                    }
                                    val pageFilter = android.content.IntentFilter(com.hsd.universal.qa.BackStackRoot.ACTION)
                                    val fixturePermission = "com.hsd.universal.permission.EMBED_DISPLAY"
                                    if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, pageFilter, fixturePermission, null, Context.RECEIVER_EXPORTED)
                                    else {
                                        // API 29–32 authorizes the separate test APK by its signature permission.
                                        @android.annotation.SuppressLint("UnspecifiedRegisterReceiverFlag")
                                        context.registerReceiver(receiver, pageFilter, fixturePermission, null)
                                    }
                                    try {
                                        for (hardware in listOf(false, true)) {
                                            depth.set(-1)
                                            val fullscreenApp = openFullscreen(display, component)
                                            await("focused input fixture on a child display") { depth.get() == 3 }
                                            val back = position(fullscreenApp, R.string.set_back)
                                            waitForIdleSync()
                                            if (!hardware) {
                                                val a = com.hsd.universal.aa.TouchPoint(3, 580, 370)
                                                val b = com.hsd.universal.aa.TouchPoint(7, 810, 410)
                                                fun touch(action: Int, index: Int, vararg points: com.hsd.universal.aa.TouchPoint) =
                                                    hu.touch(action, index, *points)
                                                fun received(action: Int) = await("app receives action $action; $gestures") {
                                                    gestures.lastOrNull()?.first == action
                                                }
                                                hu.touch(4, 0, b)
                                                hu.touch(null, 0, a); received(0)
                                                touch(3, 0, a); received(3)
                                                touch(0, 0, a); received(0)
                                                touch(0, 0, a)
                                                touch(0, 0, a.copy(x = 640)); received(2)
                                                touch(0, 0, a)
                                                touch(1, 0, b)
                                                touch(1, 0, a.copy(x = 640)); received(1)
                                                touch(0, 0, a); received(0)
                                                touch(5, 1, a, b); received(5)
                                                touch(5, 1, a, b)
                                                touch(2, 0, a.copy(x = 540), b.copy(x = 845)); received(2)
                                                touch(6, 0, a, b); received(6)
                                                touch(2, 0, b.copy(x = 850)); received(2)
                                                touch(3, 0); received(3)
                                                hu.tap(a.x, a.y)
                                                hu.tap(a.x, a.y)
                                                await("two complete consecutive taps inside the child app") {
                                                    gestures.count { it.first == 1 } == 3
                                                }
                                                val edges = gestures.filter { it.first != 2 }.map { it.first }
                                                check(edges == listOf(0,3,0,1,0,5,6,3,0,1,0,1)) { "Duplicate gesture edges: $gestures" }
                                                check(gestures.any { it == Triple(2,2,3) } && gestures.any { it == Triple(2,1,7) }) {
                                                    "Pinch lost contact identity: $gestures"
                                                }
                                                result.putString("AAP_gestures", "drag, repeated DOWN, pinch, CANCEL and two distinct taps verified inside child app")
                                            }
                                            fun press() { if (hardware) hu.key(4) else hu.tap(back.first, back.second) }
                                            press()
                                            await("single Back via hardware=$hardware inside the same app") { depth.get() == 2 }
                                            SystemClock.sleep(650)
                                            press()
                                            SystemClock.sleep(120)
                                            press()
                                            await("double Back via hardware=$hardware returning to DriveDeck") {
                                                (if (hardware) !fullscreenApp.hasWindowFocus() else fullscreenApp.isDestroyed) &&
                                                    tasks(context).firstOrNull { it.first == display }?.third == DesktopModePolicy.DRIVEDECK_PACKAGE &&
                                                    splitReady(display)
                                            }
                                        }
                                    } finally { context.unregisterReceiver(receiver) }
                                    result.putString("DriveDeck_Back", "Single Back in app, double Back to DriveDeck via touch and HU key")
                                }
                            }
                            if (!shizuku && DesktopAudioAccess.hasPermission(context)) {
                                await("USB playback capture · " + DesktopAudioState.status(targetContext)) { DesktopAudioState.running }
                                val tone = fixtureAudio()
                                try {
                                    tone.play()
                                    await("non-silent AAP audio") { hu.nonSilentAudioChunks.get() > 0 }
                                    result.putInt("AAP_audio_packets_with_signal", hu.nonSilentAudioChunks.get())
                                } finally { tone.release() }
                            } else {
                                check(!DesktopAudioState.running)
                                result.putString("audio", "external playback; capture unavailable")
                            }
                            hu.requestFocusNative()
                            check(hu.stoppedVideo.await(5, TimeUnit.SECONDS)) { "No STOP on native focus" }
                            val pausedFrames = hu.videoFrames.get()
                            SystemClock.sleep(350)
                            check(hu.videoFrames.get() == pausedFrames) { "Video continued after STOP" }
                            val headersBeforeResume = hu.codecConfigs.get()
                            val previousMediaSession = hu.videoStart?.sessionId
                            hu.requestFocusProjected()
                            val resumed = hu.videoFrames.get()
                            await { hu.videoFrames.get() >= resumed + 3 }
                            check(hu.videoStart?.sessionId != previousMediaSession && hu.codecConfigs.get() > headersBeforeResume)
                            result.putString("video_focus", "$initialFocus · native STOP, fresh START and headers on return")
                            result.putString("codec", if (hevc) "H.265" else "H.264")
                            result.putInt("frames", hu.videoFrames.get())
                            result.putInt("video_bytes", hu.videoBytes.get())
                            if (test?.update == true) {
                                val before = hu.codecConfigs.get()
                                hu.updateUi(ProjectionScreenCase.newUi)
                                await("new safe area and fresh codec headers") {
                                    size(DexState.displayId) == 1060 to 620 && hu.codecConfigs.get() > before
                                }
                                result.putString("new_safe_area", "1060x620")
                            }
                            if (modern && !shizuku && test == null) {
                                val configsBeforeChange = hu.codecConfigs.get()
                                hu.requestPortrait()
                                await("portrait video after in-session CONFIG") {
                                    hu.codecConfigs.get() > configsBeforeChange && DexState.displayId > 0 &&
                                        DexState.displayId != display && statuses.any { it.contains("720 × 1280 · H.") }
                                }
                                result.putString("AAP_reconfiguration","1280×720 to 720×1280 on the same TLS connection")
                            }
                            hu.sayByeBye()
                            check(closed.await(18, TimeUnit.SECONDS)) { "DriveDeck failed to release on disconnect" }
                            check(!DexState.running)
                        } finally {
                            runOnMainSync { projection.stop("Test session finished") }
                            hu.stop()
                            closed.await(8, TimeUnit.SECONDS)
                            if (authCase) identity.forget(peer)
                        }
                    }
                }
            } finally {
                lease?.let(DelegatedDesktopAccess::end)
                ui.dropShellPermissionIdentity()
            }
            result.putString("backend", if (shizuku) "Shizuku UserService" else "delegated")
            result.putString("result", "PASS")
            result.putString("projection", statuses.joinToString())
            finish(Activity.RESULT_OK, result)
        } catch (failure: Throwable) {
            result.putString("error", android.util.Log.getStackTraceString(failure).take(4500))
            result.putString("projection", statuses.joinToString())
            finish(Activity.RESULT_CANCELED, result)
        }
    }
    private fun checkUsbReopen() {
        val old = ParcelFileDescriptor.createSocketPair()
        val retired = UsbAccessoryTransport(old[0], "USB test accessory")
        old[1].use { retired.close() }
        val pair = ParcelFileDescriptor.createSocketPair()
        val current = UsbAccessoryTransport(pair[0], "USB test accessory")
        try {
            ParcelFileDescriptor.AutoCloseInputStream(pair[1]).use { input ->
                ParcelFileDescriptor.AutoCloseOutputStream(ParcelFileDescriptor.dup(pair[1].fileDescriptor)).use { output ->
                    retired.close()
                    retired.input.close()
                    retired.output.close()
                    current.output.write(41)
                    current.output.flush()
                    check(input.read() == 41)
                    output.write(42)
                    check(current.input.read() == 42)
                }
            }
        } finally { current.close() }
    }

    private fun localPipe(fd: ParcelFileDescriptor) = object : Transport {
        override val usbAccessory = oldUsbFrames > 0 || lateUsbVersion
        override val name = "qa socket pair"
        override val input = ParcelFileDescriptor.AutoCloseInputStream(fd)
        override val output = ParcelFileDescriptor.AutoCloseOutputStream(ParcelFileDescriptor.dup(fd.fileDescriptor))
        override fun close() {
            runCatching { android.system.Os.shutdown(fd.fileDescriptor, android.system.OsConstants.SHUT_RDWR) }
            runCatching { input.close() }; runCatching { output.close() }
        }
    }
    @Suppress("DEPRECATION") private fun tasks(context: Context) =
        context.getSystemService(ActivityManager::class.java)!!.getRunningTasks(100).mapNotNull {
            val id = FrameworkTaskIdentityReader.read(it)?.displayId ?: return@mapNotNull null
            val top = it.topActivity ?: return@mapNotNull null
            Triple(id, top.className, top.packageName)
        }
    private fun button(view: View, label: Int): View? {
        if (view.contentDescription == view.context.getString(label)) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) button(view.getChildAt(i), label)?.let { return it }
        return null
    }
    private fun fixtureAudio(): AudioTrack {
        val samples = ShortArray(4800) { (3200 * kotlin.math.sin(2 * Math.PI * it * 440 / 48000)).toInt().toShort() }
        return AudioTrack.Builder().setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setSampleRate(48000).build())
            .setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(samples.size * 2).build().also {
                check(it.write(samples, 0, samples.size) == samples.size)
                check(it.setLoopPoints(0, samples.size, -1) == AudioTrack.SUCCESS)
            }
    }
    private fun checkProjectionWifi(route: ProjectionRoute) {
        val ctx = targetContext
        val prefs = ProjectionRoute.prefs(ctx)
        val mode = prefs.getString("mode", null)
        val peer = prefs.getString("bluetoothPeer", null)
        val lastKey = "last." + route.name
        val last = ctx.getSharedPreferences("projection", Context.MODE_PRIVATE)
        val previousStatus = last.getString(lastKey, null)
        val activity = startActivitySync(Intent(ctx, ProjectionSettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        fun radio(enabled: Boolean) {
            val cmd = "svc wifi ${if (enabled) "enable" else "disable"}"
            ParcelFileDescriptor.AutoCloseInputStream(uiAutomation.executeShellCommand(cmd)).use { it.readBytes() }
            await("Wi-Fi radio $enabled") { ProjectionWifi.read(ctx) == if (enabled) ProjectionWifi.READY else ProjectionWifi.OFF }
        }
        val wasOff = ProjectionWifi.read(ctx) == ProjectionWifi.OFF
        try {
            prefs.edit().putString("mode", route.name).remove("bluetoothPeer").commit()
            radio(false)
            fun startWaiting() {
                ctx.startForegroundService(DesktopTransport.service(ctx))
                await("waiting for Wi-Fi before Bluetooth") { BridgeService.alive && CarLifeState.waitingForProjectionWifi }
                check(!CarLifeState.running && !DexState.running)
                check(ProjectionWifi.settings(ctx).resolveActivity(ctx.packageManager) != null)
            }
            startWaiting()
            ctx.startForegroundService(DesktopTransport.service(ctx).setAction(BridgeService.ACTION_STOP))
            await("stop while waiting") { !BridgeService.alive && !CarLifeState.waitingForProjectionWifi }
            radio(true)
            check(!BridgeService.alive)
            radio(false)
            last.edit().remove(lastKey).commit()
            startWaiting()
            radio(true)
            // A receiver must be selected at the next stage; use that outcome to verify resume.
            val next = ctx.getString(R.string.desktop_select_a_paired_head_unit)
            await("continued to Bluetooth selection") {
                !BridgeService.alive && !CarLifeState.waitingForProjectionWifi && last.getString(lastKey, "")!!.contains(next)
            }
        } finally {
            if (BridgeService.alive) com.hsd.universal.ui.Actions.stopBridge(ctx, userInitiated = false)
            prefs.edit().putString("mode", mode).putString("bluetoothPeer", peer).commit()
            last.edit().putString(lastKey, previousStatus).apply()
            com.hsd.universal.ui.Actions.resumeUsbAutoStart()
            radio(!wasOff)
            runOnMainSync(activity::finish)
        }
    }

    private fun checkServiceRestart() {
        val ctx = targetContext
        val actions = com.hsd.universal.ui.Actions
        val prefs = ProjectionRoute.prefs(ctx)
        val mode = prefs.getString("mode", null)
        val peer = prefs.getString("lanPeer", null)
        val activity = startActivitySync(Intent(ctx, ProjectionSettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            prefs.edit().putString("mode", ProjectionRoute.LAN_SERVER.name).putString("lanPeer", "127.0.0.1").apply()
            actions.resumeUsbAutoStart()
            ctx.startForegroundService(DesktopTransport.service(ctx))
            await("listening AA endpoint") { BridgeService.alive && DesktopTransport.activeRoute == ProjectionRoute.LAN_SERVER }
            actions.stopBridge(ctx, userInitiated = false)
            await("previous endpoint retired") { !BridgeService.alive }
            check(actions.connectDecision(false, false, false) == DexAutoConnectPolicy.Decision.CONNECT)
            ctx.startForegroundService(DesktopTransport.service(ctx))
            await("second AA endpoint") { BridgeService.alive }
            // The notification delivers a plain ACTION_STOP without extras.
            ctx.startForegroundService(DesktopTransport.service(ctx).setAction(BridgeService.ACTION_STOP))
            await("notification stop") { !BridgeService.alive && actions.isUsbAutoStartStopped() }
        } finally {
            if (BridgeService.alive) {
                actions.stopBridge(ctx)
                await("service cleanup") { !BridgeService.alive }
            }
            prefs.edit().putString("mode", mode).putString("lanPeer", peer).apply()
            actions.resumeUsbAutoStart()
            runOnMainSync { activity.finish() }
        }
    }

    private fun await(stage: String = "video", ok: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 18_000
        while (!ok() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100)
        check(ok()) { "Timed out waiting for $stage · ${DexState.error} · ${DesktopAudioState.status(targetContext)}" }
    }
}

internal fun installedDriveDeck(context: android.content.Context): Boolean {
    val pm = context.packageManager
    val pkg = DesktopModePolicy.DRIVEDECK_PACKAGE
    val info = try { pm.getPackageInfo(pkg, 0) } catch (_: android.content.pm.PackageManager.NameNotFoundException) { return false }
    return info.longVersionCode >= com.hsd.universal.update.DesktopComponent.DRIVEDECK.minCode && info.applicationInfo?.enabled == true &&
        pm.checkSignatures(context.packageName, pkg) == android.content.pm.PackageManager.SIGNATURE_MATCH
}

