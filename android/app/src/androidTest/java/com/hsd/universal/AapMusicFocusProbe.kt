package com.hsd.universal

import android.app.Instrumentation
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.AudioTrack
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import aap_protobuf.service.control.message.AudioFocusStateTypeOuterClass.AudioFocusStateType
import com.hsd.universal.dex.DesktopAudioState
import net.prototux.aare.aap.sim.HeadUnitSimulator
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** Real Android focus, media session and loopback capture; the HU exchanges actual AAP messages. */
internal class AapMusicFocusProbe(private val instrumentation: Instrumentation) {
    fun run(hu: HeadUnitSimulator, track: AudioTrack): Bundle {
        val context = instrumentation.targetContext
        val audio = context.getSystemService(AudioManager::class.java)
        val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build()
        val events = CopyOnWriteArrayList<String>()
        val pauses = AtomicInteger()
        val session = MediaSession(context, "HSD AAP music regression")
        fun state(value: Int) = PlaybackState.Builder()
            .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE)
            .setState(value, 0, if (value == PlaybackState.STATE_PLAYING) 1f else 0f).build()
        fun pause(reason: String) {
            events += reason
            pauses.incrementAndGet()
            track.pause()
            session.setPlaybackState(state(PlaybackState.STATE_PAUSED))
        }
        val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener({ change ->
                events += "android-focus=$change"
                if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
                    pause("focus loss")
            }, Handler(Looper.getMainLooper())).build()
        val noisy = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) { pause("becoming noisy") }
        }
        context.registerReceiver(noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
        try {
            session.setPlaybackToLocal(attributes)
            session.setCallback(object : MediaSession.Callback() {
                override fun onPause() { pause("media PAUSE") }
                override fun onStop() { pause("media STOP") }
            }, Handler(Looper.getMainLooper()))
            session.isActive = true
            check(audio.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
            await("playback capture") { DesktopAudioState.running }
            check(hu.mediaFocusRequests.get() == 0 && hu.audioChunks.get() == 0)
            session.setPlaybackState(state(PlaybackState.STATE_PLAYING))
            track.play()
            await("first music delivered") { hu.nonSilentAudioChunks.get() >= 20 }
            check(hu.mediaFocusRequests.get() == 1)
            val normalPeak = hu.lastMediaPeak.get()
            check(normalPeak > 0)
            val beforeCamera = DesktopAudioState.pcmBytes
            val stopsBeforeCamera = hu.audioStops.get()
            hu.requestFocusNative()
            // Longer than capture reconciliation and the old 3-second video/output lease.
            SystemClock.sleep(4_000)
            check(DesktopAudioState.running && DesktopAudioState.pcmBytes > beforeCamera)
            check(hu.audioStops.get() == stopsBeforeCamera && pauses.get() == 0) { "Camera stopped music: $events" }
            hu.audioFocus(AudioFocusStateType.AUDIO_FOCUS_STATE_LOSS_TRANSIENT_CAN_DUCK)
            await("ducked PCM remains nonzero") { hu.lastMediaPeak.get() in 1..maxOf(1, normalPeak / 3) }
            check(hu.audioStops.get() == stopsBeforeCamera && pauses.get() == 0)
            hu.audioFocus(AudioFocusStateType.AUDIO_FOCUS_STATE_GAIN)
            await("full volume restored") { hu.lastMediaPeak.get() >= normalPeak * 9 / 10 }
            hu.requestFocusProjected()
            check(hu.mediaFocusRequests.get() == 1) { "Camera released and reacquired audio focus" }
            repeat(3) {
                val stops = hu.audioStops.get()
                hu.audioFocus(AudioFocusStateType.AUDIO_FOCUS_STATE_LOSS_TRANSIENT)
                await("HU media STOP") { hu.audioStops.get() > stops }
                SystemClock.sleep(200) // allow already written packets to drain
                val muted = hu.audioChunks.get()
                SystemClock.sleep(250)
                check(hu.audioChunks.get() == muted) { "PCM sent without HU audio focus" }
                check(pauses.get() == 0 && track.playState == AudioTrack.PLAYSTATE_PLAYING) { events.toString() }
                hu.audioFocus(AudioFocusStateType.AUDIO_FOCUS_STATE_GAIN)
                val before = hu.nonSilentAudioChunks.get()
                await("music resumed after HU focus") { hu.nonSilentAudioChunks.get() >= before + 20 }
            }
            check(pauses.get() == 0 && session.controller.playbackState?.state == PlaybackState.STATE_PLAYING) {
                events.toString()
            }
            return Bundle().apply {
                putString("music_player", "Android focus retained; no media PAUSE/STOP or noisy-route pause")
                putString("audio_focus_recovery", "Three HU transient losses: STOP, no PCM without focus, fresh music after GAIN")
                putString("camera_audio", "4 seconds without video: capture retained; CAN_DUCK attenuates PCM without STOP; GAIN restores volume")
                putInt("music_packets", hu.nonSilentAudioChunks.get())
                putString("player_events", events.toString())
            }
        } finally {
            context.unregisterReceiver(noisy)
            session.release()
            track.release()
            audio.abandonAudioFocusRequest(focus)
        }
    }

    private fun await(label: String, condition: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + 10_000
        while (!condition() && SystemClock.elapsedRealtime() < until) SystemClock.sleep(20)
        check(condition()) { label }
    }
}
