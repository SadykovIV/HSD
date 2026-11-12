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
import net.prototux.aare.aap.Transport
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
    private var carPlayRadio: Bundle? = null
    private var carLifeUsbPort: Int? = null
    private var carPlay = false
    private var shizuku = false
    private var modern = false
    private var screen: ProjectionScreenCase? = null
    private var displayOnly = false
    private var oldUsbFrames = 0
    private var hevc = false
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        checkCertificates = arguments?.getString("protocol") == "certificates"
        wirelessReceiver = arguments?.getString("protocol") == "wireless-receiver"
        carPlayRadio = arguments?.takeIf { it.getString("protocol") in setOf("carplay-radio-receiver", "carplay-radio-sender") }
        carPlay = arguments?.getString("protocol") == "carplay"
        if (arguments?.getString("protocol") == "carlife-usb")
            carLifeUsbPort = arguments.getString("fixturePort")?.toInt() ?: 7240
        shizuku = arguments?.getString("backend") == "shizuku"
        modern = arguments?.getString("protocol") == "6.1"
        hevc = arguments?.getString("codec") == "h265"
        val name = arguments?.getString("screen")
        displayOnly = name?.endsWith("-display") == true
        screen = name?.removeSuffix("-display")?.let(ProjectionScreenCase::named)
        oldUsbFrames = arguments?.getString("oldUsbFrames")?.toInt() ?: 0
        start()
    }
    override fun onStart() {
        val result = Bundle()
        val statuses = java.util.concurrent.CopyOnWriteArrayList<String>()
        try {
            check(Build.HARDWARE in listOf("ranchu", "goldfish")) { "Disposable emulator required" }
            val context = targetContext
            if (checkCertificates) {
                finish(Activity.RESULT_OK, checkReceiverIdentities(context)); return
            }
            if (wirelessReceiver) {
                result.putAll(WirelessHeadUnitProbe(context).run()); finish(Activity.RESULT_OK,result);return
            }
            carPlayRadio?.let { args ->
                val probe = CarPlayRadioProbe(context,args) {
                    sendStatus(1,Bundle().apply { putString("CarPlay_fixture","listening") })
                }
                val activity = if (args.getString("protocol") == "carplay-radio-sender")
                    startActivitySync(Intent(context,ProjectionSettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                else null
                try { result.putAll(probe.run()) }
                finally { activity?.let { runOnMainSync(it::finish) } }
                finish(Activity.RESULT_OK,result)
                return
            }
            if (carPlay) {
                result.putAll(CarPlayNetworkProbe(context).run())
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
                DesktopAudioPolicy.PERMISSION, DesktopAudioPolicy.ROUTING_PERMISSION, "android.permission.DUMP")
            val lease = if (shizuku) null else DelegatedDesktopAccess.begin {}
            try {
                (if (shizuku) null else VdmProbeHost.start(this, ui)).use {
                    DexPrefs(context).apply {
                        desktopMode = DesktopModePolicy.DRIVEDECK
                        if (shizuku) useShizuku = true else useDelegated = true
                        usbPlaybackAudio = !shizuku
                    }
                    check(installedDriveDeck(context)) { "Install the matching DriveDeck companion" }
                    val fixtureApps = setOf("com.android.settings", "com.google.android.deskclock")
                    fun splitReady(display: Int) = tasks(context).let { activities ->
                        activities.any { it.first == display && it.second == DesktopModePolicy.DRIVEDECK_HOME_CLASS } &&
                            activities.filter { it.first > 0 && it.first != display }.map { it.third }.containsAll(fixtureApps)
                    }
                    carLifeUsbPort?.let { port ->
                        result.putAll(checkCarLifeBulk(this, port, ::fixtureAudio, ::splitReady))
                        finish(Activity.RESULT_OK, result)
                        return
                    }
                    if (displayOnly) {
                        val spec = checkNotNull(screen)
                        result.putAll(checkDriveDeckDisplay(context, spec.desktopW, spec.desktopH, ::splitReady))
                        return@use
                    }
                    val pair = ParcelFileDescriptor.createSocketPair()
                    val phone = localPipe(pair[0])
                    val car = localPipe(pair[1])
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
                        val hu = HeadUnitSimulator(car, width = test?.panelW ?: 1280, height = test?.panelH ?: 720,
                            previousUsbFrames = oldUsbFrames, offeredModes = test?.modes, offeredIndices = test?.indices ?: listOf(0), maxUnacked = if (modern) 1 else 4, modernNoAcks = modern, videoOut = video, videoCodec = codec)
                        val projection = AutoProjection(context, phone, identity.phone("qa-wired"), pins, "qa-wired",
                            { statuses.add(it) }, { statuses.add(it); closed.countDown() })
                        try {
                            runOnMainSync { projection.start() }
                            hu.start()
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
                            val monitor = addMonitor(DriveDeckFullscreenActivity::class.java.name, null, false)
                            try {
                                val settings = checkNotNull(context.packageManager.getLaunchIntentForPackage("com.android.settings")?.component)
                                context.startActivity(Intent(context, DriveDeckFullscreenActivity::class.java)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("component", settings.flattenToString())
                                    .putExtra("back", true).putExtra("home", true),
                                    ActivityOptions.makeBasic().setLaunchDisplayId(display).toBundle())
                                val fullscreen = checkNotNull(monitor.waitForActivityWithTimeout(8_000))
                                val point = IntArray(2)
                                await("visible fullscreen Home button") {
                                    var ready = false
                                    runOnMainSync {
                                        homeButton(fullscreen.window.decorView)?.takeIf { it.isShown && it.width > 0 }?.let {
                                            it.getLocationOnScreen(point)
                                            point[0] += it.width / 2
                                            point[1] += it.height / 2
                                            ready = true
                                        }
                                    }
                                    ready
                                }
                                val tap = test?.point(point[0], point[1]) ?: (point[0] to point[1])
                                hu.tap(tap.first, tap.second)
                                await("AAP tap returning to split view") { splitReady(display) }
                                result.putString("AAP_touch", "Fullscreen Home restored DriveDeck and both apps")
                            } finally { removeMonitor(monitor) }
                            if (!shizuku) {
                                await("USB playback capture · " + DesktopAudioState.status) { DesktopAudioState.running }
                                val tone = fixtureAudio()
                                try {
                                    tone.play()
                                    await("non-silent AAP audio") { hu.nonSilentAudioChunks.get() > 0 }
                                    result.putInt("AAP_audio_packets_with_signal", hu.nonSilentAudioChunks.get())
                                } finally { tone.release() }
                            }
                            hu.requestFocusNative()
                            SystemClock.sleep(350)
                            hu.requestFocusProjected()
                            val resumed = hu.videoFrames.get()
                            await { hu.videoFrames.get() >= resumed + 3 }
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
                            runOnMainSync { projection.stop() }
                            hu.stop()
                            closed.await(8, TimeUnit.SECONDS)
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
    private fun localPipe(fd: ParcelFileDescriptor) = object : Transport {
        override val usbAccessory = oldUsbFrames > 0
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
    private fun homeButton(view: View): View? {
        if (view.contentDescription == "Домой в DriveDeck") return view
        if (view is ViewGroup) for (i in 0 until view.childCount) homeButton(view.getChildAt(i))?.let { return it }
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
    private fun await(stage: String = "video", ok: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 18_000
        while (!ok() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100)
        check(ok()) { "Timed out waiting for $stage · ${DexState.error} · ${DesktopAudioState.status}" }
    }
}

internal fun installedDriveDeck(context: android.content.Context): Boolean {
    val pm = context.packageManager
    val pkg = DesktopModePolicy.DRIVEDECK_PACKAGE
    val info = try { pm.getPackageInfo(pkg, 0) } catch (_: android.content.pm.PackageManager.NameNotFoundException) { return false }
    return info.longVersionCode >= com.hsd.universal.update.DesktopComponent.DRIVEDECK.minCode && info.applicationInfo?.enabled == true &&
        pm.checkSignatures(context.packageName, pkg) == android.content.pm.PackageManager.SIGNATURE_MATCH
}

