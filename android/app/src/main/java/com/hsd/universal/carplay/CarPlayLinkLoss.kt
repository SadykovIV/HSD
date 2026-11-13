package com.hsd.universal.carplay

import java.io.IOException
import java.net.ProtocolException
import javax.net.ssl.SSLException

/** Distinguish a lost transport from an explicit refusal or failed authentication. */
internal fun Exception.carPlayLinkInterrupted(): Boolean =
    this is IOException && this !is RtspError && this !is ProtocolException && this !is SSLException

/** A retry window belongs to one previously streaming receiver. Flapping sessions do not extend it
 * until the receiver has stayed up for a minute. User stop or a non-transport error never retries. */
internal class CarPlayRecovery {
    private var until = 0L
    private var delay = 1000L
    fun retry(now: Long, streamingMs: Long?, linkLost: Boolean): Long? {
        if (!linkLost) return null
        if (streamingMs != null && (until == 0L || streamingMs >= 60_000)) {
            until = now + 150_000
            delay = 1000L
        }
        return delay.takeIf { until > now + it }?.also { delay = minOf(8000L, it * 2) }
    }
}
