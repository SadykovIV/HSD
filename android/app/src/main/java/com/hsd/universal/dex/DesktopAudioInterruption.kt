package com.hsd.universal.dex

/** A stream boundary survives even a complete call between two recorder reads. */
internal class DesktopAudioInterruption {
    data class State(val boundary: Long = 0, val blocked: Boolean = false, val mode: Int = 0)
    @Volatile var state = State()
        private set

    @Synchronized fun updateMode(mode: Int) {
        // AudioManager: RINGTONE, IN_CALL, IN_COMMUNICATION, CALL/COMMUNICATION_REDIRECT.
        // Screening and assistant conversation explicitly allow other playback use cases.
        val blocked = mode == 1 || mode == 2 || mode == 3 || mode == 5 || mode == 6
        val previous = state
        state = State(previous.boundary + if (blocked != previous.blocked) 1 else 0, blocked, mode)
    }
}
