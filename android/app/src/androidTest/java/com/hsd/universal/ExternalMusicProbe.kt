package com.hsd.universal

import android.app.Instrumentation
import android.content.Intent
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import net.prototux.aare.aap.sim.HeadUnitSimulator

/** A stock VLC APK runs under a different UID and owns its real media session/audio focus. */
internal fun checkExternalMusic(instrumentation: Instrumentation, hu: HeadUnitSimulator, uri: String,
                                placePlayer: (() -> Unit)? = null): Bundle {
    require(uri.startsWith("http://10.0.2.2:"))
    val context = instrumentation.targetContext
    val sessions = context.getSystemService(MediaSessionManager::class.java)
    context.startActivity(Intent(Intent.ACTION_VIEW).setPackage("org.videolan.vlc")
        .setDataAndType(Uri.parse(uri), if (uri.endsWith(".mp4")) "video/mp4" else "audio/wav")
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    val deadline = SystemClock.elapsedRealtime() + 35_000
    var player = sessions.getActiveSessions(null).firstOrNull { it.packageName == "org.videolan.vlc" }
    while ((player?.playbackState?.state != PlaybackState.STATE_PLAYING || hu.nonSilentAudioChunks.get() < 20) && SystemClock.elapsedRealtime() < deadline) {
        SystemClock.sleep(100)
        player = sessions.getActiveSessions(null).firstOrNull { it.packageName == "org.videolan.vlc" }
    }
    val selected = checkNotNull(player) { "VLC media session did not start" }
    try {
        check(selected.playbackState?.state == PlaybackState.STATE_PLAYING && hu.nonSilentAudioChunks.get() >= 20) { "VLC did not deliver music" }
        placePlayer?.invoke()
        val packets = hu.nonSilentAudioChunks.get()
        repeat(80) { i ->
            if (i == 20) hu.requestFocusNative()
            if (i == 40) hu.requestFocusProjected()
            SystemClock.sleep(250)
            check(selected.playbackState?.state == PlaybackState.STATE_PLAYING) { "Stock VLC paused at ${i * 250} ms" }
        }
        check(hu.nonSilentAudioChunks.get() > packets + 500)
        return Bundle().apply { putString("external_music", "Stock VLC, separate UID: 20 seconds PLAYING and captured audio, including 5 seconds of native camera screen; embedded=${placePlayer != null}; video=${uri.endsWith(".mp4")}") }
    } finally { selected.transportControls.stop() }
}
