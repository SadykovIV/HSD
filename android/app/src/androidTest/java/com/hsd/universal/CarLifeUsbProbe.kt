package com.hsd.universal

import android.app.Instrumentation
import android.media.AudioTrack
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import com.hsd.universal.aa.AaBridgeCore
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
