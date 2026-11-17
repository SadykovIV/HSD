package com.hsd.universal

import android.app.Instrumentation
import android.content.Context
import android.media.AudioTrack
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
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
import com.hsd.universal.dex.DelegatedDexConnector
import com.hsd.universal.dex.DesktopAudioCapture
import com.hsd.universal.dex.DexPrefs
import com.hsd.universal.dex.DexState
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
        val maxWriteMs = java.util.concurrent.atomic.AtomicLong()
        // Bound completion of bulk writes, as with a USB endpoint. Throttling only the
        // host's TCP reader would instead benchmark the opaque megabyte-sized ADB buffers.
        val bulk = object : FilterOutputStream(socket.getOutputStream()) {
            var due = 0L
            var began = 0L
            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                if (began == 0L) began = System.nanoTime()
                due = maxOf(due, System.nanoTime()) + length * 1_000_000_000L / 4_000_000
                while (true) {
                    val left = due - System.nanoTime()
                    if (left <= 0) break
                    LockSupport.parkNanos(left)
                    if (Thread.currentThread().isInterrupted) throw InterruptedIOException("Fixture stopped")
                }
                out.write(bytes, offset, length)
            }
            override fun flush() {
                out.flush()
                if (began != 0L) maxWriteMs.accumulateAndGet((System.nanoTime() - began) / 1_000_000, ::maxOf)
                began = 0L
            }
        }
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
                    "max source scheduling lateness=${maxSourceLateUs/1000}ms max whole-message write=${maxWriteMs.get()}ms"
            }
            check(finished.await(10, TimeUnit.SECONDS)) { "Receiver did not acknowledge the final PCM sample" }
            check(single.get() >= 50 && pinch.get() >= 100) { "single=$single pinch=$pinch" }
            check(transitions == buildList { addAll(listOf(0, 1, 0)); repeat(10) { add(5); add(6) }; add(1) }) {
                "Unexpected contact transitions: $transitions"
            }
            "single=$single pinch=$pinch; 2000 PCM packets; no expired or overflowing audio; " +
                "max source scheduling lateness=${maxSourceLateUs/1000}ms max whole-message write=${maxWriteMs.get()}ms"
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
internal fun checkCarLifeBulk(test: Instrumentation, port: Int, tone: () -> AudioTrack, desktopReady: (Int) -> Boolean): Bundle {
    val context = test.targetContext
    val prefs = DexPrefs(context)
    val selection = prefs.desktopVideoSelection()
    check(selection.resolution == DesktopResolution.AUTO)
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
        val transport = StreamTransport.fromPfd(ParcelFileDescriptor.fromSocket(socket), TransportCaps.USB_AOA, "USB bulk fixture")
        val session = CarLifeSession(transport, SessionConfig(desktopVideo = selection, desktopBitrateBps = prefs.desktopBitrateMbps * 1_000_000,
            fpsSetting = prefs.desktopFps, synchronizeAudioVideo = prefs.synchronizeAudioVideo,
            desktopAvc = DesktopEncoderSupport.surfaceEncoders("video/avc", prefs.desktopBitrateMbps * 1_000_000)), core) { closed.countDown() }
        var desktop = false
        var picture = false
        var touch = false
        var size = ""
        val capture = DesktopAudioCapture(context)
        val player = tone()
        try {
            session.start()
            source.start()
            test.runOnMainSync { capture.attach(core, true, restartAfterCalls = true, canCapture = session::canCaptureDesktopAudio) }
            player.play()
            val until = SystemClock.elapsedRealtime() + 65_000
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
            Bundle().apply {
                putString("CarLife_bulk", "$size AVC, ${com.hsd.universal.dex.DesktopModePolicy.label(prefs.desktopMode)}, receiver touch")
                putString("result", "PASS")
            }
        } finally {
            player.release()
            test.runOnMainSync(capture::close)
            session.close("USB fixture complete")
            source.stop { sourceStopped.countDown() }
            check(sourceStopped.await(15, TimeUnit.SECONDS)) { "Desktop source did not stop" }
        }
    }
}
