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
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.LockSupport

/** Real CarLife input and timed PCM on one duplex bulk stream. Only the clocked media source is synthetic. */
internal fun checkCarLifeGestureAudio(port: Int): String {
    require(port in 1..65535)
    val core = AaBridgeCore({ _, _ -> }).apply { desktopInputEnabled = true }
    val single = AtomicInteger()
    val pinch = AtomicInteger()
    val finished = CountDownLatch(1)
    core.peer = object : AaPeer {
        override fun requestKeyFrame() {}
        override fun sendTouch(action: Int, actionIndex: Int, points: List<TouchPoint>) {
            if (action == 2) (if (points.size == 2) pinch else single).incrementAndGet()
        }
    }
    val geometry = DesktopVideoSelection(DesktopResolution.R1280_720, 0)
    return Socket().use { socket ->
        socket.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), port), 5000)
        val transport = StreamTransport.fromPfd(ParcelFileDescriptor.fromSocket(socket), TransportCaps.USB_AOA, "CarLife gesture fixture")
        val session = CarLifeSession(transport, SessionConfig(desktopVideo = geometry), core, onClosed = { finished.countDown() })
        try {
            session.start()
            session.hidePlaceholder()
            val deadline = SystemClock.elapsedRealtime() + 8_000L
            while (!core.isCarlifeStarted && SystemClock.elapsedRealtime() < deadline) Thread.sleep(5)
            check(core.isCarlifeStarted)
            val start = System.nanoTime()
            val pcm = ByteArray(1920) // 10 ms at 48 kHz, stereo, 16-bit
            repeat(400) { tick ->
                val target = start + tick * 10_000_000L
                val wait = target - System.nanoTime()
                if (wait > 0) LockSupport.parkNanos(wait)
                if (tick % 4 == 0) {
                    // A detailed moving map generates larger access units during zoom.
                    val activePinch = pinch.get() > 0
                    val frame = ByteArray(if (activePinch) 24_000 else 2_000)
                    frame[3] = 1
                    frame[4] = if (tick % 20 == 0) 0x65 else 0x41
                    session.sendVideoAt(frame, target / 1000L)
                }
                pcm[0] = tick.toByte()
                pcm[1] = (tick ushr 8).toByte()
                core.onAudioData(AapMessages.CH_AUDIO_MEDIA, pcm, target / 1000L)
            }
            check(finished.await(10, TimeUnit.SECONDS)) { "Receiver did not acknowledge the final PCM sample" }
            check(single.get() >= 50 && pinch.get() >= 100) { "single=$single pinch=$pinch" }
            "single=$single pinch=$pinch"
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
        socket.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), port), 5000)
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
internal fun checkCarLifeBulk(test: Instrumentation, port: Int, tone: () -> AudioTrack, splitReady: (Int) -> Boolean): Bundle {
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
        socket.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), port), 5000)
        val transport = StreamTransport.fromPfd(ParcelFileDescriptor.fromSocket(socket), TransportCaps.USB_AOA, "USB bulk fixture")
        val session = CarLifeSession(transport, SessionConfig(desktopVideo = selection, desktopBitrateBps = prefs.desktopBitrateMbps * 1_000_000,
            desktopAvc = DesktopEncoderSupport.surfaceEncoders("video/avc", prefs.desktopBitrateMbps * 1_000_000)), core) { closed.countDown() }
        var split = false
        var picture = false
        var touch = false
        var size = ""
        val capture = DesktopAudioCapture(context)
        val player = tone()
        try {
            session.start()
            source.start()
            test.runOnMainSync { capture.attach(core, true, session::canCaptureDesktopAudio) }
            player.play()
            val until = SystemClock.elapsedRealtime() + 40_000
            while (closed.count > 0 && SystemClock.elapsedRealtime() < until) {
                if (DexState.displayId > 0 && !split) split = splitReady(DexState.displayId)
                if (session.hasFreshDesktopVideo()) {
                    picture = true
                    size = "${core.videoMode.outW}x${core.videoMode.outH}"
                }
                if (CarLifeState.touch != "-") touch = true
                closed.await(150, TimeUnit.MILLISECONDS)
            }
            check(closed.count == 0L && split && picture && touch) {
                "CarLife USB split=$split picture=$picture touch=$touch size=$size · ${DexState.error}"
            }
            Bundle().apply {
                putString("CarLife_bulk", "$size AVC, DriveDeck split, receiver touch")
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
