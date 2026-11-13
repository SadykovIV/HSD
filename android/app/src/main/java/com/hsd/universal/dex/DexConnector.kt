package com.hsd.universal.dex

import com.hsd.universal.R
import com.hsd.universal.DesktopTransport

import android.content.Context
import android.os.Build
import com.flyfishxu.kadb.Kadb
import com.flyfishxu.kadb.stream.AdbStream
import com.hsd.universal.AppLog
import com.hsd.universal.aa.AaBridgeCore
import com.hsd.universal.aa.AaLifecycle
import com.hsd.universal.aa.AaPeer
import com.hsd.universal.aa.AaState
import com.hsd.universal.aa.SpsCrop
import com.hsd.universal.aa.TouchPoint
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import okio.source

/** Experimental Samsung desktop -> scrcpy H.264 -> existing HSD CarLife pipeline. */
class DexConnector(context: Context, private val core: AaBridgeCore,
    private val onFailed: () -> Unit = {}) : AaLifecycle, AaPeer, com.hsd.universal.aa.TerminalSource {
    private val context = context.applicationContext
    private val started = AtomicBoolean(false)
    private val stopping = AtomicBoolean(false)
    override val isTerminated: Boolean get() = stopping.get()
    private val finished = CountDownLatch(1)
    private val resources = CopyOnWriteArrayList<AutoCloseable>()
    private val controlQueue = LinkedBlockingQueue<ByteArray>(128)
    private val hardwareBack = DesktopBackSequence()
    private val commands = Executors.newSingleThreadExecutor { r -> Thread(r, "dex-owned-actions").apply { isDaemon = true } }
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    @Volatile private var adbConnection: Kadb? = null
    private val scheduler = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "dex-watchdog").apply { isDaemon = true } }
    private val lastVideoAt = AtomicLong(System.nanoTime())
    private val lastResetAt = AtomicLong(0)
    private val displayReady = CountDownLatch(1)
    private val sourceWidth = if (DesktopTransport.universal && !com.hsd.universal.ProjectionRoute.selected(context).carLife) DexVideoGeometry.WIDTH else core.videoMode.aaW
    private val sourceHeight = if (DesktopTransport.universal && !com.hsd.universal.ProjectionRoute.selected(context).carLife) DexVideoGeometry.HEIGHT else core.videoMode.aaH
    private val touch = DexTouchMapper(sourceWidth, sourceHeight)
    @Volatile private var worker: Thread? = null
    @Volatile private var controlReady = false
    @Volatile private var streaming = false
    @Volatile private var displayId = -1
    @Volatile private var failure: String? = null

    override fun start() {
        if (!started.compareAndSet(false, true)) return
        if (stopping.get()) { finished.countDown(); return }
        DexState.error = null
        DexState.running = false
        DexState.displayId = -1
        DexState.independentPhoneLock = null
        worker = Thread({ run() }, "dex-connector").apply { isDaemon = true; start() }
    }

    override fun stop(onDone: () -> Unit) {
        stopping.set(true)
        closeResources()
        worker?.interrupt()
        if (!started.get()) finished.countDown()
        Thread({
            try { finished.await(20, TimeUnit.SECONDS) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
            onDone()
        }, "dex-stop").apply { isDaemon = true; start() }
    }

    private fun run() {
        var established = false
        try {
            check(Build.MANUFACTURER.equals("samsung", ignoreCase = true) && Build.VERSION.SDK_INT >= 36) {
                context.getString(R.string.desktop_this_prototype_is_designed_for_samsung_with_one_ui)
            }
            check(core.videoMode.marginW == 0 && core.videoMode.marginH == 0) { context.getString(R.string.desktop_for_dex_you_need_video_mode_without_android_auto) }
            core.onAaConnecting()
            core.peer = this
            setStatus(context.getString(R.string.desktop_connecting_to_wireless_debugging))
            val startAt = System.nanoTime()
            scheduler.scheduleWithFixedDelay({
                if (stopping.get()) return@scheduleWithFixedDelay
                try {
                    core.tick()
                    core.desktopVideoError?.let { fail(it); return@scheduleWithFixedDelay }
                    val silent = System.nanoTime() - if (streaming) lastVideoAt.get() else startAt
                    if (silent > TimeUnit.SECONDS.toNanos(if (streaming) 15 else 35)) {
                        fail(context.getString(R.string.desktop_dex_doesn_t_transmit_video_check_the_wireless_debugging))
                    }
                } catch (e: Exception) { fail(e.message ?: context.getString(R.string.desktop_dex_control_error)) }
            }, 500, 500, TimeUnit.MILLISECONDS)
            val prefs = DexPrefs(context)
            val adb = own(DexAccess.connect(context, prefs.connectPort))
            DexAccess.verifyShell(adb, prefs.host, prefs.connectPort)
            ensureActive()
            adbConnection = adb
            // Recover only an interrupted 0.4.1 change. Normal sessions never write display 0.
            val userId = context.applicationInfo.uid / 100000
            val pending = adb.shell("settings --user $userId get secure ${LegacyDensityRecovery.JOURNAL}")
            if (pending.exitCode == 0 && pending.output.trim() !in setOf("", "null")) {
                val apk = DexOverlayPolicy.quote(context.applicationInfo.sourceDir)
                val repaired = adb.shell("CLASSPATH=$apk /system/bin/app_process / " +
                    "${LegacyDensityRecovery::class.java.name} ${context.applicationInfo.uid}")
                check(repaired.exitCode == 0) { context.getString(R.string.desktop_unable_to_recover_the_previous_dpi_from_the_saved, DesktopTransport.shortName) }
            }
            ensureActive()

            val bytes = context.assets.open(ASSET).use { it.readBytes() }
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            check(hash == SERVER_SHA256) { context.getString(R.string.desktop_incorrect_scrcpy_server_checksum) }
            // Fixed destination in shell's private directory, no downloaded code at runtime.
            bytes.inputStream().source().use { adb.push(it, REMOTE, 0x180, 0L) } // 0600
            ensureActive()
            val scid = SecureRandom().nextInt(Int.MAX_VALUE).toString(16).padStart(8, '0')
            val mode = core.videoMode
            val density = core.densityOverride
            DexUserServicePolicy.requireDensity(density)
            require(mode.aaW in 320..4096 && mode.aaH in 240..2160)
            val command = "CLASSPATH=$REMOTE app_process / com.genymobile.scrcpy.Server ${ScrcpyProtocol.VERSION} " +
                "scid=$scid log_level=info audio=false control=true video=true video_codec=h264 " +
                "tunnel_forward=true send_device_meta=false send_dummy_byte=false " +
                "new_display=${sourceWidth}x${sourceHeight}/$density display_ime_policy=local " +
                "video_bit_rate=${DexVideoGeometry.SOURCE_BITRATE} max_fps=30 " +
                "video_codec_options=i-frame-interval=1,profile=1,max-bframes=0 " +
                "clipboard_autosync=false power_on=false cleanup=true vd_destroy_content=true"
            setStatus(context.getString(R.string.desktop_creating_a_dex_screen))
            val logStream = own(adb.open("shell:$command"))
            Thread({ readServerLog(logStream) }, "dex-server-log").apply { isDaemon = true; start() }
            val endpoint = "localabstract:scrcpy_$scid"
            val video = connectStream(adb, endpoint)
            val control = connectStream(adb, endpoint)
            check(displayReady.await(12, TimeUnit.SECONDS)) { context.getString(R.string.desktop_scrcpy_did_not_provide_a_virtual_display_id) }
            ensureActive()
            check(displayId > 0) { context.getString(R.string.desktop_you_cannot_use_the_main_screen_instead_of_dex) }
            // scrcpy owns this secondary display. Verify that Android applied the requested DPI.
            val displays = context.getSystemService(android.hardware.display.DisplayManager::class.java)
            val external = checkNotNull(displays.getDisplay(displayId)) { context.getString(R.string.desktop_created_dex_display_is_not_available) }
            @Suppress("DEPRECATION")
            val actual = android.util.DisplayMetrics().also(external::getRealMetrics).densityDpi
            DexUserServicePolicy.requireProjectionDensity(actual, density)
            // One UI 8.5/9 may default new displays to fullscreen. This only changes our display.
            val freeform = adb.shell("wm set-display-windowing-mode -d $displayId 5")
            check(freeform.exitCode == 0) { context.getString(R.string.desktop_the_firmware_did_not_allow_window_mode_dex, freeform.allOutput.take(250)) }
            val checkMode = adb.shell("wm get-display-windowing-mode -d $displayId")
            check(checkMode.exitCode == 0 && (checkMode.allOutput.contains("freeform", true) || checkMode.allOutput.trim() == "5")) {
                context.getString(R.string.desktop_the_window_mode_could_not_be_confirmed, checkMode.allOutput.take(250))
            }
            // One UI 9 can leave a new decorated display empty until its desktop HOME
            // is launched explicitly. Target only the display created by this session.
            setStatus(context.getString(R.string.desktop_opening_the_dex_desktop))
            val desktopHome = adb.shell("am start --display $displayId -a android.intent.action.MAIN " +
                "-c android.intent.category.SECONDARY_HOME -n $DESKTOP_HOME")
            check(desktopHome.exitCode == 0 && desktopHome.allOutput.lineSequence().none {
                it.trimStart().startsWith("Error", ignoreCase = true) || it.contains("Exception")
            }) { context.getString(R.string.desktop_it_was_not_possible_to_open_the_samsung_dex, desktopHome.allOutput.take(250)) }
            startControl(control)
            established = true
            val reader = ScrcpyProtocol.VideoReader(video.source.inputStream())
            reader.readCodec()
            val geometry = DexVideoGeometry()
            AppLog.i("DEX geometry sourceBitrate=${DexVideoGeometry.SOURCE_BITRATE} carlifeBitrateCeiling=${mode.bitrate} density=$density", ui = false)
            fun logSourceGeometry(infos: List<SpsCrop.Info>) {
                for (info in infos) AppLog.i("DEX geometry stage=source-sps display=$displayId coded=${info.codedW}x${info.codedH} visible=${info.visibleW}x${info.visibleH} crop=${info.left},${info.right},${info.top},${info.bottom} native720=true", ui = false)
            }
            while (!stopping.get()) {
                core.desktopVideoError?.let { throw IOException(it) }
                when (val packet = reader.next()) {
                    is ScrcpyProtocol.Packet.Session -> {
                        geometry.acceptSession(packet.width, packet.height)
                        AppLog.i("DEX geometry stage=scrcpy-session display=$displayId size=${packet.width}x${packet.height} expected=${DexVideoGeometry.WIDTH}x${DexVideoGeometry.HEIGHT}", ui = false)
                    }
                    is ScrcpyProtocol.Packet.Video -> {
                        lastVideoAt.set(System.nanoTime())
                        if (packet.config) {
                            logSourceGeometry(geometry.acceptConfig(packet.bytes))
                            core.onCodecConfig(packet.bytes)
                        } else {
                            logSourceGeometry(geometry.beforeFrame(packet.bytes))
                            core.onFrame(packet.bytes, packet.ptsUs)
                            core.desktopVideoError?.let { throw IOException(it) }
                            if (!streaming) {
                                streaming = true
                                DexState.running = true
                                DexState.displayId = displayId
                                setStatus(context.getString(R.string.desktop_dex_bluetooth, DesktopTransport.name, mode.aaW, mode.aaH), placeholder = false)
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            if (!stopping.get()) {
                failure = e.message ?: e.javaClass.simpleName
                AppLog.e("DeX: $failure", e)
            }
        } finally {
            stopping.set(true)
            closeResources()
            controlReady = false
            controlQueue.clear()
            scheduler.shutdownNow()
            if (core.peer === this) core.peer = null
            core.onAaEnded(established)
            DexState.running = false
            DexState.displayId = -1
            DexState.independentPhoneLock = null
            DexState.error = failure
            setStatus(failure?.let { "DeX: $it" } ?: context.getString(R.string.desktop_dex_has_been_stopped))
            finished.countDown()
            if (failure != null) onFailed()
        }
    }

    private fun setStatus(text: String, placeholder: Boolean = true) {
        DexState.status = text
        AaState.status = text
        if (placeholder) core.showStatus(text)
        AppLog.i("DeX: $text")
    }

    private fun readServerLog(stream: AdbStream) {
        try {
            while (!stopping.get()) {
                val line = stream.source.readUtf8LineStrict(8192)
                val found = DISPLAY_ID.find(line)?.groupValues?.get(1)?.toIntOrNull()
                if (found != null && found > 0) { displayId = found; displayReady.countDown() }
                AppLog.i("scrcpy: ${line.take(1000)}", ui = false)
            }
        } catch (e: Exception) {
            if (!stopping.get()) fail(context.getString(R.string.desktop_the_dex_process_has_concluded, e.message.orEmpty().take(200)))
        }
    }

    private fun connectStream(adb: Kadb, endpoint: String): AdbStream {
        var error: Exception? = null
        repeat(30) {
            ensureActive()
            try { return own(adb.open(endpoint)) } catch (e: Exception) { error = e }
            Thread.sleep(200)
        }
        throw IOException(context.getString(R.string.desktop_unable_to_connect_to_scrcpy_on_your_phone), error)
    }

    private fun startControl(stream: AdbStream) {
        controlReady = true
        Thread({
            try {
                while (!stopping.get()) {
                    val packet = controlQueue.poll(500, TimeUnit.MILLISECONDS) ?: continue
                    stream.sink.write(packet)
                    stream.sink.flush()
                }
            } catch (e: Exception) { if (!stopping.get()) fail(context.getString(R.string.desktop_lost_dex_control_channel)) }
        }, "dex-input").apply { isDaemon = true; start() }
        // Even with clipboard disabled, drain server messages so its sender never blocks.
        Thread({
            try { while (!stopping.get()) stream.source.readByte() }
            catch (e: Exception) { if (!stopping.get()) fail(context.getString(R.string.desktop_the_dex_control_channel_has_closed)) }
        }, "dex-control-drain").apply { isDaemon = true; start() }
    }

    override fun sendTouch(action: Int, actionIndex: Int, points: List<TouchPoint>) {
        if (!controlReady || stopping.get()) return
        synchronized(hardwareBack) { hardwareBack.reset() }
        touch.encode(action, actionIndex, points).forEach(::enqueue)
    }

    override fun sendRotary(delta: Int) { DesktopRotaryInput.keys(delta).forEach(::sendKey) }

    override fun sendKey(keycode: Int) {
        if (keycode == 65538 || keycode == android.view.KeyEvent.KEYCODE_MUSIC) {
            synchronized(hardwareBack) { hardwareBack.reset() }
            // These are launcher commands. Injecting MUSIC into an arbitrary DeX window
            // can open the phone's default app; this profile has no owned category picker.
            return
        }
        if (keycode !in 0..1000 || !controlReady || stopping.get()) return
        val home = synchronized(hardwareBack) {
            if (keycode == android.view.KeyEvent.KEYCODE_BACK) {
                val now = android.os.SystemClock.uptimeMillis()
                hardwareBack.down(now)
                hardwareBack.up(now)
            } else { hardwareBack.reset(); false }
        }
        if (home || keycode == android.view.KeyEvent.KEYCODE_HOME) { showDesktop(); return }
        if (keycode == android.view.KeyEvent.KEYCODE_SEARCH || keycode == android.view.KeyEvent.KEYCODE_VOICE_ASSIST) {
            val requestedDisplay = displayId
            main.post {
                DesktopAssistant.launch(context, requestedDisplay, setOf(requestedDisplay)) {
                    !stopping.get() && controlReady && streaming && displayId == requestedDisplay
                }
            }
            return
        }
        enqueue(ScrcpyProtocol.key(0, keycode))
        enqueue(ScrcpyProtocol.key(1, keycode))
    }

    private fun showDesktop(): Boolean {
        val connection = adbConnection ?: return false
        val id = displayId
        if (!controlReady || stopping.get() || id <= 0) return false
        return runCatching {
            commands.execute {
                if (stopping.get() || adbConnection !== connection || displayId != id) return@execute
                runCatching {
                    val command = DesktopModePolicy.homeCommand(DesktopModePolicy.SAMSUNG_DEX, id, context.packageName)
                        .joinToString(" ", transform = DexOverlayPolicy::quote)
                    val result = connection.shell(command)
                    check(result.exitCode == 0) { "Samsung desktop launch was rejected" }
                }.onFailure { AppLog.i("DEX HOME: owned display launch failed (${it.javaClass.simpleName})", false) }
            }
            true
        }.getOrDefault(false)
    }

    override fun requestKeyFrame() {
        if (!controlReady || stopping.get()) return
        val now = System.nanoTime()
        val previous = lastResetAt.get()
        // scrcpy RESET_VIDEO restarts the encoder; periodic IDRs already arrive every second.
        if (now - previous > TimeUnit.SECONDS.toNanos(2) && lastResetAt.compareAndSet(previous, now)) enqueue(byteArrayOf(17))
    }

    private fun enqueue(packet: ByteArray) {
        if (!controlReady || stopping.get()) return
        if (!controlQueue.offer(packet)) fail(context.getString(R.string.desktop_dex_control_queue_overflowed_connection_halted))
    }

    private fun ensureActive() { check(!stopping.get()) { context.getString(R.string.desktop_dex_has_been_stopped) } }

    private fun <T : AutoCloseable> own(resource: T): T {
        resources.add(resource)
        if (stopping.get()) { resource.close(); resources.remove(resource); throw IOException(context.getString(R.string.desktop_dex_has_been_stopped)) }
        return resource
    }

    private fun fail(message: String) {
        if (stopping.compareAndSet(false, true)) {
            failure = message
            closeResources()
            worker?.interrupt()
        }
    }

    private fun closeResources() {
        adbConnection = null
        commands.shutdownNow()
        synchronized(hardwareBack) { hardwareBack.reset() }
        resources.reversed().forEach { runCatching { it.close() } }
        resources.clear()
    }

    companion object {
        const val SERVER_SHA256 = "deacb991ed2509715160ffdc7907e47b4160eb30d1566217e9047fd5b8850cae"
        private const val ASSET = "dex/scrcpy-server-v4.1"
        private const val REMOTE = "/data/local/tmp/com.hsd.universal-scrcpy-4.1.jar"
        private const val DESKTOP_HOME = "com.sec.android.app.launcher/com.honeyspace.dexservice.SecondaryLauncher"
        private val DISPLAY_ID = Regex("New display: .*\\(id=(\\d+)\\)")
    }
}
