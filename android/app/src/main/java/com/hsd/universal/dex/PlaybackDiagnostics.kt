package com.hsd.universal.dex

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import com.hsd.universal.AppLog
import java.io.Closeable

/** Passive evidence for player pauses. No focus requests, media commands, titles, URIs or PCM. */
internal class PlaybackDiagnostics(private val context: Context,
    private val onPlayback: (List<PlaybackActivity>) -> Unit = {}, private val focus: () -> String) : Closeable {
    private val thread = HandlerThread("HSD-playback-events").apply { start() }
    private val handler = Handler(thread.looper)
    private val audio = context.getSystemService(AudioManager::class.java)
    private val sessions = context.getSystemService(MediaSessionManager::class.java)
    private val watched = mutableListOf<Pair<MediaController, MediaController.Callback>>()
    private var sessionEvents = false
    private var playbackEvents = false
    private var deviceEvents = false
    private var noisyEvents = false
    @Volatile private var closed = false
    private var lastPlayback = ""
    private fun log(event: String) {
        if (!closed) AppLog.i("PLAYBACK EVENT: $event; focus=${runCatching(focus).getOrDefault("unavailable")}", false)
    }
    private val playback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: List<AudioPlaybackConfiguration>) {
            if (closed) return
            val activity = configs.map { config ->
                PlaybackActivity(config.audioAttributes.usage,
                    runCatching { config.javaClass.getMethod("getClientUid").invoke(config) as Int }.getOrNull(),
                    runCatching { config.javaClass.getMethod("isActive").invoke(config) as Boolean }.getOrNull())
            }
            onPlayback(activity)
            val value = activity.take(16).map { "uid=${it.uid} usage=${it.usage} active=${it.active}" }.sorted().joinToString(" | ")
            if (value != lastPlayback) { lastPlayback = value; log("tracks=[$value]") }
        }
    }
    private val devices = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(items: Array<out AudioDeviceInfo>) = changed("added", items)
        override fun onAudioDevicesRemoved(items: Array<out AudioDeviceInfo>) = changed("removed", items)
        private fun changed(action: String, items: Array<out AudioDeviceInfo>) {
            log("outputs $action=${items.filter { it.isSink }.joinToString { "type=${it.type}/id=${it.id}" }}")
        }
    }
    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) { log("AUDIO_BECOMING_NOISY") }
    }
    private val sessionListener = MediaSessionManager.OnActiveSessionsChangedListener { observe(it.orEmpty()) }
    init {
        try {
            audio.registerAudioPlaybackCallback(playback, handler); playbackEvents = true
            handler.post { if (!closed) playback.onPlaybackConfigChanged(audio.activePlaybackConfigurations) }
            audio.registerAudioDeviceCallback(devices, handler); deviceEvents = true
            val filter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
            if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(noisy, filter, null, handler, Context.RECEIVER_NOT_EXPORTED)
            else @Suppress("DEPRECATION") context.registerReceiver(noisy, filter, null, handler)
            noisyEvents = true
        } catch (error: Exception) { close(); throw error }
        handler.post {
            if (!closed) runCatching {
                sessions.addOnActiveSessionsChangedListener(sessionListener, null, handler)
                sessionEvents = true
                observe(sessions.getActiveSessions(null))
            }.onFailure { log("media-session observation unavailable (${it.javaClass.simpleName})") }
        }
    }
    private fun observe(players: List<MediaController>) {
        if (closed) return
        watched.forEach { (player, callback) -> player.unregisterCallback(callback) }
        watched.clear()
        players.take(16).forEach { player ->
            var last: Int? = null
            fun report(state: PlaybackState?) {
                if (state?.state != last) { last = state?.state; log("session=${player.packageName} state=$last") }
            }
            val callback = object : MediaController.Callback() {
                override fun onPlaybackStateChanged(state: PlaybackState?) = report(state)
                override fun onSessionDestroyed() { log("session=${player.packageName} destroyed") }
            }
            watched += player to callback
            player.registerCallback(callback, handler)
            report(player.playbackState)
        }
    }
    override fun close() {
        if (closed) return
        closed = true
        if (playbackEvents) runCatching { audio.unregisterAudioPlaybackCallback(playback) }
        if (deviceEvents) runCatching { audio.unregisterAudioDeviceCallback(devices) }
        if (noisyEvents) runCatching { context.unregisterReceiver(noisy) }
        handler.post {
            if (sessionEvents) runCatching { sessions.removeOnActiveSessionsChangedListener(sessionListener) }
            watched.forEach { (player, callback) -> runCatching { player.unregisterCallback(callback) } }
            watched.clear()
            thread.quitSafely()
        }
    }
}

internal data class PlaybackActivity(val usage: Int, val uid: Int?, val active: Boolean?)
