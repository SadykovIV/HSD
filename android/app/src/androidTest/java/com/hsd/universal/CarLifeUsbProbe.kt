package com.hsd.universal

import android.app.Instrumentation
import android.app.Presentation
import android.content.Context
import android.media.AudioTrack
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import com.hsd.universal.aa.AaBridgeCore
import com.hsd.universal.aa.AaPeer
import com.hsd.universal.aa.AapMessages
import com.hsd.universal.aa.TouchPoint
import com.hsd.universal.aa.DesktopVideoSelection
import com.hsd.universal.aa.AaPrefs
import com.hsd.universal.aa.AaMeta
import com.hsd.universal.cluster.ClusterNowPlaying
import com.hsd.universal.cluster.GuidanceInterest
import com.hsd.universal.cluster.NavigationHintParser
import com.hsd.universal.aa.DesktopEncoderSupport
import com.hsd.universal.aa.DesktopResolution
import com.hsd.universal.aa.DesktopSurfaceListener
import com.hsd.universal.aa.SurfaceVideoInput
import com.hsd.universal.dex.DelegatedDexConnector
import com.hsd.universal.dex.CarLifePhoneIdleTimer
import com.hsd.universal.dex.DesktopAudioCapture
import com.hsd.universal.dex.DesktopPowerDiagnostics
import com.hsd.universal.dex.DexPrefs
import com.hsd.universal.dex.DexState
import com.hsd.universal.dex.DisplayEventAccess
import com.hsd.universal.dex.ProjectionTouchDispatcher
import com.hsd.universal.dex.OwnedDesktopDisplay
import com.hsd.universal.link.StreamTransport
import com.hsd.universal.link.TransportCaps
import java.net.InetSocketAddress
import java.net.Socket
import java.io.FilterOutputStream
import java.io.InterruptedIOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.LockSupport

// The head-unit fixture binds IPv4. Android 10 can resolve the default loopback to ::1.
private const val RECEIVER_LOOPBACK = "127.0.0.1"

/** Only the generated marker is rendered on this display. No apps, media capture or phone settings are involved. */
internal fun checkPrivateCarLifeTouch(test: Instrumentation, port: Int): String {
    require(port in 1..65535)
    val geometry = DesktopVideoSelection(DesktopResolution.AUTO, 0)
    val core = AaBridgeCore({ message, _ -> android.util.Log.i("HsdTouchProbe", message) }).apply {
        expectFeatureConfig = false
        videoMode = geometry.initialGeometry()
        densityOverride = geometry.densityFor(videoMode)
        usbAudioEnabled = false
    }
    val access = DisplayEventAccess()
    val panel = object : AaPeer, DesktopSurfaceListener {
        var display: OwnedDesktopDisplay? = null
        var page: Presentation? = null
        var owner = 0L
        var downTime = 0L
        val route = ProjectionTouchDispatcher({ id, e ->
            val target = display?.display
            check(id > 0 && target?.displayId == id && target.isValid)
            access.targetProjectionDisplay(e, id)
            test.uiAutomation.injectInputEvent(e, true)
        }, { android.util.Log.i("HsdTouchProbe", it) })
        override fun requestKeyFrame() {}
        @Synchronized override fun sendTouch(action: Int, actionIndex: Int, points: List<TouchPoint>) {
            val id = display?.display?.displayId ?: return
            val point = points.singleOrNull() ?: return
            check(id > 0 && actionIndex == 0 && point.id == 0)
            val now = SystemClock.uptimeMillis()
            if (action == MotionEvent.ACTION_DOWN) downTime = now
            val e = MotionEvent.obtain(downTime, now, action, point.x.toFloat(), point.y.toFloat(), 0)
            e.source = InputDevice.SOURCE_TOUCHSCREEN
            try { route.dispatch(id, e) } finally { e.recycle() }
        }
        override fun onSurfaceAvailable(generation: Long, input: SurfaceVideoInput) {
            synchronized(this) {
                check(display == null)
                owner = generation
                val vd = createTouchTestDisplay(test, "HSD Honda touch QA", input.width, input.height,
                    input.density.coerceAtLeast(120), input.surface)
                display = vd
                check(vd.display.displayId > 0)
                test.runOnMainSync {
                    page = Presentation(test.targetContext, vd.display).apply {
                        setContentView(carLifeTouchMarkerView(context, port))
                        show()
                    }
                }
            }
            core.desktopSurfaceReady(generation)
        }
        override fun onSurfaceClosing(generation: Long) {
            synchronized(this) {
                if (owner == generation) {
                    display?.let { route.finish(it.display.displayId) }
                    test.runOnMainSync { page?.dismiss(); page = null }
                    display?.release()
                    display = null
                    owner = 0
                }
            }
            core.desktopSurfaceProducerClosed(generation)
        }
    }
    core.onAaConnecting()
    core.peer = panel
    core.enableDesktopSurface(panel)
    return Socket().use { socket ->
        socket.connect(InetSocketAddress(RECEIVER_LOOPBACK, port), 5000)
        val link = StreamTransport(socket.getInputStream(), PacedBulk(socket, 2_600_000),
            TransportCaps.USB_AOA, "Private display · Honda USB fixture 2.6 MB/s", closeFd = socket::close)
        val closed = CountDownLatch(1)
        val config = SessionConfig(screenOn = false, metaMusic = false, metaNavi = false, desktopVideo = geometry,
            fpsSetting = com.hsd.universal.aa.FpsSetting.FPS30,
            desktopBitrateBps = 8_000_000, desktopAvc = DesktopEncoderSupport.surfaceEncoders("video/avc", 8_000_000))
        val session = CarLifeSession(link, config, core) { closed.countDown() }
        try {
            session.start()
            check(closed.await(110, TimeUnit.SECONDS)) { "Private receiver did not finish" }
            Socket().use { verdict ->
                verdict.connect(InetSocketAddress(RECEIVER_LOOPBACK, port), 5000)
                verdict.soTimeout = 20_000
                check(verdict.getInputStream().read() == 1) { "Receiver rejected touch-to-picture timing" }
            }
            "Private display, negotiated CarLife geometry, AVC decoded at the external receiver"
        } finally {
            session.close("Private display fixture complete")
            val stopped = CountDownLatch(1)
            core.stopDesktopSurface(stopped::countDown)
            check(stopped.await(15, TimeUnit.SECONDS)) { "Private encoder did not stop" }
        }
    }
}

/** Limits completion at the sender so TCP buffering cannot hide the cost of a USB write. */
private class PacedBulk(socket: Socket, private val bytesPerSecond: Int, private val stampTestPcm: Boolean = false) :
    FilterOutputStream(socket.getOutputStream()) {
    val maxWriteMs = java.util.concurrent.atomic.AtomicLong()
    @Volatile var writerPriority: Int? = null
        private set
    var due = 0L
    var began = 0L
    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        if (writerPriority == null) writerPriority = android.os.Process.getThreadPriority(android.os.Process.myTid())
        if (began == 0L) began = System.nanoTime()
        due = maxOf(due, System.nanoTime()) + length * 1_000_000_000L / bytesPerSecond
        while (true) {
            val left = due - System.nanoTime()
            if (left <= 0) break
            LockSupport.parkNanos(left)
            if (Thread.currentThread().isInterrupted) throw InterruptedIOException("Fixture stopped")
        }
        // Keep the numbered sample at the start. The receiver uses these spare test samples
        // to distinguish sender scheduling from the subsequent trip through the ADB fixture.
        if (stampTestPcm && offset == 0 && length == 12 + 1920 && Wire.u32(bytes, 8) == com.hsd.universal.aa.CarlifeAudio.MEDIA_DATA) {
            val stamp = java.nio.ByteBuffer.wrap(bytes)
            stamp.putInt(16, 0x48534450) // HSDP
            stamp.putLong(20, System.nanoTime())
        }
        out.write(bytes, offset, length)
    }
    override fun flush() {
        out.flush()
        if (began != 0L) maxWriteMs.accumulateAndGet((System.nanoTime() - began) / 1_000_000, ::maxOf)
        began = 0L
    }
}

/** Real CarLife input and timed PCM on one duplex bulk stream. Only the clocked media source is synthetic. */
internal fun checkCarLifeGestureAudio(port: Int, synchronize: Boolean = true): String {
    require(port in 1..65535)
    val core = AaBridgeCore({ _, _ -> }).apply { desktopInputEnabled = true }
    val single = AtomicInteger()
    val pinch = AtomicInteger()
    val transitions = java.util.concurrent.CopyOnWriteArrayList<Int>()
    val finished = CountDownLatch(1)
    core.peer = object : AaPeer {
        override fun requestKeyFrame() {}
        override fun sendTouch(action: Int, actionIndex: Int, points: List<TouchPoint>) {
            if (action == 2) (if (points.size == 2) pinch else single).incrementAndGet()
            else transitions.add(action)
        }
    }
    val geometry = DesktopVideoSelection(DesktopResolution.R1280_720, 0)
    return Socket().use { socket ->
        socket.connect(InetSocketAddress(RECEIVER_LOOPBACK, port), 5000)
        val bulk = PacedBulk(socket, 4_000_000, stampTestPcm = true)
        val transport = StreamTransport(socket.getInputStream(), bulk, TransportCaps.USB_AOA,
            "CarLife gesture fixture: 4 MB/s bulk", closeFd = socket::close)
        val session = CarLifeSession(transport, SessionConfig(desktopVideo = geometry, synchronizeAudioVideo = synchronize), core, onClosed = { finished.countDown() })
        try {
            session.start()
            session.hidePlaceholder()
            val deadline = SystemClock.elapsedRealtime() + 8_000L
            while (!core.isCarlifeStarted && SystemClock.elapsedRealtime() < deadline) Thread.sleep(5)
            check(core.isCarlifeStarted)
            val start = System.nanoTime()
            var maxSourceLateUs = 0L
            val pcm = ByteArray(1920) // 10 ms at 48 kHz, stereo, 16-bit
            repeat(2000) { tick ->
                val target = start + tick * 10_000_000L
                val wait = target - System.nanoTime()
                if (wait > 0) LockSupport.parkNanos(wait)
                maxSourceLateUs = maxOf(maxSourceLateUs, (System.nanoTime() - target) / 1000)
                if (tick % 4 == 0) {
                    // A detailed moving map generates larger access units during zoom.
                    val activePinch = pinch.get() > 0
                    val frame = ByteArray(if (activePinch && tick % 200 < 100) 240_000 else 2_000)
                    frame[3] = 1
                    frame[4] = if (tick % 20 == 0) 0x65 else 0x41
                    session.sendVideoAt(frame, target / 1000L)
                }
                pcm[0] = tick.toByte()
                pcm[1] = (tick ushr 8).toByte()
                core.onAudioData(AapMessages.CH_AUDIO_MEDIA, pcm, target / 1000L)
            }
            check(transport.audioExpiredPackets == 0L && transport.audioOverflowPackets == 0L) {
                "PCM expired=${transport.audioExpiredPackets} overflow=${transport.audioOverflowPackets} " +
                    "max source scheduling lateness=${maxSourceLateUs/1000}ms max whole-message write=${bulk.maxWriteMs.get()}ms"
            }
            check(finished.await(10, TimeUnit.SECONDS)) { "Receiver did not acknowledge the final PCM sample" }
            check(single.get() >= 50 && pinch.get() >= 100) { "single=$single pinch=$pinch" }
            check(transitions == buildList { addAll(listOf(0, 1, 0)); repeat(10) { add(5); add(6) }; add(1) }) {
                "Unexpected contact transitions: $transitions"
            }
            "single=$single pinch=$pinch; 2000 PCM packets; no expired or overflowing audio; " +
                "max source scheduling lateness=${maxSourceLateUs/1000}ms max whole-message write=${bulk.maxWriteMs.get()}ms writer nice=${bulk.writerPriority}"
        } finally { session.close("gesture fixture complete") }
    }
}

/** External receiver drives the encoder, camera return, music controls and HUD subscriptions. */
internal fun checkCarLifeHud(context: Context, port: Int) {
    require(port in 1..65535)
    val prefs = AaPrefs(context)
    check(prefs.metaNavi && prefs.metaNaviStreetNames)
    val core = AaBridgeCore({ _, _ -> }).apply { usbAudioEnabled = false }
    val started = CountDownLatch(1)
    val stopped = CountDownLatch(1)
    Socket().use { socket ->
        socket.connect(InetSocketAddress(RECEIVER_LOOPBACK, port), 5000)
        val transport = StreamTransport.fromPfd(ParcelFileDescriptor.fromSocket(socket), TransportCaps.USB_AOA, "CarLife HUD fixture")
        lateinit var session: CarLifeSession
        session = CarLifeSession(transport, SessionConfig(metaNaviStreetNames = prefs.metaNaviStreetNames), core,
            onNavigationInterestChanged = {
                when (session.guidanceInterest.mode) {
                    GuidanceInterest.Mode.REQUESTED -> {
                        session.onClusterMedia(ClusterNowPlaying("Fixture track", "", "", true, positionSeconds = 12))
                        val compact = checkNotNull(NavigationHintParser.parse("180 м", "", AaMeta.T_RIGHT, true))
                        val expanded = checkNotNull(NavigationHintParser.parse("180 м", "улица Мира", AaMeta.T_RIGHT, true))
                        session.onClusterNavigation(NavigationHintParser.fromCardVariants(sequenceOf(compact, expanded), true))
                    }
                    GuidanceInterest.Mode.ACTIVE -> started.countDown()
                    GuidanceInterest.Mode.NONE -> stopped.countDown()
                }
            }, onClosed = {})
        try {
            session.start()
            check(started.await(20, TimeUnit.SECONDS)) { "Missing navigation START" }
            check(stopped.await(25, TimeUnit.SECONDS)) { "Receiver did not finish the camera and HUD sequence" }
        } finally { session.close("HUD fixture complete") }
    }
}

/** The outside receiver speaks the USB wire format. Only the bulk file descriptor is supplied by the fixture. */
internal fun checkCarLifeBulk(test: Instrumentation, port: Int, tone: () -> AudioTrack, paced: Boolean, fixed720: Boolean, desktopReady: (Int) -> Boolean): Bundle {
    val context = test.targetContext
    val prefs = DexPrefs(context)
    val selection = if (fixed720) DesktopVideoSelection(DesktopResolution.R1280_720, prefs.desktopVideoSelection().densityOverride)
        else prefs.desktopVideoSelection().also { check(it.resolution == DesktopResolution.AUTO) }
    val core = AaBridgeCore({ message, ui -> AppLog.i(message, ui) }).apply {
        desktopInputEnabled = true
        transcodeEnabled = true
        expectFeatureConfig = false
        mapHomeKey = true
        videoMode = selection.initialGeometry()
        densityOverride = selection.densityFor(videoMode)
    }
    val source = DelegatedDexConnector(context, core) {}
    val closed = CountDownLatch(1)
    val sourceStopped = CountDownLatch(1)
    return Socket().use { socket ->
        socket.connect(InetSocketAddress(RECEIVER_LOOPBACK, port), 5000)
        val transport = if (paced) StreamTransport(socket.getInputStream(), PacedBulk(socket, 2_600_000),
            TransportCaps.USB_AOA, "USB bulk fixture 2.6 MB/s", closeFd = socket::close)
            else StreamTransport.fromPfd(ParcelFileDescriptor.fromSocket(socket), TransportCaps.USB_AOA, "USB bulk fixture")
        val session = CarLifeSession(transport, SessionConfig(desktopVideo = selection, desktopBitrateBps = prefs.desktopBitrateMbps * 1_000_000,
            fpsSetting = prefs.desktopFps, synchronizeAudioVideo = prefs.synchronizeAudioVideo,
            desktopAvc = DesktopEncoderSupport.surfaceEncoders("video/avc", prefs.desktopBitrateMbps * 1_000_000)), core) { closed.countDown() }
        var desktop = false
        var picture = false
        var touch = false
        var size = ""
        val capture = DesktopAudioCapture(context)
        val player = tone()
        val panelRequests = AtomicInteger()
        val idle = if (!paced && !prefs.desktopPanelControl) CarLifePhoneIdleTimer(context,
            { DexState.running && session.hasFreshDesktopVideo() }, { panelRequests.incrementAndGet(); false }) else null
        val power = idle?.let { DesktopPowerDiagnostics(context) }
        try {
            session.start()
            source.start()
            test.runOnMainSync {
                capture.attach(core, true, restartAfterCalls = true, canCapture = session::canCaptureDesktopAudio)
                idle?.start()
                power?.start()
            }
            player.play()
            // The receiver closes after setup, two screen-timeout observations or
            // the full 72-contact run. Each observation has its own deadline.
            val until = SystemClock.elapsedRealtime() + 120_000
            while (closed.count > 0 && SystemClock.elapsedRealtime() < until) {
                if (DexState.displayId > 0 && !desktop) desktop = desktopReady(DexState.displayId)
                if (session.hasFreshDesktopVideo()) {
                    picture = true
                    size = "${core.videoMode.outW}x${core.videoMode.outH}"
                }
                if (CarLifeState.touch != "-") touch = true
                closed.await(150, TimeUnit.MILLISECONDS)
            }
            check(closed.count == 0L && desktop && picture && touch) {
                "CarLife USB desktop=$desktop picture=$picture touch=$touch size=$size · ${DexState.error}"
            }
            if (paced) {
                // The external decoder alone can match every wire DOWN to its picture.
                Socket().use { verdict ->
                    verdict.connect(InetSocketAddress(RECEIVER_LOOPBACK, port), 5000)
                    verdict.soTimeout = 20_000
                    check(verdict.getInputStream().read() == 1) { "Receiver rejected touch-to-picture timing" }
                }
            }
            Bundle().apply {
                if (idle != null) {
                    check(panelRequests.get() == 0 && !CarLifePhoneIdleTimer.ownsPhysicalTimer)
                    putString("phone_panel", "panel control disabled; zero panel requests")
                }
                putString("CarLife_bulk", "$size AVC, ${com.hsd.universal.dex.DesktopModePolicy.label(prefs.desktopMode)}, receiver touch")
                putString("result", "PASS")
            }
        } finally {
            player.release()
            test.runOnMainSync { idle?.stop(); power?.stop(); capture.close() }
            session.close("USB fixture complete")
            source.stop { sourceStopped.countDown() }
            check(sourceStopped.await(15, TimeUnit.SECONDS)) { "Desktop source did not stop" }
        }
    }
}
