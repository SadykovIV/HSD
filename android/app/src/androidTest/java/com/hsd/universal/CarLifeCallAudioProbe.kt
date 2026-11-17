package com.hsd.universal

import android.app.Instrumentation
import android.media.AudioManager
import android.media.AudioTrack
import android.os.SystemClock
import com.hsd.universal.aa.AaBridgeCore
import com.hsd.universal.aa.CarlifeAudio
import com.hsd.universal.aa.CarlifeLink
import com.hsd.universal.dex.DesktopAudioCapture
import com.hsd.universal.dex.DesktopAudioState
import com.hsd.universal.dex.DesktopPcmFrames
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** Actual AudioService mode + playback loopback. Receiver media lifecycle is deliberately strict. */
internal fun checkCarLifeCallAudio(test: Instrumentation, tone: () -> AudioTrack): String {
    val context = test.targetContext
    check(context.checkSelfPermission("android.permission.READ_PHONE_STATE") != android.content.pm.PackageManager.PERMISSION_GRANTED)
    val manager = context.getSystemService(AudioManager::class.java)
    check(manager.mode == AudioManager.MODE_NORMAL)
    val capture = DesktopAudioCapture(context)
    val controls = CopyOnWriteArrayList<Int>()
    val packets = AtomicInteger()
    val audible = AtomicInteger()
    var initialized = false
    val link = object : CarlifeLink {
        override fun sendVideo(payload: ByteArray) = true
        override fun showPlaceholder(text: String) {}
        override fun hidePlaceholder() {}
        override fun sendModuleStatus(module: Int, status: Int) = true
        override fun sendAudio(channel: Int, type: Int, payload: ByteArray): Boolean {
            check(channel == CarlifeAudio.CH_MEDIA)
            if (type == CarlifeAudio.MEDIA_DATA) {
                check(initialized) { "PCM without new MEDIA_INIT after call" }
                packets.incrementAndGet()
                if (DesktopPcmFrames.peak(payload) > 0) audible.incrementAndGet()
            } else {
                controls += type
                initialized = type == CarlifeAudio.MEDIA_INIT
            }
            return true
        }
    }
    val core = AaBridgeCore({ message, ui -> AppLog.i(message, ui) }).apply { desktopInputEnabled = true }
    core.attach(link)
    core.onCarlifeStart()
    val player = tone()
    fun await(label: String, predicate: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + 5_000
        while (!predicate() && SystemClock.elapsedRealtime() < until) Thread.sleep(20)
        check(predicate()) { "$label; controls=$controls error=${DesktopAudioState.error}" }
    }
    try {
        test.runOnMainSync { capture.attach(core, true, restartAfterCalls = true) { true } }
        player.play()
        await("initial audible PCM") { audible.get() >= 5 }
        repeat(2) { call ->
            player.pause() // a real player pauses on the call's focus loss
            manager.mode = AudioManager.MODE_IN_COMMUNICATION
            await("Android accepted communication mode") { manager.mode == AudioManager.MODE_IN_COMMUNICATION }
            await("CarLife paused its media stream") { controls.count { it == CarlifeAudio.MEDIA_PAUSE } == call + 1 }
            val before = packets.get()
            val readBefore = DesktopAudioState.pcmBytes
            Thread.sleep(500)
            check(packets.get() == before) { "Media continued into call-owned output" }
            check(DesktopAudioState.running && DesktopAudioState.pcmBytes > readBefore) { "Call released the loopback route" }
            val signalBefore = audible.get()
            manager.mode = AudioManager.MODE_NORMAL
            await("Android released communication mode") { manager.mode == AudioManager.MODE_NORMAL }
            Thread.sleep(250)
            check(controls.count { it == CarlifeAudio.MEDIA_INIT } == call + 1) {
                "The end of a call reselected music while the player was still paused"
            }
            player.play() // the player's own focus-gain decision, never a forced PLAY from HSD
            await("audible PCM after fresh INIT") {
                controls.count { it == CarlifeAudio.MEDIA_INIT } == call + 2 && audible.get() >= signalBefore + 5
            }
        }
        return "two communication interruptions: MEDIA_PAUSE, no queued PCM, retained AudioMix, fresh INIT + audible PCM"
    } finally {
        manager.mode = AudioManager.MODE_NORMAL
        test.runOnMainSync(capture::close)
        player.release()
        core.onCarlifeClosed(link)
    }
}
