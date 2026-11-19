package com.hsd.universal.dex

/** Pause once on loss of a proven desktop output, whichever transport or physical-detach signal arrives first. */
internal class DesktopMusicOutputLink(private val graceMs: Long) {
    var serviceOwner: Any? = null
        private set
    var owner: Any? = null
        private set
    var sawReady = false
        private set
    var lossId: Any? = null
        private set
    val outputLost: Boolean get() = lossId != null
    private var readyLinkClosedAt: Long? = null

    fun attach(service: Any, link: Any, now: Long) {
        if (serviceOwner !== service) clear()
        if (owner === link) return
        owner?.let { close(it, now) }
        serviceOwner = service
        owner = link
        sawReady = false
        lossId = null
    }

    fun markReady() { if (owner != null && !outputLost) sawReady = true }

    fun consumeDisconnect(service: Any, link: Any, hadPicture: Boolean): Boolean {
        if (serviceOwner !== service || owner !== link || outputLost || !(sawReady || hadPicture)) return false
        consumeLoss()
        return true
    }

    fun close(link: Any, now: Long): Boolean {
        if (owner !== link) return false
        if (sawReady && !outputLost) readyLinkClosedAt = now
        owner = null
        sawReady = false
        return true
    }

    /** The failed fd must itself have delivered a desktop; a failed retry has no such claim. */
    fun consumeTransportLoss(service: Any, link: Any, hadPicture: Boolean, carriedAudio: Boolean): Boolean {
        // A camera or source switch may close just the projection endpoint while Bluetooth keeps playing.
        if (!carriedAudio) {
            if (serviceOwner === service && owner === link && hadPicture) markReady()
            return false
        }
        return consumeDisconnect(service, link, hadPicture)
    }

    fun consumePhysicalDetach(service: Any, now: Long): Boolean {
        if (serviceOwner !== service || outputLost) return false
        val closedAt = readyLinkClosedAt
        // The matching fd may already have posted stronger first-frame proof to the main
        // queue. An unproven broadcast must not consume that later event's claim.
        if (!sawReady && (closedAt == null || now < closedAt || now - closedAt > graceMs)) return false
        consumeLoss()
        return true
    }

    private fun consumeLoss() {
        lossId = Any()
        sawReady = false
        readyLinkClosedAt = null
    }

    fun clear() {
        serviceOwner = null
        owner = null
        sawReady = false
        lossId = null
        readyLinkClosedAt = null
    }
}
