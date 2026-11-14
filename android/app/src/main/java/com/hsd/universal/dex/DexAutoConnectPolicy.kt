package com.hsd.universal.dex

/** Process-wide intent, shared by the Activity and notification. No reboot/startup privilege. */
internal class DexAutoConnectPolicy {
    enum class Decision { STOPPED, CONNECT, MONITOR, RESTART }

    var stopped = false
        private set
    private var observed = false
    private var accessoryKey: String? = null
    private var generation = 0L
    val attachment: Long? get() = if (accessoryKey == null) null else generation

    /** Repeated attach delivery is not a new connection. A real detach/reconnect rearms it. */
    fun observeAccessory(key: String?) {
        if (key != null && key != accessoryKey) {
            generation++
            if (observed) stopped = false
        }
        accessoryKey = key
        observed = true
    }

    fun stop() { stopped = true }
    fun resume() { stopped = false }

    fun decide(explicit: Boolean, serviceAlive: Boolean, sessionReady: Boolean): Decision {
        if (explicit) resume()
        return when {
            stopped -> Decision.STOPPED
            !serviceAlive -> Decision.CONNECT
            !explicit || sessionReady -> Decision.MONITOR
            else -> Decision.RESTART
        }
    }
}
