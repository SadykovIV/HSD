package com.hsd.universal.dex

import android.content.Context
import android.media.AudioManager
import android.os.Build
import com.hsd.universal.AppLog
import java.io.Closeable

/** Observes audio routing, including VoIP, without READ_PHONE_STATE or Bluetooth profile guesses. */
internal class DesktopAudioModeObserver(context: Context) : Closeable {
    private val manager = context.getSystemService(AudioManager::class.java)
    val interruption = DesktopAudioInterruption()
    private var removeListener: (() -> Unit)? = null
    private var closed = false

    init {
        if (Build.VERSION.SDK_INT >= 31) runCatching {
            val listener = AudioManager.OnModeChangedListener { mode -> if (!closed) update(mode) }
            manager.addOnModeChangedListener(context.mainExecutor, listener)
            removeListener = { manager.removeOnModeChangedListener(listener) }
        }.onFailure { AppLog.i("DESKTOP AUDIO: mode callback unavailable; observing mode by polling", false) }
        readMode()
    }

    /** Existing capture-owner tick supplies the fallback on Android 10/11. */
    fun poll() { if (!closed && removeListener == null) readMode() }

    private fun readMode() { runCatching { manager.mode }.onSuccess(::update) }
    private fun update(mode: Int) {
        val previous = interruption.state
        interruption.updateMode(mode)
        if (mode != previous.mode) AppLog.i("DESKTOP AUDIO: Android audio mode=$mode; call owns output=${interruption.state.blocked}", false)
    }

    override fun close() {
        closed = true
        removeListener?.let { runCatching(it) }
        removeListener = null
    }
}
