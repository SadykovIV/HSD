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
import net.prototux.aare.aap.PhoneIdentity
import net.prototux.aare.aap.PinStore
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
    private var discoveryInteropPort: Int? = null
    private var externalMusic: String? = null
    private var captureReferencePcm = false
    private var disconnectMusic: String? = null
    private var passiveLan = false
    private var resumeFromDashboard = false
    private var musicDialog = false
    private var embedExternalMusic = false
    private var openMusicCard = false
    private var checkRenderDpi = false
    private var restoreRenderPrefs: (() -> Unit)? = null
    private var wirelessReceiver = false
    private var wirelessSsid = "HSD-QA"
    private var wirelessPassword = "hsd-avd-wireless"
    private var usbMusic = false
    private var mediaKeyDisconnect = false
    private var carLifeCallAudio = false
    private var projectionVolumes = false
    private var projectionRouting = false
    private var audioInfrastructure = false
    private var navigationRouting: String? = null
    private var carPlayRadio: Bundle? = null
    private var wppRadio: Bundle? = null
    private var carLifeUsbPort: Int? = null
    private var carLifeDesktop = false
    private var carLifeTouchLatency = false
    private var privateCarLifeTouchPort: Int? = null
    private var carLifeSecureSurface = false
    private var carLifeClearVideo = false
    private var fixedCarLife720 = false
    private var carLifeHudPort: Int? = null
    private var carLifeGesturePort: Int? = null
    private var carPlay = false
    private var carPlayMenu = false
    private var carPlayRouting = false
    private var carPlayMediaSeconds = 30
    private var bonjour = false
    private var shizuku = false
    private var modern = false
    private var screen: ProjectionScreenCase? = null
    private var displayOnly = false
    private var layoutProbe: DriveDeckLayoutProbe? = null
    private var checkLayoutControls = false
    private var checkCarMenu = false
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
    private var interruptUsb = false
    private var checkMusicFocus = false
    private var hevc = false
    private var strictPhone = false
    private var fieldSettingsMode: String? = null
    private var projectionControls = false
    private var selectedDesktopFps = com.hsd.universal.aa.FpsSetting.AUTO
    private var selectedAvSync = true
    private var usbDebuggingOff = false
    private var slowVideoWriter = false
    private var checkHiCar = false
    private var hicarUsbEntry = false
    private var hicarLateInterface = false
    private var usbAudioRegressions = false
    private var touchRecovery = false
    private var mediaCpu = false
    private var fieldReconnect = false
    private var checkHiCarCable = false
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        checkHiCar = arguments?.getString("protocol") == "hicar"
        hicarUsbEntry = arguments?.getString("protocol") == "hicar-usb-entry"
        hicarLateInterface = arguments?.getString("lateInterface") == "true"
        usbAudioRegressions = arguments?.getString("protocol") == "usb-audio-regressions"
        touchRecovery = arguments?.getString("protocol") == "touch-recovery"
        mediaCpu = arguments?.getString("protocol") == "media-cpu"
        checkHiCarCable = arguments?.getString("protocol") == "hicar-cable"
        fieldSettingsMode = arguments?.getString("fieldSettings")
        fieldReconnect = arguments?.getString("protocol") == "field-reconnect"
        projectionControls = arguments?.getString("protocol") == "projection-controls"
        checkCarMenu = arguments?.getString("carMenu") == "true"
        selectedDesktopFps = arguments?.getString("desktopFps")?.let(com.hsd.universal.aa.FpsSetting::valueOf) ?: com.hsd.universal.aa.FpsSetting.AUTO
        selectedAvSync = arguments?.getString("avSync") != "false"
        usbDebuggingOff = arguments?.getString("usbDebuggingOff") == "true"
        slowVideoWriter = arguments?.getString("slowVideoWriter") == "true"
        checkCertificates = arguments?.getString("protocol") == "certificates"
        if (arguments?.getString("protocol") == "discovery-interop")
            discoveryInteropPort = arguments.getString("fixturePort")?.toInt() ?: 5883
        externalMusic = arguments?.getString("externalMusic")
        captureReferencePcm = arguments?.getString("captureReferencePcm") == "true"
        disconnectMusic = arguments?.getString("disconnectMusic")
        passiveLan = arguments?.getString("protocol") == "passive-lan-power"
        resumeFromDashboard = arguments?.getString("resumeFromDashboard") == "true"
        musicDialog = arguments?.getString("musicDialog") == "true"
        embedExternalMusic = arguments?.getString("embedExternalMusic") == "true"
        openMusicCard = arguments?.getString("openMusicCard") == "true"
        checkRenderDpi = arguments?.getString("renderDpi") == "true"
        wirelessReceiver = arguments?.getString("protocol") == "wireless-receiver"
        wirelessSsid = arguments?.getString("ssid") ?: wirelessSsid
        wirelessPassword = arguments?.getString("pass") ?: wirelessPassword
        usbMusic = arguments?.getString("protocol") == "usb-music-pause"
        mediaKeyDisconnect = arguments?.getString("protocol") == "usb-music-media-keys"
        carLifeCallAudio = arguments?.getString("protocol") == "carlife-call-audio"
        projectionVolumes = arguments?.getString("protocol") == "projection-volumes"
        projectionRouting = arguments?.getString("protocol") == "projection-routing"
        audioInfrastructure = arguments?.getString("protocol") == "audio-infrastructure"
        navigationRouting = arguments?.getString("navigationRouting")
        carPlayRouting = arguments?.getString("protocol") == "carplay-routing"
        carPlayRadio = arguments?.takeIf { it.getString("protocol") in setOf("carplay-radio-receiver", "carplay-radio-sender") }
        wppRadio = arguments?.takeIf { it.getString("protocol") in setOf("wpp-radio-receiver", "wpp-radio-sender") }
        carPlay = arguments?.getString("protocol") == "carplay"
        carPlayMenu = arguments?.getString("protocol") == "carplay-menu"
        carPlayMediaSeconds = arguments?.getString("carPlayMediaSeconds")?.toIntOrNull()?.coerceIn(30, 3600) ?: 30
        bonjour = arguments?.getString("protocol") == "carplay-discovery"
        carLifeDesktop = arguments?.getString("protocol") == "carlife-desktop"
        carLifeTouchLatency = arguments?.getString("touchLatency") == "true"
        if (arguments?.getString("protocol") == "carlife-private-touch")
            privateCarLifeTouchPort = arguments.getString("fixturePort")?.toInt()
        carLifeSecureSurface = arguments?.getString("secureSurface") == "true"
        carLifeClearVideo = arguments?.getString("clearVideo") == "true"
        fixedCarLife720 = arguments?.getString("fixedCarLife720") == "true"
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
        strictPhone = arguments?.getString("phoneTrust") == "google"
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
        interruptUsb = arguments?.getString("fault") == "usb-write"
        checkMusicFocus = arguments?.getString("musicFocus") == "true"
        start()
    }
    override fun onStart() {
        val result = Bundle()
        val statuses = java.util.concurrent.CopyOnWriteArrayList<String>()
        try {
            // These probes use only memory or their own private display, including on a real phone.
            if (touchRecovery) { finish(Activity.RESULT_OK, checkProjectionTouchRecovery(this)); return }
            if (mediaCpu) { finish(Activity.RESULT_OK, measureMediaCpu()); return }
            if (fieldReconnect) { finish(Activity.RESULT_OK, checkFieldReconnect(this)); return }
            if (mediaKeyDisconnect) {
                check(targetContext.checkSelfPermission(android.Manifest.permission.MEDIA_CONTENT_CONTROL) ==
                    android.content.pm.PackageManager.PERMISSION_DENIED)
                val page = startActivitySync(Intent(targetContext, com.hsd.universal.update.ComponentUpdatesActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                try { UsbMusicPauseProbe(this).checkDisconnects() }
                finally { runOnMainSync { page.finish() } }
                result.putString("result", "PASS")
                finish(Activity.RESULT_OK, result)
                return
            }
            privateCarLifeTouchPort?.let { port ->
                result.putString("private_honda", checkPrivateCarLifeTouch(this, port))
                result.putString("result", "PASS")
                finish(Activity.RESULT_OK, result)
                return
            }
            carLifeGesturePort?.let { port ->
                result.putString("gesture", checkCarLifeGestureAudio(port, selectedAvSync))
                result.putString("result", "PASS")
                finish(Activity.RESULT_OK, result)
                return
            }
            if (checkHiCarCable) {
                check(targetContext.packageName == "com.hsd.universal.hicarqa") { "Install the isolated USB test package" }
                finish(Activity.RESULT_OK, com.hsd.universal.hicar.checkHiCarCable(this)); return
            }
            check(Build.HARDWARE in listOf("ranchu", "goldfish")) { "Disposable emulator required" }
            val context = targetContext
            if (usbAudioRegressions) {
                val observations = checkReportedUsbAndPcm()
                result.putString("production_code", observations.first)
                finish(if (observations.second) Activity.RESULT_OK else Activity.RESULT_CANCELED, result)
                return
            }
            if (passiveLan) {
                result.putString("power", checkPassiveLanPower(this))
                finish(Activity.RESULT_OK, result); return
            }
            fieldSettingsMode?.let { mode ->
                result.putString("settings", fieldSettings(this, mode))
                finish(Activity.RESULT_OK, result); return
            }
            if (checkHiCar) { finish(Activity.RESULT_OK, com.hsd.universal.hicar.checkHiCar(this)); return }
            if (audioInfrastructure) {
                result.putString("audio_queue",checkAudioQueue())
                result.putString("projection_cpu",checkProjectionCpuLease(this))
                val eld = AutoAudioEncoder(prefix=0,channels=2,profile=android.media.MediaCodecInfo.CodecProfileLevel.AACObjectELD)
                try {
                    val compatibility = runCatching { eld.verifyFrameLength(44100,480) }
                    result.putString("CarPlay_ELD_480",compatibility.fold({ "compatible platform encoder" }, { it.message }))
                } finally { eld.close() }
                finish(Activity.RESULT_OK,result);return
            }
            DexPrefs(context).apply { desktopFps = selectedDesktopFps; synchronizeAudioVideo = selectedAvSync }
            navigationRouting?.let { mode -> DexPrefs(context).apply {
                separateNavigationAudio = mode in setOf("on", "fallback", "restricted", "samsung"); navigationAudioApps=emptySet(); musicVolume=100; navigationVolume=100
            } }
            if (projectionControls) {
                finish(Activity.RESULT_OK, checkProjectionControls(this)); return
            }
            discoveryInteropPort?.let { port ->
                result.putAll(checkAapDiscoveryInterop(context, this.context, port))
                finish(Activity.RESULT_OK, result); return
            }
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
            wppRadio?.let { args ->
                ProjectionResources().use { pairing ->
                    val sender = args.getString("protocol") == "wpp-radio-sender"
                    emulatorBluetoothPairing(this,requireNotNull(args.getString("peer")),sender,pairing)
                    val activity = if (sender) startActivitySync(Intent(context,ProjectionSettingsActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) else null
                    try {
                        result.putAll(WirelessRadioProbe(context,args) {
                            sendStatus(1,Bundle().apply { putString("CarPlay_fixture","listening") })
                        }.run())
                    } finally { activity?.let { runOnMainSync(it::finish) } }
                }
                finish(Activity.RESULT_OK,result);return
            }
            carPlayRadio?.let { args ->
                ProjectionResources().use { pairing ->
                    if (args.getString("emulatorPairing") == "true") emulatorBluetoothPairing(this,
                        requireNotNull(args.getString("peer")), args.getString("protocol") == "carplay-radio-sender", pairing)
                    val probe = CarPlayRadioProbe(context,args) {
                        sendStatus(1,Bundle().apply { putString("CarPlay_fixture","listening") })
                    }
                    // Keep the OS pairing dialog in front when the bootstrap starts during bonding.
                    val activity = if (args.getString("protocol") == "carplay-radio-sender" && args.getString("pairingInProgress") != "true")
                        startActivitySync(Intent(context,ProjectionSettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    else null
                    try { result.putAll(probe.run()) }
                    finally { activity?.let { runOnMainSync(it::finish) } }
                }
                finish(Activity.RESULT_OK,result)
                return
            }
            if (carPlayRouting) {
                val previous = DexPrefs(context).separateNavigationAudio
                try {
                    for (mode in listOf("on","off","fallback")) {
                        DexPrefs(context).separateNavigationAudio = mode != "off"
                        result.putAll(CarPlayNetworkProbe(context,navigationMode=mode).run())
                    }
                } finally {
                    check(context.getSharedPreferences("hsd_dex",Context.MODE_PRIVATE).edit()
                        .putBoolean("separate_navigation_audio",previous).commit())
                }
                finish(Activity.RESULT_OK,result);return
            }
            if (carPlayMenu) {
                result.putAll(CarPlayNetworkProbe(context, carMenu = true).run())
                finish(Activity.RESULT_OK,result);return
            }
            if (carPlay) {
                result.putAll(CarPlayNetworkProbe(context).run())
                result.putAll(CarPlayNetworkProbe(context, aac = true, mediaSeconds = carPlayMediaSeconds).run())
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
                "android.permission.MODIFY_AUDIO_SETTINGS",
                *(if (carLifeClearVideo) emptyArray() else arrayOf("android.permission.DUMP")),
                *(if (Build.VERSION.SDK_INT >= 37) com.hsd.universal.dex.VdmPoolProtocol.permissions else emptyArray()),
                *(if (usbDebuggingOff) arrayOf("android.permission.WRITE_SECURE_SETTINGS") else emptyArray()))
            if (carLifeClearVideo) {
                check(!DexPrefs(context).desktopPanelControl)
                check(context.checkSelfPermission(android.Manifest.permission.DUMP) == android.content.pm.PackageManager.PERMISSION_DENIED)
            }
            val lease = if (shizuku) null else DelegatedDesktopAccess.begin {}
            val audioPrefs = DexPrefs(context)
            val originalAudio = audioPrefs.usbPlaybackAudio
            var debugging: AutoCloseable? = null
            try {
                (if (shizuku) null else VdmProbeHost.start(this, ui)).use {
                    if (usbDebuggingOff) {
                        val lifetime = com.hsd.universal.dex.VdmHostLink.nativeHostLifetime
                        debugging = disableUsbDebuggingForProbe(context)
                        check(DelegatedDesktopAccess.isReady(context) && com.hsd.universal.dex.VdmHostLink.isReady)
                        check(com.hsd.universal.dex.VdmHostLink.adbIndependent)
                        check(com.hsd.universal.dex.VdmHostLink.nativeHostLifetime === lifetime)
                        result.putBoolean("activation_retained_without_adb", true)
                    }
                    DexPrefs(context).apply {
                        desktopMode = if (carLifeDesktop) DesktopModePolicy.ANDROID_DESKTOP else DesktopModePolicy.DRIVEDECK
                        if (shizuku) useShizuku = true else useDelegated = true
                        usbPlaybackAudio = !shizuku && disconnectMusic != "external-route"
                    }
                    if (hicarUsbEntry) {
                        result.putString("hicar_usb_entry", checkHiCarUsbEntry(this, hicarLateInterface))
                        finish(Activity.RESULT_OK, result); return
                    }
                    if (usbMusic) {
                        UsbMusicPauseProbe(this).checkDisconnects()
                        result.putString("music_disconnect", "camera and stale link kept playing; USB unplug, Wi-Fi loss, explicit Stop and service destruction each paused once")
                        finish(Activity.RESULT_OK, result); return
                    }
                    if (carLifeCallAudio) {
                        result.putString("carlife_call_audio", checkCarLifeCallAudio(this, ::fixtureAudio))
                        finish(Activity.RESULT_OK, result); return
                    }
                    if (projectionVolumes) {
                        result.putString("projection_volumes", checkProjectionVolumes(this))
                        finish(Activity.RESULT_OK, result); return
                    }
                    if (projectionRouting) {
                        result.putString("audio_queue",checkAudioQueue())
                        result.putString("projection_routing",checkCarLifeRouting(this))
                        finish(Activity.RESULT_OK,result);return
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
                    val pausedPlayer = if (resumeFromDashboard) {
                        DexPrefs(context).autoMusicPlayback = false
                        openExternalPlayer(this, checkNotNull(externalMusic)).also {
                            it.transportControls.pause()
                            waitPlayer(it, android.media.session.PlaybackState.STATE_PAUSED)
                        }
                    } else null
                    val clock = listOf("com.google.android.deskclock", "com.android.deskclock").first {
                        context.packageManager.getLaunchIntentForPackage(it) != null
                    }
                    val fixtureApps = setOf("com.android.settings", clock)
                    if (checkRenderDpi) {
                        val prefs = DexPrefs(context)
                        val oldDpi = prefs.desktopDensity
                        val oldCompact = prefs.compactMapSymbols
                        val oldPackages = prefs.dpiCorrectionPackages
                        restoreRenderPrefs = {
                            prefs.desktopDensity = oldDpi; prefs.compactMapSymbols = oldCompact
                            prefs.dpiCorrectionPackages = oldPackages
                        }
                        prefs.desktopDensity = 0
                        prefs.compactMapSymbols = true
                        // A custom selection proves this is not a hardcoded Yandex-only path.
                        prefs.dpiCorrectionPackages = oldPackages + "com.android.settings" - clock
                    }
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
                        var marker: Activity? = null
                        try {
                            result.putAll(checkCarLifeBulk(this, port, ::fixtureAudio, carLifeTouchLatency, fixedCarLife720) { display ->
                                val ready = if (carLifeDesktop) tasks(context).any { it.first == display && it.second == DesktopModePolicy.ANDROID_HOME_CLASS }
                                    else splitReady(display)
                                if (ready && carLifeTouchLatency && marker == null) marker = openCarLifeTouchMarker(this, display, port)
                                if (ready && (carLifeSecureSurface || carLifeClearVideo) && marker == null)
                                    marker = openCarLifeSurfaceProbe(this, display, playClip = carLifeClearVideo)
                                ready
                            })
                        } finally { runOnMainSync { marker?.finish() } }
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
                    repeat(if (usbDebuggingOff) 3 else if (oldUsbFrames >= 32) 2 else 1) { connectionRound ->
                    val staleOnThisOpen = if (connectionRound == 0) oldUsbFrames else 0
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
                    if (staleOnThisOpen > 0) {
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
                        val localMediaPeer = !strictPhone && (navigationRouting != null || resumeFromDashboard || musicDialog)
                        val sender = if (localMediaPeer || (strictPhone && rejected == -3))
                            net.prototux.aare.aap.cert.SelfSignedCertificate.generate("Local QA phone").let {
                                PhoneIdentity(it.certificate, it.keyPair.private)
                            } else identity.phone("qa-wired")
                        val phoneRoot = if (strictPhone) context.resources.openRawResource(R.raw.aa_google_automotive_link).use {
                            java.security.cert.CertificateFactory.getInstance("X.509").generateCertificate(it) as java.security.cert.X509Certificate
                        } else null
                        val receiverClock = when (rejected) {
                            -23 -> java.util.Date(sender.certificate.notBefore.time - 1000)
                            -24 -> java.util.Date(sender.certificate.notAfter.time + 1000)
                            else -> null
                        }
                        val version = when {
                            interruptUsb -> 1 to 4
                            v13 -> 1 to 3
                            v15 -> 1 to 5
                            v4 || rejected != null || silentAuth -> 4 to 0
                            modern -> 6 to 1
                            else -> 1 to 6
                        }
                        val samsungAudio = navigationRouting == "samsung"
                        val audioRouting = navigationRouting?.let { RoutingReceiver(voiceRate = if (samsungAudio) 48000 else 16000) }
                        val hu = HeadUnitSimulator(car, offerGuidance = navigationRouting != "fallback", routing = audioRouting,
                            referencePcm = if (captureReferencePcm) File(context.cacheDir, "qa-receiver-reference.bin") else null,
                            samsungAudioOffers = samsungAudio,
                            restrictedAudioGrants = navigationRouting == "restricted",
                            authorizationStatus = if (silentAuth) null else if (receiverClock != null) 0 else rejected ?: 0,
                            disconnectDuringTls = disconnectDuringTls, encryptAuthorization = encryptAuthorization,
                            receiverClock = receiverClock, trustedPhoneRoot = phoneRoot, initialFocus = initialFocus, requestPings = !interruptUsb,
                            protocolVersion = version, width = test?.panelW ?: 1280, height = test?.panelH ?: 720,
                            dpi = if (checkRenderDpi) 213 else 160,
                            previousUsbFrames = staleOnThisOpen, offeredModes = test?.modes, offeredIndices = test?.indices ?: listOf(0), maxUnacked = if (modern) 1 else 4, modernNoAcks = modern, videoOut = video, videoCodec = codec)
                        val peer = if (authCase) "usb|qa-refusal-${java.util.UUID.randomUUID()}" else "qa-wired"
                        val ready = CountDownLatch(1)
                        val failWrites = java.util.concurrent.atomic.AtomicBoolean()
                        val reopenChecked = CountDownLatch(1)
                        val stallNextWrite = java.util.concurrent.atomic.AtomicBoolean()
                        val writeBlocked = CountDownLatch(1)
                        val releaseWrite = CountDownLatch(1)
                        val transport = if (!interruptUsb && !slowVideoWriter) phone else object : Transport by phone {
                            override val output = object : java.io.FilterOutputStream(phone.output) {
                                override fun write(bytes: ByteArray, offset: Int, length: Int) {
                                    if (failWrites.get()) throw java.io.IOException("write failed: EIO (I/O error)")
                                    if (stallNextWrite.compareAndSet(true, false)) {
                                        writeBlocked.countDown()
                                        check(releaseWrite.await(5, TimeUnit.SECONDS)) { "Probe did not release blocked video write" }
                                    }
                                    out.write(bytes, offset, length)
                                }
                            }
                        }
                        val projection = AutoProjection(context, transport, sender, if (authCase) identity else pins, peer,
                            { statuses.add(it) }, { statuses.add(it); closed.countDown() }, ready::countDown,
                            canReopenUsb = { reopenChecked.countDown(); interruptUsb })
                        val carMenuControl = if (checkCarMenu) CarMenuControl.register(context, projection::carMenu) else null
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
                                    if (strictPhone) check(hu.phoneAuthorization == -3)
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
                            if (staleOnThisOpen >= 32) {
                                check(closed.count == 0L && hu.videoStart == null && hu.videoFrames.get() == 0)
                                check(statuses.any { it.contains("VERSION_REQUEST") })
                                check(projection.retryUsbOpen)
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
                            if (strictPhone) {
                                check(hu.phoneAuthorization == 0)
                                result.putString("phone_identity", "Google Automotive Link path and TLS key possession verified by receiver")
                            }
                            check(pins.get("qa-wired").contains(fingerprint(hu.huCert.certificate)))
                            check(hu.openedChannels.containsAll(listOf(hu.video, hu.input)))
                            await { DexState.running && DexState.displayId > 0 && hu.codecConfigs.get() > 0 && hu.videoFrames.get() >= 20 }
                            if (initialFocus == HeadUnitSimulator.InitialFocus.UNCONFIRMED) {
                                val sessionId = requireNotNull(hu.videoStart).sessionId
                                val frames = hu.videoFrames.get()
                                hu.acknowledgeVideo(sessionId + 1)
                                await("frames continue without a receiver confirmation") { hu.videoFrames.get() >= frames + 8 }
                                check(ready.count == 1L && !projection.freshVideo() && !DesktopAudioState.running &&
                                    statuses.contains(context.getString(R.string.aa_wait_receiver_video)))
                                if (interruptUsb) hu.tap(240, 240) else hu.acknowledgeVideo(sessionId)
                                check(ready.await(3, TimeUnit.SECONDS) && projection.freshVideo())
                                result.putString("receiver_confirmation", "local writes and stale ACK ignored; confirmed by " +
                                    if (interruptUsb) "display input" else "current stream ACK")
                                hu.requestFocusProjected()
                            }
                            check(ready.await(3, TimeUnit.SECONDS))
                            val sessionId = hu.videoStart?.sessionId
                            val starts = hu.decodableStarts.get()
                            hu.requestFocusProjected()
                            await("decoder restart in the current video session") { hu.decodableStarts.get() > starts }
                            check(hu.videoStart?.sessionId == sessionId)
                            result.putString("decoder_restart", "codec headers and independent frame with the original START")
                            if (!interruptUsb) await("negotiated keepalive") { hu.pingsAnswered.get() > 0 }
                            if (!shizuku && DesktopAudioAccess.hasPermission(context)) {
                                await("ready audio sink") { projection.audioRate() != null }
                                val nowUs = System.nanoTime() / 1000
                                val audio = com.hsd.universal.aa.CarlifeAudio
                                check(projection.audio(audio.MEDIA_INIT, ByteArray(0), nowUs))
                                check(projection.audio(audio.MEDIA_DATA, ByteArray(1920), nowUs))
                                check(hu.mediaFocusRequests.get() == 0 && hu.audioChunks.get() == 0)
                                result.putString("startup_audio", "silent capture left the head unit's audio focus alone")
                            }
                            result.putInt("received_video_frames", hu.videoFrames.get())
                            if (checkRenderDpi) {
                                val parent = DexState.displayId
                                val dm = context.getSystemService(android.hardware.display.DisplayManager::class.java)
                                fun dpi(id: Int) = context.createDisplayContext(checkNotNull(dm.getDisplay(id))).resources.displayMetrics.densityDpi
                                check(dpi(parent) == 128) { "AA used receiver density instead of desktop density: ${dpi(parent)}" }
                                fun splitDensityReady() = tasks(context).let { all ->
                                    fun child(pkg: String, expected: Int, scaled: Boolean) = all.any { task ->
                                        val d = dm.getDisplay(task.first)
                                        task.first > 0 && task.first != parent && task.third == pkg && d != null &&
                                            dpi(task.first) == expected && (d.mode.physicalHeight > 720) == scaled
                                    }
                                    child(settings.packageName, 320, true) && child(clock, 128, false)
                                }
                                await("selected custom app 320 DPI, unselected clock 128 DPI") { splitDensityReady() }
                                hu.requestFocusNative(); SystemClock.sleep(500); hu.requestFocusProjected()
                                await("corrected split windows after camera") { splitDensityReady() }
                                val overlay = openFullscreen(parent, settings)
                                await("corrected full-screen custom app") {
                                    tasks(context).any { task -> task.third == settings.packageName &&
                                        dm.getDisplay(task.first)?.let { d ->
                                            // Full-screen correction shares the same finite pixel budget.
                                            // Preserve dp geometry even when the buffer cannot reach 320 DPI.
                                            d.name.contains("fullscreen", ignoreCase = true) &&
                                                dpi(task.first) in 256..320 &&
                                                kotlin.math.abs(d.mode.physicalWidth * 128.0 / dpi(task.first) - 1280) < 5 &&
                                                d.mode.physicalWidth.toLong() * d.mode.physicalHeight <= 5_200_000
                                        } == true }
                                }
                                runOnMainSync { checkNotNull(button(overlay.window.decorView, R.string.desktop_drivedeck_home)).performClick() }
                                await("correction restored after full-screen Home") { overlay.isDestroyed && splitDensityReady() }
                                result.putString("dpi_correction", "Receiver 213 DPI; desktop 128 DPI; selected custom app 320 DPI in tiles, full-screen >=256 DPI within pixel budget with preserved dp geometry, unselected app 128 DPI; split, camera, fullscreen, return passed")
                                return@videoTest
                            }
                            if (audioRouting != null) {
                                if (samsungAudio) {
                                    check(hu.openedChannels.containsAll(listOf(4, 7)) && hu.openedChannels.none { it in 5..6 })
                                    result.putString("pcm_channels", "4 MEDIA stereo, 7 GUIDANCE mono, both 48000 Hz")
                                }
                                if (navigationRouting == "restricted") {
                                    result.putString("restricted_focus", checkRestrictedFocus(audioRouting, hu))
                                    return@videoTest
                                }
                                if (navigationRouting == "live")
                                    DexPrefs(context).separateNavigationAudio = true
                                val moving = DexState.displayId.takeIf { navigationRouting == "continuity" }
                                val before = hu.videoFrames.get()
                                try {
                                    result.putString("navigation_routing", checkCapturedRouting(this, audioRouting,
                                        navigationRouting in setOf("on", "live", "samsung"), moving))
                                } finally {
                                    if (moving != null) result.putInt("audio_test_video_frames", hu.videoFrames.get() - before)
                                }
                                if (moving != null) check(hu.videoFrames.get() > before) { "Receiver stopped getting video" }
                                return@videoTest
                            }
                            if (checkMusicFocus) {
                                disconnectMusic?.let { route ->
                                    val player = openExternalPlayer(this, checkNotNull(externalMusic))
                                    val captured = route == "projected"
                                    if (captured) await("music on head unit") { hu.nonSilentAudioChunks.get() >= 20 }
                                    hu.requestFocusNative()
                                    SystemClock.sleep(1000)
                                    check(player.playbackState?.state == android.media.session.PlaybackState.STATE_PLAYING)
                                    hu.requestFocusProjected()
                                    await("picture back on head unit") { projection.freshVideo() }
                                    if (!captured) check(hu.nonSilentAudioChunks.get() == 0)
                                    val start = SystemClock.elapsedRealtime()
                                    car.close()
                                    waitPlayer(player, android.media.session.PlaybackState.STATE_PAUSED)
                                    val pauseMs = SystemClock.elapsedRealtime() - start
                                    repeat(40) {
                                        SystemClock.sleep(250)
                                        check(player.playbackState?.state == android.media.session.PlaybackState.STATE_PAUSED) { "VLC resumed while disconnected" }
                                    }
                                    check(closed.count == 0L)
                                    result.putLong("VLC_paused_after_disconnect_ms", pauseMs)
                                    result.putString("VLC_state", "PLAYING during native screen, PAUSED for 10 s after receiver socket closed")
                                    result.putInt("non_silent_packets", hu.nonSilentAudioChunks.get())
                                    return@videoTest
                                }
                                check(DesktopAudioAccess.hasPermission(context)) { "This case requires real playback capture" }
                                if (musicDialog) {
                                    checkMusicDialog(this, ui, DexState.displayId) { x, y ->
                                        val point = test?.point(x, y) ?: (x to y)
                                        val touch = com.hsd.universal.aa.TouchPoint(0, point.first, point.second)
                                        hu.touch(0, 0, touch)
                                        hu.touch(1, 0, touch)
                                    }
                                    result.putString("music_dialog", "Back, Home and host Home each closed the projected DriveDeck Music window")
                                }
                                val placePlayer: (() -> Unit)? = if (!embedExternalMusic && !openMusicCard) null else ({
                                    val parent = DexState.displayId
                                    val vlc = checkNotNull(context.packageManager.getLaunchIntentForPackage("org.videolan.vlc")?.component)
                                    if (openMusicCard) {
                                        val card = tapPlayingMusicCard(ui, parent) { x, y ->
                                            val point = test?.point(x, y) ?: (x to y)
                                            val touch = com.hsd.universal.aa.TouchPoint(0, point.first, point.second)
                                            hu.touch(0, 0, touch)
                                            hu.touch(1, 0, touch)
                                        }
                                        result.putString("music_card_touch", card.toString())
                                    } else openFullscreen(parent, vlc)
                                    await("VLC inside DriveDeck's full-screen child display") {
                                        tasks(context).any { task -> task.first > 0 && task.first != parent && task.third == vlc.packageName &&
                                            context.getSystemService(android.hardware.display.DisplayManager::class.java)
                                                .getDisplay(task.first)?.name?.contains("fullscreen", ignoreCase = true) == true }
                                    }
                                })
                                result.putAll(externalMusic?.let { checkExternalMusic(this, hu, it, placePlayer, pausedPlayer) }
                                    ?: AapMusicFocusProbe(this).run(hu, fixtureAudio()))
                                if (resumeFromDashboard) {
                                    val parent = DexState.displayId
                                    check(tasks(context).any { it.first == parent && it.second == DesktopModePolicy.driveDeckHome(context) })
                                    check(tasks(context).none { it.first == parent && it.third == "org.videolan.vlc" })
                                    result.putString("companion_resume", "DriveDeck alone resumed the paused player with the dashboard on the head-unit display")
                                }
                                return@videoTest
                            }
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
                            selectedDesktopFps.fixed?.let { cap ->
                                val before = hu.videoFrames.get()
                                val started = SystemClock.elapsedRealtime()
                                SystemClock.sleep(4000)
                                val elapsed = SystemClock.elapsedRealtime() - started
                                val count = hu.videoFrames.get() - before
                                check(count >= 4 && count <= cap * elapsed / 1000 + 2) {
                                    "Encoded output exceeded $cap FPS or stopped: $count frames in ${elapsed}ms"
                                }
                                result.putString("measured_frame_limit", "$count encoded frames in ${elapsed}ms; cap=$cap")
                            }
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
                                    settleInputWindows()
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
                                    val fixtureDisplay = java.util.concurrent.atomic.AtomicInteger(-1)
                                    val gestures = java.util.concurrent.CopyOnWriteArrayList<Triple<Int, Int, Int>>()
                                    val receiver = object : android.content.BroadcastReceiver() {
                                        override fun onReceive(ctx: Context, event: Intent) {
                                            val id = event.getIntExtra("display", -1)
                                            if (id <= 0 || id == display) return
                                            if (event.getBooleanExtra("ready", false)) fixtureDisplay.set(id)
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
                                            fixtureDisplay.set(-1)
                                            val fullscreenApp = openFullscreen(display, component)
                                            await("drawn input fixture on a child display") { fixtureDisplay.get() > 0 }
                                            // Android's untrusted presentation displays require a user touch
                                            // before granting key focus. Exercise that real input path before
                                            // asserting Back/gesture behavior; launch alone is not focus.
                                            val fixtureFrames = hu.videoFrames.get()
                                            await("fixture frame delivery") { hu.videoFrames.get() >= fixtureFrames + 3 }
                                            settleInputWindows()
                                            hu.tap(580, 370)
                                            await("focused input fixture on a child display") {
                                                result.putInt("fixture_depth", depth.get())
                                                result.putString("fixture_touch", gestures.toString())
                                                depth.get() == 3
                                            }
                                            await("completed focus tap") { gestures.lastOrNull()?.first == 1 }
                                            gestures.clear()
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
                                                if (slowVideoWriter) {
                                                    val before = hu.receivedVideo.size
                                                    stallNextWrite.set(true)
                                                    check(writeBlocked.await(3, TimeUnit.SECONDS))
                                                    val started = System.nanoTime()
                                                    try {
                                                        touch(0, 0, a); received(0)
                                                        touch(1, 0, a); received(1)
                                                        val touchMs = (System.nanoTime() - started) / 1_000_000
                                                        check(touchMs < 500) { "Input waited on video writer: ${touchMs}ms" }
                                                        check(releaseWrite.count == 1L)
                                                        // Let queued frames expire while the child app remains responsive.
                                                        SystemClock.sleep(750)
                                                        result.putLong("input_during_blocked_video_ms", touchMs)
                                                    } finally { releaseWrite.countDown() }
                                                    await("video recovery after stalled USB writer") { hu.receivedVideo.size >= before + 12 }
                                                    val after = hu.receivedVideo.drop(before)
                                                    val stale = after.count { (it.receivedNanos / 1000 - it.ptsUs) > 500_000 }
                                                    check(stale <= 1) { "Writer flushed $stale stale frames after USB recovered" }
                                                    check(after.drop(1).any { it.key }) { "No independent frame after clearing stale references" }
                                                    result.putString("slow_writer", "750ms stall; input delivered before release; stale frames=$stale; IDR recovery")
                                                    gestures.clear()
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
                                                val parentTasks = tasks(context).filter { it.first == display }
                                                var hidden = false
                                                runOnMainSync {
                                                    hidden = fullscreenApp.isDestroyed ||
                                                        fullscreenApp.window.decorView.windowVisibility != View.VISIBLE
                                                }
                                                result.putString("back_state", "depth=${depth.get()} destroyed=${fullscreenApp.isDestroyed} " +
                                                    "hidden=$hidden tasks=$parentTasks")
                                                // RunningTasks is ordered by recency, not display Z order.
                                                // Observe the actual host window disappearing instead.
                                                hidden && (hardware || fullscreenApp.isDestroyed) &&
                                                    splitReady(display)
                                            }
                                            result.remove("back_state")
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
                                    check(hu.mediaFocusRequests.get() >= 1)
                                    result.putInt("AAP_audio_packets_with_signal", hu.nonSilentAudioChunks.get())
                                    if (interruptUsb) {
                                        check(isUsb && hu.pingsAnswered.get() == 0)
                                        failWrites.set(true)
                                        check(closed.await(10, TimeUnit.SECONDS) && projection.retryUsbOpen)
                                        check(reopenChecked.count == 0L && !DexState.running && statuses.last().contains("EIO"))
                                        checkUsbRecovery(context, sender, pins, peer, hu.huCert)
                                        result.putString("usb_interruption", "AAP 1.4 write EIO during video and music; new descriptor to the same receiver resumed projection")
                                        return@videoTest
                                    }
                                } finally { tone.release() }
                            } else {
                                check(!DesktopAudioState.running)
                                result.putString("audio", "external playback; capture unavailable")
                            }
                            if (interruptUsb) {
                                // Descriptor recovery must also be exercised on Android versions
                                // where this activation cannot grant playback capture. Video alone
                                // still has an active USB writer and must recover from the same EIO.
                                check(isUsb && hu.pingsAnswered.get() == 0)
                                failWrites.set(true)
                                check(closed.await(10, TimeUnit.SECONDS) && projection.retryUsbOpen)
                                // canReopenUsb is consulted to retain a delivered audio route.
                                // With no audio, prove recovery by reopening and receiving video.
                                check(!DexState.running && statuses.last().contains("EIO"))
                                checkUsbRecovery(context, sender, pins, peer, hu.huCert)
                                result.putString("usb_interruption", "AAP 1.4 write EIO during video; new descriptor to the same receiver resumed projection; playback capture unavailable")
                                return@videoTest
                            }
                            if (checkCarMenu) {
                                val displayBefore = DexState.displayId
                                val bounds = tapCarMenu(this, ui, displayBefore) { x, y ->
                                    val point = test?.point(x, y) ?: (x to y)
                                    hu.touch(0, 0, com.hsd.universal.aa.TouchPoint(0, point.first, point.second))
                                    hu.touch(1, 0, com.hsd.universal.aa.TouchPoint(0, point.first, point.second))
                                }
                                result.putString("car_menu_button", bounds.toString())
                                check(hu.nativeMenuRequest.await(5, TimeUnit.SECONDS)) { "No native menu request from button" }
                                check(DexState.displayId == displayBefore && closed.count == 1L && hu.byeBye.count == 1L)
                            } else hu.requestFocusNative()
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
                                        size(DexState.displayId) == (720 to 1280) &&
                                        statuses.any { it.contains("720 × 1280 · H.") }
                                }
                                result.putString("AAP_reconfiguration","1280×720 to 720×1280 on the same TLS connection")
                            }
                            hu.sayByeBye()
                            if (!closed.await(3, TimeUnit.SECONDS)) {
                                File(context.cacheDir, "qa-failure-threads.txt").writeText(
                                    Thread.getAllStackTraces().entries.joinToString("\n\n") { (thread, stack) ->
                                        "${thread.name} ${thread.state}\n" + stack.joinToString("\n")
                                    })
                                check(closed.await(15, TimeUnit.SECONDS)) { "DriveDeck failed to release on disconnect" }
                            }
                            check(!projection.retryUsbOpen)
                            check(!DexState.running)
                            if (usbDebuggingOff) {
                                check(com.hsd.universal.dex.VdmHostLink.adbIndependent) { "Pool retired on ordinary disconnect" }
                                check(DelegatedDesktopAccess.isReady(context)) { "Activation lost on ordinary disconnect" }
                                result.putInt("connections_without_adb", connectionRound + 1)
                                if (connectionRound == 2) recordIdlePool(result)
                            }
                        } finally {
                            carMenuControl?.close()
                            runOnMainSync { projection.stop("Test session finished") }
                            hu.stop()
                            if (captureReferencePcm) check(hu.closed.await(5, TimeUnit.SECONDS)) { "Reference PCM receiver did not finish" }
                            closed.await(8, TimeUnit.SECONDS)
                            if (authCase) identity.forget(peer)
                        }
                    }
                    }
                }
            } finally {
                debugging?.close()
                if (disconnectMusic != null) audioPrefs.usbPlaybackAudio = originalAudio
                restoreRenderPrefs?.invoke()
                lease?.let(DelegatedDesktopAccess::end)
                if (usbDebuggingOff) runCatching { ui.dropShellPermissionIdentity() } else ui.dropShellPermissionIdentity()
            }
            result.putString("backend", if (shizuku) "Shizuku UserService" else "delegated")
            if (usbDebuggingOff) result.putString("debugging", "Both debugging modes off; same activation and native lifetime; settings restored")
            result.putString("result", "PASS")
            result.putString("projection", statuses.joinToString())
            finishProbe(Activity.RESULT_OK, result)
        } catch (failure: Throwable) {
            result.putString("error", android.util.Log.getStackTraceString(failure).take(4500))
            result.putString("projection", statuses.joinToString())
            finishProbe(Activity.RESULT_CANCELED, result)
        }
    }
    private fun finishProbe(code: Int, result: Bundle) {
        if (!usbDebuggingOff) { finish(code, result); return }
        val data = org.json.JSONObject().put("pass", code == Activity.RESULT_OK)
        @Suppress("DEPRECATION")
        result.keySet().forEach { key -> data.put(key, result.get(key)) }
        File(targetContext.filesDir, "qa-adb-off-result.json").writeText(data.toString(2))
        // am/UiAutomationConnection deliberately died with adbd; the app record is the result.
        runCatching { finish(code, result) }
    }
    private fun recordIdlePool(result: Bundle) {
        val context = targetContext
        val displays = context.getSystemService(android.hardware.display.DisplayManager::class.java).displays
            .filter { it.name in com.hsd.universal.dex.VdmPoolProtocol.names }
        check(displays.size == 5 && displays.all { it.displayId > 0 })
        check(tasks(context).none { task -> displays.any { it.displayId == task.first } })
        val pid = com.hsd.universal.dex.IVdmPoolTokens.Stub.asInterface(
            checkNotNull(com.hsd.universal.dex.VdmHostLink.nativeHostLifetime)).ownerPid()
        val memory = context.getSystemService(ActivityManager::class.java).getProcessMemoryInfo(intArrayOf(android.os.Process.myPid(), pid))
        result.putInt("idle_main_pss_kib", memory[0].totalPss)
        result.putInt("idle_token_owner_pss_kib", memory[1].totalPss)
        val cpu = android.os.Process.getElapsedCpuTime()
        val then = SystemClock.elapsedRealtime()
        SystemClock.sleep(3000)
        result.putLong("idle_main_cpu_ms", android.os.Process.getElapsedCpuTime() - cpu)
        result.putLong("idle_observed_ms", SystemClock.elapsedRealtime() - then)
        val power = context.getSystemService(android.os.PowerManager::class.java)
        check(displays.all {
            com.hsd.universal.dex.VdmPoolApi.call(power, "isInteractive", it.displayId) == false
        }) { "The idle display group kept the device awake" }
        result.putString("idle_displays", displays.joinToString { "${it.displayId}:${it.mode.physicalWidth}x${it.mode.physicalHeight}:state=${it.state}" })
        result.putBoolean("idle_group_asleep", true)
        for (service in listOf("power", "display")) {
            File(context.cacheDir, "qa-idle-$service.txt").outputStream().use {
                com.hsd.universal.dex.VdmHostCore.service(service).dump(it.fd, emptyArray())
            }
        }
    }
    private fun checkUsbRecovery(context: Context, sender: PhoneIdentity, pins: PinStore, peer: String, receiver: PhoneIdentity) {
        val descriptors = ParcelFileDescriptor.createSocketPair()
        val hu = HeadUnitSimulator(localPipe(descriptors[1]), protocolVersion = 1 to 4, huCert = receiver)
        val ready = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val next = AutoProjection(context, UsbAccessoryTransport(descriptors[0], "USB reopened accessory"),
            sender, pins, peer, {}, { closed.countDown() }, ready::countDown)
        try {
            runOnMainSync { next.start() }
            hu.start()
            check(ready.await(18, TimeUnit.SECONDS) && hu.firstVideoFrame.await(3, TimeUnit.SECONDS))
            await("replacement USB session delivering video") { next.freshVideo() && hu.videoFrames.get() >= 10 }
            hu.sayByeBye()
            check(closed.await(10, TimeUnit.SECONDS) && !next.retryUsbOpen && !DexState.running)
        } finally {
            runOnMainSync { next.stop("USB recovery test finished") }
            hu.stop()
            closed.await(8, TimeUnit.SECONDS)
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
        if (!ok()) {
            for (service in listOf("input", "window", "display", "power")) runCatching {
                File(targetContext.cacheDir, "qa-failure-$service.txt").outputStream().use {
                    com.hsd.universal.dex.VdmHostCore.service(service).dump(it.fd, emptyArray())
                }
            }
            error("Timed out waiting for $stage · ${DexState.error} · ${DesktopAudioState.status(targetContext)}")
        }
    }

    /** Drawing precedes InputDispatcher registration. Match Android's own UI test input barrier. */
    private fun settleInputWindows() {
        if (usbDebuggingOff) {
            val wm = com.hsd.universal.dex.VdmPoolApi.remote("android.view.IWindowManager", "window")
            com.hsd.universal.dex.VdmPoolApi.call(wm, "syncInputTransactions", true)
            return
        }
        android.app.UiAutomation::class.java.getMethod("syncInputTransactions").invoke(uiAutomation)
    }
}

internal fun installedDriveDeck(context: android.content.Context): Boolean {
    val pm = context.packageManager
    val pkg = DesktopModePolicy.DRIVEDECK_PACKAGE
    val info = try { pm.getPackageInfo(pkg, 0) } catch (_: android.content.pm.PackageManager.NameNotFoundException) { return false }
    return info.longVersionCode >= com.hsd.universal.update.DesktopComponent.DRIVEDECK.minCode && info.applicationInfo?.enabled == true &&
        pm.checkSignatures(context.packageName, pkg) == android.content.pm.PackageManager.SIGNATURE_MATCH
}

