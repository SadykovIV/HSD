package com.hsd.universal

import android.app.Instrumentation
import android.app.ActivityOptions
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.UiAutomation
import android.content.Intent
import android.graphics.Rect
import android.media.session.MediaSessionManager
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import com.hsd.universal.dex.DesktopModePolicy
import com.hsd.universal.shared.ProjectionHost
import net.prototux.aare.aap.sim.HeadUnitSimulator

/** The companion draws the music card and its transport buttons in the same custom View. */
internal fun tapPlayingMusicCard(ui: UiAutomation, display: Int, tap: (Int, Int) -> Unit): Rect {
    val service = ui.serviceInfo
    val previous = service.flags
    service.flags = previous or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
    ui.serviceInfo = service
    val deadline = SystemClock.elapsedRealtime() + 8_000
    val widgetNames = setOf("Музыка", "Music")
    val seen = mutableSetOf<String>()
    fun musicCard(node: AccessibilityNodeInfo?): Rect? {
        if (node == null) return null
        val label = node.contentDescription?.toString() ?: node.text?.toString()
        if (label != null && node.isVisibleToUser) {
            seen.add(label)
            if (widgetNames.any { label == it || label.startsWith("$it. ") || label.startsWith("$it: ") }) {
                val bounds = Rect().also(node::getBoundsInScreen)
                if (bounds.width() > 100 && bounds.height() > 60) return bounds
            }
        }
        for (i in 0 until node.childCount) musicCard(node.getChild(i))?.let { return it }
        return null
    }
    try {
        while (SystemClock.elapsedRealtime() < deadline) {
            UiAutomation::class.java.getMethod("syncInputTransactions").invoke(ui)
            val bounds = ui.windowsOnAllDisplays[display].orEmpty().asSequence()
                .mapNotNull { it.root }.filter { it.packageName == DesktopModePolicy.DRIVEDECK_PACKAGE }
                .mapNotNull(::musicCard).firstOrNull()
            if (bounds != null) {
                // Track title / artwork above the row of playback buttons.
                tap(bounds.centerX(), bounds.top + bounds.height() / 3)
                return bounds
            }
            SystemClock.sleep(80)
        }
        error("No music card on display $display. Visible labels $seen")
    } finally {
        service.flags = previous
        ui.serviceInfo = service
    }
}

internal fun openExternalPlayer(instrumentation: Instrumentation, uri: String): MediaController {
    require(uri.startsWith("http://10.0.2.2:"))
    val context = instrumentation.targetContext
    val sessions = context.getSystemService(MediaSessionManager::class.java)
    context.startActivity(Intent(Intent.ACTION_VIEW).setPackage("org.videolan.vlc")
        .setDataAndType(Uri.parse(uri), if (uri.endsWith(".mp4")) "video/mp4" else "audio/wav")
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    val deadline = SystemClock.elapsedRealtime() + 35_000
    var player = sessions.getActiveSessions(null).firstOrNull { it.packageName == "org.videolan.vlc" }
    while (player?.playbackState?.state != PlaybackState.STATE_PLAYING && SystemClock.elapsedRealtime() < deadline) {
        SystemClock.sleep(100)
        player = sessions.getActiveSessions(null).firstOrNull { it.packageName == "org.videolan.vlc" }
    }
    return checkNotNull(player?.takeIf { it.playbackState?.state == PlaybackState.STATE_PLAYING }) { "VLC media session did not start" }
}

internal fun waitPlayer(player: MediaController, state: Int) {
    val deadline = SystemClock.elapsedRealtime() + 8_000
    while (player.playbackState?.state != state && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(40)
    check(player.playbackState?.state == state) { "Expected player state $state, actual ${player.playbackState?.state}" }
}

/** Exercise the blue Music shortcut's actual modal window on the projected display. */
internal fun checkMusicDialog(test: Instrumentation, ui: UiAutomation, display: Int, tap: (Int, Int) -> Unit) {
    val context = test.targetContext
    fun show(action: String) {
        val launch = Intent(action).setClassName(DesktopModePolicy.DRIVEDECK_PACKAGE, DesktopModePolicy.driveDeckHome(context))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(ProjectionHost.PACKAGE, context.packageName).putExtra(ProjectionHost.DISPLAY, display)
        if (action == Intent.ACTION_MAIN) launch.addCategory(Intent.CATEGORY_LAUNCHER)
        context.startActivity(launch, ActivityOptions.makeBasic().setLaunchDisplayId(display).toBundle())
    }
    val previous = ui.serviceInfo.flags
    fun flags(value: Int) { ui.serviceInfo = ui.serviceInfo.apply { flags = value } }
    fun find(node: AccessibilityNodeInfo?, labels: Set<String>): Rect? {
        if (node == null) return null
        if (node.isVisibleToUser && node.className == "android.widget.ImageButton" && node.contentDescription?.toString() in labels)
            return Rect().also(node::getBoundsInScreen)
        for (child in 0 until node.childCount) find(node.getChild(child), labels)?.let { return it }
        return null
    }
    fun control(labels: Set<String>): Rect? = ui.windowsOnAllDisplays[display].orEmpty().asSequence().mapNotNull { it.root }
        .filter { it.packageName == DesktopModePolicy.DRIVEDECK_PACKAGE }.mapNotNull { find(it, labels) }.firstOrNull()
    fun until(label: String, predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 8_000
        while (!predicate()) { check(SystemClock.elapsedRealtime() < deadline) { label }; SystemClock.sleep(80) }
    }
    val back = setOf("Назад", "Back")
    val home = setOf("Домой", "Home")
    try {
        flags(previous or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS)
        for (target in listOf(back, home, emptySet())) {
            show("ru.drivedeck.launcher.MUSIC")
            until("Music window did not present Back and Home") { control(back) != null && control(home) != null }
            if (target.isEmpty()) show(Intent.ACTION_MAIN) else checkNotNull(control(target)).let { tap(it.centerX(), it.centerY()) }
            until("Music window stayed above dashboard after $target") { control(back) == null && control(home) == null }
        }
    } finally { flags(previous) }
}

/** A stock VLC APK runs under a different UID and owns its real media session/audio focus. */
internal fun checkExternalMusic(instrumentation: Instrumentation, hu: HeadUnitSimulator, uri: String,
                                placePlayer: (() -> Unit)? = null, prepared: MediaController? = null): Bundle {
    val selected = prepared ?: openExternalPlayer(instrumentation, uri)
    try {
        val deadline = SystemClock.elapsedRealtime() + 30_000
        while ((selected.playbackState?.state != PlaybackState.STATE_PLAYING || hu.nonSilentAudioChunks.get() < 20) &&
            SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100)
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
        repeat(2) {
            selected.transportControls.pause()
            waitPlayer(selected, PlaybackState.STATE_PAUSED)
            repeat(24) { SystemClock.sleep(250); check(selected.playbackState?.state == PlaybackState.STATE_PAUSED) { "User pause was overridden" } }
            val before = hu.nonSilentAudioChunks.get()
            selected.transportControls.play()
            waitPlayer(selected, PlaybackState.STATE_PLAYING)
            repeat(28) { SystemClock.sleep(250); check(selected.playbackState?.state == PlaybackState.STATE_PLAYING) { "Player paused again after manual Play" } }
            check(hu.nonSilentAudioChunks.get() > before + 100) { "Resumed player produced no sustained receiver audio" }
        }
        return Bundle().apply { putString("external_music", "VLC: 20 seconds PLAYING including native screen, two 6 s pauses and two 7 s resumes with receiver audio; prepared=${prepared != null}; embedded=${placePlayer != null}") }
    } finally { selected.transportControls.stop() }
}
