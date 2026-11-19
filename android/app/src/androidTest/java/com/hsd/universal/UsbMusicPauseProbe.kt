package com.hsd.universal

import android.app.Instrumentation
import android.content.*
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import com.hsd.universal.dex.DesktopMusicPlayback
import com.hsd.universal.ui.UsbDetachPolicy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Real Android media routing and player. Only the USB cable state is supplied by the fixture. */
internal class UsbMusicPauseProbe(private val instrumentation: Instrumentation) {
    fun checkDisconnects() {
        run()
        run(wireless = true)
        run(wireless = true, explicitStop = true)
        run(wireless = true, serviceDestroyed = true)
    }

    fun run(wireless: Boolean = false, explicitStop: Boolean = false, serviceDestroyed: Boolean = false) {
        val app = instrumentation.targetContext
        var cable = true
        val context = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun registerReceiver(receiver: BroadcastReceiver?, filter: IntentFilter): Intent? =
                if (receiver == null && filter.hasAction(UsbDetachPolicy.USB_STATE))
                    Intent(UsbDetachPolicy.USB_STATE).putExtra("connected", cable)
                else super.registerReceiver(receiver, filter)
        }
        val calls = AtomicInteger()
        val paused = CountDownLatch(1)
        val session = MediaSession(app, "HSD USB media QA")
        val attr = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build()
        val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setAudioAttributes(attr).build()
        val manager = app.getSystemService(AudioManager::class.java)
        val rate = 24000
        val track = AudioTrack.Builder().setAudioAttributes(attr).setAudioFormat(AudioFormat.Builder()
            .setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(rate * 2).setTransferMode(AudioTrack.MODE_STATIC).build()
        val owner = Any()
        val service = Any()
        fun playback(state: Int) = PlaybackState.Builder().setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE)
            .setState(state, 0, if (state == PlaybackState.STATE_PLAYING) 1f else 0f).build()
        try {
            session.setCallback(object : MediaSession.Callback() {
                override fun onPause() {
                    calls.incrementAndGet()
                    session.setPlaybackState(playback(PlaybackState.STATE_PAUSED))
                    paused.countDown()
                }
            }, Handler(Looper.getMainLooper()))
            session.setPlaybackToLocal(attr)
            session.isActive = true
            manager.requestAudioFocus(focus)
            val pcm = ShortArray(rate) { if (it % 64 < 32) 20 else -20 }
            check(track.write(pcm, 0, pcm.size) == pcm.size)
            track.setLoopPoints(0, pcm.size, -1)
            track.play()
            session.setPlaybackState(playback(PlaybackState.STATE_PLAYING))
            instrumentation.runOnMainSync {
                DesktopMusicPlayback.attach(context, service, owner, !wireless, { true }, { true })
                DesktopMusicPlayback.outputLost(service, owner, true, false)
                DesktopMusicPlayback.outputLost(service, Any(), true, true)
                DesktopMusicPlayback.physicalDetach(service)
            }
            check(!paused.await(350, TimeUnit.MILLISECONDS)) { "Camera switch paused the player" }
            instrumentation.runOnMainSync {
                when {
                    serviceDestroyed -> DesktopMusicPlayback.serviceDestroyed(service, false)
                    explicitStop -> {
                        DesktopMusicPlayback.stop("explicit fixture Stop")
                        DesktopMusicPlayback.stop("duplicate fixture Stop")
                    }
                    wireless -> {
                        DesktopMusicPlayback.outputLost(service, owner, true, true)
                        DesktopMusicPlayback.outputLost(service, owner, true, true)
                        DesktopMusicPlayback.linkClosed(owner)
                    }
                    else -> {
                        cable = false
                        DesktopMusicPlayback.physicalDetach(service)
                        DesktopMusicPlayback.physicalDetach(service)
                    }
                }
            }
            check(paused.await(2, TimeUnit.SECONDS)) { "Output loss did not pause the player (wireless=$wireless, stop=$explicitStop)" }
            instrumentation.waitForIdleSync()
            check(calls.get() == 1) { "Duplicate PAUSE on unplug" }
        } finally {
            instrumentation.runOnMainSync { DesktopMusicPlayback.cancel("fixture finished") }
            session.release()
            track.release()
            manager.abandonAudioFocusRequest(focus)
        }
    }
}
